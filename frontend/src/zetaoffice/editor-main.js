// editor-main.js — entry for the embedded LibreOffice editor page that runs
// INSIDE the isolated <webview>. Epic #43.
//
// Bundled by the dedicated Vite build (frontend/vite.zetaoffice.config.js), NOT
// by uni-app: uni-app's h5 build won't let a static/ page import src/ modules,
// so this page is its own self-contained bundle that pulls in the verified
// product composables (startEditorEndpoint -> bootZetaOffice + executor +
// serveExecutor). The build output (dist/zetaoffice/) ships in the desktop app
// and is loaded by <webview partition="persist:zetaoffice"> (isolated by
// desktop/main/zetaoffice-session.js).
//
// Transport auto-detect keeps the page verifiable OUTSIDE Electron too: in an
// Electron <webview> it talks to the host over ipcRenderer; in a plain browser /
// iframe it falls back to window.parent.postMessage, so the Phase 0 spike (and a
// browser) can drive it the same way the host will.

import { startEditorEndpoint } from '../composables/zetaOfficeEditorEndpoint.js'
import { attachImeOverlay } from '../composables/zetaOfficeImeOverlay.js'

// Electron renderer require is a runtime property access (NOT a static import),
// so the bundler leaves it alone and the browser fallback path stays clean.
function getIpcRenderer() {
  try {
    if (typeof window !== 'undefined' && typeof window.require === 'function') {
      return window.require('electron').ipcRenderer
    }
  } catch (e) { /* not Electron */ }
  return null
}

// {send, subscribe} over the host boundary — Electron <webview> IPC if present,
// else postMessage to the parent (browser/iframe/spike harness).
function pickTransport() {
  // Preferred Electron <webview> path: the webview preload
  // (desktop/preload/zetaoffice-webview-preload.js) exposes a fixed-shape bridge
  // over contextBridge (contextIsolation stays ON). Already {send, subscribe}.
  if (typeof window !== 'undefined' && window.zetaHostBridge) {
    return window.zetaHostBridge
  }
  // Legacy fallback: nodeIntegration webview where window.require is available.
  const ipc = getIpcRenderer()
  if (ipc) {
    return {
      send: (m) => ipc.sendToHost('lo-relay', m),
      subscribe: (h) => {
        const f = (_e, m) => h(m)
        ipc.on('lo-relay', f)
        return () => ipc.removeListener('lo-relay', f)
      },
    }
  }
  // <iframe> 路径（Web 服务器版 / 鸿蒙浏览器）。宿主与本页同源是部署硬前提
  // （deploy/web/nginx.conf.example：站点与 /zetaoffice/ 同 origin + 全站 COOP/COEP），
  // 所以收发都钉死在 location.origin：'*' 会把文档内容和编辑器指令暴露给任何把
  // 本页嵌进去的第三方页面，也会让任何页面能伪造 lo-relay 指令改用户的文档。
  const target = window.parent && window.parent !== window ? window.parent : window
  const origin = window.location.origin
  return {
    send: (m) => target.postMessage(m, origin),
    subscribe: (h) => {
      const f = (e) => {
        if (e.source !== target) return
        if (e.origin !== origin) return
        h(e.data)
      }
      window.addEventListener('message', f)
      return () => window.removeEventListener('message', f)
    },
  }
}

// Runtime-configurable asset locations (query string), so the same bundle works
// against the CDN LOWA in dev and a self-hosted bundle in the packaged app.
const q = new URLSearchParams(location.search)
const VERIFY = q.get('verify') === '1'

// Standalone verification panel (?verify=1): a few buttons + an IME field that
// drive the booted executor DIRECTLY (no host), so the editor can be exercised
// inside the dedicated verification window (desktop/main/zetaoffice-verify.js)
// of a packaged build. In normal (webview) mode the panel stays hidden and the
// endpoint just serves the host over the transport.
function vlog(m) {
  const el = document.getElementById('vlog')
  if (!el) return
  el.classList.add('on')
  el.textContent += m + '\n'
  el.scrollTop = el.scrollHeight
}

function wireVerifyPanel(executor) {
  const panel = document.getElementById('verify')
  if (panel) panel.classList.add('on')
  const status = document.getElementById('vstatus')
  const ime = document.getElementById('vime')
  const bIns = document.getElementById('vinsert')
  const bRep = document.getElementById('vreplace')
  const bSel = document.getElementById('vsel')
  const bExp = document.getElementById('vexport')
  if (status) status.textContent = '就绪 / ready ✓'
  for (const b of [ime, bIns, bRep, bSel, bExp]) if (b) b.disabled = false

  const run = async (label, action, params) => {
    vlog('▶ ' + label + ' …')
    try { vlog('  ← ' + JSON.stringify(await executor.executeCommand(action, params))) }
    catch (e) { vlog('  ✗ ' + (e && e.message ? e.message : e)) }
  }
  if (bIns) bIns.onclick = () => run('插入示例', 'insert_at_cursor',
    { text: '本协议由甲方与乙方于本日签订；协议自双方签署之日起生效。' })
  if (bRep) bRep.onclick = () => run('查找替换 协议→合同 (redline)', 'find_replace',
    { findText: '协议', replaceText: '合同', replaceAll: true })
  if (bSel) bSel.onclick = () => run('读选区', 'get_selection', {})
  // Track E save-pipeline verification: export the live document as .docx bytes
  // (the exact worker path the host's 保存 uses), assert the ZIP magic, and hand
  // the file to the browser so a human can open it in Word/LibreOffice.
  if (bExp) bExp.onclick = async () => {
    vlog('▶ export_document …')
    try {
      const res = await executor.executeCommand('export_document', { name: 'verify-export.docx' })
      if (!res || !res.success) throw new Error((res && res.message) || 'no success')
      const raw = res.bytes
      const u8 = raw instanceof Uint8Array ? raw
        : raw instanceof ArrayBuffer ? new Uint8Array(raw)
        : raw && raw.buffer instanceof ArrayBuffer ? new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength)
        : Array.isArray(raw) ? new Uint8Array(raw) : null
      if (!u8 || !u8.length) throw new Error('no bytes in result')
      const magicOk = u8[0] === 0x50 && u8[1] === 0x4b // 'PK' — docx is a ZIP
      vlog('  ← ' + u8.length + ' bytes, ZIP magic ' + (magicOk ? 'OK (PK)' : 'BAD: ' + u8[0] + ',' + u8[1]))
      const a = document.createElement('a')
      a.href = URL.createObjectURL(new Blob([u8], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' }))
      a.download = 'verify-export.docx'
      a.click()
      setTimeout(() => URL.revokeObjectURL(a.href), 30000)
      vlog('  ← 已触发下载 verify-export.docx——请用 Word/LibreOffice 打开核对内容')
    } catch (e) { vlog('  ✗ ' + (e && e.message ? e.message : e)) }
  }

  // IME: commit composed/typed text into the document at the cursor.
  if (ime) {
    let composing = false, skipNext = false
    const commit = (t) => { if (t) run('IME 插入「' + t + '」', 'insert_at_cursor', { text: t }); ime.value = '' }
    ime.addEventListener('compositionstart', () => { composing = true })
    ime.addEventListener('compositionend', (e) => { composing = false; skipNext = true; commit(e.data) })
    ime.addEventListener('input', (e) => {
      if (composing) return
      if (skipNext) { skipNext = false; return }
      commit(e.data != null ? e.data : ime.value)
    })
  }
}

// One transport instance, reused to both serve the host AND signal readiness.
const hostTransport = pickTransport()

// (#79) Hyperlink clicks: when LO opens a document hyperlink it lands on the
// page's window.open. Inside the isolated <webview> a popup can't open anything
// useful anyway — forward the URL to the HOST over the same lo-relay transport,
// where project-overview routes it (checkba:// internal links → 关联文件/网核
// 定位, http(s) → workspace browser tab). Wrapped BEFORE boot so no click races.
try {
  const nativeOpen = window.open ? window.open.bind(window) : null
  window.open = (url, name, feats) => {
    const u = url ? String(url) : ''
    if (u) {
      try { hostTransport.send({ __lo: 'lo-relay', type: 'open-url', url: u }); return null } catch (e) { /* fall through */ }
    }
    return nativeOpen ? nativeOpen(url, name, feats) : null
  }
} catch (e) { console.error('[zeta-editor] window.open hook failed:', e) }

// (autosave) The worker posts one 'modified' per document change (typed / IME /
// AI command — see installModifyListener in office_thread.js). The host only
// needs an edge to debounce-save on, so throttle the relay to 1/500ms.
let lastModifiedRelay = 0
function relayModified(d) {
  if (!d || !d.cmd) return
  if (d.cmd === 'sel_changed') { relaySelection(); return }
  if (d.cmd !== 'modified') return
  const now = Date.now()
  if (now - lastModifiedRelay < 500) return
  lastModifiedRelay = now
  try { hostTransport.send({ __lo: 'lo-relay', type: 'modified' }) } catch (e) { /* ignore */ }
}

// (自建工具栏) 光标/选区动了 → 宿主重读 get_ui_state 刷新激活态。两个来源：
// worker 的 XSelectionChangeListener（盖选区类变化）与 IME 覆盖层的
// onCursorMoved（盖纯光标移动与画布点击，前者盖不住）。合流后节流 1/150ms，
// 免得连续方向键把通道打满。
let lastSelectionRelay = 0
let selectionTimer = 0
function relaySelection() {
  const now = Date.now()
  const since = now - lastSelectionRelay
  if (since < 150) {
    // 尾随一发，保证最后一次移动的状态一定送到（否则连按方向键停下时是旧状态）
    if (!selectionTimer) selectionTimer = setTimeout(() => { selectionTimer = 0; relaySelection() }, 150 - since)
    return
  }
  lastSelectionRelay = now
  try { hostTransport.send({ __lo: 'lo-relay', type: 'selection' }) } catch (e) { /* ignore */ }
}

startEditorEndpoint({
  canvas: document.getElementById('qtcanvas'),
  transport: hostTransport,
  onWorkerMessage: relayModified,
  sofficeBaseUrl: q.get('lowa') || 'https://cdn.zetaoffice.net/zetaoffice_latest/',
  zetaJsUrl: q.get('zeta') || './zeta.js',
  workerScriptUrl: q.get('worker') || './office_thread.js',
  // Default to the CJK fonts served next to the page — one per Chinese typeface
  // category (黑体类 sans / 宋体类 serif / 楷体 / 仿宋), baked by
  // desktop/scripts/fetch-lowa-assets.js. bootZetaOffice skips any that 404
  // (its fontconfig alias chains then fall through to the next category font).
  // ?font= overrides/adds a single extra font (kept for the spike harness).
  fontUrl: q.get('font') || undefined,
  // family = the name LibreOffice registers (list_fonts diagnostic) — the
  // boot's fontconfig alias rules point 宋体/黑体/楷体/仿宋/… at these.
  fontUrls: [
    { url: './cjk.ttc', family: 'Noto Sans SC', category: 'sans' },
    { url: './cjk-serif.otf', family: 'Noto Serif SC', category: 'serif' },
    { url: './cjk-kai.ttf', family: '霞鹜文楷', category: 'kai' },
    { url: './cjk-fangsong.ttf', family: '朱雀仿宋（预览测试版）', category: 'fangsong' },
  ],
  // Deterministic Chinese UI regardless of the browser/Electron language (the
  // engine follows navigator.languages otherwise — v0.3.1 shipped English on an
  // en-GB system). ?uilang=env follows the environment; ?uilang=xx-YY overrides.
  uiLang: q.get('uilang') === 'env' ? '' : (q.get('uilang') || 'zh-CN'),
  // (#79) The #66 runtime zh-CN langpack injection was removed: the self-built
  // engine ships zh-CN baked in, so injecting 38 files was pure boot overhead.
  onLog: (m) => {
    console.log('[zeta-editor]', m)
    if (VERIFY) vlog(m)
    // 启动里程碑同步给宿主：LibreOfficeEditor 的加载进度面板据此推进阶段
    // （引擎下载/字体/线程/文档就绪）。宿主在 dom-ready 前就订阅了 lo-relay。
    try { hostTransport.send({ __lo: 'lo-relay', type: 'boot-log', msg: String(m) }) } catch (e) { /* ignore */ }
  },
}).then((endpoint) => {
  console.log('[zeta-editor] endpoint ready — serving host over transport')
  // (#79 click-to-open) LO WASM never calls window.open on hyperlink clicks
  // (real-machine verified on v0.7.1) — the hook above only covers hypothetical
  // engine-initiated opens. The working seam: a plain positioning click moves
  // the LO cursor; ask the worker what link the cursor landed in and forward it.
  // Guards: primary button only, no drag-selection (>5px move), 800ms cooldown,
  // and the worker returns '' for non-collapsed cursors (double-click selection).
  try {
    const canvas = document.getElementById('qtcanvas')
    let downAt = null
    let lastOpen = 0
    canvas.addEventListener('mousedown', (ev) => {
      downAt = ev.button === 0 ? { x: ev.clientX, y: ev.clientY } : null
    }, true)
    canvas.addEventListener('mouseup', (ev) => {
      const d = downAt
      downAt = null
      if (!d || ev.button !== 0 || ev.shiftKey) return
      if (Math.abs(ev.clientX - d.x) > 5 || Math.abs(ev.clientY - d.y) > 5) return // drag-selection
      // let Qt process the click and move the LO cursor first
      setTimeout(async () => {
        try {
          const r = await endpoint.executor.executeCommand('get_hyperlink_at_cursor', {})
          if (r && r.success && r.url) {
            const now = Date.now()
            if (now - lastOpen < 800) return
            lastOpen = now
            hostTransport.send({ __lo: 'lo-relay', type: 'open-url', url: String(r.url) })
          }
        } catch (e) { /* ignore */ }
      }, 150)
    }, true)
  } catch (e) { console.error('[zeta-editor] link-click seam failed:', e) }
  // Tell the host the office endpoint is booted and serving (serveExecutor is now
  // subscribed). The host (createRelayExecutor onReady) waits for this before
  // pushing load_document — sending it earlier would drop it (no subscriber yet,
  // office not booted). Track D's real-file load is gated on this handshake.
  try { hostTransport.send({ __lo: 'lo-relay', type: 'ready' }) } catch (e) { /* ignore */ }
  // Transparent IME overlay over the canvas, so users can type Chinese directly
  // in the document (Qt5-WASM gives the canvas no IME). Commits at the LO cursor
  // via the same verified path as agent commands. Attached in BOTH webview and
  // verify modes — local typing is a real-user need, not just a verification one.
  let overlay = null
  try {
    overlay = attachImeOverlay({
      canvas: document.getElementById('qtcanvas'),
      commit: (text) => endpoint.executor.executeCommand('insert_at_cursor', { text }),
      // Control keys: the overlay swallows keystrokes (it IS the focused input),
      // so Enter/Backspace/arrows must be forwarded to the worker explicitly.
      // The worker actions (insert_paragraph/delete_backward/move_cursor) have
      // existed since Track C and are whitelisted — this wiring was the missing
      // link (v0.3.1 real-machine report: Backspace did nothing).
      onEnter: () => endpoint.executor.executeCommand('insert_paragraph', {}),
      sendCommand: (action, params) => endpoint.executor.executeCommand(action, params),
      // 覆盖层每做完一个移动光标的动作就报一声，宿主据此刷新工具栏激活态
      onCursorMoved: relaySelection,
      onLog: (m) => { console.log('[zeta-editor]', m); if (VERIFY) vlog(m) },
    })
  } catch (e) { console.error('[zeta-editor] IME overlay failed:', e); if (VERIFY) vlog('IME overlay failed: ' + (e && e.message || e)) }
  // 触控板捏合缩放。Chromium 把捏合报成 ctrlKey + wheel；**不拦下来**浏览器就去
  // 缩放整个 webview 页面——LO 自己的工具栏跟着一起放大、画布重采样发糊，而且
  // IME 覆盖层的像素映射基准（CSS px per 1/100 mm）整个作废。拦下来改成缩放
  // 文档视图（ViewSettings.ZoomValue），这才是用户要的那种缩放。
  try {
    const canvas = document.getElementById('qtcanvas')
    let zoomPct = 100      // 本地镜像：捏合连发时以它为基准，引擎回值再校正
    let zoomTarget = null
    let zoomTimer = 0
    // 起始缩放问一次引擎（无参 set_zoom = 只读回当前值）
    endpoint.executor.executeCommand('set_zoom', {})
      .then((r) => { if (r && r.success && r.zoom) zoomPct = r.zoom })
      .catch(() => { /* 读不到就按 100% 起步 */ })
    const flushZoom = () => {
      zoomTimer = 0
      const v = zoomTarget
      zoomTarget = null
      if (v == null) return
      endpoint.executor.executeCommand('set_zoom', { value: v })
        .then((r) => {
          // 只有没有更新的目标在排队时才回写，否则会把连发中的中间值倒推回去
          if (zoomTarget == null && r && r.success && r.zoom) zoomPct = r.zoom
          if (overlay) overlay.reposition()   // 缩放后光标的像素位置变了
        })
        .catch((err) => console.warn('[zeta-editor] set_zoom failed:', err))
    }
    canvas.addEventListener('wheel', (ev) => {
      if (!ev.ctrlKey) return                 // 普通两指滚动照旧交给 Qt
      ev.preventDefault()
      const next = Math.max(20, Math.min(600, Math.round(zoomPct * Math.exp(-ev.deltaY / 100))))
      if (next === zoomPct) return
      zoomPct = next
      zoomTarget = next
      // 一次捏合会连发几十个 wheel：合并到 ~60ms 一发，否则每一步都要等引擎重排版
      if (!zoomTimer) zoomTimer = setTimeout(flushZoom, 60)
    }, { passive: false })
  } catch (e) { console.error('[zeta-editor] pinch-zoom wiring failed:', e) }
  if (VERIFY) {
    wireVerifyPanel(endpoint.executor)
    // Automation hook: lets a headless-browser test driver call the executor
    // directly (window.__loExecutor.executeCommand(...)) to exercise every
    // primitive against the real LOWA engine. Verify mode only.
    window.__loExecutor = endpoint.executor
  }
}).catch((e) => {
  console.error('[zeta-editor] boot failed:', e)
  if (VERIFY) vlog('boot failed: ' + (e && e.message ? e.message : e))
})
