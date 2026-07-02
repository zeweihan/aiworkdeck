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
  const target = window.parent && window.parent !== window ? window.parent : window
  return {
    send: (m) => target.postMessage(m, '*'),
    subscribe: (h) => {
      const f = (e) => h(e.data)
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

startEditorEndpoint({
  canvas: document.getElementById('qtcanvas'),
  transport: hostTransport,
  sofficeBaseUrl: q.get('lowa') || 'https://cdn.zetaoffice.net/zetaoffice_latest/',
  zetaJsUrl: q.get('zeta') || './zeta.js',
  workerScriptUrl: q.get('worker') || './office_thread.js',
  // Default to a CJK font served next to the page (the verify build drops one
  // here); bootZetaOffice skips cleanly if it 404s (Chinese would be tofu then).
  fontUrl: q.get('font') || './cjk.ttc',
  // Deterministic Chinese UI regardless of the browser/Electron language (the
  // engine follows navigator.languages otherwise — v0.3.1 shipped English on an
  // en-GB system). ?uilang=env follows the environment; ?uilang=xx-YY overrides.
  uiLang: q.get('uilang') === 'env' ? '' : (q.get('uilang') || 'zh-CN'),
  // (#79) The #66 runtime zh-CN langpack injection was removed: the self-built
  // engine ships zh-CN baked in, so injecting 38 files was pure boot overhead.
  onLog: (m) => { console.log('[zeta-editor]', m); if (VERIFY) vlog(m) },
}).then((endpoint) => {
  console.log('[zeta-editor] endpoint ready — serving host over transport')
  // Tell the host the office endpoint is booted and serving (serveExecutor is now
  // subscribed). The host (createRelayExecutor onReady) waits for this before
  // pushing load_document — sending it earlier would drop it (no subscriber yet,
  // office not booted). Track D's real-file load is gated on this handshake.
  try { hostTransport.send({ __lo: 'lo-relay', type: 'ready' }) } catch (e) { /* ignore */ }
  // Transparent IME overlay over the canvas, so users can type Chinese directly
  // in the document (Qt5-WASM gives the canvas no IME). Commits at the LO cursor
  // via the same verified path as agent commands. Attached in BOTH webview and
  // verify modes — local typing is a real-user need, not just a verification one.
  try {
    attachImeOverlay({
      canvas: document.getElementById('qtcanvas'),
      commit: (text) => endpoint.executor.executeCommand('insert_at_cursor', { text }),
      // Control keys: the overlay swallows keystrokes (it IS the focused input),
      // so Enter/Backspace/arrows must be forwarded to the worker explicitly.
      // The worker actions (insert_paragraph/delete_backward/move_cursor) have
      // existed since Track C and are whitelisted — this wiring was the missing
      // link (v0.3.1 real-machine report: Backspace did nothing).
      onEnter: () => endpoint.executor.executeCommand('insert_paragraph', {}),
      sendCommand: (action, params) => endpoint.executor.executeCommand(action, params),
      onLog: (m) => { console.log('[zeta-editor]', m); if (VERIFY) vlog(m) },
    })
  } catch (e) { console.error('[zeta-editor] IME overlay failed:', e); if (VERIFY) vlog('IME overlay failed: ' + (e && e.message || e)) }
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
