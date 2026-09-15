// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// PR#844（dev-board#646）复审：按时间线渲染引入的三条契约。
// 1) 分片不该改变正文——processTextStream 现在每轮都把缓冲区抽干，围栏剥离必须放过
//    「还可能长成完整围栏」的结尾，否则跨分片的 ``` 再也拼不起来（PR 头实测：同一段
//    文本按 1 / 3 字符分片得到 "```xml\n\n\nHello\n```" 与 "xml\n\n\nHello\n```"）。
// 2) 插话续跑新起的助手段也要 captureChatTimeline，且 visibleChatTimeline 的兜底判据
//    必须判长度——空数组是 truthy，`bubble.timeline || [...]` 会让兜底永不生效。
// 3) 时间线记的是 bubble.content 的绝对下标；正文被快照/回退改短时要降级截断。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, reactive, nextTick } from 'vue'
import { createProtocolTagRegex, decodeProtocolTags } from '../../src/composables/agentTagProtocol.mjs'
import {
  applyInboxReceipt,
  applyInboxSnapshot,
  applyInputApplied,
  createInboxState,
  markInboxEvent,
  removeInboxItem,
  replaceInboxItem,
} from '../../src/composables/agentInboxState.mjs'
import { captureChatTimeline, visibleChatTimeline } from '../../src/components/AgentMessage/chatTimeline.mjs'
import { nextBubbleId } from '../../src/composables/bubbleId.js'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

function stream() {
  const body = source.replace(/^import .*$/gm, '')
    .replace('export function useAgentStream()', 'function useAgentStream()')
    .replace('        bubbles,\n', '        bubbles, handleEvent, currentAssistantBubble, createAssistantBubble, createUserBubble,\n')
  const factory = new Function('ref', 'reactive', 'nextTick', 'onUnmounted', 'getCurrentInstance',
    'createProtocolTagRegex', 'decodeProtocolTags', 't', 'nextBubbleId', 'captureChatTimeline',
    'createInboxState', 'applyInboxReceipt', 'applyInboxSnapshot', 'applyInputApplied', 'markInboxEvent', 'removeInboxItem', 'replaceInboxItem',
    'getApiBaseUrl', 'getSessionId', 'getAgentInbox', 'updateAgentInboxItem', 'deleteAgentInboxItem', 'getConversationMetadata',
    body + '\nreturn useAgentStream()')
  const value = factory(ref, reactive, nextTick, () => {}, () => null,
    createProtocolTagRegex, decodeProtocolTags, key => key, nextBubbleId, captureChatTimeline,
    createInboxState, applyInboxReceipt, applyInboxSnapshot, applyInputApplied, markInboxEvent, removeInboxItem, replaceInboxItem,
    () => 'http://test.local', () => 'test-session',
    async () => ({ items: [], runId: null, status: null }), async () => null, async () => ({ items: [] }), async () => null)
  const bubble = value.createAssistantBubble()
  bubble.isStreaming = true
  value.bubbles.value.push(bubble)
  value.currentAssistantBubble.value = bubble
  return value
}

const sliceEvery = (text, size) => {
  const out = []
  for (let i = 0; i < text.length; i += size) out.push(text.slice(i, i + size))
  return out
}

const runChunks = (chunks) => {
  const s = stream()
  for (const chunk of chunks) s.handleEvent('text_delta', JSON.stringify({ content: chunk }))
  s.handleEvent('bubble_end', JSON.stringify({ status: 'finished' }))
  return s.bubbles.value[0]
}

const SAMPLES = {
  '裹在 ```xml 里的 final': '```xml\n<final>Hello</final>\n```',
  '裹在 ```xml 里的 thinking + final': '```xml\n<thinking>plan</thinking><final>Answer **bold**</final>\n```',
  '正文里带一个代码块': 'Plain text with a fence:\n```js\nconst a = 1\n```\ndone',
}

for (const [label, text] of Object.entries(SAMPLES)) {
  test(`${label}：按 1 / 3 / 9999 字符分片得到同一份正文，且不露出围栏`, () => {
    const contents = [1, 3, 9999].map(size => runChunks(sliceEvery(text, size)).content)
    assert.equal(contents[0], contents[1], '1 字符与 3 字符分片的正文不同：围栏剥离跨不过分片边界')
    assert.equal(contents[1], contents[2], '3 字符与整段一次到达的正文不同：围栏剥离跨不过分片边界')
    assert.ok(!contents[0].includes('```'), '正文里露出了围栏: ' + JSON.stringify(contents[0]))
    assert.ok(!/(^|\n)xml\b/.test(contents[0]), '围栏被剥了一半，语言标记漏进正文: ' + JSON.stringify(contents[0]))
  })
}

test('评审给的原始分片 ``/`xml\\n/<final>…：正文就是 final 里那句话', () => {
  const bubble = runChunks(['``', '`xml\n', '<final>Hello</final>'])
  assert.equal(bubble.content, 'Hello')
})

test('插话续跑新起的助手段：首 token 之前时间线里就有思考条目', async () => {
  const s = stream()
  s.bubbles.value.unshift(s.createUserBubble('initial request'))
  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-1',
    status: 'RUNNING',
    items: [{ id: 'message-1', message: 'steer', state: 'pending', runId: 'run-1', sequence: 1 }],
  }))
  s.handleEvent('input_applied', JSON.stringify({
    messageId: 'message-1', runId: 'run-1', sequence: 1, message: 'steer', submissionMode: 'steer',
  }))
  await nextTick()

  const next = s.bubbles.value.at(-1)
  assert.equal(next.role, 'ASSISTANT')
  assert.equal(next.content, '', '这一步测的就是首 token 之前')
  assert.ok(next.timeline.some(entry => entry.type === 'thinking'),
    '新起的助手段没有 captureChatTimeline，时间线是空的')
  assert.ok(visibleChatTimeline(next).some(entry => entry.type === 'thinking'),
    '插话续跑后首 token 前看不到思考卡，用户以为没反应')
})

test('时间线记的下标超出正文长度时降级截断，不靠 slice 兜', () => {
  const bubble = { content: 'abc', isStreaming: false, timeline: [{ type: 'text', start: 0, end: 99 }] }
  const visible = visibleChatTimeline(bubble)
  assert.deepEqual(visible.map(entry => entry.type), ['text'])
  assert.equal(visible[0].end, 3, 'end 没有截到 content.length，尾部补发判据会被这个虚高的下标压住')
})
