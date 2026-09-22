// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const code = source.slice(source.indexOf('    const connectSSE ='), source.indexOf('    // displayText（可选'))

function setup(fetch, { lastEventId = '', cursorConversation = 'conv' } = {}) {
  let timer
  let cleared = false
  const connected = { value: false }
  const streaming = { value: true }
  const bubble = { value: { content: '', isStreaming: true } }
  const build = new Function('fetch', 'setTimeout', 'clearTimeout', 'isConnected', 'isStreaming', 'currentAssistantBubble', 'lastSseEventId', 'lastSseEventConversationId', `
    let sseAbortController, reconnectAttempts, lastSseActivityAt, myNetworkRecoveryHook,
      activeNetworkRecoveryHook, disconnectedBubble, supersededByOtherClient;
    const linkStatus = { value: {} };
    const currentConversationId = { value: 'conv' };
    const getApiBaseUrl = () => '', getSessionId = () => 'sess', t = key => key;
    const clientInstanceId = () => 'instance-abc';
    const startHeartbeatMonitor = () => {}, stopHeartbeatMonitor = () => {},
      scheduleReconnect = () => {}, restoreActiveTasks = () => {}, restoreInbox = () => {},
      parseSSELineFull = () => {};
    ${code}
    return connectSSE;
  `)
  const connect = build(fetch, (callback, ms) => {
    assert.equal(ms, 15000)
    timer = callback
    return 12
  }, id => { assert.equal(id, 12); cleared = true }, connected, streaming, bubble, lastEventId, cursorConversation)
  return { connect, connected, streaming, expire: () => timer(), cleared: () => cleared }
}

test('初始发送中建连被拒必须reject，不能留下pending Promise', async () => {
  const state = setup(async () => ({ ok: false, status: 503 }))
  await assert.rejects(state.connect('conv'), /503/)
  assert.equal(state.streaming.value, false)
  assert.equal(state.cleared(), true)
})

test('未返回响应头的SSE在15秒中止并reject，等待中的发送可退出', async () => {
  const state = setup((_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  }))
  const promise = state.connect('conv')
  state.expire()
  await assert.rejects(promise, /connectionInterrupted/)
  assert.equal(state.streaming.value, false)
  assert.equal(state.cleared(), true)
})

test('响应头到达即清除建连超时，长任务流不受15秒限制', async () => {
  const state = setup(async () => ({
    ok: true, body: { getReader: () => ({ read: () => new Promise(() => {}) }) }
  }))
  await state.connect('conv')
  assert.equal(state.connected.value, true)
  assert.equal(state.cleared(), true)
})

// --- dev-board#803：X-Client-Instance 与 Last-Event-ID ---

function captureHeaders(cursor = {}) {
  let seen = null
  const state = setup(async (_url, init) => {
    seen = init.headers
    return { ok: true, body: { getReader: () => ({ read: () => new Promise(() => {}) }) } }
  }, cursor)
  return { state, headers: () => seen }
}

test('首连带 X-Client-Instance，不带 Last-Event-ID', async () => {
  const { state, headers } = captureHeaders()
  await state.connect('conv')
  assert.equal(headers()['X-Client-Instance'], 'instance-abc')
  assert.equal(headers()['X-Session-Id'], 'sess')
  // 首连没有游标：带一个空的 Last-Event-ID 会让后端把它当成 0 号之后全补一遍
  assert.equal('Last-Event-ID' in headers(), false)
})

test('已经收过事件时重连带上 Last-Event-ID，后端据此补发断线空档', async () => {
  const { state, headers } = captureHeaders({ lastEventId: '42' })
  await state.connect('conv')
  assert.equal(headers()['Last-Event-ID'], '42')
  assert.equal(headers()['X-Client-Instance'], 'instance-abc')
})

test('游标属于另一条会话时一个字都不带：否则后端会按 id 大于它把新会话前面的事件整段跳过', async () => {
  const { state, headers } = captureHeaders({ lastEventId: '42', cursorConversation: 'conv-other' })
  await state.connect('conv')
  assert.equal('Last-Event-ID' in headers(), false)
})
