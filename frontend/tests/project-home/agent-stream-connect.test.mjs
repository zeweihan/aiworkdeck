// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const code = source.slice(source.indexOf('    const connectSSE ='), source.indexOf('    // displayText（可选'))

function setup(fetch, { lastEventId = '', cursorConversation = 'conv', onReconnect = () => {} } = {}) {
  let timer
  let cleared = false
  const connected = { value: false }
  const streaming = { value: true }
  const bubble = { value: { content: '', isStreaming: true } }
  const build = new Function('fetch', 'setTimeout', 'clearTimeout', 'isConnected', 'isStreaming', 'currentAssistantBubble', 'lastSseEventId', 'lastSseEventConversationId', 'scheduleReconnect', `
    let sseAbortController, reconnectAttempts, lastSseActivityAt, myNetworkRecoveryHook,
      activeNetworkRecoveryHook, disconnectedBubble, supersededByOtherClient;
    const linkStatus = { value: {} };
    const currentConversationId = { value: 'conv' };
    const getApiBaseUrl = () => '', getSessionId = () => 'sess', t = key => key;
    const clientInstanceId = () => 'instance-abc';
    const startHeartbeatMonitor = () => {}, stopHeartbeatMonitor = () => {},
      restoreActiveTasks = () => {}, restoreInbox = () => {},
      parseSSELineFull = () => {};
    ${code}
    return connectSSE;
  `)
  const connect = build(fetch, (callback, ms) => {
    assert.equal(ms, 15000)
    timer = callback
    return 12
  }, id => { assert.equal(id, 12); cleared = true }, connected, streaming, bubble, lastEventId, cursorConversation, onReconnect)
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

// --- dev-board#821（K40）：一轮之内不许重建连接 ---
//
// 病灶背景：K32 C-04 之后 sendMessage 不再 await 建连，而是与 POST /chat 并行发出。
// 于是「同一条会话上第二条消息」会不会再开一条 SSE、把第一条顶掉（后端 emitters
// 按 conversationId 索引，后来者 put 进去就把前一个 complete 掉），就全靠 connectSSE
// 开头那一行短路。这两条用例把它钉住：连着就不重建，关流了必须重建。

test('已经连着时再调 connectSSE 直接短路，不重开一条把自己顶掉', async () => {
  let calls = 0
  const state = setup(async () => {
    calls += 1
    return { ok: true, body: { getReader: () => ({ read: () => new Promise(() => {}) }) } }
  })
  await state.connect('conv')
  assert.equal(calls, 1)
  assert.equal(state.connected.value, true)
  // sendMessage 第二次调用（同一条会话上的第二条消息）
  await state.connect('conv')
  assert.equal(calls, 1, '已有活连接时不许再发一次 GET /api/agent/connect')
})

test('上一轮收尾关流之后，下一条消息必须重新建连（短路不许粘住）', async () => {
  let calls = 0
  const state = setup(async () => {
    calls += 1
    return { ok: true, body: { getReader: () => ({ read: async () => ({ done: true }) }) } }
  })
  await state.connect('conv')
  await new Promise((resolve) => setImmediate(resolve))
  // 后端每轮收尾主动关流（endRunAndDrain），读循环收到 done 即落到 finally
  assert.equal(state.connected.value, false)
  await state.connect('conv')
  assert.equal(calls, 2, '流已经结束时必须真的重连，否则第二条消息一个事件都收不到')
})

// dev-board#821（K40）：本轮还在跑、流却自己断了 —— 必须自动重连续流。
//
// 病灶：catch 里为了立刻解锁界面先把 isStreaming 清成 false，而 finally 判断要不要重连
// 读的就是同一个 isStreaming，于是那条「异常断流 → scheduleReconnect」整条是死代码
// （v0.46.2 起如此）。后果是流一断，后台那一轮照样跑完、前端却停在「思考中…」一直读秒，
// 既不重连也不报错，内容只能等用户切走再切回来靠历史补。
function readerThatDiesAfterFirstChunk() {
  let first = true
  return {
    getReader: () => ({
      read: async () => {
        if (first) { first = false; return { value: new Uint8Array(), done: false } }
        throw new TypeError('Failed to fetch')
      }
    })
  }
}

test('本轮还在跑、流却异常断了：自动重连续流', async () => {
  const reasons = []
  const state = setup(async () => ({ ok: true, body: readerThatDiesAfterFirstChunk() }),
    { onReconnect: (why) => reasons.push(why) })
  await state.connect('conv')
  await new Promise((resolve) => setTimeout(resolve, 10))
  assert.deepEqual(reasons, ['stream-ended'], '异常断流必须安排重连，否则这一轮永远停在「思考中…」')
  assert.equal(state.streaming.value, false, '界面要立刻解锁，重连是后台的事')
})

test('主动拆流（切会话 / 卸载 / 心跳判死）不许在这里重连：那是我们自己关的', async () => {
  const reasons = []
  const state = setup(async () => ({
    ok: true,
    body: { getReader: () => ({ read: async () => { throw new DOMException('Aborted', 'AbortError') } }) }
  }), { onReconnect: (why) => reasons.push(why) })
  await state.connect('conv')
  await new Promise((resolve) => setTimeout(resolve, 10))
  assert.deepEqual(reasons, [], '自己 abort 掉的流再从这里连回去，就是和另一头互顶')
})
