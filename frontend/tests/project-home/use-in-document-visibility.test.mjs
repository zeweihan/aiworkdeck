// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#728「AI 回复后的『用到文档』按钮改为按需展示」。
//
// 原来的判据只有 `bubble.content && !bubble.isStreaming`，于是每一条助手回复下面都挂着
// 插入/替换/导出——包括「好的，已改好」这类一句话回执，以及 AI 刚刚自己写进文档的那一轮。
//
// 这里钉三件事：
// ① shouldShowUseInDocument 的四条判据（纯函数，不经 Vue）；
// ② bubble_end 的 documentEdited/status 真的落到了气泡上（走真实 useAgentStream 源码）；
// ③ 前端历史回灌那份工具前缀表与后端 ClientCapabilityService 逐字一致——
//    两边各写一份的表现是「刷新前不显示、刷新后全冒出来」，而且不报错。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, reactive, nextTick } from 'vue'
import {
  USE_IN_DOCUMENT_MIN_CHARS,
  documentEditedFromProcesses,
  hasDocumentStructure,
  isDocumentWritingTool,
  shouldShowUseInDocument,
} from '../../src/utils/useInDocumentVisibility.js'
import { createProtocolTagRegex, decodeProtocolTags, decodeProtocolTagsIncremental } from '../../src/composables/agentTagProtocol.mjs'
import { captureChatTimeline } from '../../src/components/AgentMessage/chatTimeline.mjs'
import { nextBubbleId } from '../../src/composables/bubbleId.js'
import {
  applyInboxReceipt,
  applyInboxSnapshot,
  applyInputApplied,
  createInboxState,
  markInboxEvent,
  removeInboxItem,
  replaceInboxItem,
} from '../../src/composables/agentInboxState.mjs'

// 一段够长、也够格放进文书的正文（单段、无列表无标题，只靠长度过门槛）
const LONG_ANSWER = '甲方应于本协议签署之日起十个工作日内，将标的股权对应的全部权利凭证'
  + '交付乙方，并配合办理工商变更登记手续；逾期交付的，每逾期一日按转让价款的万分之五'
  + '向乙方支付违约金，逾期超过三十日的，乙方有权解除本协议并要求甲方赔偿损失。'
// 一句回执
const SHORT_ACK = '好的，第三条已经改好了。'

const bubbleOf = (patch = {}) => ({ content: LONG_ANSWER, isStreaming: false, ...patch })

test('夹具前提：LONG_ANSWER 单靠长度就能过门槛，SHORT_ACK 过不了', () => {
  assert.ok(LONG_ANSWER.length >= USE_IN_DOCUMENT_MIN_CHARS)
  assert.equal(hasDocumentStructure(LONG_ANSWER), false, '它必须是靠长度过关，不是靠结构')
  assert.ok(SHORT_ACK.length < USE_IN_DOCUMENT_MIN_CHARS)
})

test('正文够长、流已结束：照常显示（这是唯一该出按钮的情形，先钉住它）', () => {
  assert.equal(shouldShowUseInDocument(bubbleOf()), true)
})

test('流还没结束 / 没有正文：不显示（原有判据不变）', () => {
  assert.equal(shouldShowUseInDocument(bubbleOf({ isStreaming: true })), false)
  assert.equal(shouldShowUseInDocument(bubbleOf({ content: '' })), false)
  assert.equal(shouldShowUseInDocument(null), false)
})

test('本轮已经改过文档：不显示——AI 写进去的内容不该请用户再插一遍', () => {
  assert.equal(shouldShowUseInDocument(bubbleOf({ documentEdited: true })), false)
})

test('反问态：不显示——正文是个问句，插进合同里毫无意义', () => {
  assert.equal(shouldShowUseInDocument(bubbleOf({ question: { text: '受让方是自然人还是公司？' } })), false)
  assert.equal(shouldShowUseInDocument(bubbleOf({ status: 'awaiting_input' })), false)
  // 其它终态不受影响：暂停/待审批那一轮的正文照样可能是要落进文书的产出
  assert.equal(shouldShowUseInDocument(bubbleOf({ status: 'paused' })), true)
  assert.equal(shouldShowUseInDocument(bubbleOf({ status: 'awaiting_approval' })), true)
})

test('一句回执：不显示', () => {
  assert.ok(SHORT_ACK.length < USE_IN_DOCUMENT_MIN_CHARS, '这条用例的前提是它确实短')
  assert.equal(shouldShowUseInDocument(bubbleOf({ content: SHORT_ACK })), false)
})

test('短但有列表/标题结构：仍然显示——三条要点正是要往文档里放的东西', () => {
  const cases = {
    '无序列表': '- 补充保密义务\n- 明确违约金上限\n- 增加争议解决条款',
    '有序列表': '1. 核对主体\n2. 核对金额\n3. 用印',
    '标题': '## 第三条 违约责任\n逾期即计违约金。',
  }
  for (const [label, content] of Object.entries(cases)) {
    assert.ok(content.length < USE_IN_DOCUMENT_MIN_CHARS, `${label} 这条用例的前提是它确实短`)
    assert.equal(shouldShowUseInDocument(bubbleOf({ content })), true, label)
  }
})

test('「多段」不算结构信号：解析器会在工具前后补空行，按它放行等于架空长度门槛', () => {
  // 真实形态（tests/chat-presentation-ui 实测）：调工具前随口说的那句 + <final> 正文，
  // 中间由解析器补了空行。17 个字符的一句引子加一句结论不该长出「用到文档」。
  const streamed = '先读原文。\n\n建议明确验收起算日。'
  assert.equal(hasDocumentStructure(streamed), false)
  assert.equal(shouldShowUseInDocument(bubbleOf({ content: streamed })), false)
})

test('结构判定读的是原始 Markdown：纯文本化会先把 # 和 - 剥掉，判据放在那之后等于永远为假', () => {
  assert.equal(hasDocumentStructure('## 标题\n正文'), true)
  assert.equal(hasDocumentStructure('• 已经被纯文本化的项目符号'), false)
  assert.equal(hasDocumentStructure('单独一行没有结构'), false)
  assert.equal(hasDocumentStructure(''), false)
})

test('纯 Markdown 符号的正文（分隔线之类）纯文本化后为空：不显示', () => {
  assert.equal(shouldShowUseInDocument(bubbleOf({ content: '---' })), false)
})

// ---------- 历史回灌：documentEditedFromProcesses ----------

const toolItem = (code, status = 'success') => ({ type: 'tool', code, output: '', status })

test('历史里有成功的写入类文档工具：算改过', () => {
  assert.equal(documentEditedFromProcesses([{ items: [toolItem('doc_find_replace({"find":"x"})')] }]), true)
  assert.equal(documentEditedFromProcesses([{ items: [toolItem('sheet_write_cells({})')] }]), true)
  assert.equal(documentEditedFromProcesses([{ items: [toolItem('slide_add_page({})')] }]), true)
})

test('历史里只有读取类文档工具：不算改过——先读后起草那一轮最该出按钮', () => {
  assert.equal(documentEditedFromProcesses([{ items: [
    toolItem('doc_open_file({"fileId":1})'),
    toolItem('doc_get_document_text({})'),
    toolItem('doc_get_outline({})'),
  ] }]), false)
  // 先读后写仍然算改过
  assert.equal(documentEditedFromProcesses([{ items: [
    toolItem('doc_get_outline({})'),
    toolItem('doc_insert_at_cursor({"text":"…"})'),
  ] }]), true)
})

test('历史里工具失败 / 不是文档工具 / 没有工具：不算改过', () => {
  assert.equal(documentEditedFromProcesses([{ items: [toolItem('doc_find_replace({})', 'error')] }]), false)
  assert.equal(documentEditedFromProcesses([{ items: [toolItem('search_project_files({})')] }]), false)
  assert.equal(documentEditedFromProcesses([{ items: [{ type: 'step', status: 'done' }] }]), false)
  assert.equal(documentEditedFromProcesses([]), false)
  assert.equal(documentEditedFromProcesses(undefined), false)
})

test('前缀不是子串：doc_ 开头才算，正文里提到 doc_ 不算', () => {
  assert.equal(documentEditedFromProcesses([{ items: [toolItem('write_docx({"note":"doc_find_replace"})')] }]), false)
})

test('两个名字坑：find_ 开头的 doc_find_replace 是写入；selection 两侧都有', () => {
  assert.equal(isDocumentWritingTool('doc_find_replace'), true)
  assert.equal(isDocumentWritingTool('doc_find_text'), false)
  assert.equal(isDocumentWritingTool('doc_set_selection'), false)
  assert.equal(isDocumentWritingTool('doc_replace_selection'), true)
  assert.equal(isDocumentWritingTool('doc_insert_at_cursor'), true, 'inspect 词头不能咬到 insert_')
  assert.equal(isDocumentWritingTool('doc_link_evidence'), true, 'list 词头不能咬到 link_')
  assert.equal(isDocumentWritingTool('office_replace_batch'), false)
  assert.equal(isDocumentWritingTool(''), false)
})

// 真正的对拍：把仓库里全部 doc_/sheet_/slide_ 工具名喂进前端规则，
// 与后端那份权威归类清单逐名比对。只此一份清单，两侧各自对着它验，
// 所以「后端改了前端忘了」在这里会当场红。
test('前端镜像与后端归类逐名一致（全部 113 个工具）', () => {
  const javaUrl = (rel) => new URL(`../../../backend/src/main/java/com/checkba/service/ai/${rel}`, import.meta.url)
  const testUrl = new URL(
    '../../../backend/src/test/java/com/checkba/service/ai/DocumentWritingToolClassificationTest.java',
    import.meta.url)

  // 权威只读清单：后端测试里的 EXPECTED_READ_ONLY
  const javaTest = readFileSync(testUrl, 'utf8')
  const listStart = javaTest.indexOf('EXPECTED_READ_ONLY = new TreeSet<>(List.of(')
  assert.ok(listStart > 0, '后端归类清单挪位置了，对拍失效')
  const listBody = javaTest.slice(listStart, javaTest.indexOf('));', listStart))
  const expectedReadOnly = new Set([...listBody.matchAll(/"((?:doc|sheet|slide)_\w+)"/g)].map(m => m[1]))
  assert.ok(expectedReadOnly.size >= 30, `只解析到 ${expectedReadOnly.size} 条只读工具，解析口径坏了`)

  // 全部工具名：扫真实工具类的 public 方法（与 ToolRegistry 取名口径一致）
  const toolMethod = /public\s+(?:static\s+)?[\w<>[\],\s.]+?\s+((?:doc|sheet|slide)_[a-zA-Z_0-9]+)\s*\(/g
  const all = new Set()
  for (const file of ['tools/DocumentEditTools.java', 'tools/DocumentAuditTools.java',
    'tools/CheckpointTools.java', 'tools/SlideEditTools.java']) {
    for (const m of readFileSync(javaUrl(file), 'utf8').matchAll(toolMethod)) all.add(m[1])
  }
  assert.ok(all.size > 100, `只扫到 ${all.size} 个工具，扫描口径坏了`)

  const mismatched = [...all].filter(name => isDocumentWritingTool(name) === expectedReadOnly.has(name))
  assert.deepEqual(mismatched, [],
    '前端镜像与后端归类不一致。后端判据在 ClientCapabilityService.READ_ONLY_*，'
    + '前端在 utils/useInDocumentVisibility.js，两处要一起改：' + mismatched.join(', '))
})

// ---------- bubble_end 落字段：走真实 useAgentStream 源码 ----------
// 形制照 chat-timeline-contract.test.mjs：@/ 别名与 uni 全局让 node 没法直接 import，
// 剥掉 import 后靠形参注入依赖。

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

function stream() {
  const body = source.replace(/^import .*$/gm, '')
    .replace('export function useAgentStream()', 'function useAgentStream()')
    .replace('        bubbles,\n', '        bubbles, handleEvent, currentAssistantBubble, createAssistantBubble,\n')
  const factory = new Function('ref', 'reactive', 'nextTick', 'onUnmounted', 'getCurrentInstance',
    'createProtocolTagRegex', 'decodeProtocolTags', 'decodeProtocolTagsIncremental', 't', 'nextBubbleId', 'captureChatTimeline',
    'documentEditedFromProcesses',
    'createInboxState', 'applyInboxReceipt', 'applyInboxSnapshot', 'applyInputApplied', 'markInboxEvent', 'removeInboxItem', 'replaceInboxItem',
    'getApiBaseUrl', 'getSessionId', 'getAgentInbox', 'updateAgentInboxItem', 'deleteAgentInboxItem', 'getConversationMetadata',
    body + '\nreturn useAgentStream()')
  const value = factory(ref, reactive, nextTick, () => {}, () => null,
    createProtocolTagRegex, decodeProtocolTags, decodeProtocolTagsIncremental, key => key, nextBubbleId, captureChatTimeline,
    documentEditedFromProcesses,
    createInboxState, applyInboxReceipt, applyInboxSnapshot, applyInputApplied, markInboxEvent, removeInboxItem, replaceInboxItem,
    () => 'http://test.local', () => 'test-session',
    async () => ({ items: [], runId: null, status: null }), async () => null, async () => ({ items: [] }), async () => null)
  const bubble = value.createAssistantBubble()
  bubble.isStreaming = true
  value.bubbles.value.push(bubble)
  value.currentAssistantBubble.value = bubble
  return value
}

test('bubble_end 把 documentEdited / status 落到气泡上（按钮可见性按气泡各自判）', () => {
  const s = stream()
  s.handleEvent('text_delta', JSON.stringify({ content: `<final>${LONG_ANSWER}</final>` }))
  s.handleEvent('bubble_end', JSON.stringify({ status: 'finished', documentEdited: true }))

  const bubble = s.bubbles.value[0]
  assert.equal(bubble.documentEdited, true)
  assert.equal(bubble.status, 'finished')
  assert.equal(shouldShowUseInDocument(bubble), false, 'AI 已经写进文档，不该再请用户插一遍')
})

test('bubble_end 没带 documentEdited（旧后端 / 其它客户端）：按没改过处理，按钮照出', () => {
  const s = stream()
  s.handleEvent('text_delta', JSON.stringify({ content: `<final>${LONG_ANSWER}</final>` }))
  s.handleEvent('bubble_end', JSON.stringify({ status: 'finished' }))

  const bubble = s.bubbles.value[0]
  assert.equal(bubble.documentEdited, false)
  assert.equal(shouldShowUseInDocument(bubble), true)
})

test('反问收尾：status=awaiting_input 落到气泡，问题卡那一轮不出「用到文档」', () => {
  const s = stream()
  s.handleEvent('text_delta', JSON.stringify({ content: '<question>受让方是自然人还是公司？</question>' }))
  s.handleEvent('bubble_end', JSON.stringify({ status: 'awaiting_input', documentEdited: false }))

  const bubble = s.bubbles.value[0]
  assert.equal(bubble.status, 'awaiting_input')
  assert.equal(shouldShowUseInDocument(bubble), false)
})

test('新建的助手气泡自带这两个字段：undefined 会让历史与实时两条路的判据长得不一样', () => {
  const bubble = stream().createAssistantBubble()
  assert.equal(bubble.documentEdited, false)
  assert.equal(bubble.status, '')
})
