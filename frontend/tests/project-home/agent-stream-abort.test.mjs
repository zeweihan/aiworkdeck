// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// abort() 的停止收尾（dev-board#211/#212）。
//
// 病灶：abort() 在掐断本地 SSE 之后，后端的 cancelled 事件永远到不了前端，
// 正常收尾路径里的 thinking 归零不会再被执行——顶层 thinking.status 卡在
// 'thinking'，「思考中… N 秒」计时器永远读秒（#211）。同时 abort() 把
// 「[正在停止]」提示拼进 bubble.content，让空产出的回合被当成有正文，
// 长出「用到文档」操作 chip（#212）。
//
// 与本目录既有用例同口径：useAgentStream.js 带 @/ 别名与 uni 全局，node
// 直接 import 不进来，做源码级契约断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const BUBBLE = readFileSync(new URL('../../src/components/AgentMessage/RootBubble.vue', import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

// abort 函数体：从 const abort 起用花括号配平截取
function abortBody(code) {
  const start = code.indexOf('const abort = async () => {')
  if (start < 0) return null
  let depth = 0
  for (let i = start; i < code.length; i++) {
    if (code[i] === '{') depth++
    else if (code[i] === '}') { depth--; if (depth === 0) return code.slice(start, i + 1) }
  }
  return null
}

test('abort() 里有顶层 thinking 归零（status 置 done、回填 duration）', () => {
  const body = abortBody(CODE)
  assert.ok(body, '找不到 abort 函数体')
  assert.match(body, /thinking\.status\s*===\s*'thinking'/,
    'abort 后本地 SSE 已断，cancelled 事件到不了，必须就地归零 thinking')
  assert.match(body, /thinking\.status\s*=\s*'done'/)
  assert.match(body, /thinking\.duration\s*=/, '不回填 duration 计时器停在错误读数')
})

test('abort() 的停止提示走 stopNotice 独立字段，不写 content', () => {
  const body = abortBody(CODE)
  assert.match(body, /stopNotice\s*=/, '停止提示要写 bubble.stopNotice')
  assert.ok(!/content\s*\+=/.test(body),
    '系统提示拼进 content 会触发 isReady/hasContent/「用到文档」chip 判定')
})

test('RootBubble 渲染 stopNotice，且「用到文档」chip 判据仍只认 content', () => {
  assert.match(BUBBLE, /bubble\.stopNotice/, 'RootBubble 要渲染停止提示条')
  assert.match(BUBBLE, /v-if="bubble\.content && !bubble\.isStreaming"/,
    'message-actions 的判据是 content 非空且流已结束，别把 stopNotice 算进去')
})

// 执行真正的 abort 函数体：挂起的网络请求不得阻挡本地收尾，失败也不能谎报已发送。
function executeAbort(fetchImpl, timers = {}) {
  const bubble = { isStreaming: true, thinking: { status: 'thinking', startTime: Date.now() - 1000 } }
  const isStreaming = { value: true }
  const aborted = []
  const build = new Function('currentConversationId', 'currentAssistantBubble', 'isStreaming',
    'messageAbortController', 'sseAbortController', 'finalizeProcesses', 't', 'fetch',
    'getApiBaseUrl', 'getSessionId', 'setTimeout', 'clearTimeout', 'AbortController',
    `${abortBody(CODE)}; return abort`)
  const abort = build({ value: 'conversation-old' }, { value: bubble }, isStreaming,
    { abort: () => aborted.push('message') }, { abort: () => aborted.push('sse') },
    () => {}, key => key, fetchImpl, () => '', () => '',
    timers.setTimeout || setTimeout, timers.clearTimeout || clearTimeout, AbortController)
  return { bubble, isStreaming, aborted, promise: abort() }
}

test('取消网络请求还在等待时，本地流与思考计时已收尾；超时后明确未确认', async () => {
  let expire
  let timerCleared = false
  const state = executeAbort((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  }), {
    setTimeout: (callback, ms) => { assert.equal(ms, 10000); expire = callback; return 17 },
    clearTimeout: id => { assert.equal(id, 17); timerCleared = true }
  })
  assert.equal(state.isStreaming.value, false)
  assert.deepEqual(state.aborted, ['message', 'sse'])
  assert.equal(state.bubble.thinking.status, 'done')
  assert.equal(state.bubble.stopNotice, 'agentStream.stopPending')
  expire()
  await state.promise
  assert.equal(state.bubble.stopNotice, 'agentStream.stopUnconfirmed')
  assert.equal(timerCleared, true)
})

test('取消被服务端拒绝时不显示已发送；成功返回后才确认停止指令', async () => {
  const rejected = executeAbort(async () => ({ ok: false, status: 403 }))
  await rejected.promise
  assert.equal(rejected.bubble.stopNotice, 'agentStream.stopUnconfirmed')
  const accepted = executeAbort(async () => ({ ok: true, status: 200 }))
  await accepted.promise
  assert.equal(accepted.bubble.stopNotice, 'agentStream.stopRequested')
})
