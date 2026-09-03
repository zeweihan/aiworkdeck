/**
 * 整篇分段过卷的进度展示（dev-board#422）。
 *   node --test office-addin/taskpane/lib/passProgress.test.js
 *
 * 钉住三件事：
 * 1. 收到 pass_progress 后 passProgress 有值（界面据此把「正在操作文档…」换成
 *    「校对 3/12 段 · 已改 7 处」）——整篇校对要跑十几分钟，一个不动的转圈跟卡死没区别；
 * 2. done=true 之后立刻归位（挂着「12/12 段」比没有更误导）；
 * 3. 轮次终态（bubble_end）也一律归位——后端在 done 之前掉线时不能把进度永久留在屏幕上。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { t } from './i18n.js'

// ---- localStorage / Office 环境桩（须在 import 被测模块之前就位） ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}
globalThis.Word = globalThis.Word || {}

const {
  activateSession, messages, input, send, stop, passProgress, streaming
} = await import('./chatSession.js')

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

function sseScriptedResponse(chunks) {
  const queue = [...chunks]
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: () => (queue.length
          ? Promise.resolve({ done: false, value: new TextEncoder().encode(queue.shift()) })
          : new Promise(() => {}))
      })
    }
  }
}

/**
 * 带闸门的脚本流：第一段立刻发，其余各段等 gate() 被调用才发。
 *
 * <p>为什么必须有闸门：一次性把「进度事件 + done 事件」灌进去，消费端会在同一个
 * 微任务批次里把两条都处理完，`until(() => passProgress === null)` 第一次轮询就为真——
 * 而它初始本来就是 null，用例照样绿。那是空断言，不是回归。
 */
function gatedResponse(first, rest) {
  let open
  const gate = new Promise((r) => { open = r })
  const queue = [...rest]
  let sentFirst = false
  return {
    response: {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: async () => {
            if (!sentFirst) {
              sentFirst = true
              return { done: false, value: new TextEncoder().encode(first) }
            }
            await gate
            if (queue.length) return { done: false, value: new TextEncoder().encode(queue.shift()) }
            return new Promise(() => {})
          }
        })
      }
    },
    open: () => open()
  }
}

function sseEvent(event, data) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`
}

async function until(cond, ms = 2000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (cond()) return true
    await new Promise((r) => setTimeout(r, 20))
  }
  return cond()
}

/** 建好会话，然后把脚本化事件从一条新连接里灌进来（与 streamRender.test.js 同一路子）。 */
async function runWithEvents(chunks, gated) {
  store.clear()
  const original = globalThis.fetch
  globalThis.fetch = async (url) => {
    const u = String(url)
    if (u.includes('/api/ai/history')) return jsonReply([])
    if (u.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-pass-1' })
    if (u.includes('/api/agent/connect/')) return sseScriptedResponse([])
    if (u.endsWith('/api/agent/chat')) return jsonReply({ code: 0 })
    return jsonReply({}, false, 404)
  }
  await activateSession({
    settings: { serverUrl: 'https://x.example', token: 'awdt_pass' }, projectId: '31'
  })
  await stop()
  globalThis.fetch = async (url) => {
    const u = String(url)
    if (u.includes('/api/agent/connect/')) return gated ? gated.response : sseScriptedResponse(chunks)
    if (u.endsWith('/api/agent/chat')) return jsonReply({ code: 0 })
    if (u.includes('/api/ai/history')) return jsonReply([])
    return jsonReply({}, false, 404)
  }
  input.value = '请分段过卷校对全文'
  await send()
  return () => { globalThis.fetch = original }
}

test('pass_progress：进度进入 store，状态文案变成「校对 3/12 段 · 已改 7 处」', async () => {
  const restore = await runWithEvents([
    sseEvent('pass_progress', { chunk: 3, total: 12, replaced: 7, done: false })
  ])
  try {
    assert.ok(await until(() => passProgress.value !== null), '等待 pass_progress 超时')
    assert.deepEqual(passProgress.value, { chunk: 3, total: 12, replaced: 7 })
    assert.equal(t('passProgressWithEdits', { chunk: 3, total: 12, replaced: 7 }),
      t('passProgressWithEdits').replace('{chunk}', '3').replace('{total}', '12').replace('{replaced}', '7'))
  } finally {
    await stop()
    restore()
  }
})

test('pass_progress：done=true 之后进度归位', async () => {
  const gated = gatedResponse(
    sseEvent('pass_progress', { chunk: 1, total: 4, replaced: 0, done: false }),
    [sseEvent('pass_progress', { chunk: 4, total: 4, replaced: 9, done: true })])
  const restore = await runWithEvents(null, gated)
  try {
    // 先确认进度真的挂上了，再放行 done——否则「一直是 null」也能骗过下面那句
    assert.ok(await until(() => passProgress.value !== null), '等待第 1 块进度超时')
    gated.open()
    assert.ok(await until(() => passProgress.value === null), '过卷结束后必须清掉进度')
  } finally {
    await stop()
    restore()
  }
})

test('bubble_end：轮次收尾一律清掉进度（后端在 done 之前掉线也不留残影）', async () => {
  const gated = gatedResponse(
    sseEvent('pass_progress', { chunk: 2, total: 9, replaced: 3, done: false }),
    [sseEvent('text_delta', { content: '<final>过卷被打断了</final>' }), sseEvent('bubble_end', {})])
  const restore = await runWithEvents(null, gated)
  try {
    assert.ok(await until(() => passProgress.value !== null), '等待进度挂上超时')
    gated.open()
    assert.ok(await until(() => streaming.value === false), '等待 bubble_end 超时')
    assert.equal(passProgress.value, null, '轮次结束必须归位')
    const last = messages.value[messages.value.length - 1]
    assert.equal(last.text, '过卷被打断了')
  } finally {
    await stop()
    restore()
  }
})

test('pass_progress：载荷坏掉不影响这一轮（纯展示，静默忽略）', async () => {
  const restore = await runWithEvents([
    'event: pass_progress\ndata: 不是 JSON\n\n',
    sseEvent('text_delta', { content: '<final>照常回答</final>' }),
    sseEvent('bubble_end', {})
  ])
  try {
    assert.ok(await until(() => streaming.value === false), '坏载荷把整轮打掉了')
    const last = messages.value[messages.value.length - 1]
    assert.equal(last.text, '照常回答')
  } finally {
    await stop()
    restore()
  }
})
