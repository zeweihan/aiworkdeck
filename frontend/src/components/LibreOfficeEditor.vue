<template>
  <view class="libre-editor-wrapper">
    <view class="libre-toolbar">
      <text class="libre-title">LibreOffice 编辑器（嵌入式 webview · 实验）</text>
      <text class="libre-status" :class="{ ready }">{{ statusText }}</text>
      <button class="libre-btn" :disabled="!ready" @click="runInsert">插入示例</button>
      <button class="libre-btn" :disabled="!ready" @click="runReplace">查找替换(redline)</button>
      <button class="libre-btn" :disabled="!ready" @click="runSelection">读选区</button>
      <button class="libre-btn libre-close" @click="$emit('close')">关闭</button>
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

let seq = 0

export default {
  name: 'LibreOfficeEditor',
  emits: ['close', 'ready'],
  data() {
    return {
      hostId: 'libre-host-' + (++seq),
      ready: false,
      statusText: '启动中… / booting…',
      log: '',
      webviewEl: null,
      executor: null,
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
        this.executor = createWebviewEditorExecutor(wv)
        this.ready = true
        this.statusText = '就绪 / ready ✓'
        this.appendLog('webview dom-ready — executor wired')
        this.$emit('ready', this.executor)
      } catch (e) {
        this.appendLog('executor wiring failed: ' + (e && e.message ? e.message : e))
      }
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
