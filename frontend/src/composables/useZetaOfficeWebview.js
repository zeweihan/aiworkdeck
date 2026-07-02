// useZetaOfficeWebview.js — HOST-side wiring for the embedded LibreOffice editor.
// Epic #43.
//
// The editor page (frontend/src/zetaoffice/, built by vite.zetaoffice.config.js)
// runs inside an Electron <webview partition="persist:zetaoffice"> (isolated by
// desktop/main/zetaoffice-session.js). This module is the HOST counterpart: it
// turns that <webview> element into a LibreOffice executor with the SAME
// executeCommand(action, params) contract as the other bridges, so it can be
// handed to useEditorBridge as the LibreOffice executor.
//
// Flow: host createRelayExecutor (#48) --webview IPC--> editor page's
// serveExecutor --> libreofficeExecutorClient --> office worker --> UNO --> real
// LibreOffice, and back. The per-command relay protocol + executor are verified
// against a real LibreOffice (#48/#49); only this Electron <webview> IPC adapter
// is device-specific.
//
// Electron <webview> IPC: host -> page is webviewEl.send(channel, msg); page ->
// host is ipcRenderer.sendToHost(channel, msg) (see editor-main.js), surfaced on
// the host as a 'ipc-message' event with {channel, args}. The page side needs a
// preload exposing ipcRenderer (nodeIntegrationInSubFrames / a preload script).
//
// DORMANT until the host renders the <webview> and wires the executor.

import { createRelayExecutor } from './zetaOfficeRelay.js'

const CHANNEL = 'lo-relay'

/**
 * Build a {send, subscribe} transport over an Electron <webview> element.
 * @param {HTMLElement} webviewEl an Electron <webview> DOM element.
 */
export function webviewTransport(webviewEl) {
  return {
    send: (m) => webviewEl.send(CHANNEL, m),
    subscribe: (h) => {
      const f = (e) => { if (e.channel === CHANNEL) h(e.args && e.args[0]) }
      webviewEl.addEventListener('ipc-message', f)
      return () => webviewEl.removeEventListener('ipc-message', f)
    },
  }
}

/**
 * Host-side LibreOffice executor driving the editor inside the <webview>.
 * Same executeCommand(action, params) contract as the bridges → pass to
 * useEditorBridge({ libreExecutor: createWebviewEditorExecutor(el) }).
 *
 * @param {HTMLElement} webviewEl an Electron <webview> DOM element.
 * @param {{timeoutMs?:number, onReady?:()=>void}} [opts] onReady fires once the
 *        editor endpoint inside the webview is booted and serving (Track D uses
 *        it to gate load_document — see createRelayExecutor).
 * @returns {{executeCommand:(a:string,p?:object)=>Promise<any>, dispose:()=>void}}
 */
export function createWebviewEditorExecutor(webviewEl, opts = {}) {
  const transport = webviewTransport(webviewEl)
  return createRelayExecutor({ send: transport.send, subscribe: transport.subscribe, timeoutMs: opts.timeoutMs, onReady: opts.onReady })
}
