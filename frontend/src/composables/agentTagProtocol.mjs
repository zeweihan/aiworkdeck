// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 面板伪 XML 协议的标签清单与解转义（与后端 AgentTagProtocol.java 成对）。
 *
 * 后端把工具参数与工具输出原样拼进 <tool_code>…</tool_code> / <tool_output status=…>…</tool_output>，
 * 载荷里若含协议标签（读一份讲协议的文档、模型复述自己的输出、子任务结果里带 <final>），
 * 下面这条 tagRegex 会在载荷中间错位：折叠区内容被截断，剩下的半截漏进正文。
 * 后端因此把「已知标签形状」的起始 < 换成 &lt;，本模块负责在内容落到界面前还原回去。
 *
 * 收窄到「已知标签形状」而不是所有尖括号，是因为合同正文里的 <甲方>、<Party A>
 * 这类占位符必须原样呈现——全量转义会让律师在折叠区看到 &lt;甲方&gt;。
 *
 * 清单只此一份：流式解析（useAgentStream）与历史回灌（ChatInterface）都从这里取，
 * 后端那份由 AgentTagProtocolTest 对拍，两边不许各写一份。
 *
 * .mjs 后缀是为了让 frontend/tests/tag-protocol 能用 node --test 直接 import
 * （frontend/package.json 没有 "type": "module"，.js 会被 Node 当 CommonJS）。
 */

/** 协议标签清单：改这里必须同步改 backend AgentTagProtocol.TAGS */
export const PROTOCOL_TAGS = [
  'thinking', 'title', 'process', 'step', 'tool_code', 'tool_output',
  'walkthrough', 'final', 'question', 'option', 'artifact'
]

const TAG_BODY = `(\\/?)(${PROTOCOL_TAGS.join('|')})(\\s+[^>]*)?`

/** 流式解析用的标签正则。每次新建：调用方会改 lastIndex，共用一个实例会串状态。 */
export const createProtocolTagRegex = () => new RegExp(`<${TAG_BODY}>`, 'g')

const ESCAPED_TAG_RE = new RegExp(`&lt;${TAG_BODY}>`, 'g')

/**
 * 还原后端中和过的协议标签，用户看到的是原文而不是 &lt;。
 * 幂等：没被转义过的文本原样返回，重复调用不会二次还原。
 */
export const decodeProtocolTags = (text) => {
  if (!text) return ''
  const s = String(text)
  return s.indexOf('&lt;') < 0
    ? s
    : s.replace(ESCAPED_TAG_RE, (_m, slash, name, attrs) => `<${slash}${name}${attrs || ''}>`)
}

/**
 * 一个「还可能长成完整转义标签」的尾巴最多留多长。
 *
 * 转义标签的形状是 `&lt;` + 可选 `/` + 标签名（最长 walkthrough = 11）+ 可选属性 + `>`，
 * 属性里不许有 `>`（见 TAG_BODY 的 [^>]*），所以正常情况下几十个字符就封顶。
 * 设这个上限是为了兜住「正文里有一个孤零零的 &lt;、后面永远不来 >」——不封顶的话
 * 那之后的整段输出都会被扣在缓冲区里显示不出来。
 */
const MAX_CARRY = 1024

/**
 * 增量解转义（dev-board#750）。
 *
 * 为什么需要：工具输出是逐 delta 流进来的，而原先每个 delta 都对**整段已累加**的文本
 * 重跑一次 decodeProtocolTags —— 载荷里含被中和标签时就是每 delta 一次全串正则替换 +
 * 全串重建，O(n²)，表现为「调用某个工具时整个面板卡住」。
 *
 * 做法：只解码这一段新到的文本，把「结尾那截还可能长成完整转义标签的」留到下一次。
 * 判据与 processTextStream 留半截标签同源：最后一个 `&lt;` 之后若还没出现 `>`，
 * 它就可能是被切开的标签，先不动。
 *
 * @param {string} chunk 新到的这一段
 * @param {string} carry 上一次留下的尾巴
 * @returns {{text: string, carry: string}} text 可直接追加到已显示内容后面；carry 传给下一次
 */
export const decodeProtocolTagsIncremental = (chunk, carry = '') => {
  const pending = (carry || '') + (chunk == null ? '' : String(chunk))
  if (!pending) return { text: '', carry: '' }
  const cut = unresolvedFrom(pending)
  return { text: decodeProtocolTags(pending.slice(0, cut)), carry: pending.slice(cut) }
}

const ESC = '&lt;'

/**
 * 从哪个下标开始「还可能长成完整的转义标签」，之前的部分可以定稿。
 *
 * 两件事都要考虑，漏一件就会漏还原：
 *   ① `&lt;/tool_out` —— 标签名/属性被切断；
 *   ② `&l` / `&lt` —— **连 `&lt;` 这四个字符本身都被切断了**。②（逐字符喂的情形）最容易漏：
 *      只找完整的 `&lt;` 时，`&l` 会被当普通文本发出去，下一段的 `t;/tool_output>` 再也拼不回来。
 *
 * 只有最后一个 `>` 之后的那一段还可能在生长：转义标签的属性里不许有 `>`
 * （见 TAG_BODY 的 `[^>]*`），所以一旦出现 `>`，它之前的候选要么已经匹配、要么永远不会匹配。
 */
const unresolvedFrom = (pending) => {
  const lastGt = pending.lastIndexOf('>')
  const from = lastGt + 1
  for (let i = from; i < pending.length; i++) {
    if (pending[i] !== '&') continue
    const rest = pending.slice(i)
    // rest 要么是 `&lt;` 的真前缀（②），要么以 `&lt;` 开头且还没等到 `>`（①，本段无 `>`）
    if (ESC.startsWith(rest) || rest.startsWith(ESC)) {
      // 尾巴长到不可能再是标签了就不再等：孤立的 `&lt;` 后面永远不来 `>` 时，
      // 不封顶会把之后的整段输出永久扣在缓冲区里，用户一个字都看不到
      return pending.length - i > MAX_CARRY ? pending.length : i
    }
  }
  return pending.length
}

/**
 * 历史回灌：从一个 <process>…</process> 的内容里取出工具调用与输出（均已解转义）。
 * 没有 <tool_code> 就不是工具条目，返回 null。
 */
export const parseToolBlock = (processContent) => {
  if (!processContent) return null
  const codeMatch = processContent.match(/<tool_code>([\s\S]*?)<\/tool_code>/)
  if (!codeMatch) return null
  const outputMatch = processContent.match(/<tool_output([^>]*)>([\s\S]*?)<\/tool_output>/)
  return {
    code: decodeProtocolTags(codeMatch[1]).trim(),
    // 属性串（status="SUCCESS" 等）由调用方解析状态
    attrs: outputMatch ? outputMatch[1] : '',
    output: outputMatch ? decodeProtocolTags(outputMatch[2]).trim() : ''
  }
}
