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
// 两种宿主容器，同一套 relay：
// - Electron <webview>（桌面壳）：webviewTransport，走 webview IPC；
// - <iframe>（Web 服务器版 / 鸿蒙浏览器，docs/HARMONYOS_PLAN.md Phase A）：
//   iframeTransport，走 postMessage。
// 差异只在这两个 {send, subscribe} 适配器里；relay、executor、worker 一字不改。

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

/**
 * Build a {send, subscribe} transport over an <iframe> (Web 服务器版).
 *
 * 同源部署是硬前提（deploy/web/nginx.conf.example：站点与 /zetaoffice/ 同一 origin，
 * 全站 COOP/COEP）——所以 targetOrigin 收成 location.origin，收信也只认这个 origin，
 * 且必须来自这个 iframe 自己的 contentWindow。用 '*' 会把编辑器指令广播给任何
 * 恰好被嵌进来的第三方文档，同时也会让任何页面能伪造 lo-relay 结果。
 *
 * 与 webviewTransport 的差别仅此一处；relay 协议完全相同。
 *
 * @param {HTMLIFrameElement} iframeEl
 */
export function iframeTransport(iframeEl) {
  const origin = (typeof window !== 'undefined' && window.location) ? window.location.origin : ''
  return {
    send: (m) => {
      const w = iframeEl.contentWindow
      if (w) w.postMessage(m, origin)
    },
    subscribe: (h) => {
      const f = (e) => {
        if (e.source !== iframeEl.contentWindow) return
        if (e.origin !== origin) return
        h(e.data)
      }
      window.addEventListener('message', f)
      return () => window.removeEventListener('message', f)
    },
  }
}

/**
 * Host-side LibreOffice executor driving the editor inside an <iframe>.
 * 与 createWebviewEditorExecutor 同契约，供 Web 态使用。
 *
 * @param {HTMLIFrameElement} iframeEl
 * @param {{timeoutMs?:number, onReady?:()=>void}} [opts]
 */
export function createIframeEditorExecutor(iframeEl, opts = {}) {
  const transport = iframeTransport(iframeEl)
  return createRelayExecutor({ send: transport.send, subscribe: transport.subscribe, timeoutMs: opts.timeoutMs, onReady: opts.onReady })
}
