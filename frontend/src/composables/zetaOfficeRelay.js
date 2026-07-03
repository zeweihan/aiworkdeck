// zetaOfficeRelay.js — transport-agnostic command relay across the
// host <-> isolated-webview boundary for the embedded LibreOffice editor.
// Epic #43.
//
// WHY a relay: ZetaOffice boots inside an isolated Electron <webview> (it needs
// cross-origin isolation, which the main window can't have — see
// desktop/main/zetaoffice-session.js). But the AI agent command pipeline runs in
// the HOST (project-overview.vue's handleEditorCommand). So a host executeCommand
// must cross the boundary: the host sends {action, params}, the webview runs it
// against the booted executor (libreofficeExecutorClient -> worker -> UNO), and
// returns the result. This module is that relay.
//
// Transport-agnostic ON PURPOSE: the caller supplies `send(msg)` and
// `subscribe(handler) -> unsubscribe`. That lets the SAME relay run over
// window.postMessage / a MessageChannel (how the Phase 0 spike verifies it
// against a real LibreOffice) AND over Electron <webview> IPC
// (webview.send / ipcRenderer.sendToHost) in the product — no Electron
// dependency in this module, so it is unit-verifiable.
//
// Chosen over a one-time MessagePort transfer (webview -> host) because a
// per-command relay does not depend on a MessagePort surviving the
// isolated<->non-isolated boundary, which would need on-device validation
// first. (Transfer stays a possible later optimization — see #47 notes.)

const TAG = 'lo-relay' // ignore unrelated messages on a shared channel

/**
 * WEBVIEW side: serve a booted executor to the host. Listens for exec requests
 * and replies with results, correlated by reqId.
 *
 * @param {object} args
 * @param {{executeCommand:(a:string,p:object)=>Promise<any>}} args.executor
 *        the booted LibreOffice executor (e.g. libreofficeExecutorClient).
 * @param {(msg:any)=>void} args.send post a message to the host.
 * @param {(handler:(msg:any)=>void)=>(()=>void)} args.subscribe register a
 *        host-message handler; returns an unsubscribe fn.
 * @returns {{dispose:()=>void}}
 */
export function serveExecutor({ executor, send, subscribe }) {
  const off = subscribe(async (msg) => {
    if (!msg || msg.__lo !== TAG || msg.type !== 'exec') return
    let result
    try {
      result = await executor.executeCommand(msg.action, msg.params || {})
    } catch (e) {
      result = { success: false, message: e && e.message ? e.message : String(e) }
    }
    send({ __lo: TAG, type: 'result', reqId: msg.reqId, result })
  })
  return { dispose: off }
}

/**
 * HOST side: an executeCommand(action, params) that round-trips to the webview.
 * SAME contract as libreofficeExecutorClient.executeCommand, so useEditorBridge
 * can use it as the LibreOffice executor when the editor lives in a webview.
 *
 * @param {object} args
 * @param {(msg:any)=>void} args.send post a message to the webview.
 * @param {(handler:(msg:any)=>void)=>(()=>void)} args.subscribe register a
 *        webview-message handler; returns an unsubscribe fn.
 * @param {number} [args.timeoutMs=30000]
 * @param {()=>void} [args.onReady] fired once when the webview endpoint signals
 *        it is booted and serving ({type:'ready'}). The host uses this to know
 *        when it may push commands that must not be dropped pre-boot (e.g. Track
 *        D's load_document — sent before this, serveExecutor isn't subscribed yet).
 * @returns {{executeCommand:(a:string,p?:object)=>Promise<any>, dispose:()=>void}}
 */
export function createRelayExecutor({ send, subscribe, timeoutMs = 30000, onReady }) {
  let seq = 0
  let readyCb = onReady
  const pending = new Map() // reqId -> {resolve, timer}
  const off = subscribe((msg) => {
    if (!msg || msg.__lo !== TAG) return
    if (msg.type === 'ready') { if (readyCb) { const cb = readyCb; readyCb = null; cb() } return }
    if (msg.type !== 'result') return
    const entry = pending.get(msg.reqId)
    if (!entry) return
    clearTimeout(entry.timer)
    pending.delete(msg.reqId)
    entry.resolve(msg.result)
  })

  function executeCommand(action, params = {}) {
    const reqId = 'rly_' + Date.now() + '_' + (++seq)
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (pending.has(reqId)) {
          pending.delete(reqId)
          resolve({ success: false, message: 'LibreOffice relay timeout: ' + action })
        }
      }, timeoutMs)
      pending.set(reqId, { resolve, timer })
      send({ __lo: TAG, type: 'exec', reqId, action, params })
    })
  }

  return { executeCommand, dispose: off }
}

/**
 * Adapter: build {send, subscribe} for a MessagePort (MessageChannel port or an
 * <iframe>/worker port). Used by the spike harness to simulate the
 * host<->webview boundary, and reusable wherever a MessagePort is the transport.
 *
 * @param {MessagePort} port
 */
export function portTransport(port) {
  return {
    send: (msg) => port.postMessage(msg),
    subscribe: (handler) => {
      const f = (e) => handler(e.data)
      port.addEventListener('message', f)
      if (typeof port.start === 'function') port.start()
      return () => port.removeEventListener('message', f)
    },
  }
}
