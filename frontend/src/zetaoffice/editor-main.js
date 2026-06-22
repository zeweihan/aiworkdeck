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

startEditorEndpoint({
  canvas: document.getElementById('qtcanvas'),
  transport: pickTransport(),
  sofficeBaseUrl: q.get('lowa') || 'https://cdn.zetaoffice.net/zetaoffice_latest/',
  zetaJsUrl: q.get('zeta') || './zeta.js',
  workerScriptUrl: q.get('worker') || './office_thread.js',
  fontUrl: q.get('font') || undefined,
  onLog: (m) => console.log('[zeta-editor]', m),
}).then(() => {
  console.log('[zeta-editor] endpoint ready — serving host over transport')
}).catch((e) => {
  console.error('[zeta-editor] boot failed:', e)
})
