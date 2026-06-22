// useLibreOfficeBridge.js — LibreOffice (ZetaOffice WASM) document-edit executor.
//
// Epic #43 task ④ (started additively). This is the editor-agnostic counterpart
// of useWpsBridge.js: it exposes the SAME `executeCommand(action, params)`
// contract, so the agent command pipeline (backend SSE action -> frontend
// executor -> result) stays editor-agnostic and the WPS and LibreOffice
// executors can COEXIST behind a switch (Phase 3 gray rollout). Nothing here
// touches or breaks the working WPS path — it is dormant until an embedded
// ZetaOffice worker is connected via connect(port).
//
// Architecture: the actual UNO operations run inside the ZetaOffice office
// worker (the Phase 0 spike's office_thread.js, which this will share/evolve).
// This composable is the thin request/response CLIENT to that worker — it
// forwards {action, params} and correlates the worker's reply by reqId. Keeping
// the UNO code in the worker matches the spike and keeps zetajs usage in one
// place.
//
// STATUS: SKELETON. Commands tagged [verified] map to UNO primitives already
// proven in experiments/zetaoffice-spike (model-native XSearchable search,
// RecordChanges redline, insertString at the view cursor, getSelection).
// Commands tagged [todo] are contract-complete here but need their worker-side
// UNO handler + result-shape alignment with the backend result contract.
//
// RFC §0.2 invariant: locating text uses model-native search + cursors/anchors
// (XSearchable / XParagraphCursor / bookmarks), NEVER integer character offsets.
// Any offset-shaped command (set_selection/replace_at_position) must be mapped
// to anchors on the worker side, not reproduced as offsets.

import { ref } from 'vue'

export function useLibreOfficeBridge() {
  const isProcessing = ref(false)
  const lastError = ref(null)

  /** @type {MessagePort | null} the ZetaOffice office-worker thread port */
  let workerPort = null
  let reqSeq = 0
  /** @type {Map<string, {resolve:Function, reject:Function, timer:any}>} */
  const pending = new Map()
  const REQUEST_TIMEOUT_MS = 30000

  /**
   * Connect the embedded ZetaOffice office-worker port (e.g. the value
   * Module.uno_main resolves to in the host). Idempotent.
   */
  function connect(port) {
    workerPort = port
    workerPort.onmessage = (e) => {
      const d = (e && e.data) || {}
      if (d.cmd !== 'result') return
      const entry = pending.get(d.reqId)
      if (!entry) return
      clearTimeout(entry.timer)
      pending.delete(d.reqId)
      entry.resolve(d.result)
    }
  }

  function isConnected() { return !!workerPort }

  /** Send one command to the worker and await its structured result. */
  function request(action, params) {
    if (!workerPort) return Promise.reject(new Error('LibreOffice office worker not connected'))
    const reqId = 'lo_' + Date.now() + '_' + (++reqSeq)
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (pending.has(reqId)) { pending.delete(reqId); reject(new Error('LibreOffice command timeout: ' + action)) }
      }, REQUEST_TIMEOUT_MS)
      pending.set(reqId, { resolve, reject, timer })
      workerPort.postMessage({ cmd: 'exec', reqId, action, params: params || {} })
    })
  }

  /**
   * Editor-agnostic command entry point — SAME signature/contract as
   * useWpsBridge.executeCommand(action, params). The third `instance` arg of the
   * WPS version is unneeded here (the worker holds the document model).
   */
  const executeCommand = async (action, params = {}) => {
    isProcessing.value = true
    lastError.value = null
    try {
      switch (action) {
        // ---- [verified] proven in the Phase 0 spike UNO bridge ----
        case 'insert_at_cursor':     // {text} → XText.insertString at view cursor (append, not select)
        case 'replace_selection':    // {text} → replace selection, else insert at cursor
        case 'find_replace':         // {findText, replaceText, replaceAll} → XSearchable + RecordChanges redline
        case 'get_selection':        // {} → XSelectionSupplier.getSelection (text + anchor, no integer offset)
        case 'find_text_locations':  // {keyword, matchCase} → XSearchable findFirst/findNext → anchor list
          return await request(action, params)

        // ---- [todo] contract-complete; worker UNO handler pending ----
        case 'replace_nth_match':    // {findText, replaceText, matchIndex} → findNext to Nth, setString under RecordChanges
        case 'delete_match':         // {findText, matchIndex} → locate Nth match, delete under RecordChanges
        case 'delete_text':          // {text, deleteAll} → search + delete
        case 'get_paragraph':        // {index} → XParagraphCursor to Nth paragraph
        case 'modify_paragraph':     // {index, newText} → XParagraphCursor + setString under RecordChanges
        case 'get_outline':          // {} → enumerate paragraphs, read heading levels (ParaStyleName/OutlineLevel)
        case 'insert_under_heading': // {headingText, content} → locate heading, insert below
        case 'goto':                 // {type, target} → XTextViewCursor gotoStartOf{Line,Paragraph} / bookmark anchor
          return await request(action, params)

        // ---- [avoid] offset-shaped — must become anchors on the worker (RFC §0.2) ----
        case 'set_selection':        // {start, end} → map to model cursor/anchors, NOT integer offsets
        case 'replace_at_position':  // {start, end, newText} → anchor-based replace, NOT offsets
          return await request(action, params)

        default:
          if (action && action.startsWith('ppt_')) {
            // Impress UNO differs structurally; ppt_* stays on the WPS executor
            // during migration (RFC Phase 1 — PPT single-column, may be deferred).
            throw new Error('ppt_* not yet supported by the LibreOffice executor: ' + action)
          }
          throw new Error('Unknown action: ' + action)
      }
    } catch (e) {
      lastError.value = e && e.message ? e.message : String(e)
      return { success: false, message: lastError.value }
    } finally {
      isProcessing.value = false
    }
  }

  return { executeCommand, connect, isConnected, isProcessing, lastError }
}
