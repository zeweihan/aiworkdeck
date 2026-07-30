<template>
  <view class="awd-mask" @tap.self="$emit('close')">
    <view class="awd-dialog">
      <view class="awd-header">
        <text class="awd-title">{{ version.note || version.message }}</text>
      </view>
      <view class="awd-body">
        <view class="detail-meta">{{ version.authorName }} · {{ when }}</view>
        <view v-if="loadError" class="detail-error">
          <text class="detail-error-desc">这一版的改动读取失败，请稍后重试。</text>
          <text class="detail-error-retry" @tap="load">重试</text>
        </view>
        <view v-else-if="!changes.length" class="detail-empty">这一版没有文件改动</view>
        <view v-for="c in changes" :key="c.path" class="detail-change">
          <text class="change-type" :class="'type-' + c.type">{{ typeLabel(c.type) }}</text>
          <text class="change-path">{{ c.path }}</text>
          <view
            v-if="c.type === 'MODIFY' && version.parents && version.parents.length > 0"
            class="awd-btn awd-btn-secondary change-compare-btn"
            @tap="compareFile(c.path)"
          >和上一版对比</view>
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="$emit('close')">关闭</view>
        <view class="awd-btn awd-btn-secondary" @tap="openMilestoneNaming">{{ version.milestone ? '重新命名重要版本' : '标为重要版本' }}</view>
        <view class="awd-btn awd-btn-secondary" @tap="openDraftNaming">从这一版另起一稿</view>
        <view class="awd-btn awd-btn-primary" @tap="confirmRevert">退回到这一版</view>
      </view>
    </view>

    <view v-if="milestoneNaming" class="awd-mask" @tap.self="milestoneNaming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">给这个重要版本起个名字</text></view>
        <view class="awd-body">
          <input
            v-model="milestoneName"
            class="awd-input"
            placeholder="例如：发客户第一稿"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="milestoneNaming = false">取消</view>
          <view class="awd-btn awd-btn-primary" @tap="submitMilestone">确定</view>
        </view>
      </view>
    </view>

    <view v-if="draftNaming" class="awd-mask" @tap.self="draftNaming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">给这份稿起个名字</text></view>
        <view class="awd-body">
          <input
            v-model="draftName"
            class="awd-input"
            placeholder="例如：客户方案 B"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="draftNaming = false">取消</view>
          <view class="awd-btn awd-btn-primary" @tap="submitDraftCreate">开始</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
import { getVersionChanges, revertToVersion, markVersionMilestone, createDraft } from '@/services/api.js'

export default {
  name: 'VersionNodeDetail',
  props: {
    projectId: { type: [String, Number], required: true },
    version: { type: Object, required: true },
  },
  emits: ['close', 'reload-files', 'compare-file', 'milestoned', 'draft-created'],
  data() {
    return {
      changes: [], loadError: false,
      milestoneNaming: false, milestoneName: '',
      draftNaming: false, draftName: '',
      busy: false,
    }
  },
  computed: {
    when() {
      const d = new Date(this.version.when)
      const pad = (n) => String(n).padStart(2, '0')
      return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      try {
        const res = await getVersionChanges(this.projectId, this.version.sha)
        this.changes = ((res && res.data && res.data.changes) || [])
        this.loadError = false
      } catch (e) {
        console.warn('[Version] 读取变更失败', e)
        this.loadError = true
        uni.showToast({ title: '读取失败，请稍后重试', icon: 'none' })
      }
    },
    typeLabel(t) {
      return { ADD: '新增', MODIFY: '修改', DELETE: '删除', RENAME: '改名' }[t] || t
    },
    // 对比结果开在编辑区的标签页里，弹窗留着只会挡住它（也让「弹窗上的按钮文字」
    // 被误当成对比结果渲染出来了）——上抛之后立刻关掉自己。
    compareFile(path) {
      this.$emit('compare-file', { path, sha: this.version.sha })
      this.$emit('close')
    },
    confirmRevert() {
      uni.showModal({
        title: '退回到这一版',
        content: '项目会回到这一版的样子。这次退回本身也会记进时间线，随时可以再退回来。',
        success: async (r) => {
          if (!r.confirm) return
          try {
            const res = await revertToVersion(this.projectId, this.version.sha)
            const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
            this.$emit('reload-files', affectedFileIds)
          } catch (e) {
            uni.showToast({ title: (e && e.message) || '退回失败，请稍后重试', icon: 'none' })
          }
        },
      })
    },
    openMilestoneNaming() {
      this.milestoneName = this.version.milestone || ''
      this.milestoneNaming = true
    },
    async submitMilestone() {
      if (this.busy) return
      const name = (this.milestoneName || '').trim()
      if (!name) {
        uni.showToast({ title: '请给这个重要版本起个名字', icon: 'none' })
        return
      }
      this.busy = true
      try {
        await markVersionMilestone(this.projectId, this.version.sha, name)
        this.milestoneNaming = false
        uni.showToast({ title: '已标为重要版本', icon: 'none' })
        this.$emit('milestoned')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '标记失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    openDraftNaming() {
      this.draftName = ''
      this.draftNaming = true
    },
    async submitDraftCreate() {
      if (this.busy) return
      const name = (this.draftName || '').trim()
      if (!name) {
        uni.showToast({ title: '请给这一稿起个名字', icon: 'none' })
        return
      }
      this.busy = true
      try {
        const res = await createDraft(this.projectId, this.version.sha, name)
        const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
        this.draftNaming = false
        uni.showToast({ title: `已建立稿《${name}》，正在切换`, icon: 'none' })
        this.$emit('draft-created', affectedFileIds)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '开稿失败，请稍后重试', icon: 'none' })
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
.awd-dialog { width: 640rpx; max-height: 70vh; background: #fff; border-radius: 12rpx; display: flex; flex-direction: column; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; overflow-y: auto; flex: 1; }
.detail-meta { font-size: 24rpx; color: #999; margin-bottom: 16rpx; }
.detail-empty { font-size: 26rpx; color: #999; }
.detail-error { display: flex; align-items: center; gap: 16rpx; }
.detail-error-desc { font-size: 26rpx; color: #b23; }
.detail-error-retry { font-size: 26rpx; color: #12344D; text-decoration: underline; }
.detail-change { display: flex; gap: 12rpx; padding: 8rpx 0; }
.change-type { font-size: 23rpx; flex-shrink: 0; }
.type-ADD { color: #2a7; }
.type-MODIFY { color: #C8A45D; }
.type-DELETE { color: #b23; }
.type-RENAME { color: #666; }
.change-path { font-size: 25rpx; color: #333; word-break: break-all; }
.change-compare-btn { flex-shrink: 0; padding: 6rpx 14rpx; font-size: 22rpx; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
.awd-btn { padding: 12rpx 24rpx; border-radius: 6rpx; font-size: 25rpx; }
.awd-btn-primary { background: #12344D; color: #fff; }
.awd-btn-secondary { background: #f0f0f0; color: #333; }
</style>
