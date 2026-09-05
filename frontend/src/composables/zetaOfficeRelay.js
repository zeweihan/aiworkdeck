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
  // 宿主 reqId -> worker reqId：progress 往上带宿主的 id，cancel 往下换成 worker 的 id。
  const inflight = new Map()
  const off = subscribe(async (msg) => {
    if (!msg || msg.__lo !== TAG || msg.type !== 'exec') return
    let result
    try {
      let params = msg.params || {}
      if (msg.action === 'cancel' && params.reqId && inflight.has(params.reqId)) {
        params = Object.assign({}, params, { reqId: inflight.get(params.reqId) })
      }
      result = await executor.executeCommand(msg.action, params, {
        onIssued: (id) => inflight.set(msg.reqId, id),
        onProgress: (p) => send({ __lo: TAG, type: 'progress', reqId: msg.reqId, done: p.done, total: p.total }),
      })
    } catch (e) {
      result = { success: false, message: e && e.message ? e.message : String(e) }
    }
    inflight.delete(msg.reqId)
    send({ __lo: TAG, type: 'result', reqId: msg.reqId, result })
  })
  return { dispose: off }
}

// 按 action 分级的等待预算（ms）——与 libreofficeExecutorClient.js 的
// ACTION_BUDGET_MS、后端 EditorBridgeService.ACTION_TIMEOUT_SECONDS 三处同表。
const ACTION_BUDGET_MS = {
  load_document: 180000, export_document: 180000,
  find_replace: 120000, apply_house_style: 120000, resolve_all_revisions: 120000, insert_table: 120000,
  apply_style_profile: 120000,
  resolve_revisions: 120000,
  // 整段插入类（dev-board#464）：一份十几页的报告经修订逐行落字远超 30s，超时后
  // 后端把「不再等」报成失败，模型重发一次 —— 同一份报告插了两遍。
  insert_at_cursor: 120000, insert_under_heading: 120000,
  replace_selection: 120000, modify_paragraph: 120000,
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
 * @param {(action:string,result:any)=>void} [args.onLateResult] fired when a
 *        'result' message arrives for a reqId that already timed out (see
 *        below) — the caller decides whether the straggler is still relevant.
 * @param {(reqId:string, p:{done:number,total:number})=>void} [args.onProgress]
 *        batch progress of a command still in flight (find_replace >50 hits /
 *        apply_house_style). The reqId is what executeCommand('cancel', {reqId})
 *        takes to stop it (dev-board#108).
 * @returns {{executeCommand:(a:string,p?:object)=>Promise<any>, dispose:()=>void}}
 */
export function createRelayExecutor({ send, subscribe, timeoutMs = 30000, onReady, onLateResult, onProgress }) {
  let seq = 0
  let readyCb = onReady
  const pending = new Map() // reqId -> {resolve, timer}
  // Timing out a command only stops US from waiting on it — the worker-side
  // operation (e.g. loadComponentFromURL for load_document) is not abortable
  // and often keeps running to completion after we've already resolved
  // failure to the caller. Without this, that late 'result' message has
  // nowhere to land (pending.get returns undefined) and used to be silently
  // dropped — the exact bug this fixes: editor reports "load failed" while
  // the document actually finishes loading a moment later.
  // Bounded + self-pruning on match: a long-lived webview must not leak one
  // entry per timeout forever.
  const tombstones = new Map() // reqId -> action
  const MAX_TOMBSTONES = 20
  const off = subscribe((msg) => {
    if (!msg || msg.__lo !== TAG) return
    if (msg.type === 'ready') { if (readyCb) { const cb = readyCb; readyCb = null; cb() } return }
    if (msg.type === 'progress') {
      const p = { done: Number(msg.done) || 0, total: Number(msg.total) || 0 }
      const live = pending.get(msg.reqId)
      if (live && live.onProgress) { try { live.onProgress(p) } catch (e) { /* ignore */ } }
      if (onProgress) { try { onProgress(msg.reqId, p) } catch (e) { /* ignore */ } }
      return
    }
    if (msg.type !== 'result') return
    const entry = pending.get(msg.reqId)
    if (!entry) {
      const lateAction = tombstones.get(msg.reqId)
      if (lateAction) {
        tombstones.delete(msg.reqId)
        if (onLateResult) onLateResult(lateAction, msg.result)
      }
      return
    }
    clearTimeout(entry.timer)
    pending.delete(msg.reqId)
    entry.resolve(msg.result)
  })

  // callOpts（可选）：{onProgress(p), onIssued(reqId)}；reqId 也是 cancel 的把手。
  function executeCommand(action, params = {}, callOpts) {
    const reqId = 'rly_' + Date.now() + '_' + (++seq)
    // Whole-document transfers (load/export can be tens of MB and the worker
    // marshals every byte into UNO) and whole-document batch edits get a longer
    // deadline than interactive commands — a 18MB docx on a slow disk must not
    // surface as "加载失败" just because the default 30s ran out mid-import.
    const budget = ACTION_BUDGET_MS[action] ? Math.max(timeoutMs, ACTION_BUDGET_MS[action]) : timeoutMs
    if (callOpts && callOpts.onIssued) { try { callOpts.onIssued(reqId) } catch (e) { /* ignore */ } }
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        if (pending.has(reqId)) {
          pending.delete(reqId)
          tombstones.set(reqId, action)
          if (tombstones.size > MAX_TOMBSTONES) tombstones.delete(tombstones.keys().next().value)
          resolve({ success: false, message: 'LibreOffice relay timeout: ' + action })
        }
      }, budget)
      pending.set(reqId, { resolve, timer, onProgress: callOpts && callOpts.onProgress })
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
