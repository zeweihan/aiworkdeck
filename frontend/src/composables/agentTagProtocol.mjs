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
