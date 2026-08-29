// Web 插件桥 doc.exec 放行的编辑器原语白名单（插件规范 v2.7 P1）。
//
// 这张表 = 宿主 SPI PluginHostImpl.DOC_ACTIONS 的同一份清单（JAR 与 Web 插件同一张能力面，
// 不另造子集）：AI 工具已暴露的 doc_/sheet_/slide_ 下发名 ∪ EvidenceLink 书签/链接原语。
// 宿主自用（load_document / export_document / doc_open_file_sync / set_zoom 等）与诊断原语不开放。
//
// 同步纪律：改这张表必须同步 backend PluginHostImpl.DOC_ACTIONS 与 docs/PLUGIN_SPEC.md；
// parity 测试 frontend/tests/plugin-sdk/doc-actions-parity.test.mjs 读 Java 源码逐项对拍，漏一个就红。
// 注意 doc.exec 之后还有 libreofficeExecutorClient 的 EDITOR_ACTIONS 第二道闸（既有），插件绕不过。
export const PLUGIN_DOC_ACTIONS = new Set([
  // writer
  'insert_at_cursor', 'replace_selection', 'find_replace', 'get_selection', 'find_text_locations',
  'replace_nth_match', 'delete_match', 'delete_text', 'get_paragraph', 'modify_paragraph', 'get_outline',
  'goto', 'set_selection', 'replace_at_position', 'clear_anchors', 'get_document_text', 'get_cursor_context',
  'get_clauses', 'select_paragraph', 'collapse_selection', 'delete_selection', 'format_selection',
  'set_paragraph_format', 'undo', 'redo', 'insert_paragraph', 'insert_table', 'insert_break', 'insert_image',
  'insert_under_heading', 'format_table', 'get_formatting', 'set_style', 'set_numbering', 'edit_header_footer',
  'apply_house_style', 'add_comment', 'list_comments', 'reply_comment', 'set_comment_resolved', 'delete_comment',
  'list_revisions', 'resolve_revision', 'resolve_all_revisions', 'set_hyperlink_at_anchor',
  'insert_footnote', 'insert_endnote',
  'table_read', 'table_set_cell', 'table_add_row', 'table_delete_row', 'table_add_col', 'table_delete_col',
  'set_style_profile', 'apply_style_profile', 'insert_toc', 'set_page_setup',
  'bookmark_selection', 'get_bookmark_context', 'goto_bookmark', 'check_link_anchors',
  'get_selection_hyperlink', 'set_selection_hyperlink', 'insert_link_with_bookmark',
  // calc
  'sheet_get_overview', 'sheet_read_range', 'sheet_write_cells', 'sheet_format_cells', 'sheet_set_borders',
  'sheet_merge_cells', 'sheet_set_row_col', 'sheet_edit_rows_cols', 'sheet_manage_sheets', 'sheet_search',
  'sheet_select_range', 'sheet_sort_range', 'sheet_set_autofilter', 'sheet_freeze_panes',
  'sheet_conditional_format', 'sheet_set_data_validation', 'sheet_define_name', 'sheet_group_rows_cols',
  'sheet_protect_sheet', 'sheet_add_chart', 'sheet_add_pivot_table', 'sheet_add_comment',
  'sheet_get_comments', 'sheet_delete_comment',
  // impress
  'slide_get_overview', 'slide_get_page', 'slide_goto', 'slide_add_page', 'slide_delete_page',
  'slide_move_page', 'slide_add_text_box', 'slide_add_shape', 'slide_add_table', 'slide_delete_shape',
  'slide_format_shape', 'slide_format_text', 'slide_read_notes', 'slide_write_notes', 'slide_set_layout',
  'slide_set_shape_text', 'slide_replace_text', 'slide_set_shape_geometry', 'slide_set_hyperlink',
  'slide_table_read', 'slide_table_set_cell', 'slide_table_set_style',
])
