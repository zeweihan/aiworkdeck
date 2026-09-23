// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * ask_user（dev-board#868）会话层端到端：
 *   node --test office-addin/taskpane/lib/chatSessionAskUser.test.js   （需先 npm ci，它 import 了 vue）
 *
 * 走真实的 chatSession：签发会话 → SSE 推标记 + ask_user 事件 + bubble_end(awaiting_input)
 * → 问题卡模型就位 → answerAskUser 发出结构化回答（message 是 <ask_user_answer…>，
 * displayText 是所选各项，用户气泡显示 displayText）→ 历史回灌时旧问题只读且高亮所选。
 *
 * 还原病灶即转红：
 *   - handleEvent 去掉 ask_user 分支 → 「事件整块覆盖」转红；
 *   - send 不上送 displayText / 用户气泡显示 prompt → 「结构化回答」转红；
 *   - historyToMessages 退回 history.map(toLocalMessage) → 「历史回灌」两条转红。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}
// 存过盘的 Word 文档（会话只对存过盘的文档落盘，dev-board#767）
globalThis.Office = { context: { document: { url: 'file:///C:/cases/主合同.docx' } } }
globalThis.Word = {
  run: async (cb) => cb({ document: { body: { load() {}, text: '' } }, sync: async () => {} })
}

const { activateSession, send, answerAskUser, messages, stop, notice, streaming } = await import('./chatSession.js')
const { t, getLang } = await import('./i18n.js')
const { documentKey } = await import('./hostBridge.js')
const { formatAskUserAnswer } = await import('./askUser.js')
const convKey = (projectId) => `awd_addin_conv_word_${projectId}_${documentKey()}`
const SEP = getLang() === 'en' ? '; ' : '；'

const enc = new TextEncoder()
function makeStream() {
  const queue = []
  let waiting = null
  return {
    push(event, data) {
      const chunk = enc.encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
      if (waiting) { const w = waiting; waiting = null; w({ done: false, value: chunk }) } else queue.push({ done: false, value: chunk })
    },
    reader: {
      read() {
        if (queue.length) return Promise.resolve(queue.shift())
        return new Promise((resolve) => { waiting = resolve })
      }
    }
  }
}

const jsonReply = (body, status = 200) => ({
  ok: status >= 200 && status < 300, status, json: async () => body, text: async () => JSON.stringify(body)
})

/** 与后端 AskUserQuestion.toMarkup 同形 */
const MARKUP = '\n<question kind="ask_user" id="ask-q1" multi="true">「清理」具体指哪一种？'
  + '\n<option description="只删连续空行">删除空行</option>'
  + '\n<option description="字体字号按律所标准">统一格式</option>'
  + '\n<option>去掉批注</option>'
  + '\n</question>\n'

function makeBackend({ history = [], onChat } = {}) {
  const state = { chats: [], streams: new Map() }
  return {
    state,
    push(cid, event, data) { const s = state.streams.get(cid); if (s) s.push(event, data) },
    async fetch(url, options = {}) {
      if (url.includes('/api/ai/history')) return jsonReply(history)
      if (url.includes('/api/ai/models')) return jsonReply({ models: [], defaultModel: '' })
      if (url.includes('/api/ai/skills')) return jsonReply([])
      if (url.includes('/api/projects/addin-links')) return jsonReply({ code: 0, data: [] })
      if (url.includes('/api/addin/panes/')) return jsonReply({ code: 0 })
      if (url.endsWith('/api/agent/conversations')) return jsonReply({ conversationId: 'conv-ask' })
      if (url.includes('/api/agent/connect/')) {
        const cid = decodeURIComponent(url.split('/api/agent/connect/')[1].split('?')[0])
        const s = makeStream()
        state.streams.set(cid, s)
        return { ok: true, status: 200, body: { getReader: () => s.reader } }
      }
      if (url.endsWith('/api/agent/chat')) {
        const body = JSON.parse(options.body)
        state.chats.push(body)
        if (onChat) setTimeout(() => onChat(body), 0)
        return jsonReply({ code: 0 })
      }
      if (url.includes('/api/agent/cancel/')) return jsonReply({})
      throw new Error('未预期的请求: ' + url)
    }
  }
}

const SETTINGS = { serverUrl: 'https://cloud.example', token: 'awdt_t' }
const tick = () => new Promise((r) => setTimeout(r, 5))
async function until(pred, what) {
  for (let i = 0; i < 200; i++) { if (pred()) return; await tick() }
  assert.fail('等不到: ' + what)
}
const lastOf = (role) => [...messages.value].reverse().find((m) => m.role === role)

test('实时：标记 + ask_user 事件 → 问题卡；awaiting_input 解锁；作答发出结构化回答并变只读', async () => {
  store.clear()
  let backend = null
  backend = makeBackend({
    onChat(body) {
      if (backend.state.chats.length !== 1) return
      const cid = body.conversationId
      backend.push(cid, 'text_delta', { content: '<process><step>先问清楚</step></process>' + MARKUP })
      // 事件以它为准整块覆盖：这里多带一个 header，标记里没有
      backend.push(cid, 'ask_user', {
        v: 1, id: 'ask-q1', question: '「清理」具体指哪一种？', header: '清理范围',
        options: [
          { label: '删除空行', description: '只删连续空行' },
          { label: '统一格式', description: '字体字号按律所标准' },
          { label: '去掉批注', description: '' }
        ],
        multiSelect: true
      })
      backend.push(cid, 'bubble_end', { status: 'awaiting_input' })
    }
  })
  const original = globalThis.fetch
  globalThis.fetch = (url, options) => backend.fetch(String(url), options)
  try {
    await activateSession({ settings: SETTINGS, projectId: '31' })
    await send('帮我清理这份合同')
    await until(() => !streaming.value && lastOf('assistant') && lastOf('assistant').question, '问题卡就位')

    const a = lastOf('assistant')
    assert.equal(a.question.kind, 'ask_user')
    assert.equal(a.question.id, 'ask-q1')
    assert.equal(a.question.header, '清理范围', '事件没有覆盖标记拼出的那份')
    assert.equal(a.question.text, '「清理」具体指哪一种？')
    assert.deepEqual(a.question.options, ['删除空行', '统一格式', '去掉批注'])
    assert.deepEqual(a.question.descriptions, ['只删连续空行', '字体字号按律所标准', ''])
    assert.equal(a.question.multiSelect, true)
    assert.equal(a.question.answered, false)
    // 问题在卡片里，气泡正文里不重复；process 散文也没被兜底捞进来
    assert.equal(a.text, '')
    assert.equal(notice.value, t('awaitingAnswer'))

    await answerAskUser({ kind: 'ask_user', id: 'ask-q1', question: a.question.text, selected: ['删除空行', '统一格式'], other: '顺便删页眉' })

    assert.equal(backend.state.chats.length, 2)
    const body = backend.state.chats[1]
    const expected = formatAskUserAnswer(
      { id: 'ask-q1', question: '「清理」具体指哪一种？', selected: ['删除空行', '统一格式'], other: '顺便删页眉' },
      { english: getLang() === 'en' })
    assert.equal(body.message, expected.prompt)
    assert.ok(body.message.startsWith('<ask_user_answer id="ask-q1">'))
    assert.equal(body.displayText, ['删除空行', '统一格式', '顺便删页眉'].join(SEP))
    // 用户气泡显示的是 displayText，不是回答消息原文
    assert.equal(lastOf('user').text, body.displayText)
    // 卡片变只读并记下所选
    assert.equal(a.question.answered, true)
    assert.deepEqual(a.question.answer, { selected: ['删除空行', '统一格式'], other: '顺便删页眉' })
  } finally {
    await stop()
    globalThis.fetch = original
  }
})

test('旧 <question>（无 kind）：点选项发的仍是选项原文，不带 displayText', async () => {
  store.clear()
  let backend = null
  backend = makeBackend({
    onChat(body) {
      if (backend.state.chats.length !== 1) return
      backend.push(body.conversationId, 'text_delta', {
        content: '<final>好的。</final><question>删空行还是统一格式？<option>删空行</option><option>统一格式</option></question>'
      })
      backend.push(body.conversationId, 'bubble_end', { status: 'awaiting_input' })
    }
  })
  const original = globalThis.fetch
  globalThis.fetch = (url, options) => backend.fetch(String(url), options)
  try {
    await activateSession({ settings: SETTINGS, projectId: '32' })
    await send('清理一下')
    await until(() => !streaming.value && lastOf('assistant') && lastOf('assistant').question, '旧反问就位')
    const a = lastOf('assistant')
    assert.deepEqual(a.question, { options: ['删空行', '统一格式'], answered: false })
    assert.equal(a.text, '好的。删空行还是统一格式？')
    await send('删空行')
    const body = backend.state.chats[1]
    assert.equal(body.message, '删空行')
    assert.ok(!('displayText' in body))
  } finally {
    await stop()
    globalThis.fetch = original
  }
})

const ANSWER = formatAskUserAnswer({ id: 'ask-q1', question: '「清理」具体指哪一种？', selected: ['统一格式'], other: '另外改页码' })

test('历史回灌：答过的 ask_user 只读并高亮当时的选择，用户气泡显示 displayContent', async () => {
  store.clear()
  store.set(convKey(33), 'conv-hist')
  const backend = makeBackend({
    history: [
      { role: 'USER', content: '帮我清理这份合同' },
      { role: 'ASSISTANT', content: '<process><step>先问</step></process>' + MARKUP },
      { role: 'USER', content: ANSWER.prompt, displayContent: ANSWER.displayText },
      { role: 'ASSISTANT', content: '<final>好的，按统一格式处理。</final>' }
    ]
  })
  const original = globalThis.fetch
  globalThis.fetch = (url, options) => backend.fetch(String(url), options)
  try {
    await activateSession({ settings: SETTINGS, projectId: '33' })
    const q = messages.value[1].question
    assert.equal(q.kind, 'ask_user')
    assert.equal(q.answered, true)
    assert.deepEqual(q.answer, { selected: ['统一格式'], other: '另外改页码' })
    assert.deepEqual(q.descriptions, ['只删连续空行', '字体字号按律所标准', ''])
    assert.equal(messages.value[1].text, '')
    assert.equal(messages.value[2].text, ANSWER.displayText)
  } finally {
    await stop()
    globalThis.fetch = original
  }
})

test('历史回灌：旧后端没有 displayContent 时用户气泡不露 <ask_user_answer> 原文；末尾未答的一问仍可作答', async () => {
  store.clear()
  store.set(convKey(34), 'conv-hist2')
  const backend = makeBackend({
    history: [
      { role: 'USER', content: '清理' },
      { role: 'ASSISTANT', content: MARKUP },
      { role: 'USER', content: ANSWER.prompt },
      { role: 'ASSISTANT', content: MARKUP.replace('ask-q1', 'ask-q2') }
    ]
  })
  const original = globalThis.fetch
  globalThis.fetch = (url, options) => backend.fetch(String(url), options)
  try {
    await activateSession({ settings: SETTINGS, projectId: '34' })
    assert.equal(messages.value[2].text, ['统一格式', '另外改页码'].join(SEP))
    assert.ok(!messages.value[2].text.includes('ask_user_answer'))
    assert.equal(messages.value[1].question.answered, true)
    // 高亮靠的是回答消息原文（content），与有没有 displayContent 无关
    assert.deepEqual(messages.value[1].question.answer, { selected: ['统一格式'], other: '另外改页码' })
    const last = messages.value[3].question
    assert.equal(last.id, 'ask-q2')
    assert.equal(last.answered, false)
    assert.equal(last.answer, null)
  } finally {
    await stop()
    globalThis.fetch = original
  }
})
