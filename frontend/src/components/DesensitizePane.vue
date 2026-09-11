<!-- SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors -->
<!-- SPDX-License-Identifier: AGPL-3.0-or-later -->
<template>
  <scroll-view scroll-y class="desensitize-pane">
    <view class="section">
      <view class="actions-row">
        <button class="mini-btn" :class="{ active: operation === 'redact' }" :disabled="processing" @tap="chooseOperation('redact')">{{ $t('panels.deRedactTab') }}</button>
        <button class="mini-btn" :class="{ active: operation === 'restore' }" :disabled="processing" @tap="chooseOperation('restore')">{{ $t('panels.deRestoreTab') }}</button>
      </view>
      <text class="help-text">{{ $t('panels.deLocalNotice') }}</text>
      <text class="help-text">{{ $t('panels.deWorkflow') }}</text>
      <view class="section-title">{{ $t('panels.deSectionFileSelect') }}</view>
      <view class="path-display" :class="{ empty: !filePath }" @tap="triggerFileSelect">{{ fileName || $t('panels.deFilePlaceholder') }}</view>
      <view class="actions-row">
        <button class="mini-btn" :disabled="processing" @tap="importFromActiveTab">{{ $t('panels.deImportCurrent') }}</button>
        <button class="mini-btn" :disabled="processing" @tap="triggerFileSelect">{{ $t('panels.deBrowse') }}</button>
      </view>
      <text class="help-text">{{ $t('panels.deFormats') }}</text>
    </view>

    <template v-if="operation === 'redact'">
      <view class="section">
        <view class="section-title">{{ $t('panels.deCustomWordsTitle') }}</view>
        <textarea v-model="customTerms" :disabled="processing" class="text-input custom-words-input" :maxlength="100000" :placeholder="$t('panels.deCustomPlaceholder')" />
        <text class="help-text">{{ $t('panels.deCustomWordsHint') }}</text>
      </view>
      <view class="section">
        <view class="section-title">{{ $t('panels.deModeTitle') }}</view>
        <view v-if="!isPdf" class="actions-row">
          <button class="mini-btn" :class="{ active: mode === 'TOKEN' }" :disabled="processing" @tap="mode = 'TOKEN'">{{ $t('panels.deTokenMode') }}</button>
          <button class="mini-btn" :class="{ active: mode === 'MASK' }" :disabled="processing" @tap="mode = 'MASK'">{{ $t('panels.deMaskMode') }}</button>
        </view>
        <text class="help-text">{{ $t(isPdf ? 'panels.dePdfNotice' : effectiveMode === 'TOKEN' ? 'panels.deTokenNotice' : 'panels.deMaskNotice') }}</text>
      </view>
      <view class="section">
        <view class="section-title">{{ $t('panels.deStrategiesTitle') }}</view>
        <view class="strategies-list">
          <label v-for="s in availableStrategies" :key="s.value" class="strategy-item" @tap="toggleStrategy(s.value)">
            <view class="checkbox" :class="{ checked: selectedStrategies.includes(s.value) }"><text v-if="selectedStrategies.includes(s.value)" class="check-mark">✓</text></view>
            <text class="strategy-label">{{ s.label }}</text>
          </label>
        </view>
        <view class="section-title">{{ $t('panels.deExcludedTerms') }}</view>
        <textarea v-model="excludedTerms" :disabled="processing" class="text-input" :maxlength="100000" :placeholder="$t('panels.deExcludedPlaceholder')" />
      </view>
      <view class="section">
        <button class="workdeck-btn full-width" :disabled="processing || !fileId || (!selectedStrategies.length && !customTerms.trim())" @tap="handlePreview">{{ $t('panels.dePreview') }}</button>
        <view v-if="preview" class="preview-text">{{ preview.text }}</view>
        <template v-if="effectiveMode === 'TOKEN'">
          <view class="section-title">{{ $t('panels.dePassword') }}</view>
          <input v-model="password" :disabled="processing" class="text-input password-input" password :maxlength="1024" :placeholder="$t('panels.dePasswordHint')" />
        </template>
        <button class="workdeck-btn workdeck-btn-primary full-width" :loading="processing" :disabled="processing || !preview || (effectiveMode === 'TOKEN' && password.length < 10)" @tap="handleGenerate">{{ $t('panels.deGenerate') }}</button>
      </view>
    </template>

    <view v-else class="section">
      <text class="help-text">{{ $t('panels.deRestoreNotice') }}</text>
      <button class="workdeck-btn full-width" :disabled="processing" @tap="importKit">{{ kitName || $t('panels.deImportKit') }}</button>
      <view class="section-title">{{ $t('panels.dePassword') }}</view>
      <input v-model="password" :disabled="processing" class="text-input password-input" password :maxlength="1024" :placeholder="$t('panels.dePasswordHint')" />
      <button class="workdeck-btn workdeck-btn-primary full-width" :loading="processing" :disabled="processing || !fileId || isPdf || !recoveryKit || password.length < 10" @tap="handleRestore">{{ $t('panels.deRestoreGenerate') }}</button>
    </view>

    <view v-if="preview || result" class="section">
      <text class="help-text">{{ $t('panels.deMatchCount', { count: totalMatches }) }}</text>
      <text v-for="(warning, i) in (result?.warnings || preview?.warnings || [])" :key="i" class="help-text">{{ warning }}</text>
    </view>
    <view v-if="result?.file && operation === 'redact'" class="section">
      <text class="help-text">{{ $t(isPdf ? 'panels.dePdfResult' : 'panels.deResultEditing') }}</text>
      <button v-if="exportedKit" class="workdeck-btn full-width" :disabled="processing" @tap="chooseOperation('restore')">{{ $t('panels.deRestoreResult') }}</button>
      <text class="help-text">{{ $t('panels.deAiWorkflowNotice') }}</text>
    </view>
    <view v-if="exportedKit" class="section">
      <button class="workdeck-btn full-width" @tap="downloadKit">{{ $t('panels.deDownloadKit') }}</button>
      <text class="help-text">{{ $t('panels.deSaveKitNotice') }}</text>
    </view>
    <view v-if="error" class="section error-text">{{ error }}</view>
  </scroll-view>
</template>

<script>
import { desensitizeFile, getSensitiveOptions, previewSensitiveFile, restoreSensitiveFile } from '@/services/api.js'

export default {
  name: 'DesensitizePane',
  emits: ['request-file-select', 'request-active-file', 'open-file'],
  props: {
    projectId: { type: [String, Number], required: true },
    prepareFile: { type: Function, required: true },
  },
  data() {
    return {
      operation: 'redact', mode: 'TOKEN', filePath: '', fileName: '', fileId: null,
      availableStrategies: [], selectedStrategies: [], customTerms: '', excludedTerms: '',
      password: '', recoveryKit: '', kitName: '', exportedKit: '', processing: false,
      preview: null, result: null, error: '', lastRedaction: null,
    }
  },
  computed: {
    isPdf() { return this.fileName.toLowerCase().endsWith('.pdf') },
    effectiveMode() { return this.isPdf ? 'MASK' : this.mode },
    requestSignature() {
      return JSON.stringify([this.fileId, this.selectedStrategies, this.customTerms, this.excludedTerms, this.effectiveMode])
    },
    totalMatches() { return Object.values(this.result?.counts || this.preview?.counts || {}).reduce((a, b) => a + b, 0) },
  },
  watch: {
    requestSignature() { this.preview = null; this.result = null; this.error = '' },
    operation() { this.result = null; this.error = '' },
    projectId() {
      this.fileId = null; this.filePath = ''; this.fileName = ''; this.password = ''
      this.recoveryKit = ''; this.exportedKit = ''; this.kitName = ''; this.preview = null; this.result = null
      this.customTerms = ''; this.excludedTerms = ''; this.lastRedaction = null
    },
  },
  mounted() { this.fetchOptions() },
  methods: {
    async fetchOptions() {
      try {
        const res = await getSensitiveOptions()
        this.availableStrategies = Array.isArray(res) ? res : res?.data || []
        this.selectedStrategies = this.availableStrategies.filter(s => ['COMPANY', 'CHINESE_NAME', 'PHONE', 'ID_CARD', 'EMAIL', 'BANK_CARD'].includes(s.value)).map(s => s.value)
      } catch (e) { this.error = this.$t('panels.deFetchStrategiesFailed') }
    },
    chooseOperation(operation) {
      if (this.processing || this.operation === operation) return
      this.operation = operation
      this.preview = null; this.result = null; this.error = ''; this.password = ''
      if (operation === 'restore' && this.lastRedaction?.kit) {
        this.selectFile(this.lastRedaction.file)
        this.recoveryKit = this.lastRedaction.kit
        this.kitName = this.$t('panels.deCurrentKit')
      } else if (operation === 'redact' && this.lastRedaction?.source) {
        this.selectFile(this.lastRedaction.source)
      }
    },
    toggleStrategy(value) {
      if (this.processing) return
      this.selectedStrategies = this.selectedStrategies.includes(value)
        ? this.selectedStrategies.filter(s => s !== value) : [...this.selectedStrategies, value]
    },
    selectFile(file) {
      if (this.processing) return
      if (!file?.id || !(file.filePath || file.path)) {
        this.error = this.$t('panels.deSelectValidFile'); return
      }
      this.preview = null; this.result = null; this.error = ''
      this.filePath = file.filePath || file.path; this.fileName = file.name; this.fileId = file.id
    },
    triggerFileSelect() {
      if (!this.processing) this.$emit('request-file-select', file => { if (file) this.selectFile(file) })
    },
    importFromActiveTab() {
      if (!this.processing) this.$emit('request-active-file', file => this.selectFile(file))
    },
    payload() {
      const lines = value => value.split(/\r?\n/).map(s => s.trim()).filter(Boolean)
      return { fileId: this.fileId, strategies: this.selectedStrategies, mode: this.effectiveMode,
        customTerms: lines(this.customTerms), excludedTerms: lines(this.excludedTerms) }
    },
    async handlePreview() {
      if (this.processing || !this.fileId) return
      this.processing = true; this.error = ''; this.result = null
      try {
        await this.prepareFile(this.fileId)
        this.preview = await previewSensitiveFile(this.payload())
      }
      catch (e) { this.error = e.message; this.preview = null }
      finally { this.processing = false }
    },
    async handleGenerate() {
      if (this.processing || !this.preview || !this.fileId) return
      this.processing = true; this.error = ''
      try {
        const changed = await this.prepareFile(this.fileId)
        if (changed) {
          this.preview = null
          throw new Error(this.$t('panels.dePreviewChanged'))
        }
        const source = { id: this.fileId, name: this.fileName, filePath: this.filePath }
        const res = await desensitizeFile({ ...this.payload(), password: this.password })
        this.result = res
        this.exportedKit = res.recoveryKit || ''; this.preview = null
        this.lastRedaction = res.recoveryKit ? { source, file: res.file, kit: res.recoveryKit } : null
        if (this.exportedKit) {
          try { this.downloadKit() }
          catch (e) { this.error = this.$t('panels.deDownloadRetry') }
        }
        this.password = ''
        if (res.file?.id) this.$emit('open-file', res.file)
      } catch (e) { this.error = e.message }
      finally { this.processing = false }
    },
    downloadKit() {
      if (!this.exportedKit) return
      const url = URL.createObjectURL(new Blob([this.exportedKit], { type: 'application/octet-stream' }))
      const link = document.createElement('a')
      link.href = url; link.download = `recovery-${Date.now()}.awd-recovery`
      document.body.appendChild(link); link.click(); link.remove()
      setTimeout(() => URL.revokeObjectURL(url), 1000)
    },
    importKit() {
      if (this.processing) return
      const input = document.createElement('input')
      input.type = 'file'; input.accept = '.awd-recovery'
      input.onchange = async () => {
        const file = input.files?.[0]
        if (!file) return
        if (file.size > 8000000) { this.error = this.$t('panels.deKitInvalid'); return }
        try {
          const kit = (await file.text()).trim()
          if (!kit.startsWith('AWD-RECOVERY-1:')) throw new Error(this.$t('panels.deKitInvalid'))
          this.recoveryKit = kit; this.kitName = file.name; this.error = ''
        } catch (e) { this.error = e.message }
      }
      input.click()
    },
    async handleRestore() {
      if (this.processing || !this.fileId || !this.recoveryKit) return
      this.processing = true; this.error = ''; this.result = null
      try {
        await this.prepareFile(this.fileId)
        const res = await restoreSensitiveFile({ fileId: this.fileId, recoveryKit: this.recoveryKit, password: this.password })
        this.result = res; this.password = ''
        if (res.file?.id) this.$emit('open-file', res.file)
      } catch (e) { this.error = e.message }
      finally { this.processing = false }
    },
  },
}
</script>

<style scoped>
/* 密度令牌见 App.vue 的 --awd-panel-*（基准 = 插件广场）。
   原来是「灰底 + 白卡片 + 16px 内边距 + 1px 描边」三层套娃，在 260px 宽里
   实际可用宽度只剩 196px。 */
.desensitize-pane {
  height: 100%;
  background-color: var(--awd-surface);
  box-sizing: border-box;
}

.section {
  padding: 0 var(--awd-panel-pad-x) var(--awd-panel-gap-lg);
  background: var(--awd-surface);
}

.section-title {
  display: flex;
  align-items: center;
  height: var(--awd-panel-sec-h);
  font-size: var(--awd-panel-fs-sec);
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--awd-panel-text-2);
}


.path-display {
    padding: 6px 8px;
    background: var(--awd-bg);
    border: 1px dashed var(--awd-border-strong);
    border-radius: var(--awd-panel-radius);
    font-size: var(--awd-panel-fs);
    color: var(--awd-panel-text);
    word-break: break-all;
    min-height: var(--awd-panel-row-h);
    box-sizing: border-box;
    display: flex;
    align-items: center;
    cursor: pointer;
}
.path-display.empty {
    color: var(--awd-text-3);
    justify-content: center;
}

.actions-row {
    display: flex;
    gap: 6px;
}

.mini-btn {
    flex: 1;
    height: 24px;
    line-height: 22px;
    text-align: center;
    background: var(--awd-surface);
    border: 1px solid var(--awd-panel-border);
    border-radius: 4px;
    font-size: var(--awd-panel-fs-meta);
    color: var(--awd-text-2);
    cursor: pointer;
    transition: all 0.2s;
}
.mini-btn:hover {
    background: var(--awd-bg);
    border-color: var(--awd-border-strong);
}

.strategies-list {
    display: flex;
    flex-direction: column;
    gap: 0;
}

.strategy-item {
    display: flex;
    align-items: center;
    cursor: pointer;
    height: 24px;
}
.strategy-item:hover { background: var(--awd-panel-accent-wash); }

.checkbox {
    width: 14px;
    height: 14px;
    flex-shrink: 0;
    border: 1px solid var(--awd-border-strong);
    border-radius: 3px;
    margin-right: 6px;
    display: flex;
    align-items: center;
    justify-content: center;
    transition: all 0.2s;
}
.checkbox.checked {
    background-color: var(--awd-accent);
    border-color: var(--awd-accent);
}
.check-mark {
    color: var(--awd-text-on-accent);
    font-size: 10px;
}

.strategy-label {
    font-size: var(--awd-panel-fs);
    color: var(--awd-panel-text);
}


.workdeck-btn {
  display: flex;
  justify-content: center;
  align-items: center;
  height: 32px;
  border-radius: var(--awd-panel-radius);
  font-size: var(--awd-panel-fs);
  font-weight: 600;
  cursor: pointer;
  border: none;
  background-color: var(--awd-accent);
  color: var(--awd-text-on-accent);
  transition: opacity 0.2s;
}
.workdeck-btn:disabled {
    opacity: 0.6;
    cursor: not-allowed;
}

.help-text { display: block; color: var(--awd-text-2); font-size: 12px; line-height: 1.6; margin: 8px 0; }
.text-input { width: 100%; box-sizing: border-box; min-height: 64px; height: 76px; border: 1px solid var(--awd-border); border-radius: 4px; padding: 8px; color: var(--awd-text); background: var(--awd-surface); font-size: 12px; }
.password-input { min-height: 36px; height: 36px; margin-bottom: 12px; }
.preview-text { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 300px; overflow: auto; padding: 8px; margin: 8px 0; border: 1px solid var(--awd-border); font-size: 12px; line-height: 1.7; user-select: text; }
.mini-btn.active { color: var(--awd-accent); border-color: currentColor; }
button.mini-btn { min-height: 28px; height: auto; padding: 4px 6px; line-height: 1.5; margin: 0; white-space: normal; }
.error-text { color: #b42318; font-size: 12px; }
</style>
