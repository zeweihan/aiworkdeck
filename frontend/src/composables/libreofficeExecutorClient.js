// libreofficeExecutorClient.js — framework-agnostic LibreOffice executor client.
//
// Epic #43 task ④. Plain ES module (NO Vue) so it can be unit-driven by the
// Phase 0 spike harness against a REAL running LibreOffice, AND wrapped by the
// Vue composable useLibreOfficeBridge.js for the app. It is the request/response
// CLIENT to the ZetaOffice office worker: it forwards editor-agnostic commands
// ({action, params}) over the worker thread port and correlates replies by
// reqId. The actual UNO operations run in the office worker (the spike's
// office_thread.js, shared/evolved for the product).
//
// Contract = the SAME action names useWpsBridge.executeCommand dispatches
// (get_selection / find_replace / insert_at_cursor / ...). RFC §0.2 invariant:
// offset-shaped actions must map to anchors on the worker, never integer offsets.

export const EDITOR_ACTIONS = [
  // [verified] proven against the Phase 0 spike UNO bridge
  'insert_at_cursor', 'replace_selection', 'find_replace', 'get_selection', 'find_text_locations',
  // [verified-extend] Writer command set
  'replace_nth_match', 'delete_match', 'delete_text', 'get_paragraph', 'modify_paragraph', 'get_outline', 'goto',
  // [stub:§0.2] integer-offset — must become anchor-based in the worker
  'set_selection', 'replace_at_position',
]

/**
 * Create an executor client bound (later, via connect) to a ZetaOffice worker
 * thread port. Returns { connect, isConnected, executeCommand }.
 *
 * @param {object} [opts]
 * @param {number} [opts.timeoutMs=30000]
 * @param {(msg:string)=>void} [opts.onError] optional error sink
 */
export function createLibreOfficeExecutor(opts = {}) {
  const timeoutMs = opts.timeoutMs || 30000
  let workerPort = null
  let reqSeq = 0
  const pending = new Map() // reqId -> {resolve, reject, timer}

  function handleMessage(e) {
    const d = (e && e.data) || {}
    if (d.cmd !== 'result') return
    const entry = pending.get(d.reqId)
    if (!entry) return
    clearTimeout(entry.timer)
    pending.delete(d.reqId)
    entry.resolve(d.result)
  }

  /**
   * Wire the embedded ZetaOffice office-worker port (the value Module.uno_main
   * resolves to in the host). Uses addEventListener so it COEXISTS with any
   * onmessage handler the host already set (e.g. the spike's boot-log handler).
   */
  function connect(port) {
    workerPort = port
    if (typeof port.addEventListener === 'function') {
      port.addEventListener('message', handleMessage)
      if (typeof port.start === 'function') port.start()
    } else {
      // Fallback: chain onto an existing onmessage.
      const prev = port.onmessage
      port.onmessage = (e) => { handleMessage(e); if (prev) prev(e) }
    }
  }

  function isConnected() { return !!workerPort }

  function request(action, params) {
    if (!workerPort) return Promise.reject(new Error('LibreOffice office worker not connected'))
    const reqId = 'lo_' + Date.now() + '_' + (++reqSeq)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (pending.has(reqId)) { pending.delete(reqId); reject(new Error('LibreOffice command timeout: ' + action)) }
      }, timeoutMs)
      pending.set(reqId, { resolve, reject, timer })
      workerPort.postMessage({ cmd: 'exec', reqId, action, params: params || {} })
    })
  }

  /**
   * Editor-agnostic command entry point — SAME signature/contract as
   * useWpsBridge.executeCommand(action, params). Unknown / ppt_* actions reject.
   */
  async function executeCommand(action, params = {}) {
    if (action && action.startsWith && action.startsWith('ppt_')) {
      const m = 'ppt_* not supported by the LibreOffice executor (use WPS executor): ' + action
      if (opts.onError) opts.onError(m)
      return { success: false, message: m }
    }
    if (!EDITOR_ACTIONS.includes(action)) {
      const m = 'Unknown action: ' + action
      if (opts.onError) opts.onError(m)
      return { success: false, message: m }
    }
    try {
      return await request(action, params)
    } catch (e) {
      const m = e && e.message ? e.message : String(e)
      if (opts.onError) opts.onError(m)
      return { success: false, message: m }
    }
  }

  return { connect, isConnected, executeCommand }
}
