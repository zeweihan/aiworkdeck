// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const code = source.slice(source.indexOf('    const connectSSE ='), source.indexOf('    // displayText（可选'))

function setup(fetch) {
  let timer
  let cleared = false
  const connected = { value: false }
  const streaming = { value: true }
  const bubble = { value: { content: '', isStreaming: true } }
  const build = new Function('fetch', 'setTimeout', 'clearTimeout', 'isConnected', 'isStreaming', 'currentAssistantBubble', `
    let sseAbortController, reconnectAttempts, lastSseActivityAt, myNetworkRecoveryHook,
      activeNetworkRecoveryHook, disconnectedBubble;
    const linkStatus = { value: {} };
    const currentConversationId = { value: 'conv' };
    const getApiBaseUrl = () => '', getSessionId = () => '', t = key => key;
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
  }, id => { assert.equal(id, 12); cleared = true }, connected, streaming, bubble)
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
