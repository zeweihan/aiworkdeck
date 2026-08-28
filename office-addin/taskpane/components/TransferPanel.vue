<template>
  <div class="overlay" @click.self="$emit('close')">
    <div class="panel glass">
      <div class="panel-head">
        <span>{{ t('transferPanelTitle') }}</span>
        <button class="panel-close" @click="$emit('close')">x</button>
      </div>

      <!-- 远程项目边界说明（取代 #250 的 4 秒自隐提示，原样挪到面板顶部常驻） -->
      <p class="intro">{{ t('remoteProjectNotice') }}</p>

      <div class="tabs">
        <button class="tab" :class="{ active: activeTab === 'pull' }" @click="activeTab = 'pull'">{{ t('transferTabPull') }}</button>
        <button class="tab" :class="{ active: activeTab === 'push' }" @click="activeTab = 'push'">{{ t('transferTabPush') }}</button>
      </div>

      <!-- ==================== 拉取 ==================== -->
      <div v-if="activeTab === 'pull'" class="tab-body">
        <label class="field-label">{{ t('transferDeviceLabel') }}</label>
        <select v-model="pullDeviceId" class="field-select" @change="onPullDeviceChange">
          <option value="" disabled>{{ t('transferChooseDevicePlaceholder') }}</option>
          <option v-for="d in devices" :key="d.deviceId" :value="d.deviceId">{{ deviceLabel(d) }}</option>
        </select>

        <template v-if="pullDeviceId">
          <label class="field-label">{{ t('transferProjectLabel') }}</label>
          <select v-model="pullProjectKey" class="field-select" @change="onPullProjectChange">
            <option value="" disabled>{{ t('transferChooseProjectPlaceholder') }}</option>
            <option v-for="p in pullProjects" :key="p.key" :value="p.key">{{ p.name }}</option>
          </select>
        </template>

        <p v-if="pullOffline" class="hint warn">{{ t('transferDeviceOfflineReason') }}</p>

        <button
          class="btn primary"
          :disabled="!pullDeviceId || !pullProjectKey || pullOffline || pullListStage === 'loading'"
          @click="fetchList"
        >{{ pullListStage === 'loading' ? t('transferListLoading') : t('transferFetchListBtn') }}</button>
        <p v-if="pullListStage === 'loading'" class="hint">{{ t('transferListWaitingHint') }}</p>
        <p v-if="pullListStage === 'error'" class="error-line">{{ pullListError }}</p>

        <template v-if="pullListStage === 'done'">
          <p v-if="!pullFiles.length" class="hint">{{ t('transferNoFilesFound') }}</p>
          <div v-else class="file-list">
            <button
              v-for="f in pullFiles"
              :key="f.id"
              class="file-row"
              :class="{ selected: pullSelectedFile && pullSelectedFile.id === f.id, disabled: f.size > MAX_TRANSFER_BYTES }"
              :disabled="f.size > MAX_TRANSFER_BYTES"
              @click="selectPullFile(f)"
            >
              <span class="file-name">{{ f.name }}</span>
              <span class="file-size">{{ formatBytes(f.size) }}</span>
              <span v-if="f.size > MAX_TRANSFER_BYTES" class="file-warn">{{ t('transferFileSizeTooLarge') }}</span>
            </button>
          </div>
        </template>

        <template v-if="pullSelectedFile && pullStage === 'idle'">
          <p v-if="pullQuoteLoading" class="hint">{{ t('transferQuoteLoading') }}</p>
          <p v-else-if="pullQuoteError" class="error-line">{{ pullQuoteError }}</p>
          <template v-else-if="pullQuote">
            <p class="quote-line">{{ t('transferQuoteLine', { credits: pullQuote.credits }) }}</p>
            <button class="btn primary" @click="confirmPull">{{ t('transferConfirmPull') }}</button>
          </template>
        </template>

        <template v-if="pullStage === 'pulling'">
          <p class="hint">{{ t('transferWaitingUpload') }}</p>
          <p class="hint">{{ t('transferListWaitingHint') }}</p>
          <button class="btn" :disabled="pullCancelling" @click="cancelPull">{{ pullCancelling ? t('transferCancelling') : t('transferCancelBtn') }}</button>
        </template>

        <p v-if="pullStage === 'saving'" class="hint">{{ t('transferSavingToProject') }}</p>

        <template v-if="pullStage === 'done'">
          <p class="success-line">{{ t('transferPullDone', { name: pullResult.name }) }}</p>
          <button v-if="!pullAttached" class="btn primary" @click="attachPullResult">{{ t('transferAttachToChat') }}</button>
          <p v-else class="hint">{{ t('transferAttachedDone') }}</p>
        </template>

        <p v-if="pullStage === 'error'" class="error-line">{{ pullError }}</p>
      </div>

      <!-- ==================== 投送 ==================== -->
      <div v-else class="tab-body">
        <p v-if="pushLoadingFiles" class="hint">{{ t('transferLoadingFiles') }}</p>
        <p v-else-if="!pushFiles.length" class="hint">{{ t('transferNoFilesFound') }}</p>
        <template v-else>
          <label class="field-label">{{ t('transferPushLocalFilesTitle') }}</label>
          <div class="file-list">
            <button
              v-for="f in pushFiles"
              :key="f.id"
              class="file-row"
              :class="{ selected: pushSelectedFile && pushSelectedFile.id === f.id }"
              @click="selectPushFile(f)"
            >
              <span class="file-name">{{ f.name }}</span>
              <span class="file-size">{{ formatBytes(f.size) }}</span>
            </button>
          </div>
        </template>

        <template v-if="pushSelectedFile">
          <label class="field-label">{{ t('transferTargetDeviceLabel') }}</label>
          <select v-model="pushDeviceId" class="field-select" @change="onPushDeviceChange">
            <option value="" disabled>{{ t('transferChooseDevicePlaceholder') }}</option>
            <option v-for="d in devices" :key="d.deviceId" :value="d.deviceId">{{ deviceLabel(d) }}</option>
          </select>

          <template v-if="pushDeviceId">
            <label class="field-label">{{ t('transferTargetProjectLabel') }}</label>
            <select v-model="pushProjectKey" class="field-select">
              <option value="" disabled>{{ t('transferChooseProjectPlaceholder') }}</option>
              <option v-for="p in pushProjects" :key="p.key" :value="p.key">{{ p.name }}</option>
            </select>
          </template>

          <p v-if="pushOffline" class="hint">{{ t('transferPushOfflineNotice') }}</p>

          <template v-if="pushStage === 'idle' || pushStage === 'submitting'">
            <p v-if="pushQuoteLoading" class="hint">{{ t('transferQuoteLoading') }}</p>
            <p v-else-if="pushQuoteError" class="error-line">{{ pushQuoteError }}</p>
            <template v-else-if="pushQuote">
              <p class="quote-line">{{ t('transferQuoteLine', { credits: pushQuote.credits }) }}</p>
              <button
                class="btn primary"
                :disabled="!pushDeviceId || !pushProjectKey || pushStage === 'submitting'"
                @click="submitPush"
              >{{ pushStage === 'submitting' ? t('transferCancelling') : t('transferConfirmPush') }}</button>
            </template>
          </template>

          <p v-if="pushStage === 'done'" class="success-line">{{ t('transferPushSubmitted') }}</p>
          <p v-if="pushStage === 'error'" class="error-line">{{ pushError }}</p>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { t } from '../lib/i18n.js'
import {
  transferPreset, fetchQuote, createList, fetchTransfer, createPull, saveToProject,
  createPush, cancelTransfer, pollUntil, newRequestId, fetchLocalProjectFiles, MAX_TRANSFER_BYTES
} from '../lib/transfer.js'
import { ensureAddinDefaultProject } from '../lib/api.js'
import { toggleAttachedFile } from '../lib/chatSession.js'

/**
 * 跨设备文件传输面板（dev-board#251，overlay 面板模式，挂在 App.vue 层，
 * 不依赖 ChatView 的 scoped 样式——样式自含，z-index 高于 App.vue 已有的
 * 账户菜单/新建项目弹层（40），盖住整个任务窗格）。
 */
const props = defineProps({
  devices: { type: Array, default: () => [] },
  settings: { type: Object, required: true },
  projectId: { type: String, default: '' }
})
defineEmits(['close'])

const activeTab = ref('pull')

function deviceLabel(d) {
  const name = d.deviceName || t('unknownDevice')
  return d.online ? t('remoteGroupOnline', { name }) : t('remoteGroupOffline', { name })
}

function deviceById(deviceId) {
  return (props.devices || []).find((d) => d.deviceId === deviceId) || null
}

function projectsForDevice(deviceId) {
  const d = deviceById(deviceId)
  return d ? (d.projects || []) : []
}

function formatBytes(n) {
  const num = Number(n) || 0
  if (num < 1024) return `${num} B`
  const units = ['KB', 'MB', 'GB']
  let v = num
  let i = -1
  do { v /= 1024; i += 1 } while (v >= 1024 && i < units.length - 1)
  return `${v.toFixed(v >= 10 ? 0 : 1)} ${units[i]}`
}

// ==================== 拉取 ====================

const pullDeviceId = ref('')
const pullProjectKey = ref('')
const pullDevice = computed(() => deviceById(pullDeviceId.value))
const pullProjects = computed(() => projectsForDevice(pullDeviceId.value))
const pullOffline = computed(() => pullDevice.value ? !pullDevice.value.online : false)

const pullListStage = ref('idle') // idle/loading/done/error
const pullListError = ref('')
const pullFiles = ref([])

const pullSelectedFile = ref(null)
const pullQuote = ref(null)
const pullQuoteLoading = ref(false)
const pullQuoteError = ref('')

const pullStage = ref('idle') // idle/pulling/saving/done/error
const pullTransferId = ref('')
const pullError = ref('')
const pullResult = ref(null)
const pullCancelling = ref(false)
const pullAttached = ref(false)

function resetPullFlow() {
  pullListStage.value = 'idle'
  pullListError.value = ''
  pullFiles.value = []
  pullSelectedFile.value = null
  pullQuote.value = null
  pullQuoteError.value = ''
  pullStage.value = 'idle'
  pullTransferId.value = ''
  pullError.value = ''
  pullResult.value = null
  pullAttached.value = false
}

function onPullDeviceChange() {
  pullProjectKey.value = ''
  resetPullFlow()
}

function onPullProjectChange() {
  resetPullFlow()
}

async function fetchList() {
  if (!pullDeviceId.value || !pullProjectKey.value || pullOffline.value) return
  pullListStage.value = 'loading'
  pullListError.value = ''
  pullFiles.value = []
  try {
    const id = await createList(props.settings, {
      deviceId: pullDeviceId.value, projectKey: pullProjectKey.value, requestId: newRequestId()
    })
    const tr = await pollUntil(async () => {
      const cur = await fetchTransfer(props.settings, id)
      const done = cur.status === 'DONE' || cur.status === 'FAILED' || cur.status === 'EXPIRED'
      return done ? cur : null
    }, { intervalMs: 3000, timeoutMs: 10 * 60 * 1000 })
    if (tr.status === 'DONE') {
      pullFiles.value = Array.isArray(tr.files) ? tr.files : []
      pullListStage.value = 'done'
    } else {
      pullListError.value = tr.error || t('transferBadResponse')
      pullListStage.value = 'error'
    }
  } catch (e) {
    pullListError.value = (e && e.message) || t('transferBadResponse')
    pullListStage.value = 'error'
  }
}

function selectPullFile(f) {
  if (f.size > MAX_TRANSFER_BYTES) return
  pullSelectedFile.value = f
  pullQuote.value = null
  pullQuoteError.value = ''
  pullStage.value = 'idle'
  loadPullQuote()
}

async function loadPullQuote() {
  if (!pullSelectedFile.value) return
  pullQuoteLoading.value = true
  pullQuoteError.value = ''
  try {
    pullQuote.value = await fetchQuote(props.settings, pullSelectedFile.value.size)
  } catch (e) {
    pullQuoteError.value = (e && e.message) || t('transferBadResponse')
  } finally {
    pullQuoteLoading.value = false
  }
}

async function confirmPull() {
  if (!pullSelectedFile.value || pullStage.value === 'pulling') return
  pullStage.value = 'pulling'
  pullError.value = ''
  const file = pullSelectedFile.value
  try {
    const { id } = await createPull(props.settings, {
      deviceId: pullDeviceId.value,
      projectKey: pullProjectKey.value,
      remoteFileId: file.id,
      fileName: file.name,
      fileSize: file.size,
      requestId: newRequestId()
    })
    pullTransferId.value = id
    const tr = await pollUntil(async () => {
      const cur = await fetchTransfer(props.settings, id)
      const done = cur.status === 'STAGED' || cur.status === 'FAILED' || cur.status === 'EXPIRED'
      return done ? cur : null
    }, { intervalMs: 3000, timeoutMs: 30 * 60 * 1000 })
    if (tr.status !== 'STAGED') {
      pullError.value = tr.error || t('transferBadResponse')
      pullStage.value = 'error'
      return
    }
    pullStage.value = 'saving'
    let targetProjectId = props.projectId
    if (!targetProjectId) {
      const created = await ensureAddinDefaultProject(props.settings)
      if (created) targetProjectId = String(created.id)
    }
    pullResult.value = await saveToProject(props.settings, id, parseInt(targetProjectId, 10))
    pullStage.value = 'done'
  } catch (e) {
    pullError.value = (e && e.message) || t('transferBadResponse')
    pullStage.value = 'error'
  }
}

async function cancelPull() {
  if (!pullTransferId.value || pullCancelling.value) return
  pullCancelling.value = true
  try {
    await cancelTransfer(props.settings, pullTransferId.value)
    pullStage.value = 'idle'
    pullTransferId.value = ''
  } catch (e) {
    pullError.value = (e && e.message) || t('transferBadResponse')
  } finally {
    pullCancelling.value = false
  }
}

function attachPullResult() {
  if (!pullResult.value) return
  toggleAttachedFile({ id: pullResult.value.fileId, name: pullResult.value.name, fileType: '' })
  pullAttached.value = true
}

// ==================== 投送 ====================

const pushFiles = ref([])
const pushLoadingFiles = ref(false)
const pushSelectedFile = ref(null)

const pushDeviceId = ref('')
const pushProjectKey = ref('')
const pushDevice = computed(() => deviceById(pushDeviceId.value))
const pushProjects = computed(() => projectsForDevice(pushDeviceId.value))
const pushOffline = computed(() => pushDevice.value ? !pushDevice.value.online : false)

const pushQuote = ref(null)
const pushQuoteLoading = ref(false)
const pushQuoteError = ref('')

const pushStage = ref('idle') // idle/submitting/done/error
const pushError = ref('')

async function loadPushFiles() {
  pushLoadingFiles.value = true
  try {
    pushFiles.value = await fetchLocalProjectFiles(props.settings, props.projectId)
  } finally {
    pushLoadingFiles.value = false
  }
}

function onPushDeviceChange() {
  pushProjectKey.value = ''
}

function selectPushFile(f) {
  pushSelectedFile.value = f
  pushStage.value = 'idle'
  pushError.value = ''
  loadPushQuote()
}

async function loadPushQuote() {
  if (!pushSelectedFile.value) return
  pushQuoteLoading.value = true
  pushQuoteError.value = ''
  pushQuote.value = null
  try {
    pushQuote.value = await fetchQuote(props.settings, pushSelectedFile.value.size)
  } catch (e) {
    pushQuoteError.value = (e && e.message) || t('transferBadResponse')
  } finally {
    pushQuoteLoading.value = false
  }
}

async function submitPush() {
  if (!pushSelectedFile.value || !pushDeviceId.value || !pushProjectKey.value || pushStage.value === 'submitting') return
  pushStage.value = 'submitting'
  pushError.value = ''
  try {
    await createPush(props.settings, {
      targetDeviceId: pushDeviceId.value,
      projectKey: pushProjectKey.value,
      fileId: pushSelectedFile.value.id,
      requestId: newRequestId()
    })
    pushStage.value = 'done'
  } catch (e) {
    pushError.value = (e && e.message) || t('transferBadResponse')
    pushStage.value = 'error'
  }
}

watch(activeTab, (tab) => {
  if (tab === 'push' && !pushFiles.value.length && !pushLoadingFiles.value) loadPushFiles()
})

onMounted(() => {
  const preset = transferPreset.value
  if (preset && preset.deviceId) {
    activeTab.value = 'pull'
    pullDeviceId.value = preset.deviceId
    pullProjectKey.value = preset.projectKey || ''
  } else if (activeTab.value === 'push') {
    loadPushFiles()
  }
})
</script>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: rgba(14, 33, 23, 0.32);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.panel {
  width: 100%;
  max-width: 360px;
  max-height: 92%;
  overflow-y: auto;
  border: 1px solid rgba(26, 83, 54, 0.12);
  border-radius: var(--awd-radius-md, 10px);
  box-shadow: var(--awd-shadow-float, 0 8px 32px rgba(18, 58, 38, 0.16));
  padding: 12px 14px 14px;
}

.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}

.panel-close {
  border: none;
  background: none;
  color: var(--awd-text-secondary);
  font-family: monospace;
  cursor: pointer;
  padding: 0 4px;
}

.intro {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-mint-pale, #E6F9F0);
  color: var(--awd-text-secondary);
  font-size: 11px;
  line-height: 1.5;
}

.tabs {
  display: flex;
  gap: 6px;
  margin-bottom: 10px;
}

.tab {
  flex: 1;
  padding: 6px 0;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text-secondary);
  font-size: 12px;
  font-weight: 600;
  transition: background 0.15s ease, color 0.15s ease, border-color 0.15s ease;
}

.tab.active {
  border-color: var(--awd-primary);
  color: var(--awd-primary);
  background: var(--awd-mint-pale, #E6F9F0);
}

.tab-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.field-label {
  font-size: 11px;
  font-weight: 600;
  color: var(--awd-text-secondary);
  margin-top: 4px;
}

.field-select {
  width: 100%;
  padding: 6px 8px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text);
}

.btn {
  padding: 7px 12px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
  transition: background 0.15s ease, transform 0.1s ease;
}

.btn:active { transform: translateY(1px); }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }

.btn.primary {
  background: var(--awd-primary);
  border-color: var(--awd-primary);
  color: #fff;
}

.btn.primary:hover:not(:disabled) { background: var(--awd-primary-hover); }

.hint {
  font-size: 11px;
  color: var(--awd-text-secondary);
  margin: 2px 0;
  line-height: 1.5;
}

.hint.warn { color: var(--awd-danger); }

.error-line {
  font-size: 11px;
  color: var(--awd-danger);
  margin: 2px 0;
  word-break: break-word;
}

.success-line {
  font-size: 12px;
  color: var(--awd-primary);
  font-weight: 600;
  margin: 2px 0;
}

.quote-line {
  font-size: 11px;
  color: var(--awd-text);
  background: var(--awd-mint-pale, #E6F9F0);
  border-radius: var(--awd-radius-sm, 6px);
  padding: 6px 8px;
  margin: 2px 0;
  line-height: 1.5;
}

.file-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 160px;
  overflow-y: auto;
}

.file-row {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  text-align: left;
  padding: 7px 9px;
  border: 1px solid var(--awd-border);
  border-radius: var(--awd-radius-sm, 6px);
  background: var(--awd-surface);
  color: var(--awd-text);
  font-size: 12px;
}

.file-row:hover:not(:disabled) { border-color: var(--awd-accent); }
.file-row.selected { border-color: var(--awd-primary); background: var(--awd-mint-pale, #E6F9F0); }
.file-row.disabled, .file-row:disabled { opacity: 0.5; cursor: not-allowed; }

.file-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-size {
  flex-shrink: 0;
  color: var(--awd-text-secondary);
  font-size: 11px;
}

.file-warn {
  flex-shrink: 0;
  color: var(--awd-danger);
  font-size: 10px;
}
</style>
