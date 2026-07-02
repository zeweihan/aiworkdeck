<template>
  <view class="libre-editor-wrapper">
    <view class="libre-toolbar">
      <text class="libre-title">{{ variant === 'default' ? 'LibreOffice 编辑器' : 'LibreOffice 编辑器（嵌入式 webview · 实验）' }}</text>
      <text class="libre-status" :class="{ ready }">{{ statusText }}</text>
      <!-- Save is REAL product UI (Track E): shown in every variant whenever the
           editor is bound to a backend file — without it edits die with the tab. -->
      <button v-if="file" class="libre-btn" :disabled="!ready || saving" @click="saveDocument">
        {{ saving ? '保存中…' : '保存' }}
      </button>
      <!-- The dev probe buttons + close are the ⌘⇧O experimental overlay's
           controls. In the inline 'default' variant (Track B: embedded editor is
           the document's default editor) they are hidden — the document tab's ×
           closes it and the AI agent drives commands. -->
      <template v-if="variant !== 'default'">
        <button class="libre-btn" :disabled="!ready" @click="runInsert">插入示例</button>
        <button class="libre-btn" :disabled="!ready" @click="runReplace">查找替换(redline)</button>
        <button class="libre-btn" :disabled="!ready" @click="runSelection">读选区</button>
        <button class="libre-btn libre-close" @click="$emit('close')">关闭</button>
      </template>
    </view>
    <!-- The Electron <webview> is created imperatively (uni-app's template
         compiler does not know the <webview> tag); it mounts into this host. -->
    <view :id="hostId" class="libre-host"></view>
    <pre v-if="log" class="libre-log">{{ log }}</pre>
  </view>
</template>

<script>
// LibreOfficeEditor.vue — HOST-side embed of the LibreOffice editor as an
// Electron <webview partition="persist:zetaoffice"> inside the MAIN renderer.
// Epic #43.
//
// This is the activation of the dormant foundation: the webview loads
// dist/zetaoffice/editor.html over the shared same-origin server, gets COOP/COEP
// isolation from desktop/main/zetaoffice-session.js (so LOWA's SharedArrayBuffer
// works), and is wrapped by createWebviewEditorExecutor (#52) into the standard
// executeCommand(action, params) contract. The toolbar buttons drive that
// executor directly — proving the host -> webview IPC -> office worker -> UNO ->
// real LibreOffice round-trip on the device (the last unverified link before the
// backend agent stream is routed through useEditorBridge).
//
// Self-contained / dormant: nothing renders this unless explicitly mounted
// (⌘⇧O overlay), so the WPS document flow is byte-for-byte unaffected.

import { createWebviewEditorExecutor } from '@/composables/useZetaOfficeWebview.js'
import { getFileDownloadUrl, getFileUploadUrl } from '@/services/api.js'
import { getAuthHeaders } from '@/utils/auth.js'

let seq = 0

export default {
  name: 'LibreOfficeEditor',
  emits: ['close', 'ready'],
  props: {
    // 'experimental' = the original ⌘⇧O overlay (dev probe toolbar shown).
    // 'default'      = inline document editor (Track B): toolbar chrome hidden.
    variant: { type: String, default: 'experimental' },
    // Track D: the Office file to load into the editor ({ id, name, fileType,
    // wpsFileId }). When set, the editor fetches its bytes (authed) and loads the
    // REAL document once the office endpoint is ready. null (⌘⇧O overlay) keeps
    // the seeded prototype.
    file: { type: Object, default: null },
  },
  data() {
    return {
      hostId: 'libre-host-' + (++seq),
      ready: false,
      statusText: '启动中… / booting…',
      log: '',
      webviewEl: null,
      executor: null,
      saving: false,
    }
  },
  async mounted() {
    try {
      const api = typeof window !== 'undefined' && window.checkbaDesktop && window.checkbaDesktop.zetaoffice
      if (!api || typeof api.getEditor !== 'function') {
        this.statusText = '仅桌面版可用 / desktop only'
        return
      }
      const info = await api.getEditor() // { url, preload, partition }
      this.mountWebview(info)
    } catch (e) {
      this.statusText = '初始化失败 / init failed'
      this.appendLog('init failed: ' + (e && e.message ? e.message : e))
    }
  },
  beforeUnmount() {
    // Tell the host this editor (and its executor) is going away — so it can
    // stop routing AI commands to a disposed executor when the document tab is
    // closed or switched. The executor ref lets the host ignore stale closes.
    try { this.$emit('close', this.executor) } catch (e) { /* ignore */ }
    try { if (this.executor && typeof this.executor.dispose === 'function') this.executor.dispose() } catch (e) { /* ignore */ }
    try { if (this.webviewEl && this.webviewEl.remove) this.webviewEl.remove() } catch (e) { /* ignore */ }
    this.webviewEl = null
    this.executor = null
  },
  methods: {
    appendLog(m) {
      this.log = (this.log + m + '\n').split('\n').slice(-200).join('\n')
    },
    mountWebview(info) {
      const host = document.getElementById(this.hostId)
      if (!host) { this.appendLog('host element missing'); return }
      const wv = document.createElement('webview')
      wv.setAttribute('partition', info.partition)
      if (info.preload) wv.setAttribute('preload', info.preload)
      // contextIsolation ON (the preload uses contextBridge), nodeIntegration OFF.
      wv.setAttribute('webpreferences', 'contextIsolation=yes,nodeIntegration=no')
      wv.style.width = '100%'
      wv.style.height = '100%'
      wv.style.border = '0'
      wv.addEventListener('dom-ready', () => this.onDomReady(wv))
      wv.addEventListener('did-fail-load', (e) => this.appendLog('did-fail-load: ' + (e.errorDescription || e.errorCode)))
      wv.addEventListener('console-message', (e) => { if (e.level >= 2) this.appendLog('[webview] ' + e.message) })
      wv.setAttribute('src', info.url)
      host.appendChild(wv)
      this.webviewEl = wv
    },
    onDomReady(wv) {
      if (this.executor) return // dom-ready can fire again on in-page navigation
      try {
        // dom-ready means the webview's renderer is up — NOT that the office is
        // booted. We wait for the endpoint-ready handshake (onReady) before
        // marking ready / pushing the document, so load_document can't be dropped
        // pre-boot. The dev-probe toolbar in the experimental variant stays
        // disabled until then (ready=false).
        this.executor = createWebviewEditorExecutor(wv, { onReady: () => this.onEndpointReady() })
        this.statusText = this.file ? '加载文档中… / loading…' : '启动中… / booting…'
        this.appendLog('webview dom-ready — executor wired, awaiting office endpoint')
      } catch (e) {
        this.appendLog('executor wiring failed: ' + (e && e.message ? e.message : e))
      }
    },
    // The office endpoint inside the webview is booted and serving. Load the real
    // document (Track D) if we have one, then publish readiness so the host
    // starts routing AI commands to the (now correctly-targeted) editor.
    async onEndpointReady() {
      if (!this.executor) return // unmounted during boot
      if (this.file) {
        try {
          await this.loadDocument()
        } catch (e) {
          // Load failed → the seeded prototype is still showing. Surface it; the
          // editor stays usable (AI/IME act on whatever is shown) but the content
          // is wrong, so this is loud, not silent.
          this.statusText = '文档加载失败 / load failed'
          this.appendLog('load_document failed: ' + (e && e.message ? e.message : e))
        }
      }
      this.ready = true
      if (this.statusText.indexOf('失败') === -1) this.statusText = '就绪 / ready ✓'
      this.$emit('ready', this.executor)
    },
    async loadDocument() {
      const f = this.file
      const fileId = f.wpsFileId || f.id
      if (!fileId) throw new Error('file has no id/wpsFileId')
      const url = getFileDownloadUrl(fileId)
      const buf = await this.fetchArrayBuffer(url)
      const bytes = new Uint8Array(buf || new ArrayBuffer(0))
      const name = f.name || (String(fileId) + '.' + String(f.fileType || 'docx'))
      // Empty body = a brand-new / unsaved document — the backend streams HTTP
      // 200 with 0 bytes for it. That's NOT a load failure: keep the clean blank
      // editor the worker booted (the user edits + saves into it). Only a real
      // fetch error (non-200, handled in fetchArrayBuffer) surfaces as failed.
      if (bytes.length === 0) {
        this.appendLog('文档为空（新建/未保存）→ 显示空白文档 / empty doc → blank editor: ' + name)
        return
      }
      this.appendLog('▶ load_document「' + name + '」(' + bytes.length + ' bytes) …')
      const res = await this.executor.executeCommand('load_document', { bytes, name })
      this.appendLog('  ← ' + JSON.stringify(res))
      if (!res || !res.success) throw new Error((res && res.message) || 'load_document returned no success')
    },
    // Authed binary fetch — same XHR auth pattern as FilePreview.fetchAuthedBlob,
    // but ArrayBuffer (the bytes we relay into the worker).
    fetchArrayBuffer(url) {
      return new Promise((resolve, reject) => {
        const headers = getAuthHeaders() || {}
        const xhr = new XMLHttpRequest()
        xhr.open('GET', url, true)
        xhr.responseType = 'arraybuffer'
        Object.keys(headers).forEach((k) => xhr.setRequestHeader(k, headers[k]))
        xhr.onload = () => (xhr.status === 200 ? resolve(xhr.response) : reject(new Error('HTTP ' + xhr.status)))
        xhr.onerror = () => reject(new Error('网络错误 / network error'))
        xhr.send()
      })
    },
    // Track E: save — export the edited document from the worker (storeToURL →
    // bytes) and persist via the backend upload endpoint (same fileId contract
    // as the download the document was loaded from). Without this, edits die
    // with the tab — the last functional gap vs. the WPS editor.
    async saveDocument() {
      const f = this.file
      if (!f || !this.executor || this.saving) return
      const fileId = f.wpsFileId || f.id
      if (!fileId) { this.appendLog('save: file has no id/wpsFileId'); return }
      this.saving = true
      const prevStatus = this.statusText
      this.statusText = '保存中… / saving…'
      try {
        const name = f.name || (String(fileId) + '.' + String(f.fileType || 'docx'))
        this.appendLog('▶ export_document「' + name + '」…')
        const res = await this.executor.executeCommand('export_document', { name })
        if (!res || !res.success) throw new Error((res && res.message) || 'export_document returned no success')
        // Structured clone across the relay hops may surface bytes as
        // Uint8Array / ArrayBuffer / plain array — normalize (mirror of the
        // worker's load_document normalization).
        const raw = res.bytes
        let u8 = null
        if (raw instanceof Uint8Array) u8 = raw
        else if (raw instanceof ArrayBuffer) u8 = new Uint8Array(raw)
        else if (raw && raw.buffer instanceof ArrayBuffer) u8 = new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength)
        else if (Array.isArray(raw)) u8 = new Uint8Array(raw)
        if (!u8 || u8.length === 0) throw new Error('export produced no bytes')
        this.appendLog('  ← exported ' + u8.length + ' bytes, uploading…')
        await this.uploadBytes(getFileUploadUrl(fileId), u8, name)
        this.statusText = '已保存 / saved ✓'
        this.appendLog('  ← saved to backend (fileId=' + fileId + ')')
      } catch (e) {
        this.statusText = '保存失败 / save failed'
        this.appendLog('save failed: ' + (e && e.message ? e.message : e))
      } finally {
        this.saving = false
        // Let the badge linger, then settle back to ready (unless a failure is showing).
        setTimeout(() => { if (this.statusText === '已保存 / saved ✓') this.statusText = prevStatus }, 2500)
      }
    },
    // Authed multipart POST — the upload twin of fetchArrayBuffer (backend
    // contract: POST /api/files/{fileId}/upload, part name "file").
    uploadBytes(url, u8, filename) {
      return new Promise((resolve, reject) => {
        const headers = getAuthHeaders() || {}
        const form = new FormData()
        form.append('file', new Blob([u8]), filename)
        const xhr = new XMLHttpRequest()
        xhr.open('POST', url, true)
        Object.keys(headers).forEach((k) => { if (k.toLowerCase() !== 'content-type') xhr.setRequestHeader(k, headers[k]) })
        xhr.onload = () => (xhr.status >= 200 && xhr.status < 300 ? resolve(xhr.response) : reject(new Error('HTTP ' + xhr.status)))
        xhr.onerror = () => reject(new Error('网络错误 / network error'))
        xhr.send(form)
      })
    },
    async run(label, action, params) {
      if (!this.executor) return
      this.appendLog('▶ ' + label + ' …')
      try { this.appendLog('  ← ' + JSON.stringify(await this.executor.executeCommand(action, params))) }
      catch (e) { this.appendLog('  ✗ ' + (e && e.message ? e.message : e)) }
    },
    runInsert() {
      this.run('插入示例', 'insert_at_cursor',
        { text: '本协议由甲方与乙方于本日签订；协议自双方签署之日起生效。' })
    },
    runReplace() {
      this.run('查找替换 协议→合同 (redline)', 'find_replace',
        { findText: '协议', replaceText: '合同', replaceAll: true })
    },
    runSelection() { this.run('读选区', 'get_selection', {}) },
  },
}
</script>

<style scoped>
.libre-editor-wrapper { display: flex; flex-direction: column; width: 100%; height: 100%; background: #fff; }
.libre-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 6px 10px; background: #1F2937; color: #fff; }
.libre-title { font-size: 13px; font-weight: 600; }
.libre-status { font-size: 12px; color: #fca5a5; }
.libre-status.ready { color: #86efac; }
.libre-btn { font-size: 12px; padding: 4px 10px; border: 0; border-radius: 4px; background: #059669; color: #fff; cursor: pointer; }
.libre-btn[disabled] { background: #6b7280; cursor: not-allowed; }
.libre-close { background: #4b5563; margin-left: auto; }
.libre-host { flex: 1; min-height: 0; width: 100%; }
.libre-log { position: absolute; right: 8px; bottom: 8px; width: 380px; max-height: 38vh; margin: 0;
  padding: 8px; background: #0b1220; color: #d1fae5; font: 11px/1.45 ui-monospace, monospace;
  overflow: auto; white-space: pre-wrap; word-break: break-all; border-radius: 6px; z-index: 10; }
</style>
