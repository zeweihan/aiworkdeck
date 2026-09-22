// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * excel_replace（表格内查找替换，dev-board#804 / 审查 A16·B-04）的**纯函数层**：
 * 参数归一、单格判定、返回值拼装。Office.js 版（officeExecutor.js）与 WPS JSAPI 版
 * （wpsEtHandlers.js）共用这一份——同一条指令在 Excel 和 WPS 表格上给出不同结果，
 * 比两边都做不到更糟。与 textMatch.js / minimalEdit.js / batchEdits.js 同一类角色：
 * 两族共享的判定逻辑放独立模块，不让一边 import 另一边的执行器。
 *
 * 缺口背景：Excel 两族此前都只有只读的 search，成批改写只能退回 set_values——那是
 * **按矩形区域写**的，散点命中整块回写会把区域内不该动的格子一起覆盖掉（静默数据错误）。
 */

/**
 * 单次替换的硬顶。与后端 OfficeEditTools.MAX_EXCEL_REPLACEMENTS、桌面端
 * DocumentEditTools.MAX_SHEET_REPLACEMENTS / office_thread.js 同值——同一个上限
 * 在三个宿主上必须一样，否则模型换个宿主就撞到不同的墙。
 */
export const MAX_EXCEL_REPLACEMENTS = 2000

/** 返回值里最多列几个命中坐标（其余靠 replaced 计数交代） */
export const MAX_REPORTED_CELLS = 50

/**
 * 字面量替换（**不是正则**：合同里的「(甲方)」「*备注」带正则元字符，编成 RegExp
 * 会被当成语法而不是文字）。matchCase=false 时按小写位置扫原串，保证替换落点与
 * 大小写无关、而未命中的部分连同它原本的大小写一起原样保留。
 */
export function replaceAllLiteral(text, find, replace, matchCase) {
  if (!find) return text
  const hay = matchCase ? text : text.toLowerCase()
  const needle = matchCase ? find : find.toLowerCase()
  let out = ''
  let i = 0
  for (;;) {
    const at = hay.indexOf(needle, i)
    if (at === -1) return out + text.slice(i)
    out += text.slice(i, at) + replace
    i = at + needle.length
  }
}

/** 同口径的出现次数计数（返回值里的 occurrences：一格里可能命中多处） */
export function countLiteral(text, find, matchCase) {
  if (!find) return 0
  const hay = matchCase ? text : text.toLowerCase()
  const needle = matchCase ? find : find.toLowerCase()
  let n = 0
  let i = 0
  for (;;) {
    const at = hay.indexOf(needle, i)
    if (at === -1) return n
    n++
    i = at + needle.length
  }
}

/**
 * 一格该不该替换、替换成什么。
 *
 * 只动纯文本格：
 * - 公式格（formula 以 '=' 开头）不改——写字符串会把公式本身毁掉；
 * - 数值/布尔格不改——写字符串会把数字变成文本，而 "2500.5" 里含 "50" 这类误伤
 *   在数字面上极其常见。
 *
 * 两种跳过都返回 {skip}，由调用方计数并如实报给模型（「没替的那些不是漏了，是本
 * 原语做不到」）。命中但替换后与原值相同（例如 wholeCell 命中而 replace 恰等于原值）
 * 返回 null，不计入 replaced。
 */
export function planCellReplacement({ value, formula, find, replace, matchCase, wholeCell }) {
  if (value == null || value === '') return null
  const shown = String(value)
  const hay = matchCase ? shown : shown.toLowerCase()
  const needle = matchCase ? find : find.toLowerCase()
  if (wholeCell ? hay !== needle : hay.indexOf(needle) === -1) return null
  if (typeof formula === 'string' && formula.charAt(0) === '=') return { skip: 'formula' }
  if (typeof value !== 'string') return { skip: 'numeric' }
  const next = wholeCell ? replace : replaceAllLiteral(shown, find, replace, matchCase)
  if (next === shown) return null
  return { next, occurrences: wholeCell ? 1 : countLiteral(shown, find, matchCase) }
}

/**
 * 参数归一 + 校验。抛出的就是给模型看的中文原因（后端已拦一道同口径的，
 * 这一道是给宿主直调与旧后端留的）。
 */
export function normalizeReplaceArgs(args) {
  const find = String((args && args.find) || '')
  if (!find) throw new Error('查找文本不能为空')
  // 省略 replace 不等于「替换成空串」：那会把命中的内容静默删掉。要删就显式传空串。
  if (!args || args.replace == null) {
    throw new Error('缺少替换文本（要把命中的文本删掉，请显式传空字符串）')
  }
  const replace = String(args.replace)
  if (find === replace) throw new Error('查找与替换文本相同，这次替换不会改变任何内容')
  let cap = MAX_EXCEL_REPLACEMENTS
  if (args.maxReplacements != null) {
    const n = Number(args.maxReplacements)
    if (!Number.isFinite(n) || n < 1) throw new Error('maxReplacements 需为不小于 1 的整数')
    cap = Math.min(Math.floor(n), MAX_EXCEL_REPLACEMENTS)
  }
  return { find, replace, matchCase: !!args.matchCase, wholeCell: !!args.wholeCell, cap }
}

/** 返回值拼装（两侧共用，保证 Office 与 WPS 的出参形状一字不差） */
export function buildReplaceResult({
  sheet, address, find, replace, cells,
  replaced, occurrences, skippedFormula, skippedNumeric, truncated, cap
}) {
  const out = {
    sheet,
    address,
    find,
    replace,
    replaced,
    occurrences,
    cells,
    cellsTruncated: replaced > cells.length,
    truncated
  }
  if (skippedFormula) out.skippedFormulaCells = skippedFormula
  if (skippedNumeric) out.skippedNumericCells = skippedNumeric
  const notes = []
  if (truncated) {
    notes.push(`已达单次替换上限（${cap} 格）并提前停下，剩余命中未处理——缩小 rangeAddress 或分批继续`)
  }
  if (skippedFormula) {
    notes.push(`${skippedFormula} 个公式格显示的文本命中但未改动（改了会毁掉公式，公式请用 office_excel_set_formulas 重写）`)
  }
  if (skippedNumeric) {
    notes.push(`${skippedNumeric} 个数值格显示的文本命中但未改动（改了会把数字变成文本，数值请用 office_excel_set_values 写）`)
  }
  if (!replaced && !skippedFormula && !skippedNumeric) notes.push('区域内没有命中')
  if (notes.length) out.note = notes.join('；')
  return out
}
