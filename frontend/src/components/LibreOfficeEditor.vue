<template>
  <view class="libre-editor-wrapper">
    <!-- Product ('default') variant: NO full-width bar — it read as alien chrome
         on top of the document (user feedback). Status + save float over the
         editor's top-right corner instead; the status pill only appears while
         something is happening (booting/loading/saving/failure) and vanishes
         when ready. -->
    <view v-if="variant === 'default'" class="libre-float">
      <!-- No manual save button: edits auto-save (modify listener → debounced
           saveDocument). The pill doubles as the autosave indicator
           (保存中… / 已保存 / 保存失败). -->
      <view v-if="displayStatus" class="libre-pill" :class="{ error: isError }">
        <view v-if="!isError && !ready" class="libre-spin"></view>
        <text>{{ displayStatus }}</text>
      </view>
    </view>
    <!-- Experimental (⌘⇧O overlay) variant keeps the dev-probe toolbar. -->
    <view v-else class="libre-toolbar">
      <text class="libre-title">LibreOffice 编辑器（嵌入式 webview · 实验）</text>
      <text v-if="displayStatus" class="libre-status" :class="{ ready, error: isError }">{{ displayStatus }}</text>
      <button v-if="file" class="libre-btn" :disabled="!ready || saving" @click="saveDocument">
        {{ saving ? '保存中…' : '保存' }}
      </button>
      <button class="libre-btn" :disabled="!ready" @click="runInsert">插入示例</button>
      <button class="libre-btn" :disabled="!ready" @click="runReplace">查找替换(redline)</button>
      <button class="libre-btn" :disabled="!ready" @click="runSelection">读选区</button>
      <button class="libre-btn libre-close" @click="$emit('close')">关闭</button>
    </view>
    <!-- The Electron <webview> is created imperatively (uni-app's template
         compiler does not know the <webview> tag); it mounts into this host. -->
    <view :id="hostId" class="libre-host"></view>
    <!-- Log overlay is dev-probe UI only; product builds get the same lines via
         devtools console (appendLog mirrors there). -->
    <pre v-if="log && variant !== 'default'" class="libre-log">{{ log }}</pre>
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
  emits: ['close', 'ready', 'open-url'],
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
      statusText: '启动中…',
      log: '',
      webviewEl: null,
      executor: null,
      saving: false,
      // Autosave: set by the worker's modify signal, cleared when a save starts;
      // read by the host (closeFile / evict) to know if a flush is needed.
      dirty: false,
    }
  },
  computed: {
    isError() {
      return this.statusText.indexOf('失败') !== -1
    },
    // Product variant stays quiet once ready — no permanent "就绪" badge.
    displayStatus() {
      if (this.variant !== 'default') return this.statusText
      return this.statusText === '就绪' ? '' : this.statusText
    },
  },
  async mounted() {
    try {
      const api = typeof window !== 'undefined' && window.checkbaDesktop && window.checkbaDesktop.zetaoffice
      if (!api || typeof api.getEditor !== 'function') {
        this.statusText = '仅桌面版可用'
        return
      }
      // Prefetch the document bytes IN PARALLEL with the LOWA boot — the fetch
      // (backend download) and the engine boot are independent, so serializing
      // them (the old flow: boot → endpoint ready → fetch → load) just added the
      // whole download to the perceived open time.
      if (this.file) this.prefetchBytes()
      const info = await api.getEditor() // { url, preload, partition }
      this.mountWebview(info)
    } catch (e) {
      this.statusText = '初始化失败'
      this.appendLog('init failed: ' + (e && e.message ? e.message : e))
    }
  },
  beforeUnmount() {
    // Autosave timers die with the instance. Any still-dirty edits were flushed
    // by the closer (closeFile / evictLibreInstance await flushSave first) —
    // export needs the live webview, so saving from here is already too late.
    clearTimeout(this._saveTimer)
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
      // Mirror to devtools so the product variant (overlay hidden) stays diagnosable.
      console.log('[libre-editor]', m)
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
      // (#79) document hyperlink clicks: the editor page forwards LO's
      // window.open over lo-relay as {type:'open-url'} — surface it to the host
      // (project-overview routes checkba:// internal links / http(s) tabs).
      wv.addEventListener('ipc-message', (e) => {
        if (e.channel !== 'lo-relay') return
        const msg = e.args && e.args[0]
        if (!msg || msg.__lo !== 'lo-relay') return
        if (msg.type === 'open-url' && msg.url) {
          this.$emit('open-url', String(msg.url))
        } else if (msg.type === 'modified') {
          // Worker modify signal (throttled in editor-main.js) → autosave.
          this.onDocModified()
        }
      })
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
        this.statusText = this.file ? '加载文档中…' : '启动中…'
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
          this.statusText = '文档加载失败'
          this.appendLog('load_document failed: ' + (e && e.message ? e.message : e))
        }
      }
      this.ready = true
      if (this.statusText.indexOf('失败') === -1) this.statusText = '就绪'
      this.$emit('ready', this.executor)
    },
    // Kick off the (authed) document download without waiting for the engine.
    // loadDocument() awaits this promise; on failure it falls back to a fresh
    // fetch there so a transient prefetch error can't kill the load path.
    prefetchBytes() {
      const f = this.file
      const fileId = f && (f.wpsFileId || f.id)
      if (!fileId) return
      const t0 = Date.now()
      this._bytesPromise = this.fetchArrayBuffer(getFileDownloadUrl(fileId))
        .then((buf) => { this.appendLog('文档字节预取完成 / prefetched ' + (buf ? buf.byteLength : 0) + ' bytes in ' + (Date.now() - t0) + 'ms'); return buf })
        .catch((e) => { this.appendLog('预取失败（加载时重试）/ prefetch failed: ' + (e && e.message ? e.message : e)); return null })
    },
    async loadDocument() {
      const f = this.file
      const fileId = f.wpsFileId || f.id
      if (!fileId) throw new Error('file has no id/wpsFileId')
      const url = getFileDownloadUrl(fileId)
      let buf = this._bytesPromise ? await this._bytesPromise : null
      if (!buf) buf = await this.fetchArrayBuffer(url)
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
      const t0 = Date.now()
      const res = await this.executor.executeCommand('load_document', { bytes, name })
      this.appendLog('  ← ' + (Date.now() - t0) + 'ms ' + JSON.stringify(res))
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
    // Autosave: the worker's modify listener reports every document change
    // (typed / IME / AI command) — debounce-save on it so the user never has to
    // press anything. Idle window 2.5s; continuous typing still hits the backend
    // at least every 15s (max-wait), so a crash can't eat a long burst.
    onDocModified() {
      if (!this.ready || !this.file) return
      this.dirty = true
      if (!this._dirtySince) this._dirtySince = Date.now()
      this.scheduleAutoSave()
    },
    scheduleAutoSave() {
      clearTimeout(this._saveTimer)
      const elapsed = Date.now() - this._dirtySince
      const delay = Math.max(200, Math.min(2500, 15000 - elapsed))
      this._saveTimer = setTimeout(() => this.autoSave(), delay)
    },
    async autoSave() {
      if (this.saving) { this.scheduleAutoSave(); return } // a save is in flight — retry after it
      this.dirty = false
      this._dirtySince = 0
      const ok = await this.saveDocument()
      if (this.dirty) { this.scheduleAutoSave(); return } // edits arrived mid-save
      if (!ok) {
        // Transient failure (offline / backend hiccup): the edits are still
        // unsaved — keep them marked dirty and retry on a slow cadence.
        this.dirty = true
        this._dirtySince = Date.now()
        clearTimeout(this._saveTimer)
        this._saveTimer = setTimeout(() => this.autoSave(), 15000)
      }
    },
    // Flush before unmount (tab close / LRU evict): export needs the live
    // webview, so the closer awaits this BEFORE removing the instance.
    async flushSave() {
      clearTimeout(this._saveTimer)
      while (this.saving) await new Promise((r) => setTimeout(r, 100))
      if (this.dirty) {
        this.dirty = false
        this._dirtySince = 0
        await this.saveDocument()
      }
    },
    // Track E: save — export the edited document from the worker (storeToURL →
    // bytes) and persist via the backend upload endpoint (same fileId contract
    // as the download the document was loaded from). Autosave calls this;
    // returns true on success so autoSave can schedule failure retries.
    async saveDocument() {
      const f = this.file
      if (!f || !this.executor || this.saving) return false
      const fileId = f.wpsFileId || f.id
      if (!fileId) { this.appendLog('save: file has no id/wpsFileId'); return false }
      this.saving = true
      const prevStatus = this.statusText
      this.statusText = '保存中…'
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
        this.statusText = '已保存'
        this.appendLog('  ← saved to backend (fileId=' + fileId + ')')
        return true
      } catch (e) {
        this.statusText = '保存失败'
        this.appendLog('save failed: ' + (e && e.message ? e.message : e))
        return false
      } finally {
        this.saving = false
        // Let the badge linger, then settle back to ready (unless a failure is showing).
        setTimeout(() => { if (this.statusText === '已保存') this.statusText = prevStatus }, 2500)
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
.libre-editor-wrapper { position: relative; display: flex; flex-direction: column; width: 100%; height: 100%; background: #fff; }
/* Product variant: floating status pill + save, pinned over the editor's
   top-right corner (LO's own menubar leaves that region empty). No layout
   height is reserved — the document canvas gets the full pane. */
.libre-float { position: absolute; top: 6px; right: 16px; z-index: 20; display: flex; align-items: center; gap: 8px; }
.libre-pill { display: flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: 999px;
  background: rgba(31, 41, 55, 0.78); color: #e5e7eb; font-size: 12px; backdrop-filter: blur(4px); }
.libre-pill.error { background: rgba(153, 27, 27, 0.9); color: #fecaca; }
.libre-spin { width: 10px; height: 10px; border: 2px solid rgba(229, 231, 235, 0.35); border-top-color: #e5e7eb;
  border-radius: 50%; animation: libre-rot 0.8s linear infinite; }
@keyframes libre-rot { to { transform: rotate(360deg); } }
.libre-save { font-size: 12px; line-height: 1; padding: 5px 12px; border: 0; border-radius: 999px;
  background: #059669; color: #fff; cursor: pointer; box-shadow: 0 1px 4px rgba(0, 0, 0, 0.18); }
.libre-save[disabled] { background: #9ca3af; cursor: not-allowed; }
.libre-toolbar { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 6px 10px; background: #1F2937; color: #fff; }
.libre-title { font-size: 13px; font-weight: 600; }
.libre-status { font-size: 12px; color: #d1d5db; }
.libre-status.ready { color: #86efac; }
.libre-status.error { color: #fca5a5; }
.libre-btn { font-size: 12px; padding: 4px 10px; border: 0; border-radius: 4px; background: #059669; color: #fff; cursor: pointer; }
.libre-btn[disabled] { background: #6b7280; cursor: not-allowed; }
.libre-close { background: #4b5563; margin-left: auto; }
.libre-host { flex: 1; min-height: 0; width: 100%; }
.libre-log { position: absolute; right: 8px; bottom: 8px; width: 380px; max-height: 38vh; margin: 0;
  padding: 8px; background: #0b1220; color: #d1fae5; font: 11px/1.45 ui-monospace, monospace;
  overflow: auto; white-space: pre-wrap; word-break: break-all; border-radius: 6px; z-index: 10; }
</style>
