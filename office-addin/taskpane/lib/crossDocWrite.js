// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 跨文档写入的痕迹（dev-board#717）：别的窗格的 AI 经云端下发、要改**本文档**的命令
 * （SSE client_action 带 origin），在本窗格里怎么执行、怎么留痕、怎么撤销。
 *
 * 维护者拍板：双向可写，但修订记录与痕迹必须在目标文档自己的窗格里明示。于是：
 *   - Word（Office / WPS 文字）：强制带修订执行（__forceTracking），宿主标不了修订就**拒绝**，
 *     无痕迹的跨文档写入不允许发生；执行本身就标不出修订的命令（删表格行列、接受/拒绝修订、
 *     改文档属性、批注三件）一律拒绝。撤销走 Word 自己的「拒绝修订」，条目不可在这里撤销。
 *   - Excel / PPT：没有修订机制。执行前取受影响区域原值、执行后读回改后值，一并记入修订记录；
 *     撤销前比对当前值与改后值，用户已再改过就报冲突、不覆盖。取不到改前值（格式/结构类命令、
 *     区域过大）照常执行，条目如实标「无法记录改前值」。
 *   - 只读命令直接执行，不留条目。
 *
 * 宿主相关的取值/写回在两个家族的执行器里（经 hostBridge 分派），本模块只管编排与比对，
 * 依赖都可注入，便于在 node 里测。
 */
import { isReadOnlyCommand } from './docSnapshot.js'
import {
  executeCommand, commandDisplayName, crossDocTrackingOk,
  captureCrossDocState, readCrossDocState, writeCrossDocState,
  locateInDocument, locateCrossDocTarget
} from './hostBridge.js'
import { t } from './i18n.js'

/**
 * 改前值的体积上限：超了就不记（条目标不可撤销）。修订记录按文档存 localStorage、上限 200 条，
 * 大快照会很快撑满配额（revisionLog 写满时会丢较早条目的快照，这里先从源头挡住巨型区域）。
 */
export const UNDO_LIMITS = Object.freeze({ maxCells: 2000, maxChars: 20000 })

/**
 * Word 面执行时本身就标不出修订的写入命令：表格删行删列走 API 直删（开着修订也不留痕，
 * WPS 侧还会在执行期临时关修订）、接受/拒绝修订会抹掉已有痕迹、文档属性没有修订概念、
 * 批注三件（新增/回复/标记已处理）在两个家族的执行器里都不经 withTracking——批注本来
 * 就不是修订，开着修订也不会给它留痕，__forceTracking 传进去只会被原样丢掉。
 *
 * 判据是「这条命令执行完，文档里有没有一处可被拒绝的修订」；答案为否就必须列在这里。
 * 漏一条的后果不只是少一道痕迹：runCrossDocWrite 会照样记下一条 undoable:false 且没有
 * noBefore 的条目，面板据此显示「已标为修订，撤销请在修订中拒绝」——文档里根本没有那处
 * 修订可拒绝，用户按提示去找只会一无所获。跨文档一律拒绝，请用户在目标文档自己的窗格里做。
 */
export const WORD_UNTRACKABLE_COMMANDS = new Set([
  'table_delete_row', 'table_delete_col', 'accept_revision', 'reject_revision', 'set_document_properties',
  'add_comment', 'reply_comment', 'resolve_comment'
])

/** 不在只读名单里、但不改文档内容的命令：不留条目、不弹横幅 */
const NO_TRACE_COMMANDS = new Set(['excel_select_range'])

/** 横幅合并窗口：同一来源在这段时间内连续改，合并成「改了 N 处」 */
export const BANNER_MERGE_WINDOW_MS = 10000

const SUMMARY_TEXT_MAX = 30
const LOCATE_TEXT_MAX = 255

function clip(value, max = SUMMARY_TEXT_MAX) {
  const chars = Array.from(String(value == null ? '' : value).replace(/\s+/g, ' ').trim())
  return chars.length > max ? chars.slice(0, max).join('') + '…' : chars.join('')
}

function rangeLabel(args) {
  const range = String(args.rangeAddress || '')
  return args.sheetName ? `${args.sheetName}!${range}` : range
}

/** 摘要的字典 key 与参数；没有专门摘要的命令回 null（回退为命令显示名） */
function summaryParts(command, args) {
  const a = args || {}
  switch (command) {
    case 'replace_text':
    case 'ppt_replace_text':
      return { key: 'revSumReplace', params: { from: clip(a.searchText), to: clip(a.replaceText) } }
    case 'replace_batch':
      return { key: 'revSumReplaceBatch', params: { count: Array.isArray(a.items) ? a.items.length : 0 } }
    case 'insert_text':
      return { key: 'revSumInsert', params: { text: clip(a.text) } }
    case 'excel_set_values':
    case 'excel_set_formulas':
      return { key: 'revSumExcelWrite', params: { range: rangeLabel(a) } }
    case 'excel_sort_range':
      return { key: 'revSumExcelSort', params: { range: rangeLabel(a) } }
    case 'ppt_table_set_cell':
      return {
        key: 'revSumPptCell',
        params: { slide: Number(a.slideNumber), row: Number(a.row) + 1, col: Number(a.col) + 1, text: clip(a.text) }
      }
    default:
      return null
  }
}

/** 人话摘要（按当前界面语言） */
export function summarize(command, args) {
  const parts = summaryParts(command, args)
  return parts ? t(parts.key, parts.params) : commandDisplayName(command)
}

/**
 * 条目摘要的渲染入口：条目里存了字典 key 就按当前语言现查（切语言后跟着换，dev-board#713
 * 同一条纪律），没有 key 才用记录时的 summary 文字。
 */
export function entrySummary(entry) {
  if (!entry) return ''
  if (entry.summaryKey) return t(entry.summaryKey, entry.summaryParams || {})
  return entry.summary || commandDisplayName(entry.command)
}

/** Word 条目的定位文字（修订记录面板「定位」用）：改后的那段，删除时退回原文 */
function locateTextOf(command, args) {
  const a = args || {}
  let text = ''
  if (command === 'replace_text') text = a.replaceText || a.searchText
  else if (command === 'replace_batch') {
    const first = Array.isArray(a.items) && a.items[0]
    text = first ? (first.replaceText || first.searchText) : ''
  } else if (command === 'insert_text') text = a.text
  else text = a.anchorText || a.text || ''
  return String(text || '').slice(0, LOCATE_TEXT_MAX)
}

/** 'Sheet1!A1' / "'带 空格'!A1" 拆成表名与地址（与 officeExecutor.splitSheetQualifiedAddress 同口径） */
function splitSheetQualified(raw) {
  const s = String(raw || '').trim()
  const at = s.lastIndexOf('!')
  if (at < 0) return { sheetName: '', address: s }
  let name = s.slice(0, at)
  if (name.startsWith("'") && name.endsWith("'")) name = name.slice(1, -1).replace(/''/g, "'")
  return { sheetName: name, address: s.slice(at + 1) }
}

/**
 * 取不到改前值的 Excel/PPT 条目（格式、结构类命令）按参数推出定位目标，供修订记录面板「定位」。
 * Excel 必须知道是哪张表：参数里没表名时写入落在当时的活动表上，事后已无从得知，宁可不给定位，
 * 也不去选中「现在的活动表」上的同一地址（那很可能是另一张表）。删掉的幻灯片没有地方可去。
 */
function locateTargetFromArgs(command, args) {
  const a = args || {}
  if (command.startsWith('excel_')) {
    const q = splitSheetQualified(a.rangeAddress || a.cellAddress)
    const sheetName = q.sheetName || String(a.sheetName || '')
    if (!sheetName || !q.address) return null
    return { kind: 'excel', sheetName, address: q.address }
  }
  if (command.startsWith('ppt_') && command !== 'ppt_delete_slide') {
    const n = Math.floor(Number(a.slideNumber))
    return n >= 1 ? { kind: 'pptSlide', slideNumber: n } : null
  }
  return null
}

function draftEntry(command, args, origin, extra) {
  const o = origin || {}
  const parts = summaryParts(command, args)
  const entry = {
    originDocName: String(o.docName || ''),
    originConversationId: String(o.conversationId || ''),
    command,
    summary: parts ? t(parts.key, parts.params) : commandDisplayName(command)
  }
  if (parts) {
    entry.summaryKey = parts.key
    entry.summaryParams = parts.params
  }
  return { ...entry, ...extra }
}

async function safeExec(exec, command, args) {
  try {
    const r = await exec(command, args)
    if (r && typeof r === 'object') return r
    return { ok: false, error: '插件执行失败（未返回结果）' }
  } catch (e) {
    return { ok: false, error: (e && e.message) || String(e) }
  }
}

function noTrackingError(family) {
  return family === 'wps'
    ? '本机 WPS 文字无法标记修订，已拒绝跨文档修改'
    : '本机 Word 版本无法标记修订，已拒绝跨文档修改'
}

function untrackableError(command) {
  return `该操作（${command}）在 Word/WPS 文字里无法标记为修订，跨文档执行会留下没有痕迹的修改，已拒绝。`
    + '请让用户在目标文档自己的 AI WorkDeck 窗格里完成这一步。'
}

/**
 * 执行一条跨文档命令并给出修订记录条目草稿。永不 throw。
 *
 * @returns {Promise<{result: {ok:boolean, data?:any, error?:string}, entry: object|null}>}
 *   result 原样回传给发起方；entry 为 null 表示不留条目（只读命令、执行失败、被拒绝）。
 *   entry 的 before 形如 {target, ...改前值}、after 为执行后按 target 读回的当前值，
 *   撤销时二者配合 undoEntry 使用。
 */
export async function runCrossDocWrite({
  command, args, origin, host, family,
  exec = executeCommand,
  capture = captureCrossDocState,
  readCurrent = readCrossDocState,
  trackingOk = crossDocTrackingOk
}) {
  const cmd = String(command || '')
  const a = args || {}

  if (isReadOnlyCommand(cmd) || NO_TRACE_COMMANDS.has(cmd)) {
    return { result: await safeExec(exec, cmd, a), entry: null }
  }

  if (host === 'word') {
    if (WORD_UNTRACKABLE_COMMANDS.has(cmd)) {
      return { result: { ok: false, error: untrackableError(cmd) }, entry: null }
    }
    let supported = false
    try { supported = !!trackingOk() } catch (e) { supported = false }
    if (!supported) return { result: { ok: false, error: noTrackingError(family) }, entry: null }
    const result = await safeExec(exec, cmd, { ...a, __forceTracking: true })
    if (!result.ok) return { result, entry: null }
    return { result, entry: draftEntry(cmd, a, origin, { undoable: false, locateText: locateTextOf(cmd, a) }) }
  }

  // Excel / PPT（以及取不到宿主的情形：照常执行，能记就记）
  let snap = null
  try { snap = await capture(cmd, a, UNDO_LIMITS) } catch (e) { snap = null }
  if (!snap || !snap.target || !snap.before) snap = null
  const result = await safeExec(exec, cmd, a)
  if (!result.ok) return { result, entry: null }

  // 改后值必须读得回来才标可撤销：没有它就没法判断用户是否又改过，撤销可能覆盖用户的修改
  let after = null
  if (snap) {
    try { after = await readCurrent(snap.target) } catch (e) { after = null }
  }
  // 定位目标单独存一份（locateTarget）：存储写满时 revisionLog 会丢掉较早条目的改前值，
  // 目标在改前值里的那份跟着没了，「定位」不能因此失效
  if (snap && after) {
    return {
      result,
      entry: draftEntry(cmd, a, origin, {
        undoable: true, before: { ...snap.before, target: snap.target }, after, locateTarget: snap.target
      })
    }
  }
  const extra = { undoable: false, noBefore: true }
  const locateTarget = snap ? snap.target : locateTargetFromArgs(cmd, a)
  if (locateTarget) extra.locateTarget = locateTarget
  return { result, entry: draftEntry(cmd, a, origin, extra) }
}

/** 快照比对：键序无关，忽略 target 键（它是定位信息，不是值） */
function canonical(v) {
  if (Array.isArray(v)) return '[' + v.map(canonical).join(',') + ']'
  if (v && typeof v === 'object') {
    return '{' + Object.keys(v).filter((k) => k !== 'target').sort()
      .map((k) => JSON.stringify(k) + ':' + canonical(v[k])).join(',') + '}'
  }
  return JSON.stringify(v === undefined ? null : v)
}

export function sameState(a, b) {
  return canonical(a) === canonical(b)
}

/**
 * 撤销一条跨文档写入：当前值仍等于改后值才写回改前值；已被再改过（或目标已不存在）
 * 返回 {ok:false, conflict:true}，一格都不动。
 *
 * @returns {Promise<{ok:true} | {ok:false, conflict?:true, error?:string}>}
 */
export async function undoEntry(entry, { readCurrent = readCrossDocState, writeBack = writeCrossDocState } = {}) {
  if (!entry || !entry.undoable || !entry.before) return { ok: false, error: 'not_undoable' }
  if (entry.undone) return { ok: false, error: 'already_undone' }
  let current = null
  try { current = await readCurrent(entry.before.target) } catch (e) { current = null }
  if (!current || !sameState(current, entry.after)) return { ok: false, conflict: true }
  try {
    await writeBack(entry.before)
  } catch (e) {
    return { ok: false, error: (e && e.message) || String(e) }
  }
  return { ok: true }
}

/**
 * 横幅计数：同一来源在 BANNER_MERGE_WINDOW_MS 内（按最近一次更新滑动）连续改，合并成
 * 「改了 N 处」；换了来源或过了窗口就重新计。返回新的横幅状态 {originDocName, count, at}。
 */
export function mergeCrossDocBanner(prev, originDocName, now, windowMs = BANNER_MERGE_WINDOW_MS) {
  const name = String(originDocName || '')
  if (prev && prev.originDocName === name && now - prev.at <= windowMs) {
    return { originDocName: name, count: prev.count + 1, at: now }
  }
  return { originDocName: name, count: 1, at: now }
}

/* ==================== 修订记录面板（Task 13） ==================== */

/** 条目的位置目标：新条目单独存了 locateTarget，本任务之前记下的条目只有改前值里那份 */
function entryLocateTarget(entry) {
  if (!entry) return null
  return entry.locateTarget || (entry.before && entry.before.target) || null
}

/** 这一条能不能「定位」：Word 条目靠文字，Excel/PPT 条目靠位置目标 */
export function canLocate(entry) {
  return Boolean(entry && (entry.locateText || entryLocateTarget(entry)))
}

/**
 * 在本文档里定位到这条修改：Word 选中改后的那段文字，Excel 激活目标表并选中区域，
 * PPT 跳到目标页。永不 throw，一律回 {found}——找不到（文字已被再改、表或页已被删）
 * 由面板给一句轻提示，不弹错误。
 */
export async function locateEntry(entry, { locateText = locateInDocument, locateTarget = locateCrossDocTarget } = {}) {
  if (!entry) return { found: false }
  try {
    if (entry.locateText) {
      const r = await locateText(entry.locateText)
      return { found: Boolean(r && r.found) }
    }
    const target = entryLocateTarget(entry)
    if (target) {
      const r = await locateTarget(target)
      return { found: Boolean(r && r.found) }
    }
  } catch (e) { /* 宿主定位失败按找不到处理 */ }
  return { found: false }
}

/**
 * 面板上这一条的撤销状态：
 *   'undoable'  有改前值，可一键撤销（Excel / PPT）
 *   'undone'    已撤销
 *   'noBefore'  取不到改前值（格式/结构类命令、区域过大、存储写满时被丢弃），撤不了
 *   'reject'    Word 条目：修改已作为修订标在文档里，撤销请在修订中拒绝
 * 标了可撤销却没有改前值的坏数据按 noBefore 处理——不给一个按了必然失败的按钮。
 */
export function entryUndoState(entry) {
  const e = entry || {}
  if (e.undone) return 'undone'
  if (e.undoable && e.before) return 'undoable'
  if (e.undoable || e.noBefore || e.snapshotDropped) return 'noBefore'
  return 'reject'
}

/**
 * 面板「撤销」按钮：撤销成功才回调标记已撤销（revisionLog.markUndone，会落盘——刷新后
 * 不能再显示成可撤销）。永不 throw。
 * @returns {Promise<{status: 'undone'|'conflict'|'failed', error?: string}>}
 */
export async function undoRevisionEntry(entry, { undo = undoEntry, onUndone } = {}) {
  let r
  try {
    r = await undo(entry)
  } catch (e) {
    return { status: 'failed', error: (e && e.message) || String(e) }
  }
  if (r && r.ok) {
    if (onUndone && entry) {
      try { onUndone(entry.id) } catch (e) { /* 标记失败不影响已经完成的撤销 */ }
    }
    return { status: 'undone' }
  }
  if (r && r.conflict) return { status: 'conflict' }
  return { status: 'failed', error: (r && r.error) || '' }
}
