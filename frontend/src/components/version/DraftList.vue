<template>
  <view v-if="drafts.length" class="draft-list">
    <view class="draft-list-header">
      <text class="draft-list-title">{{ $t('version.draftsInProgress') }}</text>
      <view class="awd-btn awd-btn-secondary draft-new-btn" @tap="openNaming">{{ $t('version.newDraft') }}</view>
    </view>
    <view v-for="d in drafts" :key="d.id" class="draft-row">
      <view class="draft-row-main">
        <text class="draft-row-name">{{ d.name || $t('version.unnamedDraft') }}</text>
        <text class="draft-row-date">{{ dateOf(d.startedAt) }}</text>
      </view>
      <view class="awd-btn awd-btn-secondary draft-row-btn" @tap="switchTo(d)">{{ $t('version.switchToDraft') }}</view>
    </view>

    <view v-if="naming" class="awd-mask" @tap.self="naming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">{{ $t('version.nameDraftTitle') }}</text></view>
        <view class="awd-body">
          <input
            v-model="title"
            class="awd-input"
            :placeholder="$t('version.draftNamePlaceholder')"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="naming = false">{{ $t('common.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="create">{{ $t('version.start') }}</view>
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
      return this.$t('version.dateYmdHm', {
        year: d.getFullYear(), month: d.getMonth() + 1, day: d.getDate(),
        time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
      })
    },
    openNaming() {
      this.title = ''
      this.naming = true
    },
    async create() {
      if (this.busy) return
      const name = (this.title || '').trim()
      if (!name) {
        uni.showToast({ title: this.$t('version.draftNameRequired'), icon: 'none' })
        return
      }
      this.busy = true
      try {
        const res = await createDraft(this.projectId, null, name)
        const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
        this.naming = false
        uni.showToast({ title: this.$t('version.draftCreatedSwitching', { name }), icon: 'none' })
        this.$emit('created', affectedFileIds)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.createDraftFailed'), icon: 'none' })
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
        uni.showToast({ title: (e && e.message) || this.$t('version.switchDraftFailed'), icon: 'none' })
      } finally {
        this.busy = false
      }
    },
  },
}
</script>

<style lang="scss" scoped>
.draft-list {
  padding: 12rpx 20rpx; border-bottom: 1px solid #eee; background: #FBFAF8;
}
.draft-list-header {
  display: flex; align-items: center; justify-content: space-between;
  margin-bottom: 8rpx;
}
.draft-list-title { font-size: 12.5px; color: #64748b; font-weight: 600; }
.draft-row {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 0;
}
.draft-row-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.draft-row-name { font-size: 13.5px; color: #0f172a; }
.draft-row-date { font-size: 12px; color: #94a3b8; }

.awd-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 13.5px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.awd-btn-primary { background: #1A5336; color: #fff; }
.awd-btn-primary:hover { background: #14422b; }
.awd-btn-secondary { background: #fff; color: #475569; border: 1px solid #cbd5e1; }
.awd-btn-secondary:hover { background: #f1f5f9; }
/* 列表行内的按钮个头小一点，覆盖顺序放在 .awd-btn 之后才生效（同权重按源码序） */
.draft-new-btn, .draft-row-btn { padding: 5px 12px; font-size: 12px; }

.awd-mask {
  position: fixed; inset: 0; background: rgba(0,0,0,.4);
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.awd-dialog {
  width: 380px; max-width: 90vw; background: #fff; border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.awd-header { padding: 18px 24px; border-bottom: 1px solid #f1f5f9; }
.awd-title { font-size: 16px; font-weight: 600; color: #0f172a; }
.awd-body { padding: 20px 24px; }
.awd-input {
  width: 100%; height: 38px; padding: 0 12px; border: 1px solid #cbd5e1;
  border-radius: 6px; font-size: 14px; color: #0f172a; box-sizing: border-box;
}
.awd-footer {
  display: flex; justify-content: flex-end; gap: 12px;
  padding: 14px 24px; border-top: 1px solid #f1f5f9; background: #f8f9fa;
}
</style>
