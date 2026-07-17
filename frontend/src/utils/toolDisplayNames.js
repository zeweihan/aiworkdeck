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
  list_files: { zh: '列出文件', en: 'List files' },
  write_file: { zh: '写入文件', en: 'Write file' },
  write_docx: { zh: '生成Word文档', en: 'Create Word doc' },
  scan_files: { zh: '扫描项目文件', en: 'Scan files' },
  delete_file: { zh: '删除文件', en: 'Delete file' },
  move_file: { zh: '移动文件', en: 'Move file' },
  // 记忆
  save_memory: { zh: '保存记忆', en: 'Save memory' },
  query_memory: { zh: '检索记忆', en: 'Query memory' },
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
  doc_undo: { zh: '撤销修改', en: 'Undo' },
  doc_redo: { zh: '重做修改', en: 'Redo' },
  doc_debug_revisions: { zh: '检查修订记录', en: 'Inspect revisions' },
  doc_restore_checkpoint: { zh: '恢复文档快照', en: 'Restore snapshot' },
  // PPT
  pptx_check_service: { zh: '检查PPT服务', en: 'Check PPT service' },
  pptx_generate: { zh: '生成PPT演示文稿', en: 'Generate slides' },
  pptx_generate_outline: { zh: '生成PPT大纲', en: 'Outline slides' },
  pptx_refine_outline: { zh: '优化PPT大纲', en: 'Refine outline' },
  pptx_smart_modify: { zh: '智能修改PPT', en: 'Modify slides' },
  pptx_open_file: { zh: '打开PPT', en: 'Open slides' },
  pptx_save: { zh: '保存PPT', en: 'Save slides' },
  pptx_list_files: { zh: '列出PPT文件', en: 'List slide files' },
  pptx_search_files: { zh: '搜索PPT文件', en: 'Search slide files' },
  pptx_get_presentation_info: { zh: '读取PPT信息', en: 'Get slides info' },
  pptx_get_project_pages: { zh: '读取PPT页面', en: 'Get slide pages' },
  pptx_get_slide_content: { zh: '读取幻灯片内容', en: 'Read slide' },
  pptx_get_page_screenshot: { zh: '截取幻灯片', en: 'Screenshot slide' },
  pptx_get_selection: { zh: '读取PPT选区', en: 'Read slide selection' },
  pptx_edit_page: { zh: '编辑幻灯片', en: 'Edit slide' },
  pptx_insert_text: { zh: '插入PPT文本', en: 'Insert slide text' },
  pptx_modify_slide_text: { zh: '修改幻灯片文本', en: 'Edit slide text' },
  pptx_mark_delete_text: { zh: '标记删除文本', en: 'Mark text deletion' },
  pptx_export_editable: { zh: '导出可编辑PPT', en: 'Export editable slides' },
}

function appLocale() {
  try {
    if (typeof uni !== 'undefined' && uni.getLocale) return uni.getLocale() || 'zh-CN'
  } catch (e) { /* uni 不可用时退回浏览器语言 */ }
  return (typeof navigator !== 'undefined' && navigator.language) || 'zh-CN'
}

// code 可以是纯工具名，也可以是 <tool_code> 里的 `tool_name({...})` 完整调用串。
export function toolDisplayName(code) {
  if (!code) return ''
  const m = String(code).match(/^\s*([\w.]+)\s*\(/)
  let name = m ? m[1] : String(code).trim()
  if (name.startsWith('wps_')) name = 'doc_' + name.slice(4) // 灰度别名归一
  const entry = NAMES[name]
  if (entry) {
    return appLocale().toLowerCase().startsWith('zh') ? entry.zh : entry.en
  }
  // 兜底：未收录的代号按 snake_case 分词，至少可读
  return name.replace(/_/g, ' ')
}

export function toolRawName(code) {
  if (!code) return ''
  const m = String(code).match(/^\s*([\w.]+)\s*\(/)
  return m ? m[1] : String(code).trim().split(/\s/)[0]
}
