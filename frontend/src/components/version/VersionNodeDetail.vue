<template>
  <view class="awd-mask" @tap.self="$emit('close')">
    <view class="awd-dialog">
      <view class="awd-header">
        <text class="awd-title">{{ version.note || version.message }}</text>
      </view>
      <view class="awd-body">
        <view class="detail-meta">{{ version.authorName }} · {{ when }}</view>
        <view v-if="loadError" class="detail-error">
          <text class="detail-error-desc">{{ $t('version.changesLoadFailedDesc') }}</text>
          <text class="detail-error-retry" @tap="load">{{ $t('common.retry') }}</text>
        </view>
        <view v-else-if="!changes.length" class="detail-empty">{{ $t('version.noChangesThisVersion') }}</view>
        <view v-for="c in changes" :key="c.path" class="detail-change">
          <text class="change-type" :class="'type-' + c.type">{{ typeLabel(c.type) }}</text>
          <text class="change-path">{{ c.path }}</text>
          <view
            v-if="c.type === 'MODIFY' && version.parents && version.parents.length > 0"
            class="awd-btn awd-btn-secondary change-compare-btn"
            @tap="compareFile(c.path)"
          >{{ $t('version.compareWithPrevious') }}</view>
        </view>
      </view>
      <view class="awd-footer">
        <view class="awd-btn awd-btn-secondary" @tap="$emit('close')">{{ $t('common.close') }}</view>
        <view class="awd-btn awd-btn-secondary" @tap="openMilestoneNaming">{{ version.milestone ? $t('version.renameMilestone') : $t('version.markMilestone') }}</view>
        <view class="awd-btn awd-btn-secondary" @tap="openDraftNaming">{{ $t('version.newDraftFromVersion') }}</view>
        <view class="awd-btn awd-btn-primary" @tap="confirmRevert">{{ $t('version.revertToVersion') }}</view>
      </view>
    </view>

    <view v-if="milestoneNaming" class="awd-mask" @tap.self="milestoneNaming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">{{ $t('version.nameMilestoneTitle') }}</text></view>
        <view class="awd-body">
          <input
            v-model="milestoneName"
            class="awd-input"
            :placeholder="$t('version.milestoneNamePlaceholder')"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="milestoneNaming = false">{{ $t('common.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="submitMilestone">{{ $t('common.confirm') }}</view>
        </view>
      </view>
    </view>

    <view v-if="draftNaming" class="awd-mask" @tap.self="draftNaming = false">
      <view class="awd-dialog">
        <view class="awd-header"><text class="awd-title">{{ $t('version.nameDraftTitle') }}</text></view>
        <view class="awd-body">
          <input
            v-model="draftName"
            class="awd-input"
            :placeholder="$t('version.draftNamePlaceholder')"
          />
        </view>
        <view class="awd-footer">
          <view class="awd-btn awd-btn-secondary" @tap="draftNaming = false">{{ $t('common.cancel') }}</view>
          <view class="awd-btn awd-btn-primary" @tap="submitDraftCreate">{{ $t('version.start') }}</view>
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
      return this.$t('version.dateYmdHm', {
        year: d.getFullYear(), month: d.getMonth() + 1, day: d.getDate(),
        time: `${pad(d.getHours())}:${pad(d.getMinutes())}`,
      })
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
        uni.showToast({ title: this.$t('version.loadFailedToast'), icon: 'none' })
      }
    },
    typeLabel(t) {
      return {
        ADD: this.$t('version.changeTypeAdd'),
        MODIFY: this.$t('version.changeTypeModify'),
        DELETE: this.$t('version.changeTypeDelete'),
        RENAME: this.$t('version.changeTypeRename'),
      }[t] || t
    },
    // 对比结果开在编辑区的标签页里，弹窗留着只会挡住它（也让「弹窗上的按钮文字」
    // 被误当成对比结果渲染出来了）——上抛之后立刻关掉自己。
    compareFile(path) {
      this.$emit('compare-file', { path, sha: this.version.sha })
      this.$emit('close')
    },
    confirmRevert() {
      uni.showModal({
        title: this.$t('version.revertToVersion'),
        content: this.$t('version.revertConfirmContent'),
        success: async (r) => {
          if (!r.confirm) return
          try {
            const res = await revertToVersion(this.projectId, this.version.sha)
            const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
            this.$emit('reload-files', affectedFileIds)
          } catch (e) {
            uni.showToast({ title: (e && e.message) || this.$t('version.revertFailed'), icon: 'none' })
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
        uni.showToast({ title: this.$t('version.milestoneNameRequired'), icon: 'none' })
        return
      }
      this.busy = true
      try {
        await markVersionMilestone(this.projectId, this.version.sha, name)
        this.milestoneNaming = false
        uni.showToast({ title: this.$t('version.markedMilestone'), icon: 'none' })
        this.$emit('milestoned')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.markMilestoneFailed'), icon: 'none' })
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
        uni.showToast({ title: this.$t('version.draftNameRequired'), icon: 'none' })
        return
      }
      this.busy = true
      try {
        const res = await createDraft(this.projectId, this.version.sha, name)
        const affectedFileIds = (res && res.data && res.data.affectedFileIds) || []
        this.draftNaming = false
        uni.showToast({ title: this.$t('version.draftCreatedSwitching', { name }), icon: 'none' })
        this.$emit('draft-created', affectedFileIds)
      } catch (e) {
        uni.showToast({ title: (e && e.message) || this.$t('version.createDraftFailed'), icon: 'none' })
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
