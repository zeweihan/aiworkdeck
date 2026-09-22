// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * get_text 的分页口径单源（dev-board#806，审计 B-05）。
 *
 * Office 面与 WPS 文字面各有自己的取正文实现，但**怎么切、上限多少、返回什么**必须同一份：
 * 后端只有一条 office_get_text 工具描述，两个家族不能对模型说两套话。
 *
 * 为什么要分页：
 * 1. 改造前 get_text 一次回吐至多 20 万字符且没有任何参数——一份 30 万字符的合同，
 *    后 10 万字在插件会话里**根本读不到**（Word 面没有按段落取数的工具，只能用
 *    office_search 盲猜关键词）。
 * 2. 20 万字符 ≈ 十几万 token 的单条工具结果，正是 ToolFileGuard 那段长注释描述的
 *    「下一次 generate 必然被服务商以上下文超限 400 挡回、且落在 compactor 尾区剪不掉」
 *    的场景。LOWA 侧为此把读取类工具压到 80k（ToolFileGuard.MAX_TOOL_TEXT_CHARS），
 *    插件侧却是它的 2.5 倍，而那道闸一处都没覆盖 office_*。
 *
 * <b>刻意不复用 officeExecutor 的 MAX_TEXT_CHARS（20 万）</b>：那个常量同时服务
 * 「随消息附带的内联正文上限」（与后端 ContextAssemblerService.MAX_INLINE_CONTENT_CHARS
 * 对齐）——动它会连带改掉内联正文口径。get_text 要自己的常量。
 */

/** 不传 maxChars 时一次给多少字符。留在 80k 硬上限之内，够读一份普通合同的大半。 */
export const GET_TEXT_DEFAULT_CHARS = 50_000

/**
 * 一次最多给多少字符。与后端 ToolFileGuard.MAX_TOOL_TEXT_CHARS 同值——
 * 那是「单条工具结果交给模型」的既有口径，插件链路此前完全不受它约束。
 */
export const GET_TEXT_MAX_CHARS = 80_000

/**
 * 按 startChar / maxChars 切一段正文，并告诉模型还有没有、下一段从哪开始。
 *
 * @param {string} text 完整正文（宿主原文，不做任何归一）
 * @param {{startChar?: number|string, maxChars?: number|string}} [args]
 * @returns {{text: string, startChar: number, returned: number, totalChars: number,
 *            truncated: boolean, nextStart?: number}}
 *          nextStart 只在「还有后文」时出现——有它就是「请接着读」，没有就是「读完了」。
 */
export function pageText(text, args) {
  const s = text == null ? '' : String(text)
  const total = s.length
  const a = args || {}

  let start = Math.floor(Number(a.startChar))
  if (!Number.isFinite(start) || start < 0) start = 0
  if (start > total) start = total

  let limit = Math.floor(Number(a.maxChars))
  if (!Number.isFinite(limit) || limit <= 0) limit = GET_TEXT_DEFAULT_CHARS
  if (limit > GET_TEXT_MAX_CHARS) limit = GET_TEXT_MAX_CHARS

  const end = Math.min(total, start + limit)
  const result = {
    text: s.slice(start, end),
    startChar: start,
    returned: end - start,
    totalChars: total,
    // truncated 保留旧字段名与旧语义（「还没给完」），老日志与老回放不用改口径
    truncated: end < total
  }
  if (end < total) result.nextStart = end
  return result
}
