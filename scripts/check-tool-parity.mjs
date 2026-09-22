#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 两族编辑工具的对拍（dev-board#806，审计 A10 / B-16）。
 *
 *   node scripts/check-tool-parity.mjs
 *
 * 同一件事在两个客户端上有两套工具名：工作台（LOWA 编辑器）见 doc_ / sheet_ / slide_，
 * Word / Excel / PowerPoint 任务窗格见 office_ / office_excel_ / office_ppt_。
 * 会话按客户端只放行其中一族（ClientCapabilityService），所以**任何一族缺了一件事，
 * 那件事在那个客户端上就做不了**——而这类缺口是沉默的：没有报错，只有用户说「这个在
 * Word 里做不了」。已经发生过两次：
 *   B-16  Word 面能标批注已解决却不能删（Excel 面自己就有删批注），也没有目录与页面设置；
 *   A10   contract-review 的清单里 LOWA 面列了 doc_reply_comment，Office 面漏了
 *         office_reply_comment ——工具明明在，被白名单裁掉了，等于「模型看不见」。
 *
 * 所以这里查两层：
 *   1. **工具面**：下面那张能力矩阵里，两族都该有的能力必须两族都真的注册着；
 *      只有一族有的，必须在 LOWA_ONLY / OFFICE_ONLY 里带着理由列出来。
 *      矩阵对两族**全覆盖**——新加一个工具而没人归类，这条检查当场红，
 *      逼着加工具的人自己回答「另一族要不要跟着加」。
 *   2. **skill 白名单**：任何 skill 的 allowed_tools 里出现了某个能力的一族工具，
 *      而同一能力的另一族工具也存在却没被列出，即判失败（SKILL_ASYMMETRY 可豁免，带理由）。
 *
 * 退出码非 0 即失败，失败信息直接说清该往哪加。
 */

import { readFileSync, readdirSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const ROOT = fileURLToPath(new URL('..', import.meta.url))
const TOOLS_DIR = path.join(ROOT, 'backend/src/main/java/com/checkba/service/ai/tools')
const SKILLS_DIR = path.join(ROOT, 'backend/skills')

/* ==================== 能力矩阵 ==================== */

/**
 * 每行：[能力, LOWA 侧工具, 插件侧工具]。
 * 两侧同一行 = 「同一件事」，缺任何一边都判失败。
 */
const MATRIX = [
  // ---- 文字（doc_ ↔ office_）----
  ['读取正文', 'doc_get_document_text', 'office_get_text'],
  ['读取选区', 'doc_get_selection', 'office_get_selection'],
  ['查找文本', 'doc_find_text', 'office_search'],
  ['查找替换', 'doc_find_replace', 'office_replace_text'],
  ['光标处插入', 'doc_insert_at_cursor', 'office_insert_text'],
  ['字符格式', 'doc_format_selection', 'office_format_text'],
  ['段落格式', 'doc_set_paragraph_format', 'office_set_paragraph_format'],
  ['读取格式', 'doc_get_formatting', 'office_get_formatting'],
  ['自动编号', 'doc_set_numbering', 'office_set_numbering'],
  ['套用样式', 'doc_set_style', 'office_apply_style'],
  ['律所标准格式', 'doc_apply_standard_format', 'office_apply_standard_format'],
  ['插入表格', 'doc_insert_table', 'office_insert_table'],
  ['读取表格', 'doc_table_read', 'office_table_read'],
  ['写表格单元格', 'doc_table_set_cell', 'office_table_set_cell'],
  ['表格插行', 'doc_table_add_row', 'office_table_add_row'],
  ['表格删行', 'doc_table_delete_row', 'office_table_delete_row'],
  ['表格插列', 'doc_table_add_col', 'office_table_add_col'],
  ['表格删列', 'doc_table_delete_col', 'office_table_delete_col'],
  ['表格格式', 'doc_format_table', 'office_format_table'],
  ['分页/分节符', 'doc_insert_break', 'office_insert_break'],
  ['超链接', 'doc_set_hyperlink', 'office_set_hyperlink'],
  ['页眉页脚', 'doc_edit_header_footer', 'office_edit_header_footer'],
  ['插入目录', 'doc_insert_toc', 'office_insert_toc'],
  ['页面设置', 'doc_set_page_setup', 'office_set_page_setup'],
  ['插入脚注', 'doc_insert_footnote', 'office_insert_footnote'],
  ['插入尾注', 'doc_insert_endnote', 'office_insert_endnote'],
  ['插入图片', 'doc_insert_image', 'office_insert_image'],
  ['添加批注', 'doc_add_comment', 'office_add_comment'],
  ['读取批注', 'doc_get_comments', 'office_get_comments'],
  ['回复批注', 'doc_reply_comment', 'office_reply_comment'],
  ['解决批注', 'doc_resolve_comment', 'office_resolve_comment'],
  ['删除批注', 'doc_delete_comment', 'office_delete_comment'],
  ['读取修订', 'doc_list_revisions', 'office_get_revisions'],
  ['接受修订', 'doc_accept_revision', 'office_accept_revision'],
  ['拒绝修订', 'doc_reject_revision', 'office_reject_revision'],

  // ---- 表格（sheet_ ↔ office_excel_）----
  ['表格读区域', 'sheet_read_range', 'office_excel_get_range'],
  ['表格写区域', 'sheet_write_cells', 'office_excel_set_values'],
  ['表格查找', 'sheet_search', 'office_excel_search'],
  ['表格查找替换', 'sheet_find_replace', 'office_excel_replace'],
  ['工作簿概览', 'sheet_get_overview', 'office_excel_get_overview'],
  ['表格选中区域', 'sheet_select_range', 'office_excel_select_range'],
  ['单元格格式', 'sheet_format_cells', 'office_excel_format_cells'],
  ['单元格边框', 'sheet_set_borders', 'office_excel_set_borders'],
  ['行列增删', 'sheet_edit_rows_cols', 'office_excel_edit_rows_cols'],
  ['合并单元格', 'sheet_merge_cells', 'office_excel_merge_cells'],
  ['区域排序', 'sheet_sort_range', 'office_excel_sort_range'],
  ['工作表管理', 'sheet_manage_sheets', 'office_excel_manage_sheets'],
  ['冻结窗格', 'sheet_freeze_panes', 'office_excel_freeze_panes'],
  ['自动筛选', 'sheet_set_autofilter', 'office_excel_set_autofilter'],
  ['条件格式', 'sheet_conditional_format', 'office_excel_conditional_format'],
  ['数据验证', 'sheet_set_data_validation', 'office_excel_set_data_validation'],
  ['插入图表', 'sheet_add_chart', 'office_excel_add_chart'],
  ['命名区域', 'sheet_define_name', 'office_excel_define_name'],
  ['保护工作表', 'sheet_protect_sheet', 'office_excel_protect_sheet'],
  ['行列分组', 'sheet_group_rows_cols', 'office_excel_group_rows_cols'],
  ['透视表', 'sheet_add_pivot_table', 'office_excel_add_pivot_table'],
  ['表格加批注', 'sheet_add_comment', 'office_excel_add_comment'],
  ['表格读批注', 'sheet_get_comments', 'office_excel_get_comments'],
  ['表格删批注', 'sheet_delete_comment', 'office_excel_delete_comment'],

  // ---- 演示（slide_ ↔ office_ppt_）----
  ['演示概览', 'slide_get_overview', 'office_ppt_get_slides'],
  ['读取单页', 'slide_get_page', 'office_ppt_get_slide_details'],
  ['演示查找替换', 'slide_replace_text', 'office_ppt_replace_text'],
  ['演示字符格式', 'slide_format_text', 'office_ppt_format_text'],
  ['新增幻灯片', 'slide_add_page', 'office_ppt_add_slide'],
  ['删除幻灯片', 'slide_delete_page', 'office_ppt_delete_slide'],
  ['移动幻灯片', 'slide_move_page', 'office_ppt_move_slide'],
  ['插入文本框', 'slide_add_text_box', 'office_ppt_add_text_box'],
  ['插入形状', 'slide_add_shape', 'office_ppt_add_shape'],
  ['删除形状', 'slide_delete_shape', 'office_ppt_delete_shape'],
  ['演示插表格', 'slide_add_table', 'office_ppt_add_table'],
  ['演示读表格', 'slide_table_read', 'office_ppt_table_read'],
  ['演示写单元格', 'slide_table_set_cell', 'office_ppt_table_set_cell'],
  ['演示超链接', 'slide_set_hyperlink', 'office_ppt_set_hyperlink']
]

/**
 * 只有 LOWA 侧有的工具，按理由分组。
 * 插件面补不了这些**不是缺口**——理由必须说清为什么补不了或不该补。
 */
const LOWA_ONLY = {
  '编辑器宿主专属：插件直接改用户打开的 Word/Excel/PPT，没有 worker、没有服务端文档检查点，也没有可编程的撤销栈（Office.js 不提供）': [
    'doc_undo', 'doc_redo', 'doc_restore_checkpoint', 'doc_open_file', 'doc_start_stream',
    'doc_debug_revisions', 'doc_accept_all_revisions', 'doc_reject_all_revisions'
  ],
  '光标/选区定位面：LOWA 的编辑是「先选中再改」，插件面一律按锚点文本定位，没有独立的选区原语': [
    'doc_select_anchor', 'doc_select_paragraph', 'doc_set_selection', 'doc_collapse_cursor',
    'doc_goto', 'doc_get_cursor_context', 'doc_replace_selection', 'doc_delete_selection'
  ],
  '按段落号取数/改数：插件面没有稳定的段落号（Office.js 的段落集合随编辑漂移），对应能力由 office_get_text 分页 + 锚点定位承担': [
    'doc_get_paragraph', 'doc_modify_paragraph', 'doc_get_outline', 'doc_get_clauses',
    'doc_insert_under_heading', 'doc_audit_structure', 'doc_apply_style_profile'
  ],
  '按第 N 处/按匹配删改：插件面由 office_replace_text(replaceAll) 与 office_replace_batch 覆盖': [
    'doc_replace_nth_match', 'doc_replace_at_anchor', 'doc_delete_match', 'doc_delete_text'
  ],
  '项目文件面，不属于文档编辑基本面（插件会话用 read_file / search_project_files 等通用工具）': [
    'doc_list_project_files', 'doc_search_related_docs', 'doc_link_evidence', 'doc_list_evidence',
    'sheet_create_file'
  ],
  '表格行高列宽：插件面并进了 office_excel_edit_rows_cols 的 set_width / set_height 两个 action': [
    'sheet_set_row_col'
  ],
  '演示细项：插件面尚未提供对等工具（形状几何、版式、表格样式、备注、跳页）': [
    'slide_format_shape', 'slide_set_shape_geometry', 'slide_set_layout', 'slide_set_shape_text',
    'slide_table_set_style', 'slide_write_notes', 'slide_read_notes', 'slide_goto'
  ]
}

/**
 * 只有插件侧有的工具，按理由分组。
 */
const OFFICE_ONLY = {
  '批量/过卷原语：Office.js 每处改动都要一次 Word.run 往返，几十处就会撞上单轮步数上限；LOWA 侧一次 find_replace 就是引擎原生全文替换，不需要成批提交': [
    'office_replace_batch', 'office_pass_step'
  ],
  'Word 专属对象模型：LOWA（LibreOffice）没有对等概念或没有可用的写入路径': [
    'office_manage_content_control', 'office_set_document_properties'
  ],
  '公式单独成面：LOWA 的 sheet_write_cells 同一条命令就能写公式，插件面因 Office.js 的 values 与 formulas 是两个属性而拆成两条': [
    'office_excel_set_formulas'
  ],
  '表格批注线程：Excel 的批注有线程语义（回复/解决），LibreOffice Calc 的批注是单条，没有对等动作': [
    'office_excel_reply_comment', 'office_excel_resolve_comment'
  ]
}

/**
 * skill 白名单的豁免：`'<skill>/<能力>': '理由'`。
 * 空表是好事——有条目就说明某个 skill 刻意只给一族，理由要写清。
 */
const SKILL_ASYMMETRY = {}

/* ==================== 读取仓库现状 ==================== */

const TOOL_DECL = /public\s+(?:static\s+)?[\w<>[\],\s.]+?\s+((?:doc|sheet|slide|office)_[a-zA-Z_0-9]+)\s*\(/g

function registeredTools() {
  const found = new Set()
  for (const name of readdirSync(TOOLS_DIR)) {
    if (!name.endsWith('.java')) continue
    const src = readFileSync(path.join(TOOLS_DIR, name), 'utf8')
    for (const m of src.matchAll(TOOL_DECL)) found.add(m[1])
  }
  return found
}

/** skill.yml 的 allowed_tools 列表（只认 `  - tool_name` 这种行，够用且不引 yaml 依赖）。 */
function skillAllowedTools(file) {
  const src = readFileSync(file, 'utf8')
  const start = src.indexOf('\nallowed_tools:')
  if (start < 0) return null
  const rest = src.slice(start + '\nallowed_tools:'.length)
  const tools = []
  for (const line of rest.split('\n')) {
    if (/^\s*#/.test(line) || line.trim() === '') continue
    const m = /^\s+-\s+([a-z][a-z0-9_]*)\s*$/.exec(line)
    if (m) { tools.push(m[1]); continue }
    if (/^\S/.test(line)) break // 顶格的新键，列表结束
  }
  return tools
}

function skillFiles() {
  const out = []
  for (const name of readdirSync(SKILLS_DIR)) {
    const dir = path.join(SKILLS_DIR, name)
    if (!statSync(dir).isDirectory()) continue
    const yml = path.join(dir, 'skill.yml')
    try {
      if (statSync(yml).isFile()) out.push([name, yml])
    } catch (e) { /* 没有 skill.yml 的目录跳过 */ }
  }
  return out
}

/* ==================== 检查 ==================== */

const errors = []
const tools = registeredTools()

// 0) 矩阵自身的合法性：不许有空行、不许写重复工具
const seen = new Map()
for (const [cap, lowa, office] of MATRIX) {
  if (!lowa || !office) errors.push(`矩阵行「${cap}」两侧都必须填工具名；只有一族有的请放进 LOWA_ONLY / OFFICE_ONLY 并写理由`)
  for (const t of [lowa, office]) {
    if (!t) continue
    if (seen.has(t)) errors.push(`工具 ${t} 在矩阵里出现了两次（「${seen.get(t)}」与「${cap}」）`)
    seen.set(t, cap)
  }
}
for (const [reason, list] of Object.entries({ ...LOWA_ONLY, ...OFFICE_ONLY })) {
  if (!reason || reason.length < 10) errors.push(`单族分组的理由太短，说不清为什么不对称：「${reason}」`)
  for (const t of list) {
    if (seen.has(t)) errors.push(`工具 ${t} 既在矩阵里又在单族名单里`)
    seen.set(t, '单族：' + reason)
  }
}

// 1) 工具面：矩阵点名的工具必须真的注册着
for (const [cap, lowa, office] of MATRIX) {
  for (const t of [lowa, office]) {
    if (t && !tools.has(t)) {
      errors.push(`能力「${cap}」点名的 ${t} 在后端工具类里不存在——要么补上它，要么把这一行改成单族并写理由`)
    }
  }
}
for (const [reason, list] of Object.entries({ ...LOWA_ONLY, ...OFFICE_ONLY })) {
  for (const t of list) {
    if (!tools.has(t)) errors.push(`单族名单里的 ${t} 已经不存在了（分组：${reason}），请把它删掉`)
  }
}

// 2) 全覆盖：新加的工具必须被归类（配对，或作为单族并写明理由）
const uncovered = [...tools].filter((t) => !seen.has(t)).sort()
if (uncovered.length) {
  errors.push(
    '这些编辑工具还没在对拍表里归类：\n    ' + uncovered.join('\n    ') +
    '\n  新加一个工具时必须回答一个问题：另一族要不要跟着加？' +
    '\n  要加 → 两族都加完，然后往 MATRIX 里补一行；' +
    '\n  不加 → 放进 LOWA_ONLY / OFFICE_ONLY 并写清楚为什么另一族不需要（或做不到）。'
  )
}

// 3) skill 白名单：一族列了、另一族没列 = 同一个 skill 在两个客户端上能力不对等
for (const [skill, file] of skillFiles()) {
  const allowed = skillAllowedTools(file)
  if (!allowed || !allowed.length) continue
  const set = new Set(allowed)
  for (const [cap, lowa, office] of MATRIX) {
    const hasLowa = set.has(lowa)
    const hasOffice = set.has(office)
    if (hasLowa === hasOffice) continue
    if (SKILL_ASYMMETRY[`${skill}/${cap}`]) continue
    const missing = hasLowa ? office : lowa
    const present = hasLowa ? lowa : office
    errors.push(
      `skill ${skill}：能力「${cap}」只列了 ${present}，漏了 ${missing}。` +
      `会话按客户端只放行一族，漏列 = 模型在另一个客户端上看不见这个能力（审计 A10 就是这么发生的）。`
    )
  }
}

/* ==================== 报告 ==================== */

if (errors.length) {
  console.error('工具两族对拍失败：\n')
  for (const e of errors) console.error('  - ' + e + '\n')
  process.exit(1)
}
console.log(`工具两族对拍通过：${MATRIX.length} 项能力两族齐备，` +
  `${tools.size} 个编辑工具全部已归类，${skillFiles().length} 个 skill 的白名单两族对称。`)
