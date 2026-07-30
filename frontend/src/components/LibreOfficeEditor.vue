<template>
  <view class="libre-editor-wrapper">
    <!-- NO full-width bar — it read as alien chrome on top of the document
         (user feedback). Status floats over the editor's top-right corner;
         the pill only appears while something is happening (saving/failure)
         and vanishes when ready. -->
    <view class="libre-float">
      <!-- No manual save button: edits auto-save (modify listener → debounced
           saveDocument). The pill doubles as the autosave indicator
           (保存中… / 已保存 / 保存失败). Boot/load 阶段由下方进度面板展示，
           pill 只负责就绪后的保存状态。 -->
      <view v-if="displayStatus && !loadingOverlayVisible" class="libre-pill" :class="{ error: isError }">
        <view v-if="!isError && !ready" class="libre-spin"></view>
        <text>{{ displayStatus }}</text>
      </view>
    </view>
    <!-- 加载进度面板：引擎启动 + 文档下载/打开是感知最慢的一段（尤其大文档），
         把过程阶段化展示出来（用户反馈：不能更快，也要看得见进展）。 -->
    <view v-if="loadingOverlayVisible" class="libre-loading">
      <!-- 只读预览接力：文档字节一到就先用 docx-preview 本地渲出可读内容，
           引擎继续后台 boot；就绪后 overlay 整体消失、无缝换入可编辑视图。
           渲染失败/非 Word/空文档保持原进度卡片。 -->
      <view v-show="previewReady" class="libre-preview-strip">
        <view class="libre-strip-track"><view class="libre-strip-fill" :style="{ width: bootPct + '%' }"></view></view>
        <text class="libre-strip-text">{{ bootStage }} {{ Math.round(bootPct) }}% — 已可阅读，编辑器就绪后可直接编辑</text>
      </view>
      <view v-show="previewReady" ref="docxPreviewHost" class="libre-preview-host"></view>
      <view v-if="!previewReady" class="libre-loading-card">
        <view class="libre-doc-icon">
          <view class="doc-fold"></view>
          <view class="doc-line l1"></view>
          <view class="doc-line l2"></view>
          <view class="doc-line l3"></view>
        </view>
        <text class="libre-loading-name">{{ loadingTitle }}</text>
        <view class="libre-progress-track">
          <view class="libre-progress-fill" :style="{ width: bootPct + '%' }">
            <view class="libre-progress-shimmer"></view>
          </view>
        </view>
        <view class="libre-loading-meta">
          <text class="libre-loading-stage">{{ bootStage }}</text>
          <text class="libre-loading-pct">{{ Math.round(bootPct) }}%</text>
        </view>
        <text v-if="dlText" class="libre-loading-dl">{{ dlText }}</text>
        <text class="libre-loading-hint">首次打开需初始化文档引擎，大文档会稍慢，请稍候</text>
      </view>
    </view>
    <!-- The Electron <webview> is created imperatively (uni-app's template
         compiler does not know the <webview> tag); it mounts into this host. -->
    <view :id="hostId" class="libre-host"></view>
  </view>
</template>

<script>
// LibreOfficeEditor.vue — HOST-side embed of the LibreOffice editor as an
// Electron <webview partition="persist:zetaoffice"> inside the MAIN renderer.
// Epic #43.
//
// The webview loads dist/zetaoffice/editor.html over the shared same-origin
// server, gets COOP/COEP isolation from desktop/main/zetaoffice-session.js (so
// LOWA's SharedArrayBuffer works), and is wrapped by createWebviewEditorExecutor
// (#52) into the standard executeCommand(action, params) contract. Mounted only
// by the project-overview keep-alive pool as the product inline document editor
// （原 ⌘⇧O 实验覆盖层与探针工具栏已移除）.

import { createWebviewEditorExecutor } from '@/composables/useZetaOfficeWebview.js'
import { getFileDownloadUrl, getFileUploadUrl } from '@/services/api.js'
import { getAuthHeaders, getCurrentUser } from '@/utils/auth.js'

let seq = 0

export default {
  name: 'LibreOfficeEditor',
  emits: ['close', 'ready', 'open-url'],
  props: {
    // Track D: the Office file to load into the editor ({ id, name, fileType,
    // wpsFileId }). When set, the editor fetches its bytes (authed) and loads the
    // REAL document once the office endpoint is ready.
    // （原 'experimental' ⌘⇧O 探针工具栏变体已移除：产品只有内联编辑器一种形态。）
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
      // 数据安全闸：load_document 失败（或非空文件下载到 0 字节）后置位。此时
      // worker 里还是空白 boot 文档，任何保存都会用空文档覆盖后端真文件——
      // autosave/flushSave 一律拒绝。不能复用 isError（'保存失败' 也含'失败'，
      // 会误杀保存重试）。
      docLoadFailed: false,
      // 加载进度面板状态：bootPct 按里程碑推进（其间缓慢滴答，避免看起来卡死），
      // bootCap 是当前阶段允许滴到的上限；dl* 是文档字节下载进度。
      bootPct: 3,
      bootCap: 12,
      bootStage: '正在启动文档引擎',
      dlLoaded: 0,
      dlTotal: 0,
      // 只读预览接力：字节预取完成后 docx-preview 渲染成功置 previewReady，
      // overlay 从进度卡片切换为可滚动阅读的文档 + 顶部细进度条。
      previewReady: false,
      previewFailed: false,
    }
  },
  computed: {
    isError() {
      return this.statusText.indexOf('失败') !== -1
    },
    // Stays quiet once ready — no permanent "就绪" badge.
    displayStatus() {
      return this.statusText === '就绪' ? '' : this.statusText
    },
    loadingOverlayVisible() {
      // 「仅桌面版可用」是终态（h5 预览等场景），不是加载中——不展示进度面板
      return !this.ready && !this.isError && this.statusText !== '仅桌面版可用'
    },
    loadingTitle() {
      return (this.file && this.file.name) ? this.file.name : '正在准备编辑器'
    },
    dlText() {
      if (!this.dlLoaded) return ''
      const fmt = (n) => n > 1024 * 1024 ? (n / 1024 / 1024).toFixed(1) + ' MB' : Math.max(1, Math.round(n / 1024)) + ' KB'
      return this.dlTotal > 0
        ? `文档内容 ${fmt(this.dlLoaded)} / ${fmt(this.dlTotal)}`
        : `已下载文档内容 ${fmt(this.dlLoaded)}`
    },
  },
  watch: {
    // 预热备胎过继（librePool.js）：宿主把 file 从 null 换成真实文档。引擎
    // 可能已空白就绪（_endpointUp），也可能仍在 boot——后者只预取字节，
    // onEndpointReady 会照常装载。file→file 换文档不支持（池按实例=文档）。
    file(newFile, oldFile) {
      if (!newFile || oldFile) return
      this.prefetchBytes()
      if (!this._endpointUp) return
      this.ready = false
      this.docLoadFailed = false
      this.statusText = '加载文档中…'
      this.bootPct = 75
      this.bootCap = 95
      this.bootStage = '正在打开文档'
      this.startBootTrickle()
      this.finishDocLoad()
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
      this.startBootTrickle()
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
    clearInterval(this._bootTimer)
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
    // ---- 加载进度面板 ----
    // 里程碑之间用慢速滴答填充（封顶 bootCap），避免长阶段（WASM 编译/大文档
    // 排版）看起来像卡死；真正的阶段跳变由 boot-log 里程碑驱动。
    startBootTrickle() {
      clearInterval(this._bootTimer)
      this._bootTimer = setInterval(() => {
        if (this.ready || this.isError) { clearInterval(this._bootTimer); return }
        if (this.bootPct < this.bootCap) this.bootPct = Math.min(this.bootCap, this.bootPct + 0.6)
      }, 400)
    },
    bootMilestone(base, cap, stage) {
      if (this.ready) return
      this.bootPct = Math.max(this.bootPct, base)
      this.bootCap = Math.max(this.bootCap, cap)
      if (stage) this.bootStage = stage
    },
    onBootLog(m) {
      if (!m) return
      if (m.indexOf('CJK font fetched') !== -1) this.bootMilestone(15, 30, '正在加载中文字体')
      else if (m.indexOf('soffice.js loaded') !== -1) this.bootMilestone(32, 55, '正在初始化排版引擎')
      else if (m.indexOf('thread port ready') !== -1) this.bootMilestone(58, 70, '正在启动文档服务')
      else if (m.indexOf('空白文档就绪') !== -1 || m.indexOf('UI ready') !== -1) {
        this.bootMilestone(72, 85, this.file ? '正在打开文档' : '即将就绪')
      } else if (m.indexOf('load_document: 已加载真实文档') !== -1) {
        this.bootMilestone(96, 99, '正在渲染文档')
      }
    },
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
        } else if (msg.type === 'boot-log') {
          // 引擎启动里程碑 → 加载进度面板推进阶段
          this.onBootLog(String(msg.msg || ''))
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
        // pre-boot.
        this.executor = createWebviewEditorExecutor(wv, { onReady: () => this.onEndpointReady() })
        // 命令繁忙跟踪：AI 命令与用户输入都走这同一个 executor。autoSave 据此
        // 避开活跃期（export_document 会冻结 office 线程上的 Qt 事件循环）。
        this._cmdBusy = 0
        this._lastCmdAt = 0
        const innerExec = this.executor.executeCommand.bind(this.executor)
        this.executor.executeCommand = async (action, params) => {
          if (action === 'export_document') return innerExec(action, params) // 保存自身不算「活跃编辑」
          this._cmdBusy++
          this._lastCmdAt = Date.now()
          try { return await innerExec(action, params) }
          finally { this._cmdBusy--; this._lastCmdAt = Date.now() }
        }
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
      this._endpointUp = true
      await this.finishDocLoad()
    },
    // 装载 + 发布就绪。两个入口：onEndpointReady（常规：mount 时就有 file，或
    // 备胎空白 boot 完成），以及 file watcher（备胎在引擎就绪后被过继）。
    async finishDocLoad() {
      if (this.file) {
        try {
          await this.loadDocument()
        } catch (e) {
          // Load failed → the seeded prototype is still showing. Surface it; the
          // editor stays usable (AI/IME act on whatever is shown) but the content
          // is wrong, so this is loud, not silent. docLoadFailed 关保存闸——
          // 空白画布上的任何编辑都不得回传覆盖后端真文件。
          this.docLoadFailed = true
          this.statusText = '文档加载失败'
          this.appendLog('load_document failed: ' + (e && e.message ? e.message : e))
        }
      }
      this.bootPct = 100
      clearInterval(this._bootTimer)
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
      this._bytesPromise = this.fetchArrayBuffer(getFileDownloadUrl(fileId), (loaded, total) => {
        this.dlLoaded = loaded
        this.dlTotal = total
      })
        .then((buf) => { this.appendLog('文档字节预取完成 / prefetched ' + (buf ? buf.byteLength : 0) + ' bytes in ' + (Date.now() - t0) + 'ms'); return buf })
        .catch((e) => { this.appendLog('预取失败（加载时重试）/ prefetch failed: ' + (e && e.message ? e.message : e)); return null })
      // 只读预览接力：不影响 _bytesPromise 本身（loadDocument 仍 await 它）
      this._bytesPromise.then((buf) => this.tryDocxPreview(buf))
    },
    // 引擎 boot 期间先把预取到的 docx 渲成只读预览（docx-preview 本地解析，
    // 同 FilePreview.renderDocx 的配置）。失败静默回落进度卡片。
    async tryDocxPreview(buf) {
      if (!buf || buf.byteLength === 0 || this.ready || this.previewReady || this.previewFailed) return
      const t = String((this.file && this.file.fileType) || '').toLowerCase()
      if (t !== 'docx' && t !== 'doc') return
      try {
        const { renderAsync } = await import('docx-preview')
        await this.$nextTick() // 过继路径上 overlay 可能刚重新显示，等 ref 挂上
        const ref = this.$refs.docxPreviewHost
        const container = ref && (ref.$el || ref)
        if (!container || this.ready) return
        container.innerHTML = ''
        await renderAsync(buf, container, null, {
          className: 'docx',
          inWrapper: true,
          ignoreWidth: false,
          ignoreHeight: false,
          breakPages: true,
          experimental: true,
        })
        if (this.ready) return
        this.previewReady = true
        this.appendLog('只读预览就绪（引擎继续后台启动）')
      } catch (e) {
        this.previewFailed = true
        this.appendLog('docx 预览渲染失败（保留进度面板）: ' + (e && e.message ? e.message : e))
      }
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
        // 元数据说文件非空却下载到 0 字节 = 后端/存储瞬时异常，绝不能当
        // "新建空白文档"——那会让后续编辑以空文档覆盖真文件。按加载失败走。
        if (f.fileSize > 0) throw new Error('文件非空（' + f.fileSize + ' bytes）但下载到 0 字节，拒绝按空白文档打开')
        this.appendLog('文档为空（新建/未保存）→ 显示空白文档 / empty doc → blank editor: ' + name)
        return
      }
      this.appendLog('▶ load_document「' + name + '」(' + bytes.length + ' bytes) …')
      this.bootMilestone(86, 95, '正在打开文档')
      // 当前登录用户名随文档传给 worker：用户本人编辑的修订以用户名署名，
      // AI 命令产生的修订署名 AI Workdeck（worker execCommand 按 __agent 切换）。
      const u = getCurrentUser() || {}
      const authorName = String(u.name || u.nickname || u.username || '')
      const t0 = Date.now()
      const res = await this.executor.executeCommand('load_document', { bytes, name, authorName })
      this.appendLog('  ← ' + (Date.now() - t0) + 'ms ' + JSON.stringify(res))
      if (!res || !res.success) throw new Error((res && res.message) || 'load_document returned no success')
    },
    // Authed binary fetch — same XHR auth pattern as FilePreview.fetchAuthedBlob,
    // but ArrayBuffer (the bytes we relay into the worker).
    fetchArrayBuffer(url, onProgress) {
      return new Promise((resolve, reject) => {
        const headers = getAuthHeaders() || {}
        const xhr = new XMLHttpRequest()
        xhr.open('GET', url, true)
        xhr.responseType = 'arraybuffer'
        Object.keys(headers).forEach((k) => xhr.setRequestHeader(k, headers[k]))
        if (onProgress) {
          xhr.onprogress = (ev) => {
            try { onProgress(ev.loaded || 0, ev.lengthComputable ? ev.total : 0) } catch (e) { /* ignore */ }
          }
        }
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
      // docLoadFailed：画布上是空白 boot 文档，标脏会引发空文档覆盖真文件
      if (!this.ready || !this.file || this.docLoadFailed) return
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
      // 假死根因修复：export_document 是全文档同步序列化，跑在 office 线程上会把
      // Qt 事件循环（滚动/输入/重绘）整段冻住。AI 修订风暴期间 modify 不断，旧的
      // 15s max-wait 会把导出硬塞进风暴中间 → 用户「完全无法滚动的假死」。
      // 改为：命令在飞或刚结束（<1.5s）时让路、等空闲窗口再导出；只有脏数据
      // 超过 60s 才强制保存（崩溃丢失上限从 15s 放宽到 60s，换取修订期不冻结）。
      const dirtyAge = this._dirtySince ? Date.now() - this._dirtySince : 0
      const sinceCmd = Date.now() - (this._lastCmdAt || 0)
      if ((this._cmdBusy > 0 || sinceCmd < 1500) && dirtyAge < 60000) {
        clearTimeout(this._saveTimer)
        this._saveTimer = setTimeout(() => this.autoSave(), 2000)
        return
      }
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
      // 最后一道闸（onDocModified 之外的调用方也拦住）：文档没成功加载，
      // 导出的只会是空白 boot 文档——拒绝覆盖后端真文件。
      if (this.docLoadFailed) { this.appendLog('save blocked: 文档未成功加载，拒绝用空白文档覆盖后端文件'); return false }
      const fileId = f.wpsFileId || f.id
      if (!fileId) { this.appendLog('save: file has no id/wpsFileId'); return false }
      this.saving = true
      const prevStatus = this.statusText
      this.statusText = '保存中…'
      try {
        const name = f.name || (String(fileId) + '.' + String(f.fileType || 'docx'))
        this.appendLog('▶ export_document「' + name + '」…')
        const tExp = Date.now()
        const res = await this.executor.executeCommand('export_document', { name })
        this.appendLog('  ← export ' + (Date.now() - tExp) + 'ms')
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
  },
}
</script>

<style scoped>
.libre-editor-wrapper { position: relative; display: flex; flex-direction: column; width: 100%; height: 100%; background: #fff; }
/* Floating status pill, pinned over the editor's top-right corner (LO's own
   menubar leaves that region empty). No layout height is reserved — the
   document canvas gets the full pane. */
.libre-float { position: absolute; top: 6px; right: 16px; z-index: 20; display: flex; align-items: center; gap: 8px; }
.libre-pill { display: flex; align-items: center; gap: 6px; padding: 3px 10px; border-radius: 999px;
  background: rgba(31, 41, 55, 0.78); color: #e5e7eb; font-size: 12px; backdrop-filter: blur(4px); }
.libre-pill.error { background: rgba(153, 27, 27, 0.9); color: #fecaca; }
.libre-spin { width: 10px; height: 10px; border: 2px solid rgba(229, 231, 235, 0.35); border-top-color: #e5e7eb;
  border-radius: 50%; animation: libre-rot 0.8s linear infinite; }
@keyframes libre-rot { to { transform: rotate(360deg); } }
.libre-host { flex: 1; min-height: 0; width: 100%; }
/* ---- 加载进度面板 ---- */
.libre-loading { position: absolute; inset: 0; z-index: 15; display: flex; align-items: center; justify-content: center;
  background: #F8F9FA; }
.libre-loading-card { display: flex; flex-direction: column; align-items: center; gap: 10px; width: 320px; max-width: 80%; }
.libre-doc-icon { position: relative; width: 44px; height: 56px; background: #fff; border: 1.5px solid #DEE2E6;
  border-radius: 5px; margin-bottom: 4px; overflow: hidden; }
.doc-fold { position: absolute; top: -1px; right: -1px; width: 14px; height: 14px;
  background: #F8F9FA; border-left: 1.5px solid #DEE2E6; border-bottom: 1.5px solid #DEE2E6; border-radius: 0 0 0 5px; }
.doc-line { position: absolute; left: 8px; height: 4px; border-radius: 2px; background: #E6F9F0;
  animation: doc-line-pulse 1.6s ease-in-out infinite; }
.doc-line.l1 { top: 20px; width: 26px; animation-delay: 0s; }
.doc-line.l2 { top: 30px; width: 20px; animation-delay: 0.25s; }
.doc-line.l3 { top: 40px; width: 24px; animation-delay: 0.5s; }
@keyframes doc-line-pulse { 0%, 100% { background: #E9ECEF; } 50% { background: #5BD197; } }
.libre-loading-name { font-size: 14px; font-weight: 600; color: #2C3338; max-width: 100%;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.libre-progress-track { width: 100%; height: 6px; background: #E9ECEF; border-radius: 999px; overflow: hidden; }
.libre-progress-fill { position: relative; height: 100%; background: #5BD197; border-radius: 999px;
  transition: width 0.5s ease; overflow: hidden; }
.libre-progress-shimmer { position: absolute; inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.55), transparent);
  animation: libre-shimmer 1.4s linear infinite; }
@keyframes libre-shimmer { 0% { transform: translateX(-100%); } 100% { transform: translateX(100%); } }
.libre-loading-meta { display: flex; justify-content: space-between; width: 100%; }
.libre-loading-stage { font-size: 12px; color: #495057; }
.libre-loading-pct { font-size: 12px; color: #1A5336; font-weight: 600; }
.libre-loading-dl { font-size: 11px; color: #868E96; }
.libre-loading-hint { font-size: 11px; color: #ADB5BD; margin-top: 6px; }
/* ---- 只读预览接力 ---- */
.libre-preview-strip { position: absolute; top: 0; left: 0; right: 0; z-index: 2; display: flex; flex-direction: column;
  gap: 4px; padding: 6px 14px 8px; background: rgba(248, 249, 250, 0.95); border-bottom: 1px solid #E9ECEF;
  backdrop-filter: blur(4px); }
.libre-strip-track { width: 100%; height: 3px; background: #E9ECEF; border-radius: 999px; overflow: hidden; }
.libre-strip-fill { height: 100%; background: #5BD197; border-radius: 999px; transition: width 0.5s ease; }
.libre-strip-text { font-size: 11px; color: #868E96; }
.libre-preview-host { position: absolute; inset: 0; top: 34px; overflow-y: auto; background: #F1F3F5; }
/* docx-preview 生成的页面居中呈现（deep：内容是运行时注入的非 scoped DOM） */
.libre-preview-host :deep(.docx-wrapper) { background: transparent; padding: 16px 0; }
</style>
