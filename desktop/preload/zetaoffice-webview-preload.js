// zetaoffice-webview-preload.js — preload for the embedded LibreOffice editor
// page running inside <webview partition="persist:zetaoffice">. Epic #43.
//
// The editor page (frontend/src/zetaoffice/editor-main.js) talks to the HOST over
// a {send, subscribe} transport. In an Electron <webview>, guest -> host is
// ipcRenderer.sendToHost(channel, msg) and host -> guest arrives as an
// ipcRenderer 'lo-relay' message. contextIsolation stays ON (the page is our own
// localhost bundle, but defense in depth costs nothing here): this preload
// exposes a minimal, fixed-shape bridge via contextBridge instead of leaking the
// whole ipcRenderer. editor-main.js prefers window.zetaHostBridge when present,
// and falls back to window.parent.postMessage in a plain browser / the spike.
//
// Host counterpart: useZetaOfficeWebview.webviewTransport (#52) — webviewEl.send
// (-> 'lo-relay') and the 'ipc-message' event with the same channel.

const { contextBridge, ipcRenderer } = require('electron')

const CHANNEL = 'lo-relay'

contextBridge.exposeInMainWorld('zetaHostBridge', {
  // guest -> host
  send: (msg) => ipcRenderer.sendToHost(CHANNEL, msg),
  // host -> guest; strips the (non-cloneable) IpcRendererEvent so the page sees
  // only the message. Returns an unsubscribe fn.
  subscribe: (handler) => {
    const listener = (_evt, msg) => handler(msg)
    ipcRenderer.on(CHANNEL, listener)
    return () => ipcRenderer.removeListener(CHANNEL, listener)
  },
})
