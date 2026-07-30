<template>
  <view v-if="!collapsed" class="awd-mask">
    <view class="awd-dialog adopt-dialog">
      <view class="awd-header">
        <text class="awd-title">采纳《{{ draftName || '这一稿' }}》</text>
      </view>
      <view class="awd-body">
        <template v-if="draftId">
          <view class="adopt-hint">这些文件两边都改过，选一下留哪一份：</view>
          <view v-for="row in rows" :key="row.path" class="adopt-row">
            <view class="adopt-row-main">
              <text class="adopt-row-name">{{ row.name }}</text>
              <text
                v-if="mainlineTip && draftTip"
                class="adopt-row-compare"
                @tap="compare(row)"
              >对比</text>
            </view>
            <view class="adopt-row-choices">
              <view
                v-for="opt in choiceOptions"
                :key="opt.value"
                class="radio-item"
                :class="{ checked: resolutions[row.path] === opt.value }"
                @tap="choose(row.path, opt.value)"
              >
                <view class="radio-dot" />
                <text class="radio-label">{{ opt.label }}</text>
              </view>
            </view>
          </view>
        </template>
        <view v-else class="adopt-orphan-hint">
          这次采纳的信息不完整，请先撤销后重试。
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="abort">先不采纳</view>
        <view
          v-if="draftId"
          class="awd-btn awd-btn-primary"
          :class="{ 'awd-btn-disabled': !allChosen || busy }"
          @tap="confirm"
        >确认采纳</view>
      </view>
    </view>
  </view>
  <view v-else class="adopt-collapsed-bar">
    <text class="adopt-collapsed-text">还在等你给《{{ draftName || '这一稿' }}》的文件做选择</text>
    <text class="adopt-collapsed-resume" @tap="collapsed = false">继续处理</text>
  </view>
</template>

<script>
import { resolveAdopt, abortAdopt } from '@/services/api.js'

export default {
  name: 'AdoptConflictDialog',
  props: {
    projectId: { type: [String, Number], required: true },
    draftId: { type: [String, Number], default: null },
    draftName: { type: String, default: '' },
    conflictingPaths: { type: Array, default: () => [] },
    // 「对比」按钮要用的两个 ref：主线侧 tip / 稿侧 tip，来自 /status 的 adoptConflict。
    mainlineTip: { type: String, default: null },
    draftTip: { type: String, default: null },
  },
  emits: ['resolved', 'aborted', 'compare-file'],
  data() {
    return {
      resolutions: {},
      collapsed: false,
      busy: false,
      choiceOptions: [
        { value: 'MAIN', label: '用主线的' },
        { value: 'DRAFT', label: '用这一稿的' },
        { value: 'BOTH', label: '两份都留' },
      ],
    }
  },
  computed: {
    rows() {
      return this.conflictingPaths.map((path) => ({
        path,
        name: path.split('/').pop() || path,
      }))
    },
    allChosen() {
      return this.rows.length > 0 && this.rows.every((r) => !!this.resolutions[r.path])
    },
  },
  methods: {
    choose(path, value) {
      this.resolutions = { ...this.resolutions, [path]: value }
    },
    // 弹窗的 .awd-mask 是全屏遮罩，和「对比」打开的编辑区标签页没法同屏共存；
    // 先收起弹窗（不销毁，已选的三选一保留在内存里），对比看完点「继续处理」再展开——
    // 采纳态本身留在后端（/status 的 adoptConflict），收起不会丢任何东西。
    compare(row) {
      this.collapsed = true
      this.$emit('compare-file', {
        path: row.path,
        name: row.name,
        newRef: this.mainlineTip,
        oldRef: this.draftTip,
        newLabel: '主线这一份',
        oldLabel: '这一稿这一份',
      })
    },
    async confirm() {
      if (!this.allChosen || this.busy) return
      this.busy = true
      try {
        const res = await resolveAdopt(this.projectId, this.draftId, this.resolutions)
        const data = (res && res.data) || {}
        if (data.notice) uni.showToast({ title: data.notice, icon: 'none' })
        this.$emit('resolved', data.affectedFileIds || [])
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '采纳失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async abort() {
      if (this.busy) return
      this.busy = true
      try {
        // abort-adopt 的路径参数在后端不参与判断（只按 projectId 找当前合并中的仓库），
        // draftId 反查落空（残局）时也用得到这条逃生门，占位传 0。
        const res = await abortAdopt(this.projectId, this.draftId || 0)
        uni.showToast({ title: (res && res.message) || '这次采纳没有完成，你的两份稿件都还在', icon: 'none' })
        this.$emit('aborted')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '撤销失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.adopt-dialog { width: 680rpx; max-height: 74vh; display: flex; flex-direction: column; background: #fff; border-radius: 12rpx; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.adopt-hint { font-size: 25rpx; color: #666; margin-bottom: 16rpx; }
.adopt-orphan-hint { font-size: 26rpx; color: #b23; line-height: 1.6; }
.adopt-row { padding: 16rpx 0; border-bottom: 1px solid #f0f0f0; }
.adopt-row-main { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10rpx; }
.adopt-row-name { font-size: 26rpx; color: #222; word-break: break-all; }
.adopt-row-compare { font-size: 23rpx; color: #12344D; text-decoration: underline; flex-shrink: 0; margin-left: 16rpx; }
.adopt-row-choices { display: flex; flex-wrap: wrap; gap: 20rpx; }
.radio-item { display: flex; align-items: center; gap: 8rpx; cursor: pointer; }
.radio-dot {
  width: 20rpx; height: 20rpx; border-radius: 50%; border: 1px solid #ccc; box-sizing: border-box;
}
.radio-item.checked .radio-dot { border-color: #12344D; background: #12344D; }
.radio-label { font-size: 24rpx; color: #444; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
.awd-btn-disabled { opacity: .4; pointer-events: none; }

.adopt-collapsed-bar {
  position: fixed; left: 50%; bottom: 40rpx; transform: translateX(-50%);
  display: flex; align-items: center; gap: 16rpx;
  background: #12344D; color: #fff; padding: 14rpx 24rpx; border-radius: 999rpx;
  font-size: 24rpx; z-index: 999; box-shadow: 0 4rpx 16rpx rgba(0,0,0,.2);
}
.adopt-collapsed-resume { text-decoration: underline; flex-shrink: 0; }
</style>
