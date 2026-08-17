<template>
  <!-- Vue 3 多根节点：与 DdFilesPanel 同构 -->

  <!-- 功能已下线（左栏入口移除），组件保留待恢复。
       标题由外壳的 sidebar-header 统一出，这里只留分组头。 -->
  <view class="sm-sec-head">
    <text class="sm-sec-title">{{ $t('panels.smListLabel') }}</text>
    <view class="sm-sec-spacer"></view>
    <view class="sm-add-btn" @tap="showCreateForm = !showCreateForm" :title="$t('panels.smCreateTitle')">
      <text class="sm-add-icon">＋</text>
    </view>
  </view>

  <!-- Create Form -->
  <view class="sm-create-form" v-if="showCreateForm">
    <input class="sm-input" v-model="form.companyName" :placeholder="$t('panels.smCompanyNamePlaceholder')" />
    <input class="sm-input" v-model="form.stockCode" :placeholder="$t('panels.smStockCodePlaceholder')" />
    <input class="sm-input" v-model="form.meetingName" :placeholder="$t('panels.smMeetingNamePlaceholder')" />
    <input class="sm-input" v-model="form.meetingDate" :placeholder="$t('panels.smMeetingDatePlaceholder')" />
    <view class="sm-form-actions">
      <view class="sm-btn secondary" @tap="showCreateForm = false">{{ $t('panels.smCancel') }}</view>
      <view class="sm-btn primary" @tap="createCheck">{{ $t('panels.smCreate') }}</view>
    </view>
  </view>

  <!-- Check List -->
  <view class="sm-check-list">
    <view v-for="check in checks" :key="check.id" class="sm-check-item">
      <view class="sm-check-head" @tap="toggleExpand(check)">
        <view class="sm-check-info">
          <text class="sm-check-name">{{ check.companyName }}</text>
          <text class="sm-check-meeting">{{ check.meetingName }}</text>
        </view>
        <view class="sm-check-right">
          <text class="sm-status" :class="check.status.toLowerCase()">{{ statusText(check.status) }}</text>
          <view
            class="sm-del-btn"
            v-if="expandedId === check.id"
            @tap.stop="confirmDelete(check)"
            :title="$t('panels.smDeleteTitle')"
          >
            <text>{{ $t('panels.smDeleteShort') }}</text>
          </view>
        </view>
      </view>

      <!-- Detail -->
      <view class="sm-check-detail" v-if="expandedId === check.id">
        <view class="sm-meta-line" v-if="check.stockCode || check.meetingDate">
          <text>{{ check.stockCode || $t('panels.smNoStockCode') }} · {{ check.meetingDate || $t('panels.smNoMeetingDate') }}</text>
        </view>

        <!-- Material Slots -->
        <view class="sm-slot" v-for="slot in slotDefs" :key="slot.key">
          <view class="sm-slot-head">
            <text class="sm-slot-label">{{ slot.label }}</text>
            <text class="sm-slot-link" @tap="openPicker(check, slot)">{{ slot.multi ? $t('panels.smAdd') : (slotFiles(check, slot).length ? $t('panels.smReplace') : $t('panels.smLink')) }}</text>
          </view>
          <view class="sm-slot-files">
            <view v-for="f in slotFiles(check, slot)" :key="f.id" class="sm-slot-file">
              <text class="sm-file-name">{{ f.name }}</text>
              <text class="sm-file-remove" @tap="removeMaterial(check, slot, f)">×</text>
            </view>
            <text v-if="slotFiles(check, slot).length === 0" class="sm-slot-empty">{{ slot.optional ? $t('panels.smNotLinkedOptional') : $t('panels.smNotLinked') }}</text>
          </view>
        </view>

        <!-- Actions -->
        <view class="sm-actions">
          <view class="sm-btn secondary" :class="{ disabled: fetching }" @tap="fetchCninfo(check)">
            {{ fetching ? $t('panels.smFetchingCninfo') : $t('panels.smFetchCninfo') }}
          </view>
          <view class="sm-btn primary" :class="{ disabled: starting || check.status === 'RUNNING' }" @tap="startCheck(check)">
            {{ starting ? $t('panels.smPreparing') : (check.status === 'RUNNING' ? $t('panels.smCheckRunning') : $t('panels.smStartCheck')) }}
          </view>
        </view>
        <view class="sm-hint" v-if="check.status === 'RUNNING'">
          <text>{{ $t('panels.smHintRunning') }}</text>
        </view>
        <view class="sm-hint" v-if="check.status === 'DONE'">
          <text>{{ $t('panels.smHintDone') }}</text>
        </view>
      </view>
    </view>

    <view v-if="checks.length === 0 && !showCreateForm" class="sm-empty-state">
      <text>{{ $t('panels.smEmptyState') }}</text>
    </view>
  </view>

  <!-- File Picker -->
  <FilePickerDialog
    v-model:visible="pickerVisible"
    :project-id="projectId"
    :title="pickerTitle"
    :accept="pickerAccept"
    @confirm="handlePickerConfirm"
  />

  <!-- Delete Confirm -->
  <view class="sm-dialog-mask" v-if="showDeleteDialog" @tap="showDeleteDialog = false">
    <view class="sm-dialog-content" @tap.stop>
      <view class="sm-dialog-header"><text class="sm-dialog-title">{{ $t('panels.smDialogTitle') }}</text></view>
      <view class="sm-dialog-body">
        <text>{{ $t('panels.smDeleteConfirmBody') }}</text>
      </view>
      <view class="sm-dialog-footer">
        <view class="sm-dialog-btn cancel" @tap="showDeleteDialog = false">{{ $t('panels.smCancel') }}</view>
        <view class="sm-dialog-btn confirm" @tap="handleDelete">{{ $t('panels.smConfirm') }}</view>
      </view>
    </view>
  </view>
</template>

<script>
import api from '@/services/api'
import FilePickerDialog from '@/components/FilePickerDialog.vue'

import { t } from '@/i18n'

const SLOT_DEFS = [
  { key: 'notice', label: t('panels.smSlotNotice'), field: 'noticeFileId', multi: false, optional: false, accept: ['pdf', 'docx', 'doc'] },
  { key: 'resolution', label: t('panels.smSlotResolution'), field: 'resolutionFileId', multi: false, optional: false, accept: ['pdf', 'docx', 'doc'] },
  { key: 'voteResult', label: t('panels.smSlotVoteResult'), field: 'voteResultFileIds', multi: true, optional: false, accept: ['xlsx', 'xls', 'csv', 'pdf', 'docx', 'doc'] },
  { key: 'template', label: t('panels.smSlotTemplate'), field: 'templateFileId', multi: false, optional: true, accept: ['docx', 'doc'] },
  { key: 'other', label: t('panels.smSlotOther'), field: 'otherFileIds', multi: true, optional: true, accept: [] }
]

export default {
  name: 'ShareholderMeetingPanel',
  components: { FilePickerDialog },
  props: {
    projectId: {
      type: [String, Number],
      required: true
    },
    currentUser: {
      type: Object,
      default: null
    }
  },
  emits: ['start-verification'],
  data() {
    return {
      checks: [],
      fileNames: {}, // fileId -> ProjectFile 摘要（材料名展示用）
      expandedId: null,
      showCreateForm: false,
      form: { companyName: '', stockCode: '', meetingName: '', meetingDate: '' },
      slotDefs: SLOT_DEFS,
      pickerVisible: false,
      pickerTitle: t('panels.smPickerDefaultTitle'),
      pickerAccept: [],
      pickerTarget: null, // { check, slot }
      fetching: false,
      starting: false,
      showDeleteDialog: false,
      deletingCheck: null
    }
  },
  mounted() {
    this.loadChecks()
  },
  methods: {
    async loadChecks() {
      try {
        const res = await api.getShareholderMeetingChecks(this.projectId)
        this.checks = Array.isArray(res) ? res : (res.data || [])
        this.resolveFileNames()
      } catch (e) {
        console.error('加载股东大会核查列表失败', e)
      }
    },
    // 材料只存 fileId，从项目文件列表里解析名称
    async resolveFileNames() {
      try {
        const res = await api.getProjectFiles(this.projectId)
        const list = Array.isArray(res) ? res : (res.data || res.files || [])
        const map = {}
        const walk = (nodes) => {
          for (const n of nodes || []) {
            map[n.id] = { id: n.id, name: n.name }
            if (n.children) walk(n.children)
          }
        }
        walk(list)
        this.fileNames = map
      } catch (e) {
        console.error('解析材料文件名失败', e)
      }
    },
    slotFiles(check, slot) {
      const ids = slot.multi
        ? this.parseIds(check[slot.field])
        : (check[slot.field] ? [check[slot.field]] : [])
      return ids.map(id => this.fileNames[id] || { id, name: this.$t('panels.smFileNamed', { id }) })
    },
    parseIds(json) {
      if (!json) return []
      try {
        const arr = JSON.parse(json)
        return Array.isArray(arr) ? arr : []
      } catch (e) {
        return []
      }
    },
    statusText(status) {
      const map = {
        DRAFT: this.$t('panels.smStatusDraft'),
        READY: this.$t('panels.smStatusReady'),
        RUNNING: this.$t('panels.smStatusRunning'),
        DONE: this.$t('panels.smStatusDone')
      }
      return map[status] || status
    },
    toggleExpand(check) {
      this.expandedId = this.expandedId === check.id ? null : check.id
    },
    async createCheck() {
      if (!this.form.companyName.trim() || !this.form.meetingName.trim()) {
        uni.showToast({ title: this.$t('panels.smCompanyMeetingRequired'), icon: 'none' })
        return
      }
      if (this.form.meetingDate && !/^\d{4}-\d{2}-\d{2}$/.test(this.form.meetingDate.trim())) {
        uni.showToast({ title: this.$t('panels.smDateFormatInvalid'), icon: 'none' })
        return
      }
      try {
        const check = await api.createShareholderMeetingCheck(this.projectId, {
          companyName: this.form.companyName.trim(),
          stockCode: this.form.stockCode.trim(),
          meetingName: this.form.meetingName.trim(),
          meetingDate: this.form.meetingDate.trim() || null
        })
        this.showCreateForm = false
        this.form = { companyName: '', stockCode: '', meetingName: '', meetingDate: '' }
        await this.loadChecks()
        this.expandedId = (check && check.id) || null
      } catch (e) {
        uni.showToast({ title: this.$t('panels.smCreateFailed', { msg: e.message || e }), icon: 'none' })
      }
    },
    openPicker(check, slot) {
      this.pickerTarget = { check, slot }
      this.pickerTitle = this.$t('panels.smPickerTitleFor', { label: slot.label })
      this.pickerAccept = slot.accept
      this.pickerVisible = true
    },
    async handlePickerConfirm(file) {
      const { check, slot } = this.pickerTarget || {}
      if (!check) return
      try {
        await api.attachShareholderMeetingMaterial(check.id, slot.key, file.id)
        await this.loadChecks()
        this.expandedId = check.id
      } catch (e) {
        uni.showToast({ title: this.$t('panels.smLinkFailed', { msg: e.message || e }), icon: 'none' })
      }
    },
    async removeMaterial(check, slot, file) {
      try {
        await api.detachShareholderMeetingMaterial(check.id, slot.key, file.id)
        await this.loadChecks()
        this.expandedId = check.id
      } catch (e) {
        uni.showToast({ title: this.$t('panels.smRemoveFailed', { msg: e.message || e }), icon: 'none' })
      }
    },
    async fetchCninfo(check) {
      if (this.fetching) return
      if (!check.stockCode || !check.meetingDate) {
        uni.showToast({ title: this.$t('panels.smNeedStockAndDate'), icon: 'none' })
        return
      }
      this.fetching = true
      try {
        const res = await api.fetchShareholderMeetingCninfo(check.id)
        const errors = (res && res.errors) || []
        const got = []
        if (res && res.notice && res.notice.fileId) got.push(this.$t('panels.smNoticeWord'))
        if (res && res.resolution && res.resolution.fileId) got.push(this.$t('panels.smResolutionWord'))
        if (got.length) {
          uni.showToast({ title: this.$t('panels.smFetchedList', { list: got.join(this.$t('panels.smListSeparator')) }), icon: 'none' })
        }
        if (errors.length) {
          uni.showToast({ title: this.$t('panels.smPartialFetchFailed', { msg: errors[0] }), icon: 'none' })
        }
        await this.loadChecks()
        this.expandedId = check.id
      } catch (e) {
        uni.showToast({ title: this.$t('panels.smFetchFailedFallback', { msg: e.message || e }), icon: 'none' })
      } finally {
        this.fetching = false
      }
    },
    async startCheck(check) {
      if (this.starting || check.status === 'RUNNING') return
      const hasVote = this.parseIds(check.voteResultFileIds).length > 0
      if (!check.noticeFileId && !check.resolutionFileId && !hasVote) {
        uni.showToast({ title: this.$t('panels.smAtLeastOneMaterial'), icon: 'none' })
        return
      }
      this.starting = true
      try {
        const res = await api.startShareholderMeetingCheck(check.id)
        // prompt 交给外壳 → ChatInterface 以 AGENT 模式发送
        this.$emit('start-verification', { check: res.check || check, prompt: res.prompt })
        await this.loadChecks()
        this.expandedId = check.id
      } catch (e) {
        uni.showToast({ title: this.$t('panels.smStartCheckFailed', { msg: e.message || e }), icon: 'none' })
      } finally {
        this.starting = false
      }
    },
    confirmDelete(check) {
      this.deletingCheck = check
      this.showDeleteDialog = true
    },
    async handleDelete() {
      this.showDeleteDialog = false
      if (!this.deletingCheck) return
      try {
        await api.deleteShareholderMeetingCheck(this.deletingCheck.id)
        if (this.expandedId === this.deletingCheck.id) this.expandedId = null
        await this.loadChecks()
      } catch (e) {
        uni.showToast({ title: this.$t('panels.smDeleteFailed', { msg: e.message || e }), icon: 'none' })
      } finally {
        this.deletingCheck = null
      }
    }
  }
}
</script>

<style scoped lang="scss">
.sm-sec-head {
  display: flex;
  align-items: center;
  gap: 4px;
  height: var(--awd-panel-sec-h);
  padding: 0 var(--awd-panel-pad-x);
}

.sm-sec-title {
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}

.sm-sec-spacer { flex: 1; }

.sm-add-btn {
  width: 22px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  cursor: pointer;

  &:hover { background: #e8e8e8; }
}

.sm-add-icon {
  font-size: 15px;
  color: #555;
}

.sm-create-form {
  padding: 8px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  border-bottom: 1px solid #ececec;
}

.sm-input {
  border: 1px solid #d9d9d9;
  border-radius: 5px;
  padding: 6px 8px;
  font-size: 12px;
  background: #fff;
}

.sm-form-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 2px;
}

.sm-btn {
  padding: 5px 12px;
  border-radius: 5px;
  font-size: 12px;
  cursor: pointer;
  text-align: center;

  &.primary {
    background: #1A5336;
    color: #fff;
    &:hover { opacity: 0.9; }
  }
  &.secondary {
    background: #fff;
    border: 1px solid #d1d5db;
    color: #374151;
    &:hover { background: #f5f5f5; }
  }
  &.disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.sm-check-list {
  flex: 1;
  overflow-y: auto;
}

.sm-check-item {
  border-bottom: 1px solid #f0f0f0;
}

.sm-check-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 9px 14px;
  cursor: pointer;

  &:hover { background: #f5f5f5; }
}

.sm-check-info {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.sm-check-name {
  font-size: 13px;
  font-weight: 500;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sm-check-meeting {
  font-size: 11px;
  color: #888;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.sm-check-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.sm-status {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 8px;
  background: #f0f0f0;
  color: #888;

  &.ready { background: #e6f0eb; color: #1A5336; }
  &.running { background: #fff4e0; color: #b26a00; }
  &.done { background: #e6f0eb; color: #1A5336; }
}

.sm-del-btn {
  font-size: 11px;
  color: #bbb;
  cursor: pointer;
  padding: 2px 4px;

  &:hover { color: #c0392b; }
}

.sm-check-detail {
  padding: 4px 14px 12px;
  background: #fafafa;
}

.sm-meta-line {
  font-size: 11px;
  color: #999;
  padding: 2px 0 6px;
}

.sm-slot {
  padding: 5px 0;
}

.sm-slot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sm-slot-label {
  font-size: 12px;
  color: #555;
  font-weight: 500;
}

.sm-slot-link {
  font-size: 11px;
  color: #1A5336;
  cursor: pointer;

  &:hover { text-decoration: underline; }
}

.sm-slot-files {
  padding-top: 2px;
}

.sm-slot-file {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 2px 0;
}

.sm-file-name {
  font-size: 11px;
  color: #333;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  flex: 1;
}

.sm-file-remove {
  font-size: 13px;
  color: #bbb;
  cursor: pointer;
  padding: 0 4px;
  flex-shrink: 0;

  &:hover { color: #c0392b; }
}

.sm-slot-empty {
  font-size: 11px;
  color: #bbb;
}

.sm-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-top: 8px;
}

.sm-hint {
  margin-top: 6px;
  font-size: 11px;
  color: #888;
  line-height: 1.5;
}

.sm-empty-state {
  padding: 30px 14px;
  text-align: center;
  font-size: 12px;
  color: #aaa;
}

.sm-dialog-mask {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.5);
  z-index: 9999;
  display: flex;
  align-items: center;
  justify-content: center;
}

.sm-dialog-content {
  background: #fff;
  border-radius: 10px;
  width: 320px;
  max-width: 85vw;
  overflow: hidden;
}

.sm-dialog-header {
  padding: 14px 18px 0;
}

.sm-dialog-title {
  font-size: 15px;
  font-weight: 600;
  color: #111;
}

.sm-dialog-body {
  padding: 10px 18px 16px;
  font-size: 13px;
  color: #555;
  line-height: 1.6;
}

.sm-dialog-footer {
  display: flex;
  border-top: 1px solid #eee;
}

.sm-dialog-btn {
  flex: 1;
  text-align: center;
  padding: 11px 0;
  font-size: 13px;
  cursor: pointer;

  &.cancel {
    color: #666;
    border-right: 1px solid #eee;
    &:hover { background: #f7f7f7; }
  }
  &.confirm {
    color: #1A5336;
    font-weight: 600;
    &:hover { background: #f0f7f3; }
  }
}
</style>
