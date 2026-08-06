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
  'get_document_text', 'get_cursor_context', 'get_clauses', 'select_paragraph', 'collapse_selection',
  'delete_selection', 'format_selection', 'set_paragraph_format', 'undo', 'redo',
  // [spike/IME] implemented by the worker since Phase B but never whitelisted
  // (found by the primitive self-test: "Unknown action: move_cursor").
  'move_cursor', 'delete_backward', 'delete_forward', 'insert_paragraph', 'get_cursor_rect',
  // [overlay 快捷键] desktop-parity keys (Cmd/Ctrl+A/B/I/U, Home/End) — the
  // worker holds the .uno: allowlist (UI_COMMANDS in office_thread.js).
  'ui_command',
  // [Track D] load the user's real document into the editor (host-initiated, not
  // an AI-agent command): {bytes, name} -> MEMFS + loadComponentFromURL + retarget.
  'load_document',
  // [Track E] export the current document as bytes (host-initiated save):
  // {name} -> storeToURL into MEMFS -> {bytes, size}; the host uploads them.
  'export_document',
  // [diagnostic #66] report resolved UI locale (ooLocale) to confirm zh-CN took effect.
  'get_ui_lang',
  // [diagnostic] which app modules (swriter/scalc/simpress/sdraw) the engine build contains.
  'probe_modules',
  // [diagnostic] registered device font families — ground truth for the CJK
  // font-injection/alias chain (zetaOfficeBoot.js).
  'list_fonts',
  // [diagnostic] 修订记录清单（类型/作者/文本）。后端 doc_debug_revisions 的
  // worker 端实现——此前 worker 未实现且未入白名单，一直返回 Unknown action。
  'debug_revisions',
  // [#104 变量面板] document variable fields — bookmark-marked spans bound to a
  // (scope, varName) variable; driven by VariablePanel through the getEditor adapter
  // in project-overview.vue, not by the AI agent pipeline.
  'var_list', 'var_insert', 'var_update',
  // [#79 债务清偿] WPS-instance-bound features restored on LibreOffice:
  // 选区↔文件超链接关联 (get/set_selection_hyperlink)、网核证据标记
  // (insert_link_with_bookmark)、图片插入 (insert_image)。Host-initiated
  // (drag-association / evidence-drop / OCR image), not AI-agent commands.
  'get_selection_hyperlink', 'set_selection_hyperlink', 'insert_link_with_bookmark', 'insert_image',
  // [#79 click-to-open] LO WASM 不触发 window.open（v0.7.1 真机证实）：编辑器页
  // 监听 canvas 点击后经此原语读取光标处链接再转发宿主。
  'get_hyperlink_at_cursor',
  // [批注] Word comment on an anchored range — 解释/说明类文字不进正文的通道
  // （backend doc_add_comment）。
  'add_comment',
  // [第 2 期 版本对比] host-initiated：当前文档与旧版字节比较产出修订，随后切只读。
  'compare_document',
  // [格式增强] 富格式原语：编号/表格/格式读取/全文标准格式化；insert_under_heading
  // 是后端一直在派发但从未接通的原语（本次补齐 worker 实现）。
  'set_numbering', 'format_table', 'insert_table', 'get_formatting', 'apply_house_style',
  'insert_under_heading',
  // [Word 表格单元格级] doc_table_* 原语：读表 / 改一格 / 增删行列。insert_table 与
  // format_table 是"整张表"粒度，这一组补的是"改既有表里的一格"（issue #261）。
  'table_read', 'table_set_cell', 'table_add_row', 'table_delete_row',
  'table_add_col', 'table_delete_col',
  // [流式标准格式] doc_start_stream 的落字端：markdown 剥离 + 标准格式写入。
  // stream_insert 攒行消费，stream_flush 收尾/复位（{discard:true} 换文档硬清）。
  'stream_insert', 'stream_flush',
  // [Calc 电子表格] sheet_* 原语集：xlsx 的读写/选区/格式/边框/行高列宽。
  // doc_* 是 Writer 专属（getText 一族在 Calc 上必然失败），表格操作走这组；
  // worker 侧 resolveSheet 对非 Calc 文档返回明确错误。
  'sheet_get_overview', 'sheet_read_range', 'sheet_write_cells', 'sheet_select_range',
  'sheet_format_cells', 'sheet_set_borders', 'sheet_set_row_col',
  // [Calc 结构操作] 工作表增删改名移动/插删行列/合并单元格/排序/筛选/冻结/条件格式。
  'sheet_manage_sheets', 'sheet_edit_rows_cols', 'sheet_merge_cells', 'sheet_sort_range',
  'sheet_set_autofilter', 'sheet_freeze_panes', 'sheet_conditional_format',
  // [审阅面板] 修订/批注的清单·定位·逐条处置。Host-initiated（ReviewPanel.vue），
  // 页边小字读不到作者/时间、表格同行多格删除还会互叠——面板是修订的权威视图。
  'list_revisions', 'goto_revision', 'resolve_revision', 'resolve_all_revisions',
  'list_comments', 'goto_comment', 'set_comment_resolved', 'delete_comment',
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
    // Whole-document transfers get a longer deadline (mirror of the host-side
    // relay budget in zetaOfficeRelay.js — see the comment there).
    const budget = (action === 'load_document' || action === 'export_document')
      ? Math.max(timeoutMs, 180000) : timeoutMs
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (pending.has(reqId)) { pending.delete(reqId); reject(new Error('LibreOffice command timeout: ' + action)) }
      }, budget)
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
