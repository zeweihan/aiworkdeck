<template>
  <view v-if="drafts.length" class="draft-list">
    <view class="draft-list-header">
      <text class="draft-list-title">进行中的稿</text>
      <view class="awd-btn awd-btn-secondary draft-new-btn" @tap="openNaming">另起一稿</view>
    </view>
    <view v-for="d in drafts" :key="d.id" class="draft-row">
      <view class="draft-row-main">
        <text class="draft-row-name">{{ d.name || '未命名稿' }}</text>
        <text class="draft-row-date">{{ dateOf(d.startedAt) }}</text>
      </view>
      <view class="awd-btn awd-btn-secondary draft-row-btn" @tap="switchTo(d)">切到这一稿</view>
    </view>

    <view v-if="naming" class="awd-mask" @tap.self="naming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">给这份稿起个名字</text></view>
        <view class="awd-body">
          <input
            v-model="title"
            class="awd-input"
            placeholder="例如：客户方案 B"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="naming = false">取消</view>
          <view class="awd-btn awd-btn-primary" @tap="create">开始</view>
        </view>
      </view>
    </view>
  </view>
</template>

<script>
// 从当前版本另起一稿（挂在 VersionPanel 时间线上方）；从某个历史版本另起一稿走
// VersionNodeDetail 的同名入口，两处共用 createDraft，只是 ref 不同。
import { createDraft, switchToDraft } from '@/services/api.js'

export default {
  name: 'DraftList',
  props: {
    projectId: { type: [String, Number], required: true },
    drafts: { type: Array, default: () => [] },
  },
  emits: ['created', 'switched'],
  data() {
    return { naming: false, title: '', busy: false }
  },
  methods: {
    dateOf(v) {
      if (!v) return ''
      const d = new Date(v)
      const pad = (n) => String(n).padStart(2, '0')
      return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日 ${pad(d.getHours())}:${pad(d.getMinutes())}`
    },
    openNaming() {
      this.title = ''
      this.naming = true
    },
    async create() {
      if (this.busy) return
      const name = (this.title || '').trim()
      if (!name) {
        uni.showToast({ title: '请给这一稿起个名字', icon: 'none' })
        return
      }
      this.busy = true
      try {
        const res = await createDraft(this.projectId, null, name)
        const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
        this.naming = false
        uni.showToast({ title: `已建立稿《${name}》，正在切换`, icon: 'none' })
        this.$emit('created', affectedFileIds)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '开稿失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
    async switchTo(d) {
      if (this.busy) return
      this.busy = true
      try {
        const res = await switchToDraft(this.projectId, d.id)
        const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
        this.$emit('switched', affectedFileIds)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '切换失败，请稍后重试', icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.draft-list {
  padding: 12rpx 20rpx; border-bottom: 1px solid $awd-chrome-line; background: transparent;
}
.draft-list-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8rpx;
}
.draft-list-title { font-size: 24rpx; color: $awd-text-on-dark-2; font-weight: 600; }
.draft-new-btn { flex-shrink: 0; padding: 8rpx 18rpx; font-size: 22rpx; }
.draft-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 10rpx 0;
}
.draft-row-main { display: flex; flex-direction: column; gap: 4rpx; min-width: 0; }
.draft-row-name { font-size: 26rpx; color: $awd-text-on-dark; }
.draft-row-date { font-size: 22rpx; color: $awd-text-on-dark-3; }
.draft-row-btn { flex-shrink: 0; padding: 8rpx 18rpx; font-size: 22rpx; }

/* 深底（面板内）按钮基调；弹窗（浮层白卡）里的按钮在下方覆写回浅色 */
.awd-btn { padding: 10rpx 20rpx; border-radius: 6rpx; font-size: 24rpx; }
.awd-btn-primary { background: rgba($awd-mint, 0.12); border: 1px solid $awd-mint; color: $awd-mint; }
.awd-btn-secondary { background: transparent; border: 1px solid $awd-chrome-active; color: $awd-text-on-dark-2; }
.awd-dialog .awd-btn-primary { background: #12344D; border: none; color: #fff; }
.awd-dialog .awd-btn-secondary { background: #f0f0f0; border: none; color: #333; }

.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 999;
}
.awd-dialog { width: 600rpx; background: #fff; border-radius: 12rpx; overflow: hidden; }
.awd-header { padding: 24rpx; border-bottom: 1px solid #eee; }
.awd-title { font-size: 30rpx; font-weight: 600; }
.awd-body { padding: 24rpx; }
.awd-input {
  width: 100%; padding: 16rpx; border: 1px solid #ddd;
  border-radius: 8rpx; font-size: 26rpx;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 16rpx;
  padding: 20rpx 24rpx; border-top: 1px solid #eee;
}
</style>
