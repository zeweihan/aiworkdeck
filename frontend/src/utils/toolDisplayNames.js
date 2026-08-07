// toolDisplayNames.js — 工具代号 → 人性化名称（按软件语言环境取 zh/en）。
//
// 卡片里的「search_web」「doc_open_file」对律师用户是噪音；这里维护一份与后端
// @ToolMeta(displayName) 对齐的映射（后端元数据不随 SSE 下发，前端只能自备一份）。
// 后端新增工具时同步补一行；未收录的代号按 snake_case → 空格分词兜底展示。
// wps_* 是 doc_* 的灰度别名（PR#87），查表前先归一化。

const NAMES = {
  // 计划 / 项目
  todo_write: { zh: '更新任务清单', en: 'Update plan' },
  get_project_context: { zh: '获取项目信息', en: 'Get project context' },
  update_project_info: { zh: '更新项目信息', en: 'Update project info' },
  get_conversation_summary: { zh: '回顾会话记录', en: 'Recall conversation' },
  list_project_folders: { zh: '列出项目文件夹', en: 'List folders' },
  // 网络
  search_web: { zh: '网络搜索', en: 'Web search' },
  browse_url: { zh: '浏览网页', en: 'Browse page' },
  deep_search: { zh: '深度检索', en: 'Deep search' },
  search_knowledge_base: { zh: '搜索知识库', en: 'Search knowledge base' },
  // 法律
  law_search: { zh: '语义搜索法规', en: 'Search regulations' },
  law_search_keyword: { zh: '关键词搜索法规', en: 'Keyword law search' },
  law_recognition: { zh: '法条识别与溯源', en: 'Identify citations' },
  get_law_article: { zh: '查询法条', en: 'Look up article' },
  // 文件
  read_document: { zh: '读取文档', en: 'Read document' },
  search_project_files: { zh: '搜索项目文件', en: 'Search files' },
  read_file: { zh: '读取文件', en: 'Read file' },
  extract_file_text: { zh: '提取文档全文', en: 'Extract document text' },
  list_files: { zh: '列出文件', en: 'List files' },
  write_file: { zh: '写入文件', en: 'Write file' },
  write_docx: { zh: '生成Word文档', en: 'Create Word doc' },
  scan_files: { zh: '扫描项目文件', en: 'Scan files' },
  delete_file: { zh: '删除文件', en: 'Delete file' },
  move_file: { zh: '移动文件', en: 'Move file' },
  create_folder: { zh: '新建文件夹', en: 'Create folder' },
  rename_project_file: { zh: '重命名文件', en: 'Rename file' },
  move_project_file: { zh: '移动文件', en: 'Move file' },
  // 记忆
  save_memory: { zh: '保存记忆', en: 'Save memory' },
  query_memory: { zh: '检索记忆', en: 'Query memory' },
  retrieve_evidence: { zh: '检索证据', en: 'Retrieve evidence' },
  get_user_profile: { zh: '获取用户画像', en: 'Get user profile' },
  // 子任务 / Python
  dispatch_subtask: { zh: '委派子任务', en: 'Delegate subtask' },
  run_python: { zh: '执行Python代码', en: 'Run Python' },
  // 文档编辑（LibreOffice 拟人式原语）
  doc_list_project_files: { zh: '列出可编辑文档', en: 'List documents' },
  doc_open_file: { zh: '打开文档', en: 'Open document' },
  doc_start_stream: { zh: '流式写入文档', en: 'Stream to document' },
  doc_get_document_text: { zh: '通读文档', en: 'Read document text' },
  doc_get_clauses: { zh: '识别合同条款', en: 'Map contract clauses' },
  doc_get_outline: { zh: '获取文档大纲', en: 'Get outline' },
  doc_get_selection: { zh: '读取选区', en: 'Read selection' },
  doc_get_cursor_context: { zh: '查看光标位置', en: 'Inspect cursor' },
  doc_get_paragraph: { zh: '读取段落', en: 'Read paragraph' },
  doc_goto: { zh: '跳转光标', en: 'Move cursor' },
  doc_set_selection: { zh: '设置选区', en: 'Set selection' },
  doc_find_text: { zh: '查找定位', en: 'Find in document' },
  doc_find_replace: { zh: '查找替换', en: 'Find & replace' },
  doc_replace_nth_match: { zh: '替换指定匹配', en: 'Replace match' },
  doc_delete_match: { zh: '删除匹配文本', en: 'Delete match' },
  doc_delete_text: { zh: '删除文本', en: 'Delete text' },
  doc_replace_selection: { zh: '替换选区', en: 'Replace selection' },
  doc_insert_at_cursor: { zh: '插入文本', en: 'Insert text' },
  doc_modify_paragraph: { zh: '修改段落', en: 'Edit paragraph' },
  doc_insert_under_heading: { zh: '标题下插入', en: 'Insert under heading' },
  doc_search_related_docs: { zh: '搜索相关文档', en: 'Find related docs' },
  doc_select_anchor: { zh: '选中定位点', en: 'Select anchor' },
  doc_select_paragraph: { zh: '选中段落', en: 'Select paragraph' },
  doc_collapse_cursor: { zh: '收起光标', en: 'Collapse cursor' },
  doc_replace_at_anchor: { zh: '锚点替换', en: 'Replace at anchor' },
  doc_delete_selection: { zh: '删除选区', en: 'Delete selection' },
  doc_format_selection: { zh: '设置文字格式', en: 'Format text' },
  doc_set_paragraph_format: { zh: '设置段落格式', en: 'Format paragraph' },
  doc_set_numbering: { zh: '设置编号', en: 'Set numbering' },
  doc_format_table: { zh: '设置表格格式', en: 'Format table' },
  doc_insert_table: { zh: '插入表格', en: 'Insert table' },
  doc_table_read: { zh: '读取表格', en: 'Read table' },
  doc_table_set_cell: { zh: '修改单元格', en: 'Edit table cell' },
  doc_table_add_row: { zh: '插入表格行', en: 'Add table row' },
  doc_table_delete_row: { zh: '删除表格行', en: 'Delete table row' },
  doc_table_add_col: { zh: '插入表格列', en: 'Add table column' },
  doc_table_delete_col: { zh: '删除表格列', en: 'Delete table column' },
  doc_get_formatting: { zh: '读取格式', en: 'Read formatting' },
  doc_apply_standard_format: { zh: '应用标准格式', en: 'Apply standard format' },
  doc_undo: { zh: '撤销修改', en: 'Undo' },
  doc_redo: { zh: '重做修改', en: 'Redo' },
  doc_add_comment: { zh: '添加批注', en: 'Add comment' },
  doc_debug_revisions: { zh: '检查修订记录', en: 'Inspect revisions' },
  doc_restore_checkpoint: { zh: '恢复文档快照', en: 'Restore snapshot' },
  // 电子表格（Calc / xlsx）sheet_* 原语
  sheet_get_overview: { zh: '查看工作表结构', en: 'Sheet overview' },
  sheet_read_range: { zh: '读取单元格区域', en: 'Read cells' },
  sheet_write_cells: { zh: '写入单元格', en: 'Write cells' },
  sheet_select_range: { zh: '选中单元格区域', en: 'Select cells' },
  sheet_format_cells: { zh: '设置单元格格式', en: 'Format cells' },
  sheet_set_borders: { zh: '设置单元格边框', en: 'Set cell borders' },
  sheet_set_row_col: { zh: '设置行高列宽', en: 'Set row/column size' },
  sheet_create_file: { zh: '新建表格文件', en: 'Create spreadsheet' },
  sheet_manage_sheets: { zh: '管理工作表', en: 'Manage sheets' },
  sheet_edit_rows_cols: { zh: '插入删除行列', en: 'Edit rows/columns' },
  sheet_merge_cells: { zh: '合并单元格', en: 'Merge cells' },
  sheet_sort_range: { zh: '区域排序', en: 'Sort range' },
  sheet_set_autofilter: { zh: '设置自动筛选', en: 'Set autofilter' },
  sheet_freeze_panes: { zh: '冻结窗格', en: 'Freeze panes' },
  sheet_conditional_format: { zh: '设置条件格式', en: 'Conditional format' },
  // Office 插件（Word 任务窗格）office_* 工具桥
  // 正常情况下主前端（lowa 会话）看不到这些工具（会话能力过滤），收录仅作兜底
  office_get_text: { zh: '读取文档', en: 'Read document' },
  office_get_selection: { zh: '读取选区', en: 'Read selection' },
  office_search: { zh: '查找文本', en: 'Find in document' },
  office_replace_text: { zh: '替换文本（修订）', en: 'Replace text (tracked)' },
  office_insert_text: { zh: '插入文本（修订）', en: 'Insert text (tracked)' },
  office_add_comment: { zh: '插入批注', en: 'Add comment' },
  office_format_text: { zh: '设置文字格式', en: 'Format text' },
  office_set_paragraph_format: { zh: '设置段落格式', en: 'Set paragraph format' },
  office_get_formatting: { zh: '读取格式', en: 'Read formatting' },
  office_set_numbering: { zh: '设置自动编号', en: 'Set list numbering' },
  office_format_table: { zh: '设置表格格式', en: 'Format table' },
  office_apply_standard_format: { zh: '套用标准格式', en: 'Apply standard format' },
  office_excel_get_range: { zh: '读取区域', en: 'Read range' },
  office_excel_set_values: { zh: '写入区域', en: 'Write range' },
  office_excel_search: { zh: '查找单元格', en: 'Find cells' },
  office_excel_format_cells: { zh: '设置单元格格式', en: 'Format cells' },
  office_excel_set_borders: { zh: '设置边框', en: 'Set borders' },
  office_excel_edit_rows_cols: { zh: '编辑行列', en: 'Edit rows/columns' },
  office_excel_merge_cells: { zh: '合并单元格', en: 'Merge cells' },
  office_excel_sort_range: { zh: '排序', en: 'Sort range' },
  office_excel_manage_sheets: { zh: '管理工作表', en: 'Manage sheets' },
  office_excel_freeze_panes: { zh: '冻结窗格', en: 'Freeze panes' },
  office_excel_set_formulas: { zh: '写入公式', en: 'Set formulas' },
  office_excel_get_overview: { zh: '读取总览', en: 'Read overview' },
  office_excel_select_range: { zh: '选中区域', en: 'Select range' },
  office_excel_set_autofilter: { zh: '设置自动筛选', en: 'Set autofilter' },
  office_excel_conditional_format: { zh: '设置条件格式', en: 'Conditional format' },
  // Office 插件（PowerPoint 任务窗格）office_ppt_* 工具桥（批次7；含此前遗漏的基础两项）
  office_ppt_get_slides: { zh: '读取幻灯片', en: 'Read slides' },
  office_ppt_replace_text: { zh: '替换幻灯片文本', en: 'Replace slide text' },
  office_ppt_format_text: { zh: '设置幻灯片文字格式', en: 'Format slide text' },
  office_ppt_add_slide: { zh: '新增幻灯片', en: 'Add slide' },
  office_ppt_delete_slide: { zh: '删除幻灯片', en: 'Delete slide' },
  office_ppt_add_text_box: { zh: '插入文本框', en: 'Insert text box' },
  office_ppt_move_slide: { zh: '移动幻灯片', en: 'Move slide' },
  office_ppt_add_shape: { zh: '插入形状', en: 'Insert shape' },
  office_ppt_get_slide_details: { zh: '读取幻灯片明细', en: 'Read slide details' },
  office_ppt_delete_shape: { zh: '删除形状', en: 'Delete shape' },
  // PPT
  pptx_check_service: { zh: '检查PPT服务', en: 'Check PPT service' },
  pptx_generate: { zh: '生成PPT演示文稿', en: 'Generate slides' },
  pptx_generate_outline: { zh: '生成PPT大纲', en: 'Outline slides' },
  pptx_refine_outline: { zh: '优化PPT大纲', en: 'Refine outline' },
  pptx_open_file: { zh: '打开PPT', en: 'Open slides' },
  pptx_list_files: { zh: '列出PPT文件', en: 'List slide files' },
  pptx_search_files: { zh: '搜索PPT文件', en: 'Search slide files' },
  pptx_get_project_pages: { zh: '读取PPT页面', en: 'Get slide pages' },
  pptx_edit_page: { zh: '编辑幻灯片', en: 'Edit slide' },
  pptx_export_editable: { zh: '导出可编辑PPT', en: 'Export editable slides' },
  pptx_inspect_format: { zh: '读取PPT格式', en: 'Inspect slide formatting' },
  pptx_apply_format: { zh: '设置PPT格式', en: 'Format slides' },
  // PDF
  pdf_list_files: { zh: '列出PDF文件', en: 'List PDF files' },
  pdf_inspect: { zh: '读取PDF内容', en: 'Inspect PDF' },
  pdf_highlight: { zh: '高亮PDF文本', en: 'Highlight PDF text' },
  pdf_annotate: { zh: '添加PDF批注', en: 'Annotate PDF' },
  pdf_redact: { zh: 'PDF脱敏', en: 'Redact PDF' },
  pdf_replace_text: { zh: '替换PDF文本', en: 'Replace PDF text' },
  pdf_to_word: { zh: 'PDF转Word', en: 'Convert PDF to Word' },
}

// code 可以是纯工具名，也可以是 <tool_code> 里的 `tool_name({...})` 完整调用串。
// 产品是中文优先：显示名一律取 zh（此前按 uni.getLocale() 取语言，Electron 常
// 返回 en-*，导致面板里中英文混杂——用户反馈统一为中文）。en 列保留给未来 i18n。
export function toolDisplayName(code) {
  if (!code) return ''
  const m = String(code).match(/^\s*([\w.]+)\s*\(/)
  let name = m ? m[1] : String(code).trim()
  if (name.startsWith('wps_')) name = 'doc_' + name.slice(4) // 灰度别名归一
  const entry = NAMES[name]
  if (entry) return entry.zh
  // 兜底：未收录的代号按 snake_case 分词，至少可读
  return name.replace(/_/g, ' ')
}

export function toolRawName(code) {
  if (!code) return ''
  const m = String(code).match(/^\s*([\w.]+)\s*\(/)
  return m ? m[1] : String(code).trim().split(/\s/)[0]
}
