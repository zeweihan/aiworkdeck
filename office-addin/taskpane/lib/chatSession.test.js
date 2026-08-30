/**
 * 会话自愈回归用例（dev-board#142，2026-08-24 mac 插件「SSE 403」事故）。
 *   node --test office-addin/taskpane/lib/chatSession.test.js
 *
 * 病灶：官方云强制服务端签发会话（conversation-issuance-required），而签发登记簿
 * 是进程内存态——后端一重启登记即丢，localStorage 里钉着的存量会话 ID 从此
 * connect 恒 403。旧代码两处放大成永久自锁：
 *   1) connect 403 只 console.warn，不丢弃死 ID，下次还是拿它连；
 *   2) createConversation 任何失败（含 403）都静默回 null，随后把客户端自造的
 *      conv-<毫秒> 落进 localStorage——在强制签发的云上生来就是死的。
 * 下面用例分别还原这两个病灶：把自愈逻辑拿掉（或恢复「失败回退自造 ID」）就会转红。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage 内存桩：settings.js 的持久化走它 ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}

// 宿主桩：wordDoc.detectHost() 的兜底路径是「有 Word 全局即判 word 宿主」。
// 会话 ID 的存储键按宿主分作用域（dev-board#285），不打这个桩就落在 'unknown' 上，
// 用例读到的键与真实产品路径不是同一个。
globalThis.Word = {}

const { activateSession, messages, stop } = await import('./chatSession.js')
const { createConversation } = await import('./api.js')

/** 永不出数据的 SSE 响应体（建连成功后读流挂起，不影响用例收尾） */
function sseOkResponse() {
  return {
    ok: true,
    status: 200,
    body: { getReader: () => ({ read: () => new Promise(() => {}) }) }
  }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return {
    calls,
    restore: () => { globalThis.fetch = original }
  }
}

test('存量会话 connect 403 时自愈：丢弃死 ID → 重新签发 → 重连成功', async () => {
  store.set('awd_addin_conv_word_7', 'conv-dead-123')
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.includes('/api/agent/connect/conv-dead-123')) return jsonReply({}, false, 403)
    if (url.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-fresh-456' })
    if (url.includes('/api/agent/connect/conv-fresh-456')) return sseOkResponse()
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await activateSession({
      settings: { serverUrl: 'https://cloud.example', token: 'awdt_t1' },
      projectId: '7'
    })
    // 死 ID 已被换成服务端新签发的 ID，并落回 localStorage
    assert.equal(store.get('awd_addin_conv_word_7'), 'conv-fresh-456')
    // 完整自愈链：403 建连 → 重签发 → 新 ID 建连
    const urls = f.calls.map(c => c.url)
    assert.ok(urls.some(u => u.includes('/connect/conv-dead-123')))
    assert.ok(urls.some(u => u.endsWith('/api/agent/conversations')))
    assert.ok(urls.some(u => u.includes('/connect/conv-fresh-456')))
  } finally {
    await stop()
    f.restore()
  }
})

test('签发被拒（403）不回退自造 conv-* ID，更不落盘', async () => {
  store.delete('awd_addin_conv_word_9')
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) return jsonReply({ message: 'denied' }, false, 403)
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await activateSession({
      settings: { serverUrl: 'https://cloud.example', token: 'awdt_t2' },
      projectId: '9'
    })
    // 预连失败被吞掉（不打断用户），但绝不能把自造 ID 写进 localStorage 锁死后续
    assert.equal(store.get('awd_addin_conv_word_9') || '', '')
    assert.ok(!f.calls.some(c => c.url.includes('/api/agent/connect/conv-')))
  } finally {
    await stop()
    f.restore()
  }
})

test('createConversation：404（旧后端）回退 null，403 抛错带状态码', async () => {
  const f404 = stubFetch(() => jsonReply({}, false, 404))
  try {
    assert.equal(await createConversation({ serverUrl: 'https://x', token: 't' }, 1), null)
  } finally { f404.restore() }

  const f403 = stubFetch(() => jsonReply({}, false, 403))
  try {
    await assert.rejects(
      () => createConversation({ serverUrl: 'https://x', token: 't' }, 1),
      (e) => e.status === 403
    )
  } finally { f403.restore() }
})

test('自愈只针对空会话：有历史消息的会话 403 不重签（那是权限/账号问题）', async () => {
  store.set('awd_addin_conv_word_11', 'conv-history-1')
  let issuedCalls = 0
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) {
      return jsonReply([{ role: 'USER', content: '早先的问题' }])
    }
    if (url.includes('/api/agent/connect/conv-history-1')) return jsonReply({}, false, 403)
    if (url.endsWith('/api/agent/conversations')) { issuedCalls++; return jsonReply({ conversationId: 'conv-x' }) }
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await activateSession({
      settings: { serverUrl: 'https://cloud.example', token: 'awdt_t3' },
      projectId: '11'
    })
    assert.equal(issuedCalls, 0)
    // 历史消息还在，会话 ID 没被丢
    assert.equal(store.get('awd_addin_conv_word_11'), 'conv-history-1')
    assert.ok(messages.value.length >= 1)
  } finally {
    await stop()
    f.restore()
  }
})
