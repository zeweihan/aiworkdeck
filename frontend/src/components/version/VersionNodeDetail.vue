<template>
  <view class="awd-mask" @tap.self="$emit('close')">
    <view class="awd-dialog detail-dialog">
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
  display: flex; align-items: center; justify-content: center; z-index: 9999;
}
.awd-dialog {
  width: 380px; max-width: 90vw; max-height: 76vh;
  display: flex; flex-direction: column; background: #fff;
  border-radius: 12px; overflow: hidden;
  box-shadow: 0 20px 25px -5px rgba(0,0,0,.1), 0 10px 10px -5px rgba(0,0,0,.04);
}
.awd-dialog.detail-dialog { width: 460px; }
.awd-header { padding: 18px 24px; border-bottom: 1px solid #f1f5f9; }
.awd-title { font-size: 16px; font-weight: 600; color: #0f172a; }
.awd-body { padding: 20px 24px; overflow-y: auto; flex: 1; }
.detail-meta { font-size: 12.5px; color: #94a3b8; margin-bottom: 14px; }
.detail-empty { font-size: 13.5px; color: #94a3b8; }
.detail-error { display: flex; align-items: center; gap: 12px; }
.detail-error-desc { font-size: 13.5px; color: #b23; }
.detail-error-retry { font-size: 13.5px; color: #1A5336; text-decoration: underline; cursor: pointer; }
.detail-change { display: flex; align-items: center; gap: 10px; padding: 8px 0; border-bottom: 1px solid #f8fafc; }
.change-type { font-size: 12px; font-weight: 500; flex-shrink: 0; }
.type-ADD { color: #4C9A6A; }
.type-MODIFY { color: #C8A45D; }
.type-DELETE { color: #b23; }
.type-RENAME { color: #64748b; }
.change-path { font-size: 13px; color: #334155; word-break: break-all; flex: 1; }
.change-compare-btn { flex-shrink: 0; padding: 5px 12px; font-size: 12px; }
.awd-footer {
  display: flex; justify-content: flex-end; gap: 12px; flex-wrap: wrap;
  padding: 14px 24px; border-top: 1px solid #f1f5f9; background: #f8f9fa;
}
.awd-btn {
  padding: 8px 18px; border-radius: 6px; font-size: 13.5px; cursor: pointer;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}
.awd-btn-primary { background: #1A5336; color: #fff; }
.awd-btn-primary:hover { background: #14422b; }
.awd-btn-secondary { background: #fff; color: #475569; border: 1px solid #cbd5e1; }
.awd-btn-secondary:hover { background: #f1f5f9; }
.awd-input {
  width: 100%; height: 38px; padding: 0 12px; border: 1px solid #cbd5e1;
  border-radius: 6px; font-size: 14px; color: #0f172a; box-sizing: border-box;
}
</style>
