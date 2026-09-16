// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 发送路径上的会话自愈回归用例（dev-board#715）：
 *   node --test office-addin/taskpane/lib/chatSessionChat403.test.js
 *
 * 病灶：会话归属校验在 connect 与 chat 两条路上是同一个 canUseConversation，
 * 但客户端只给 connect 做了自愈（dev-board#142）。POST /api/agent/chat 的 403
 * 直接变成「对话请求失败（HTTP 403）」，而且此后每一条都失败——SSE 那条通道的 403
 * 被重连循环吞在退避里只显示「正在重连」，用户只看到发消息一直报错，点「新对话」才恢复。
 *
 * 更隐蔽的一半：connect 的自愈判据写的是 `!messages.value.length`，而 send() 在调
 * preconnect 之前就把用户气泡推进了 messages——那段自愈在**发送路径上从来没有生效过**，
 * 只有进面板那一次能走到。判据因此换成「服务端有没有落库消息」（conversationPersisted）。
 *
 * 还原病灶的办法（任一条都会让下面的用例转红）：
 *   - 把 canHealConversation 里的 conversationPersisted 换回 !messages.value.length；
 *   - 去掉 send() 里 postChat 的 try/catch 重发；
 *   - 把 postChat 抛出的 Error 上的 status 拿掉。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage 内存桩 ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}
// 宿主桩：会话 ID 的存储键按宿主分作用域（dev-board#285），不打桩就落在 'unknown'
globalThis.Word = {}

const { activateSession, send, messages, stop } = await import('./chatSession.js')
const { t, ZH } = await import('./i18n.js')

/** 永不出数据的 SSE 响应体（建连成功后读流挂起，不影响用例收尾） */
function sseOkResponse() {
  return { ok: true, status: 200, body: { getReader: () => ({ read: () => new Promise(() => {}) }) } }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body, text: async () => JSON.stringify(body) }
}

function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return { calls, restore: () => { globalThis.fetch = original } }
}

/** 每次 POST /api/agent/chat 请求体里的 conversationId，按发生顺序 */
function chatConversationIds(calls) {
  return calls
    .filter((c) => c.url.endsWith('/api/agent/chat'))
    .map((c) => JSON.parse(c.options.body).conversationId)
}

function lastAssistant() {
  for (let i = messages.value.length - 1; i >= 0; i--) {
    if (messages.value[i].role === 'assistant') return messages.value[i]
  }
  return null
}

const SETTINGS = { serverUrl: 'https://cloud.example', token: 'awdt_t' }

test('chat 403（会话已失效）：丢弃死 ID → 重新签发 → 自动重发一次并成功', async () => {
  store.clear()
  let issued = 0
  const f = stubFetch((url, options) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) {
      issued++
      return jsonReply({ conversationId: issued === 1 ? 'conv-dead' : 'conv-fresh' })
    }
    if (url.includes('/api/agent/connect/')) return sseOkResponse()
    if (url.endsWith('/api/agent/chat')) {
      const cid = JSON.parse(options.body).conversationId
      // 死会话：后端 canUseConversation 不放行（签发登记簿丢了 + 没有落库消息）
      if (cid === 'conv-dead') return jsonReply({ status: 'error', message: '无权操作该会话' }, false, 403)
      return jsonReply({ status: 'ok' })
    }
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await activateSession({ settings: SETTINGS, projectId: '21' })
    assert.equal(store.get('awd_addin_conv_word_21'), 'conv-dead')

    await send('帮我看看这份合同')

    // 死 ID 发了一次、新 ID 重发了一次，且只重发一次
    assert.deepEqual(chatConversationIds(f.calls), ['conv-dead', 'conv-fresh'])
    // 新会话 ID 落盘，替换掉死的那个
    assert.equal(store.get('awd_addin_conv_word_21'), 'conv-fresh')
    // 新会话也建起了自己的 SSE 通道（连接绑在会话 ID 上，不能继续用旧的）
    assert.ok(f.calls.some((c) => c.url.includes('/api/agent/connect/conv-fresh')))
    // 用户看不到报错——这条消息确实发出去了
    assert.equal(lastAssistant().error, '')
  } finally {
    await stop()
    f.restore()
  }
})

test('重发仍失败才报错，且文案说清下一步（不是裸的 HTTP 403）', async () => {
  store.clear()
  let issued = 0
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) { issued++; return jsonReply({ conversationId: 'conv-' + issued }) }
    if (url.includes('/api/agent/connect/')) return sseOkResponse()
    if (url.endsWith('/api/agent/chat')) return jsonReply({ status: 'error' }, false, 403)
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await activateSession({ settings: SETTINGS, projectId: '22' })
    await send('再试一次')

    // 一共两次 chat：原会话一次 + 新会话重发一次，不无限重试
    assert.equal(chatConversationIds(f.calls).length, 2)
    const err = lastAssistant().error
    assert.equal(err, t('conversationExpiredRetryFailed'))
    assert.ok(!/HTTP 403/.test(err), '错误文案不该把裸状态码甩给用户: ' + err)
  } finally {
    await stop()
    f.restore()
  }
})

test('有落库历史的会话 403 不换 ID（那是归属问题），文案如实交代', async () => {
  store.clear()
  store.set('awd_addin_conv_word_23', 'conv-owned-by-someone-else')
  let issued = 0
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) return jsonReply([{ role: 'USER', content: '早先的问题' }])
    if (url.endsWith('/api/agent/conversations')) { issued++; return jsonReply({ conversationId: 'conv-new' }) }
    if (url.includes('/api/agent/connect/')) return sseOkResponse()
    if (url.endsWith('/api/agent/chat')) return jsonReply({ status: 'error' }, false, 403)
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await activateSession({ settings: SETTINGS, projectId: '23' })
    await send('继续')

    assert.equal(issued, 0, '有历史的会话不该被悄悄丢掉换新的')
    assert.deepEqual(chatConversationIds(f.calls), ['conv-owned-by-someone-else'])
    assert.equal(store.get('awd_addin_conv_word_23'), 'conv-owned-by-someone-else')
    assert.equal(lastAssistant().error, t('conversationDenied'))
  } finally {
    await stop()
    f.restore()
  }
})

test('chat 404（会话不存在）与 403 同一条自愈路径', async () => {
  store.clear()
  let issued = 0
  const f = stubFetch((url, options) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) {
      issued++
      return jsonReply({ conversationId: issued === 1 ? 'conv-gone' : 'conv-ok' })
    }
    if (url.includes('/api/agent/connect/')) return sseOkResponse()
    if (url.endsWith('/api/agent/chat')) {
      const cid = JSON.parse(options.body).conversationId
      if (cid === 'conv-gone') return jsonReply({ status: 'error' }, false, 404)
      return jsonReply({ status: 'ok' })
    }
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    await activateSession({ settings: SETTINGS, projectId: '24' })
    await send('你好')
    assert.deepEqual(chatConversationIds(f.calls), ['conv-gone', 'conv-ok'])
    assert.equal(lastAssistant().error, '')
  } finally {
    await stop()
    f.restore()
  }
})

test('发送路径上的 connect 403 同样自愈（旧判据 !messages.length 在这里永远不成立）', async () => {
  store.clear()
  let issued = 0
  // 连不上的会话 ID：第二幕里把 conv-2 也加进来，模拟「后端重启，签发登记簿清空」
  const dead = new Set(['conv-1'])
  const f = stubFetch((url) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) { issued++; return jsonReply({ conversationId: 'conv-' + issued }) }
    if (url.includes('/api/agent/connect/')) {
      const cid = url.split('/api/agent/connect/')[1]
      return dead.has(cid) ? jsonReply({}, false, 403) : sseOkResponse()
    }
    if (url.endsWith('/api/agent/chat')) return jsonReply({ status: 'ok' })
    if (url.includes('/api/agent/cancel/')) return jsonReply({})
    throw new Error(`未预期的请求: ${url}`)
  })
  try {
    // 第一幕：进面板时预连就 403（会话是空的），activateSession 那次自愈把 conv-1 换成 conv-2
    await activateSession({ settings: SETTINGS, projectId: '25' })
    assert.equal(store.get('awd_addin_conv_word_25'), 'conv-2')

    // 第二幕：conv-2 在发送之前也死了，且连接已断（每轮结束后端会主动关流）
    dead.add('conv-2')
    await stop()

    await send('发一条')
    // messages 里已经有用户气泡了：旧判据会在这里放弃自愈，把 SSE 403 甩给用户
    assert.equal(issued, 3, '发送路径上的 connect 403 必须同样换 ID 重连')
    assert.deepEqual(chatConversationIds(f.calls), ['conv-3'])
    assert.equal(lastAssistant().error, '')
    assert.equal(store.get('awd_addin_conv_word_25'), 'conv-3')
  } finally {
    await stop()
    f.restore()
  }
})

test('自愈相关中文文案不含「登录/未授权/请先」三个子串（api.js 文件头红线）', () => {
  for (const key of ['conversationRenewedNotice', 'conversationExpiredRetryFailed', 'conversationDenied']) {
    const text = ZH[key]
    assert.ok(text, key + ' 缺 ZH 文案')
    for (const banned of ['登录', '未授权', '请先']) {
      assert.ok(!text.includes(banned), `${key} 不该含「${banned}」: ${text}`)
    }
  }
})
