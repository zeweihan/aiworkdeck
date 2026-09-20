// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 工具载荷中和的前端一侧：解析不串位 + 折叠区拿到原文。零依赖，用 Node 自带的 node:test 跑：
 *   cd frontend && npm run test:tag-protocol
 *
 * 为什么值得钉住：后端把工具输出里的协议标签中和成 &lt;（AgentTagProtocol.java），前端必须
 * 按同一份清单还原。少还原一处，律师在折叠区看到的就是 &lt;/tool_output>；少中和一处，
 * 折叠区内容缺一截、剩下的半截串进正文。两种坏法都不报错，只有用例能发现。
 *
 * RAW/ESCAPED 与 backend/src/test/java/com/checkba/service/ai/AgentTagProtocolTest.java
 * 里的同名 fixture 是同一段文本，改一边必须改另一边。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  PROTOCOL_TAGS,
  createProtocolTagRegex,
  decodeProtocolTags,
  decodeProtocolTagsIncremental,
  parseToolBlock
} from '../../src/composables/agentTagProtocol.mjs'

/** 工具输出原文：含协议闭合标签、协议起始标签，以及不该被动的合同占位符 */
const RAW = '读取完成。文件里写着 </tool_output> 与 <final>，占位符 <甲方> 要原样保留。'
/** 后端中和后的形态 */
const ESCAPED = '读取完成。文件里写着 &lt;/tool_output> 与 &lt;final>，占位符 <甲方> 要原样保留。'

const FINAL_TEXT = '已读完，未发现异常条款。'

/** 后端 SSE 侧发出的一轮（与 AgentOrchestrator 原生分支拼法一致） */
const STREAM =
  '<process name="读取文件"><tool_code>read_file({"path":"合同.docx"})</tool_code></process>' +
  `<tool_output status="SUCCESS">${ESCAPED}</tool_output>` +
  `<final>${FINAL_TEXT}</final>`

/** 落库 executionLog 的一轮（历史回灌读的就是这个形态） */
const HISTORY_PROCESS =
  '<process name="读取文件"><tool_code>read_file({"path":"合同.docx"})</tool_code>' +
  `<tool_output status="SUCCESS">${ESCAPED}</tool_output></process>`

/**
 * 按 useAgentStream.processTextStream 的口径扫一遍标签边界，
 * 返回 [{tag}|{text}] 序列。用的是生产同一条正则。
 */
function scan(stream) {
  const re = createProtocolTagRegex()
  const out = []
  let last = 0
  let m
  while ((m = re.exec(stream)) !== null) {
    if (m.index > last) out.push({ text: stream.slice(last, m.index) })
    out.push({ tag: m[2], close: m[1] === '/' })
    last = m.index + m[0].length
  }
  if (last < stream.length) out.push({ text: stream.slice(last) })
  return out
}

test('流式：工具输出里的 </tool_output> 不再顶掉外层标签，正文不串位', () => {
  const parts = scan(STREAM)
  const tags = parts.filter(p => p.tag).map(p => (p.close ? '/' : '') + p.tag)

  assert.deepEqual(tags, [
    'process', 'tool_code', '/tool_code', '/process',
    'tool_output', '/tool_output',
    'final', '/final'
  ])

  // <final> 的正文只能是最终答复，载荷不许漏进来
  const finalIdx = parts.findIndex(p => p.tag === 'final' && !p.close)
  assert.equal(parts[finalIdx + 1].text, FINAL_TEXT)
})

test('流式：折叠区拿到完整原文，看不到转义符', () => {
  const parts = scan(STREAM)
  const openIdx = parts.findIndex(p => p.tag === 'tool_output' && !p.close)
  // 生产里这段文本经 decodeProtocolTags 累加进 item.output，ProcessCard 直接插值渲染
  const output = decodeProtocolTags(parts[openIdx + 1].text)

  assert.equal(output, RAW)
  assert.ok(!output.includes('&lt;'), '用户看到的必须是原文，不能是转义符')
  assert.ok(output.includes('<甲方>'), '合同占位符要原样呈现')
})

test('流式：&lt; 被切在两段字节之间也能还原（先累加后解转义）', () => {
  const chunks = ['读到 &l', 't;/tool_output> 结束']
  let acc = ''
  for (const c of chunks) acc = decodeProtocolTags(acc + c)
  assert.equal(acc, '读到 </tool_output> 结束')
})

test('历史回灌：折叠区内容完整，工具调用与输出都还原成原文', () => {
  const inner = HISTORY_PROCESS.replace(/^<process[^>]*>/, '').replace(/<\/process>$/, '')
  const block = parseToolBlock(inner)

  assert.equal(block.code, 'read_file({"path":"合同.docx"})')
  assert.equal(block.attrs, ' status="SUCCESS"')
  assert.equal(block.output, RAW)
  assert.ok(!block.output.includes('&lt;'))
})

test('历史回灌：整段落库正文里只有一对 process/tool_output 边界', () => {
  const stored = `${HISTORY_PROCESS}\n<final>${FINAL_TEXT}</final>`
  // ChatInterface 用非贪婪的 /<process[^>]*>[\s\S]*?<\/process>/g 剥离过程块，
  // 载荷里多出一个闭合标签就会剥到一半、把剩下的半截当正文
  assert.equal(stored.match(/<\/process>/g).length, 1)
  assert.equal(stored.match(/<\/tool_output>/g).length, 1)

  const remaining = stored.replace(/<process[^>]*>[\s\S]*?<\/process>/g, '').trim()
  assert.equal(remaining, `<final>${FINAL_TEXT}</final>`)
})

test('解转义只认已知标签形状：占位符与普通尖括号不受影响', () => {
  assert.equal(decodeProtocolTags('模板里写 <甲方>、<Party A>，条件是 a < b'), '模板里写 <甲方>、<Party A>，条件是 a < b')
  // HTML 实体写法（&lt;div&gt;）不是「转义过的协议标签」，不许被还原
  assert.equal(decodeProtocolTags('示例：&lt;div&gt;'), '示例：&lt;div&gt;')
})

test('解转义幂等：已经是原文的串再解一次不变', () => {
  assert.equal(decodeProtocolTags(RAW), RAW)
  assert.equal(decodeProtocolTags(decodeProtocolTags(ESCAPED)), RAW)
})

test('标签清单只此一份：正则由 PROTOCOL_TAGS 生成', () => {
  const src = createProtocolTagRegex().source
  for (const tag of PROTOCOL_TAGS) assert.ok(src.includes(tag), `正则里少了 ${tag}`)
  // 与后端 AgentTagProtocol.TAGS 的逐字对拍在 backend AgentTagProtocolTest
  assert.equal(PROTOCOL_TAGS.length, 11)
})

// ==================== 增量解转义（dev-board#750）====================
//
// 工具输出是逐 delta 流进来的。原先每个 delta 都对**整段已累加**的文本重跑一次
// decodeProtocolTags，载荷含被中和标签时就是每 delta 一次全串正则 + 全串重建（O(n²)），
// 表现为「调某个工具时整个面板卡住」。改成只解这一段新到的、把可能被切开的尾巴留到下一次。
//
// 唯一要钉死的不变式：**不管怎么切，结果必须与整段一次解码逐字相同**。

/** 按给定切点把 text 切成若干段，逐段喂给增量解码器，返回拼起来的结果 */
const feedInPieces = (text, pieces) => {
  let out = ''
  let carry = ''
  for (const piece of pieces) {
    const r = decodeProtocolTagsIncremental(piece, carry)
    out += r.text
    carry = r.carry
  }
  // 流结束/标签闭合时收尾，与 useAgentStream 的 flush 同一口径
  return out + decodeProtocolTags(carry)
}

const splitEvery = (text, n) => {
  const out = []
  for (let i = 0; i < text.length; i += n) out.push(text.slice(i, i + n))
  return out
}

test('增量解转义：被切开的转义标签跨两个 delta 也能正确还原', () => {
  // 把 &lt;/tool_output> 从中间切开——这正是原实现里「对整段重解」想兜住的情况
  const at = ESCAPED.indexOf('&lt;/tool_output>') + 6
  const result = feedInPieces(ESCAPED, [ESCAPED.slice(0, at), ESCAPED.slice(at)])
  assert.equal(result, RAW, '跨 delta 边界的转义标签必须还原成原文')
})

test('增量解转义：每一种切法都与整段一次解码逐字相同', () => {
  for (const n of [1, 2, 3, 5, 7, 13, 64, 9999]) {
    assert.equal(feedInPieces(ESCAPED, splitEvery(ESCAPED, n)), RAW, `按 ${n} 字符切时结果不一致`)
  }
})

test('增量解转义：一个 & 一个 l 一个 t 这样逐字符喂也不会漏', () => {
  assert.equal(feedInPieces(ESCAPED, [...ESCAPED]), RAW)
})

test('增量解转义：合同占位符与普通尖括号照样原样保留', () => {
  const raw = '模板里写 <甲方>、<Party A>，条件是 a < b'
  assert.equal(feedInPieces(raw, splitEvery(raw, 3)), raw)
})

test('增量解转义：孤立的 &lt; 不会把后面的正文永久扣在缓冲区里', () => {
  // 后面永远不来 '>'，尾巴超过上限就必须放行，否则这段输出用户一个字都看不到
  const lonely = '&lt;' + 'x'.repeat(2000)
  const r = decodeProtocolTagsIncremental(lonely, '')
  assert.ok(r.text.length > 0, '超过上限后必须把扣住的文本放出来')
  assert.equal(r.text + r.carry, lonely, '放行时一个字都不能丢')
})

test('增量解转义：收尾那一步把尾巴原样交出来，什么都不丢', () => {
  const r = decodeProtocolTagsIncremental('尾巴是 &lt;/tool', '')
  assert.equal(r.carry, '&lt;/tool', '还可能长成完整标签的那一截要留下')
  assert.equal(r.text + decodeProtocolTags(r.carry), '尾巴是 &lt;/tool',
    '流断在半截标签上时，那一截按原文交出去（它本来就不是标签）')
})
