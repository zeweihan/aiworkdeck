// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * read_for_reference 的纯函数部分（dev-board#717）：别的窗格经云端下发
 * `read_for_reference {locator}`，本窗格按 locator 读出本文档的一块文字回传。
 * Office 面（officeExecutor）与 WPS 面（wps*Handlers）共用这里的解析、切段与截断口径，
 * 两个家族对后端和模型说的必须是同一套话。
 *
 * locator 语义（spec 第 3 节）：
 *   page:N                 文字文档第 N 页
 *   slide:N                演示稿第 N 页
 *   sheet:名称[!A1:D20]    表格的某张工作表（可带区域）
 *   heading:标题文字       文字文档里该标题所辖的段落
 *   空                     整篇（与 get_text / 随消息附带的正文同口径）
 * 宿主不支持的组合一律报明确错误，**绝不静默退回全文**——模型以为自己拿到的是
 * 第 3 页，其实是全文，比报错更糟。
 *
 * 错误文案给模型看（指令性），保持中文，与 office_* 其余命令同腔调。
 */

/** 与后端 ReferenceSourceService.MAX_CHARS / ContextAssemblerService 内联正文同一上限 */
export const MAX_REFERENCE_CHARS = 200_000
/** 截断标注，与后端 ReferenceSourceService.cap 逐字一致 */
export const TRUNCATION_MARK = '\n...(截断)'

const LOCATOR_USAGE = '可用的定位：page:页码、slide:页码、sheet:工作表名[!A1:D20]、heading:标题文字，或留空读全文'

function badLocator(raw) {
  return new Error(`无法识别的定位：${raw}。${LOCATOR_USAGE}`)
}

/** 正整数页码；其余一律非法（page:0、page:1.5、page:三 都不猜） */
function parsePageNumber(body, raw) {
  const s = body.trim()
  if (!/^\d+$/.test(s)) throw badLocator(raw)
  const n = Number(s)
  if (!Number.isSafeInteger(n) || n < 1) throw badLocator(raw)
  return n
}

/**
 * 解析 locator。返回 `{kind:'none'}` / `{kind:'page'|'slide', n}` /
 * `{kind:'sheet', sheet, range}` / `{kind:'heading', text}`；非法格式抛错。
 */
export function parseLocator(str) {
  const raw = str == null ? '' : String(str).trim()
  if (!raw) return { kind: 'none' }
  const colon = raw.indexOf(':')
  if (colon <= 0) throw badLocator(raw)
  const prefix = raw.slice(0, colon).trim().toLowerCase()
  const body = raw.slice(colon + 1)
  if (prefix === 'page' || prefix === 'slide') {
    return { kind: prefix, n: parsePageNumber(body, raw) }
  }
  if (prefix === 'sheet') {
    const spec = body.trim()
    // 区域地址里不会有「!」，工作表名里可能有——按最后一个切
    const bang = spec.lastIndexOf('!')
    let sheet = bang === -1 ? spec : spec.slice(0, bang)
    const range = bang === -1 ? '' : spec.slice(bang + 1).trim()
    sheet = sheet.trim()
    // 'Sheet 名'!A1 这种引号包裹写法（与 Excel 公式里的跨表引用同形），'' 是转义的单引号
    if (sheet.length >= 2 && sheet.startsWith("'") && sheet.endsWith("'")) {
      sheet = sheet.slice(1, -1).replace(/''/g, "'")
    }
    if (!sheet) throw badLocator(raw)
    return { kind: 'sheet', sheet, range }
  }
  if (prefix === 'heading') {
    const text = body.trim()
    if (!text) throw badLocator(raw)
    return { kind: 'heading', text }
  }
  throw badLocator(raw)
}

/** 标题比对口径：去掉全部空白与控制字符（WPS 段落文字带 \r、表格带 \x07） */
function headingKey(s) {
  return String(s == null ? '' : s).replace(/[\s\x00-\x1f]+/g, '')
}

const HEADING_LIST_MAX = 20

function headingNotFound(paragraphs, headingText) {
  const headings = paragraphs
    .filter((p) => p && p.isHeading)
    .map((p) => String(p.text || '').replace(/[\r\x07]+/g, '').trim())
    .filter(Boolean)
  if (!headings.length) {
    return new Error(`没有找到标题：${headingText}。该文档没有设置标题样式（大纲级别）的段落，`
      + '无法按标题定位，请不带定位读取全文。')
  }
  const shown = headings.slice(0, HEADING_LIST_MAX)
  return new Error(`没有找到标题：${headingText}。文档现有标题：${shown.join('、')}`
    + (headings.length > shown.length ? ` 等 ${headings.length} 个` : '')
    + '。请用其中之一重试。')
}

/**
 * 找标题所辖的段落区间 `[start, end)`：从匹配的标题段起，到下一个同级或更高级
 * （level 更小或相等）的标题之前；后面没有这样的标题就到文末。
 *
 * paragraphs 形如 `[{text, isHeading, level}]`。只有 isHeading 的段落参与匹配——正文里
 * 「依照第三条约定」不能被当成「第三条」。匹配口径：去空白后**完全相同优先**，
 * 其次「包含」（要「第一条」时不能先撞上「第一条之一」）。
 * 非标题段落的 text 不会被读，WPS 面据此只取标题段文字（同步桥省调用）。
 */
export function findHeadingSpan(paragraphs, headingText) {
  const key = headingKey(headingText)
  const list = Array.isArray(paragraphs) ? paragraphs : []
  let start = -1
  if (key) {
    start = list.findIndex((p) => p && p.isHeading && headingKey(p.text) === key)
    if (start === -1) start = list.findIndex((p) => p && p.isHeading && headingKey(p.text).includes(key))
  }
  if (start === -1) throw headingNotFound(list, headingText)
  const level = Number(list[start].level)
  let end = list.length
  for (let i = start + 1; i < list.length; i++) {
    const p = list[i]
    if (p && p.isHeading && Number(p.level) <= level) {
      end = i
      break
    }
  }
  return { start, end }
}

/** 标题所辖段落的文字（各段以 \n 连接） */
export function sliceByHeading(paragraphs, headingText) {
  const { start, end } = findHeadingSpan(paragraphs, headingText)
  return paragraphs.slice(start, end).map((p) => String(p.text == null ? '' : p.text)).join('\n')
}

/** 截断到上限并标注；返回值即 read_for_reference 的结果（后端只取 text） */
export function capReferenceText(text) {
  const s = text == null ? '' : String(text)
  if (s.length > MAX_REFERENCE_CHARS) {
    return { text: s.slice(0, MAX_REFERENCE_CHARS) + TRUNCATION_MARK, truncated: true, totalChars: s.length }
  }
  return { text: s, truncated: false, totalChars: s.length }
}

/** 页/幻灯片没有文字时回一句说明：空串会被当成「整个文件没有文字」 */
export function blankPageText(n) {
  return `（第 ${n} 页没有可读取的文字）`
}

const UNSUPPORTED_HINTS = {
  word: ['Word 文档', 'page:页码、heading:标题文字，或留空读全文'],
  excel: ['表格', 'sheet:工作表名[!A1:D20]，或留空读活动工作表'],
  powerpoint: ['演示稿', 'slide:页码，或留空读全部幻灯片']
}

/** 当前宿主不支持该 locator 类型时的报错（两个家族同一份文案） */
export function unsupportedLocatorError(host, kind) {
  const [label, usage] = UNSUPPORTED_HINTS[host] || ['当前文档', '留空读全文']
  return new Error(`${label}不支持该定位（${kind}:）。可用的定位：${usage}`)
}
