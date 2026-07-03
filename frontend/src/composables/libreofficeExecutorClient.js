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
// Contract = the editor-agnostic action names of the agent command pipeline
// (get_selection / find_replace / insert_at_cursor / ...). RFC §0.2 invariant:
// offset-shaped actions must map to anchors on the worker, never integer offsets.

export const EDITOR_ACTIONS = [
  // [verified] proven against the Phase 0 spike UNO bridge
  'insert_at_cursor', 'replace_selection', 'find_replace', 'get_selection',
  // find_text_locations returns stable anchorIds (bookmarks), NOT integer offsets (§0.2)
  'find_text_locations',
  // [verified-extend] Writer command set
  'replace_nth_match', 'delete_match', 'delete_text', 'get_paragraph', 'modify_paragraph', 'get_outline', 'goto',
  // [§0.2 anchor-based] take {anchor} from find_text_locations; integer offsets rejected
  'set_selection', 'replace_at_position', 'clear_anchors',
  // [拟人式原语] anthropomorphic primitive set (docs/AI_EDITOR_PRIMITIVES.md):
  // perceive (get_document_text/get_cursor_context), position visibly
  // (select_paragraph/collapse_selection), edit (delete_selection), format
  // (format_selection/set_paragraph_format), recover (undo/redo).
  'get_document_text', 'get_cursor_context', 'select_paragraph', 'collapse_selection',
  'delete_selection', 'format_selection', 'set_paragraph_format', 'undo', 'redo',
  // [spike/IME] implemented by the worker since Phase B but never whitelisted
  // (found by the primitive self-test: "Unknown action: move_cursor").
  'move_cursor', 'delete_backward', 'insert_paragraph', 'get_cursor_rect',
  // [Track D] load the user's real document into the editor (host-initiated, not
  // an AI-agent command): {bytes, name} -> MEMFS + loadComponentFromURL + retarget.
  'load_document',
  // [Track E] export the current document as bytes (host-initiated save):
  // {name} -> storeToURL into MEMFS -> {bytes, size}; the host uploads them.
  'export_document',
  // [diagnostic #66] report resolved UI locale (ooLocale) to confirm zh-CN took effect.
  'get_ui_lang',
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
   * the WPS-era useWpsBridge.executeCommand (removed #79). Unknown / ppt_* actions reject.
   */
  async function executeCommand(action, params = {}) {
    if (action && action.startsWith && action.startsWith('ppt_')) {
      const m = 'ppt_* not supported by the LibreOffice executor: ' + action
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
