// docEvents.js — 「文档被写过一笔」的全局通知（uni.$emit/$on），载荷 { fileId }。
//
// 为什么需要它（dev-board#460）：审阅面板的「修订 / 批注」计数只有一条刷新来路，
// 就是引擎广播的 modified 边沿（editor-main.js 里 500ms 前沿节流、无尾随）。这条
// 信号是**刻意做成有损的**——它服务的是自动保存，不是 UI 计数。于是 AI 一批命令
// 写完之后，最后那一次 modified 常常落在节流窗口里被丢掉，面板端着写入之前的清单，
// 要等用户下一次操作（切标签、打字）才对得上。
//
// 宿主侧本来就握着一个无损的接缝：AI 的每条编辑命令都是宿主自己发出去的，命令
// 返回 = 这一笔确定写完了。这里就把那个接缝广播出来，与 awd:evidence-changed
// 同一套做法（全局事件 + 按 fileId 认领），让面板不必依赖有损的引擎边沿。
export const DOC_MUTATED_EVENT = 'awd:doc-mutated'

// 防抖窗口：AI 一轮改稿会连着发几十条命令，每条各打一轮 list_revisions +
// list_comments 会把单事件循环的 office 线程读死。只在停笔后补一发。
export const DOC_MUTATED_DEBOUNCE_MS = 300

// 只读 / 只动视图的 action：跑完它们文档内容没变，面板不必重读。
// **判定刻意做成「白名单之外一律算写入」**：将来新增写入原语（doc_* 四件套只要求
// 改 EDITOR_ACTIONS + worker 实现，不会有人记得回来改这里）自动被算作写入——
// 漏判一条写入 = 计数又不刷新（本卡的病灶本身），漏判一条只读 = 多打一轮读命令。
export const DOC_READONLY_ACTIONS = new Set([
  // 读正文 / 读状态
  'get_selection', 'get_paragraph', 'get_outline', 'get_document_text', 'get_cursor_context',
  'get_clauses', 'get_cursor_rect', 'get_ui_state', 'get_ui_lang', 'get_selection_hyperlink',
  'get_hyperlink_at_cursor', 'get_bookmark_context', 'list_styles', 'list_fonts',
  'list_revisions', 'list_comments', 'check_link_anchors', 'var_list',
  'debug_revisions', 'probe_modules',
  // 只动视图光标 / 只动锚点书签，不改正文，也不产生修订与批注
  'find_text_locations', 'find_navigate', 'goto', 'goto_revision', 'goto_comment', 'goto_bookmark',
  'select_paragraph', 'set_selection', 'collapse_selection', 'move_cursor', 'clear_anchors',
  // 显示 / 外观 / 宿主自发的存取，都不改内容
  'export_document', 'load_document', 'set_zoom', 'set_app_theme', 'set_chrome',
  'set_track_changes', 'set_revision_view',
])

export function isDocMutatingAction(action) {
  return !!action && !DOC_READONLY_ACTIONS.has(action)
}
