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
  // [视图缩放] 触控板捏合 / Cmd+加减号；{value} 绝对百分比、{delta} 相对增量，
  // 不带参数就是只读回当前缩放。宿主发起，不是 AI 管线。
  'set_zoom',
  // [自建工具栏 P1] 工具栏状态一次性回读、样式下拉数据、LO chrome 开关、修订开关。
  // 全部宿主发起（EditorToolbar → executor），不是 AI 管线。
  'get_ui_state', 'list_styles', 'set_chrome', 'set_track_changes',
  // [自建工具栏 P2b] 用户对当前选区加批注（署名用户本人；AI 管线走 add_comment）
  'add_comment_at_selection',
  // [自建查找栏 P3] 上一个/下一个匹配。不留书签——只动视图光标。
  'find_navigate',
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
  // [EvidenceLink dev-board#103] 书签名 = linkKey 的证据锚点五原语。Host-initiated
  //（EvidenceLink 服务 / 拖拽关联 / 底稿面板），不是 AI 管线；失败双字段 error+message。
  'bookmark_selection', 'get_bookmark_context', 'check_link_anchors', 'adopt_legacy_links', 'goto_bookmark',
  // [批注] Word comment on an anchored range — 解释/说明类文字不进正文的通道
  // （backend doc_add_comment）。
  'add_comment',
  // [第 2 期 版本对比] host-initiated：当前文档与旧版字节比较产出修订，随后切只读。
  'compare_document',
  // [格式增强] 富格式原语：编号/表格/格式读取/全文标准格式化；insert_under_heading
  // 是后端一直在派发但从未接通的原语（本次补齐 worker 实现）。
  'set_numbering', 'format_table', 'insert_table', 'get_formatting', 'apply_house_style',
  'insert_under_heading',
  // [样式画像 dev-board#111] 项目模板画像：set_style_profile 换 worker 的 HOUSE、
  // apply_style_profile 落到样式定义与正文；insert_toc / set_page_setup 补齐目录与纸张。
  'set_style_profile', 'apply_style_profile', 'insert_toc', 'set_page_setup',
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
  // 这一组同时也是文档能力矩阵 4.2 节 Word 待办里"包一层 AI 工具面"的落点：
  // doc_list_revisions/doc_accept(_all)_revision(s)/doc_reject(_all)_revision(s)、
  // doc_get_comments/doc_resolve_comment/doc_delete_comment 直接复用同一批 action。
  'list_revisions', 'goto_revision', 'resolve_revision', 'resolve_all_revisions',
  'list_comments', 'goto_comment', 'set_comment_resolved', 'delete_comment',
  // [批注回复] doc_reply_comment 的落点——同一批注锚点上追加一条新批注，见
  // office_thread.js reply_comment 的实现注释（原生线程属性 best-effort）。
  'reply_comment',
  // [Word 二期结构面] 文档能力矩阵 4.2 节桌面端待办：页眉页脚 / 分页分节符 /
  // 脚注尾注 / 锚点定位超链接 / 应用既有样式。均为新 worker 实现（非包装既有
  // host-initiated action）。
  'edit_header_footer', 'insert_break', 'insert_footnote', 'insert_endnote',
  'set_hyperlink_at_anchor', 'set_style',
  // [Calc 二期] 文档能力矩阵 4.2 节 Excel 待办：单元格批注（无线程回复/解决态，
  // 只做增/查/删）/ 数据验证 / 图表（建图表+选类型+标题起步）/ Excel 专用搜索 /
  // 工作簿级命名区域 / 工作表保护 / 行列分组大纲 / 数据透视表（行分组+求和基础形态）。
  'sheet_add_comment', 'sheet_get_comments', 'sheet_delete_comment',
  'sheet_set_data_validation', 'sheet_add_chart', 'sheet_search',
  'sheet_define_name', 'sheet_protect_sheet', 'sheet_group_rows_cols',
  'sheet_add_pivot_table',
  // [Impress 演示文稿] slide_* 原语集 Phase 1（打开/读取/文本编辑）：与 doc_*(Writer)/
  // sheet_*(Calc) 三分，工具名 = action 名不做映射。worker 侧 resolvePage/resolveShape
  // 对非 Impress 文档返回明确错误（同 resolveSheet 口径）。
  // 设计依据：docs/superpowers/specs/2026-08-07-impress-bridge-design.md §4。
  'slide_get_overview', 'slide_get_page', 'slide_read_notes', 'slide_write_notes',
  'slide_goto', 'slide_set_shape_text', 'slide_replace_text',
  // [Impress Phase 2] 页与形状结构：插删移页 / 版式与母版 / 插文本框/形状 / 删形状 /
  // 移动改尺寸形状。设计依据同上 §4.2（原语表 8-15）。
  'slide_add_page', 'slide_delete_page', 'slide_move_page', 'slide_set_layout',
  'slide_add_text_box', 'slide_add_shape', 'slide_delete_shape', 'slide_set_shape_geometry',
  // [Impress Phase 3] 格式与表格：文字格式 / 形状填充边框透明度 / 建表读表改格 /
  // 表格整体样式（表头加粗/边框/列宽，能做多少做多少）/ 超链接。设计依据同上 §4.2
  // （原语表 16-20；slide_format_shape/slide_table_set_style 不在原表里，是任务方
  // 直接点名要补的两项）。
  'slide_format_text', 'slide_format_shape', 'slide_add_table', 'slide_table_read',
  'slide_table_set_cell', 'slide_table_set_style', 'slide_set_hyperlink',
  // [诊断] 当前文档内核类型（writer/calc/impress/unknown）——审阅按钮等 UI 按 kind
  // 隐藏的判据；load_document 的返回值里也带 kind，宿主常规路径无需二次往返调用本诊断。
  'get_doc_kind',
  // [取消] 宿主对在飞的批量命令（find_replace / apply_house_style）喊停：
  // {reqId} 取自 onProgress 回调。宿主发起，不是 AI 管线（dev-board#108）。
  'cancel',
]

// 按 action 分级的等待预算（ms）。整文档传输与全文批量改稿都远超 30s 默认值；
// 与 zetaOfficeRelay.js 的 ACTION_BUDGET_MS 和后端 EditorBridgeService
// ACTION_TIMEOUT_SECONDS 三处同表，改一处要同步另两处。
export const ACTION_BUDGET_MS = {
  load_document: 180000, export_document: 180000,
  find_replace: 120000, apply_house_style: 120000, resolve_all_revisions: 120000, insert_table: 120000,
  apply_style_profile: 120000,
}

/**
 * Create an executor client bound (later, via connect) to a ZetaOffice worker
 * thread port. Returns { connect, isConnected, executeCommand }.
 *
 * @param {object} [opts]
 * @param {number} [opts.timeoutMs=30000]
 * @param {(msg:string)=>void} [opts.onError] optional error sink
 * @param {(attrs:object)=>void} [opts.onTelemetry] optional editor.action usage sink (app-side only)
 * @param {(reqId:string, p:{done:number,total:number})=>void} [opts.onProgress]
 *        batch progress from the worker for a command still in flight
 *        (find_replace >50 hits / apply_house_style, dev-board#108)
 */
export function createLibreOfficeExecutor(opts = {}) {
  const timeoutMs = opts.timeoutMs || 30000
  let workerPort = null
  let reqSeq = 0
  const pending = new Map() // reqId -> {resolve, reject, timer}

  function handleMessage(e) {
    const d = (e && e.data) || {}
    if (d.cmd === 'progress') {
      const p = { done: Number(d.done) || 0, total: Number(d.total) || 0 }
      const entry = pending.get(d.reqId)
      if (entry && entry.onProgress) { try { entry.onProgress(p) } catch (err) { /* ignore */ } }
      if (opts.onProgress) { try { opts.onProgress(d.reqId, p) } catch (err) { /* ignore */ } }
      return
    }
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

  // callOpts（可选）：{onProgress(p), onIssued(reqId)}——serveExecutor 用 onIssued 记下
  // worker 侧 reqId，宿主的 cancel 才能对上号。
  function request(action, params, callOpts) {
    if (!workerPort) return Promise.reject(new Error('LibreOffice office worker not connected'))
    const reqId = 'lo_' + Date.now() + '_' + (++reqSeq)
    // Whole-document transfers and whole-document batch edits get a longer
    // deadline (mirror of the host-side relay budget in zetaOfficeRelay.js).
    const budget = ACTION_BUDGET_MS[action] ? Math.max(timeoutMs, ACTION_BUDGET_MS[action]) : timeoutMs
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (pending.has(reqId)) { pending.delete(reqId); reject(new Error('LibreOffice command timeout: ' + action)) }
      }, budget)
      pending.set(reqId, { resolve, reject, timer, onProgress: callOpts && callOpts.onProgress })
      if (callOpts && callOpts.onIssued) { try { callOpts.onIssued(reqId) } catch (e) { /* ignore */ } }
      workerPort.postMessage({ cmd: 'exec', reqId, action, params: params || {} })
    })
  }

  /**
   * Editor-agnostic command entry point — SAME signature/contract as
   * the WPS-era useWpsBridge.executeCommand (removed #79). Unknown / ppt_* actions reject.
   */
  async function executeCommand(action, params = {}, callOpts) {
    if (action && action.startsWith && action.startsWith('ppt_')) {
      const m = 'ppt_* not supported by the LibreOffice executor: ' + action
      if (opts.onError) opts.onError(m)
      return { success: false, message: m }
    }
    if (!EDITOR_ACTIONS.includes(action)) {
      // 埋点：白名单外拒绝是「新工具漏配 EDITOR_ACTIONS」的静默失败计数器
      trackEditorAction(action, params, false, 0, true)
      const m = 'Unknown action: ' + action
      if (opts.onError) opts.onError(m)
      return { success: false, message: m }
    }
    const startMs = Date.now()
    try {
      const result = await request(action, params, callOpts)
      trackEditorAction(action, params, !!(result && result.success), Date.now() - startMs, false)
      return result
    } catch (e) {
      trackEditorAction(action, params, false, Date.now() - startMs, false)
      const m = e && e.message ? e.message : String(e)
      if (opts.onError) opts.onError(m)
      return { success: false, message: m }
    }
  }

  // 埋点：AI 与人工的全部编辑器动作总闸（__agent 是 AI 下发的唯一判别标记）；
  // 只发 action 枚举名与成败/耗时，params 内容不采集。
  // 本模块保持 framework-agnostic（还被 zetaoffice 独立 bundle 与 spike/e2e 打包，
  // 那些上下文没有 @ 别名也没有 uni），采集经 opts.onTelemetry 注入：
  // 应用侧 useLibreOfficeBridge 传入，其他宿主不传即零开销
  function trackEditorAction(action, params, success, durationMs, whitelistRejected) {
    if (!opts.onTelemetry) return
    try {
      opts.onTelemetry({
        action: String(action || ''),
        agent: !!(params && params.__agent),
        success,
        durationMs,
        whitelistRejected
      })
    } catch (e) { /* 静默 */ }
  }

  return { connect, isConnected, executeCommand }
}
