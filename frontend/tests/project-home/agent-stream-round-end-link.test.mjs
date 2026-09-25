// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机 BUG-16（C5-01）：每轮 AI 回复结束（含点停止）都闪
// 「与服务器的连接已断开，正在自动重连（第 1 次）。AI 仍在后台运行，重连后会续上。」
//
// 病灶：后端每轮收尾主动关流是设计内行为（AgentOrchestrator.endRunAndDrain / closeSse），
// 前端却把「流已关」一律当成「连接断了」：
//   ① 关流之后窗口回前台 / 网络恢复，模块级恢复钩子照样 scheduleReconnect，
//      linkStatus 置 reconnecting，输入区挂出断线横幅——而这一轮早就正常收尾了；
//   ② 终态事件是 error 时，主分支不清 isStreaming，关流落到 finally 就走
//      「流式中意外断开」那条重连分支，同样挂横幅，还在气泡上补一句「已中断」。
// 真机日志对照（backend.log 2026-09-25 15:14~15:31）：每轮 Loop Finished 之后 7~55 秒
// 才出现下一次 connect，不是 finally 那条 1 秒退避——就是回前台触发的恢复钩子。
//
// 与 question-stream.test.mjs 同法：去掉 import 后整段跑真实 composable（真 Vue ref），
// 只把 fetch / window / document 换成可控的桩。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, reactive, nextTick } from 'vue'
import { createProtocolTagRegex, decodeProtocolTags, decodeProtocolTagsIncremental } from '../../src/composables/agentTagProtocol.mjs'
import {
  applyInboxReceipt, applyInboxSnapshot, applyInputApplied, createInboxState,
  markInboxEvent, removeInboxItem, replaceInboxItem,
} from '../../src/composables/agentInboxState.mjs'
import { ASK_USER_KIND, decodeAttr, normalizeAskUserEvent } from '../../src/utils/askUserAnswer.mjs'
import { captureChatTimeline } from '../../src/components/AgentMessage/chatTimeline.mjs'
import { nextBubbleId } from '../../src/composables/bubbleId.js'
import { documentEditedFromProcesses } from '../../src/utils/useInDocumentVisibility.js'
import { isSameFileChange } from '../../src/utils/chatFileChange.js'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

/** 一条可控的 SSE 响应体：push 一段原文、end 正常收尾（服务端关流）、fail 异常断开。 */
function controllableBody() {
  const encoder = new TextEncoder()
  const queue = []
  let waiter = null
  const settle = (item) => {
    if (waiter) { const w = waiter; waiter = null; w(item) } else queue.push(item)
  }
  return {
    body: {
      getReader: () => ({
        read: () => new Promise((resolve, reject) => {
          const deliver = (item) => (item.error ? reject(item.error) : resolve(item.chunk))
          if (queue.length) deliver(queue.shift())
          else waiter = deliver
        }),
      }),
    },
    push: (text) => settle({ chunk: { value: encoder.encode(text), done: false } }),
    end: () => settle({ chunk: { value: undefined, done: true } }),
    fail: (error) => settle({ error }),
  }
}

let seq = 0
const sse = (event, data) => `id:${++seq}\nevent:${event}\ndata:${data}\n\n`

async function flush() {
  for (let i = 0; i < 5; i++) {
    await new Promise((resolve) => setImmediate(resolve))
    await nextTick()
  }
}

function harness() {
  const listeners = { window: {}, document: {} }
  const fakeDocument = {
    visibilityState: 'visible',
    addEventListener: (name, fn) => { listeners.document[name] = fn },
  }
  const fakeWindow = { addEventListener: (name, fn) => { listeners.window[name] = fn } }
  globalThis.window = fakeWindow
  globalThis.document = fakeDocument

  const streams = []
  const calls = []
  globalThis.fetch = async (url, init = {}) => {
    calls.push(String(url))
    if (String(url).includes('/api/agent/connect/')) {
      const s = controllableBody()
      streams.push(s)
      return { ok: true, status: 200, body: s.body }
    }
    if (String(url).includes('/api/agent/chat')) {
      return {
        ok: true, status: 200,
        json: async () => ({ data: { status: 'accepted', messageId: 'm-1', state: 'applied', submissionMode: 'steer' } }),
      }
    }
    if (String(url).includes('/api/agent/cancel/')) {
      return { ok: true, status: 200, json: async () => ({ cancelled: true }) }
    }
    // /api/agent/tasks/active 等恢复性补拉
    return { ok: true, status: 200, json: async () => [] }
  }

  const body = source.replace(/^import .*$/gm, '')
    .replace('export function useAgentStream()', 'function useAgentStream()')
  const factory = new Function('ref', 'reactive', 'nextTick', 'onUnmounted', 'getCurrentInstance',
    'createProtocolTagRegex', 'decodeProtocolTags', 'decodeProtocolTagsIncremental', 't', 'nextBubbleId', 'captureChatTimeline',
    'documentEditedFromProcesses', 'isSameFileChange',
    'createInboxState', 'applyInboxReceipt', 'applyInboxSnapshot', 'applyInputApplied', 'markInboxEvent', 'removeInboxItem', 'replaceInboxItem',
    'getApiBaseUrl', 'getSessionId', 'getAgentInbox', 'updateAgentInboxItem', 'deleteAgentInboxItem', 'getConversationMetadata',
    'ASK_USER_KIND', 'decodeAttr', 'normalizeAskUserEvent',
    body + '\nreturn useAgentStream()')
  const s = factory(ref, reactive, nextTick, () => {}, () => null,
    createProtocolTagRegex, decodeProtocolTags, decodeProtocolTagsIncremental, (key) => key, nextBubbleId, captureChatTimeline,
    documentEditedFromProcesses, isSameFileChange,
    createInboxState, applyInboxReceipt, applyInboxSnapshot, applyInputApplied, markInboxEvent, removeInboxItem, replaceInboxItem,
    () => 'http://test.local', () => 'test-session',
    async () => ({ items: [], runId: null, status: null }), async () => null, async () => ({ items: [] }), async () => null,
    ASK_USER_KIND, decodeAttr, normalizeAskUserEvent)

  const connects = () => calls.filter((u) => u.includes('/api/agent/connect/')).length
  const becomeVisible = () => {
    fakeDocument.visibilityState = 'visible'
    listeners.document.visibilitychange && listeners.document.visibilitychange()
  }
  const goOnline = () => listeners.window.online && listeners.window.online()
  // 收尾：清掉重连定时器与心跳，node:test 不留挂起的 timer
  const dispose = () => s.resetSSE()
  return { s, streams, connects, becomeVisible, goOnline, dispose }
}

async function startRound(h) {
  const pending = h.s.sendMessage({ prompt: '你好', projectId: 1 })
  await flush()
  await pending
  assert.equal(h.streams.length, 1, '发送时应当建起一条 SSE')
  const stream = h.streams[0]
  stream.push(sse('bubble_start', '{}'))
  stream.push(sse('text_delta', JSON.stringify({ content: '<final>好的</final>' })))
  await flush()
  assert.equal(h.s.isStreaming.value, true)
  return stream
}

test('正常收尾：bubble_end 后服务端关流，不进重连横幅', async () => {
  const h = harness()
  try {
    const stream = await startRound(h)
    stream.push(sse('bubble_end', JSON.stringify({ status: 'finished' })))
    stream.end()
    await flush()
    assert.equal(h.s.isStreaming.value, false)
    assert.equal(h.s.linkStatus.value.state, 'live', '正常收尾不是断线')
  } finally { h.dispose() }
})

test('正常收尾之后窗口回前台：不许挂「连接已断开，正在自动重连」', async () => {
  const h = harness()
  try {
    const stream = await startRound(h)
    stream.push(sse('bubble_end', JSON.stringify({ status: 'finished' })))
    stream.end()
    await flush()
    h.becomeVisible()
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'live',
      '这一轮已经收尾，服务端按设计关了流；回前台时把它说成断线会让律师以为出故障了')
    h.goOnline()
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'live', '网络恢复事件同理')
  } finally { h.dispose() }
})

test('反问停机（awaiting_input）收尾同样不挂横幅', async () => {
  const h = harness()
  try {
    const stream = await startRound(h)
    stream.push(sse('bubble_end', JSON.stringify({ status: 'awaiting_input' })))
    stream.end()
    await flush()
    h.becomeVisible()
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'live')
    assert.equal(h.s.agentAwaitingInput.value, true)
  } finally { h.dispose() }
})

test('点停止：cancelled 后服务端关流、再回前台，都不挂横幅', async () => {
  const h = harness()
  try {
    const stream = await startRound(h)
    await h.s.abort()
    stream.push(sse('cancelled', '{}'))
    stream.end()
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'live')
    h.becomeVisible()
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'live', '停止是用户自己点的，不是断线')
  } finally { h.dispose() }
})

test('终态 error 后服务端关流：解锁输入、不重连、不挂横幅、不补「已中断」', async () => {
  const h = harness()
  try {
    const stream = await startRound(h)
    stream.push(sse('error', 'Stream Error: AI_QUOTA_EXHAUSTED'))
    stream.end()
    await flush()
    assert.equal(h.s.isStreaming.value, false, '终态错误之后输入框必须可用')
    assert.equal(h.s.linkStatus.value.state, 'live', '出错是这一轮的结局，不是连接断了')
    const bubble = h.s.bubbles.value.at(-1)
    assert.doesNotMatch(bubble.content, /connectionInterrupted/)
  } finally { h.dispose() }
})

// ---- 下面两条是护栏：真断线仍然要提示并重连（dev-board#821 的行为不许被本修复吃掉）----

test('护栏：一轮还在跑、流异常断开，照旧挂横幅并安排重连', async () => {
  const h = harness()
  try {
    const stream = await startRound(h)
    stream.fail(new TypeError('network error'))
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'reconnecting')
    assert.equal(h.s.linkStatus.value.attempt, 1)
  } finally { h.dispose() }
})

test('护栏：没收到任何终态事件流就结束了（终态丢在路上），照旧当断线重连', async () => {
  const h = harness()
  try {
    const stream = await startRound(h)
    stream.end()
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'reconnecting')
  } finally { h.dispose() }
})

test('护栏：上一轮正常收尾后开始新一轮，新一轮中途断线仍要提示', async () => {
  const h = harness()
  try {
    const first = await startRound(h)
    first.push(sse('bubble_end', JSON.stringify({ status: 'finished' })))
    first.end()
    await flush()
    const pending = h.s.sendMessage({ prompt: '再来一条', projectId: 1 })
    await flush()
    await pending
    assert.equal(h.streams.length, 2, '上一轮关流后，下一条消息重新建连')
    const second = h.streams[1]
    second.push(sse('text_delta', JSON.stringify({ content: '半截' })))
    await flush()
    second.fail(new TypeError('network error'))
    await flush()
    assert.equal(h.s.linkStatus.value.state, 'reconnecting', '「已收尾」的记号必须随新一轮开始清掉')
  } finally { h.dispose() }
})
