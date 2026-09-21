// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「终态却零正文」这一档的端到端回归（dev-board#768）：
 *   node --test office-addin/taskpane/lib/emptyAnswer.test.js
 *
 * 真机 2026-09-21（Parallels / Windows Word / 英文界面 / addin.workdeck.ai）：
 * 一轮 55 秒的对话把内容以修订形式写进了文档，窗格里却只剩一行
 * 「Done in 55s (This reply was lost while the connection was down...)」。
 * 云后端日志证明**那一轮从头到尾没有断过线**（11:19:24 建连、11:20:33 才是下一次建连，
 * 中间的 54 秒就是这一轮），文案纯属栽赃——用户被指向网络，而该看的是文档。
 *
 * 钉住两件事：
 *   1. 本轮没断过线的零正文，文案不许提「连接中断」；
 *   2. 本轮真断过线时，先去 /api/ai/history 把已落库的回复补回气泡（补回来就不是「丢了」）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

// ---- localStorage / Office 环境桩（须在 import 被测模块之前就位） ----
const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}
globalThis.Word = globalThis.Word || {}

const { activateSession, messages, input, send, stop } = await import('./chatSession.js')
const { t } = await import('./i18n.js')

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

function sseEvent(event, data) {
  return `event: ${event}\ndata: ${JSON.stringify(data)}\n\n`
}

/** 依次吐出 chunks 后**挂起**（流不结束，模拟一条健康连接） */
function openStream(chunks) {
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

/** 吐完 chunks 就**结束**流：轮次中途的这种结束会被客户端判成断线并排重连 */
function endingStream(chunks) {
  const queue = [...chunks]
  return {
    ok: true,
    status: 200,
    body: {
      getReader: () => ({
        read: () => (queue.length
          ? Promise.resolve({ done: false, value: new TextEncoder().encode(queue.shift()) })
          : Promise.resolve({ done: true }))
      })
    }
  }
}

async function until(cond, ms = 6000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (cond()) return true
    await new Promise((r) => setTimeout(r, 20))
  }
  return cond()
}

// 模型整轮只调工具、一个 <final> 都没有：这一轮对用户而言零正文
const TOOL_ONLY_TURN = '<process name="写摘要">'
  + '<tool_code>office_insert_text(text="第一节正文")</tool_code></process>\n'

async function startSession(name) {
  const boot = async (url) => {
    if (url.includes('/api/ai/history')) return jsonReply([])
    if (url.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-' + name })
    if (url.includes('/api/agent/connect/')) return openStream([])
    if (url.includes('/api/ai/models') || url.includes('/api/skills/list')) return jsonReply([], false, 404)
    return jsonReply({}, false, 404)
  }
  globalThis.fetch = async (url) => boot(String(url))
  await activateSession({
    settings: { serverUrl: 'https://x.example', token: 'awdt_' + name }, projectId: '77'
  })
  // 换掉预连建好的那条连接，让 send 的兜底 preconnect 拿到本用例脚本化的流
  await stop()
}

test('没断过线的零正文：文案说「这一轮没有文字回复」，不许栽赃给连接', async () => {
  store.clear()
  const original = globalThis.fetch
  try {
    await startSession('none')
    globalThis.fetch = async (url) => {
      const u = String(url)
      if (u.includes('/api/agent/connect/')) {
        return openStream([
          sseEvent('text_delta', { content: TOOL_ONLY_TURN }),
          sseEvent('bubble_end', { status: 'finished' })
        ])
      }
      if (u.endsWith('/api/agent/chat')) return jsonReply({ code: 0 })
      if (u.includes('/api/ai/history')) return jsonReply([]) // 服务端也没有落库正文
      return jsonReply({}, false, 404)
    }
    input.value = 'write me a summary of legal tech of today'
    await send()
    assert.ok(await until(() => {
      const last = messages.value[messages.value.length - 1]
      return last && last.role === 'assistant' && last.notice
    }), '等待零正文收尾的提示超时')
    const last = messages.value[messages.value.length - 1]
    assert.equal(last.notice, t('emptyAnswerNone'))
    assert.notEqual(last.notice, t('emptyAnswerLost'), '本轮一次都没断线，不许说是连接中断')
  } finally {
    await stop()
    globalThis.fetch = original
  }
})

test('本轮真断过线：重连后把已落库的回复从 /api/ai/history 补回气泡', async () => {
  store.clear()
  const original = globalThis.fetch
  try {
    await startSession('lost')
    let connects = 0
    globalThis.fetch = async (url) => {
      const u = String(url)
      if (u.includes('/api/agent/connect/')) {
        connects++
        // 第一条连接在轮次中途断掉（流结束），客户端据此判「断线过」并排重连
        if (connects === 1) return endingStream([sseEvent('text_delta', { content: TOOL_ONLY_TURN })])
        return openStream([sseEvent('bubble_end', { status: 'finished' })])
      }
      if (u.endsWith('/api/agent/chat')) return jsonReply({ code: 0 })
      if (u.includes('/api/ai/history')) {
        return jsonReply([
          { role: 'USER', content: 'write me a summary of legal tech of today' },
          { role: 'ASSISTANT', content: '<final>已经把摘要写进文档了。</final>' }
        ])
      }
      return jsonReply({}, false, 404)
    }
    input.value = 'write me a summary of legal tech of today'
    await send()
    assert.ok(await until(() => {
      const last = messages.value[messages.value.length - 1]
      return last && last.role === 'assistant' && last.notice
    }), '等待断线重连后的收尾超时')
    assert.ok(connects >= 2, `只建连 ${connects} 次，断线重连没发生，这条用例没测到东西`)
    const last = messages.value[messages.value.length - 1]
    assert.equal(last.text, '已经把摘要写进文档了。', '落库的回复没被补回来 = 用户白丢一轮')
    assert.equal(last.notice, t('emptyAnswerRecovered'))
  } finally {
    await stop()
    globalThis.fetch = original
  }
})
