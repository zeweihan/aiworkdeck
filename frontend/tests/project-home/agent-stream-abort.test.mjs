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
import { shouldShowUseInDocument } from '../../src/utils/useInDocumentVisibility.js'

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
    'cancelled 事件可能因为这条 SSE 也断了而永远不到，必须就地归零 thinking')
  assert.match(body, /thinking\.status\s*=\s*'done'/)
  assert.match(body, /thinking\.duration\s*=/, '不回填 duration 计时器停在错误读数')
})

test('abort() 的停止提示走 stopNotice 独立字段，不写 content', () => {
  const body = abortBody(CODE)
  assert.match(body, /stopNotice\s*=/, '停止提示要写 bubble.stopNotice')
  assert.ok(!/content\s*\+=/.test(body),
    '系统提示拼进 content 会触发 isReady/hasContent/「用到文档」chip 判定')
})

test('RootBubble 渲染 stopNotice，且「用到文档」chip 判据不认 stopNotice', () => {
  assert.match(BUBBLE, /bubble\.stopNotice/, 'RootBubble 要渲染停止提示条')
  // 判据在 dev-board#728 收窄并整体挪进 shouldShowUseInDocument（按需展示），
  // 这里改断行为而不是断模板字面量：#212 要守的是「只有停止提示、没有模型正文的
  // 那一回合不该长出可插入文档的操作项」，换成源码字符串比对只会在每次改模板时误报。
  assert.match(BUBBLE, /v-if="showUseInDocument"/,
    'message-actions 的可见性统一走 shouldShowUseInDocument')
  assert.equal(
    shouldShowUseInDocument({ content: '', isStreaming: false, stopNotice: '[正在停止] 已请求中断' }),
    false, '停止提示不是模型正文，不该让「用到文档」冒出来')
})

// 执行真正的 abort 函数体：挂起的网络请求不得阻挡本地收尾，失败也不能谎报已发送。
function executeAbort(fetchImpl, timers = {}) {
  const bubble = { isStreaming: true, thinking: { status: 'thinking', startTime: Date.now() - 1000 } }
  const isStreaming = { value: true }
  const aborted = []
  const build = new Function('currentConversationId', 'currentAssistantBubble', 'isStreaming',
    'messageAbortController', 'sseAbortController', 'finalizeProcesses', 't', 'fetch',
    'getApiBaseUrl', 'getSessionId', 'setTimeout', 'clearTimeout', 'AbortController',
    'STOP_CONFIRM_TIMEOUT_MS', 'pendingStopBubble', 'stopConfirmTimer',
    `${abortBody(CODE)}; return abort`)
  const abort = build({ value: 'conversation-old' }, { value: bubble }, isStreaming,
    { abort: () => aborted.push('message') }, { abort: () => aborted.push('sse') },
    () => {}, key => key, fetchImpl, () => '', () => '',
    timers.setTimeout || setTimeout, timers.clearTimeout || clearTimeout, AbortController,
    15000, null, null)
  return { bubble, isStreaming, aborted, promise: abort() }
}

/** 按超时毫秒分派的计时器桩：10000 是取消请求的超时，15000 是等 cancelled 事件的兜底。 */
function timerStub() {
  const fired = {}
  const cleared = []
  return {
    fired,
    cleared,
    setTimeout: (callback, ms) => { fired[ms] = callback; return 'timer-' + ms },
    clearTimeout: id => { if (id) cleared.push(id) }
  }
}

// 计划 K4 ⑥：abort 不再拆本地 SSE。当时先拆流是为了「断网时停止键不失效」，
// 而那件事现在由「先就地解锁本地状态、再发网络请求」解决；拆流的代价是后端随后
// 发出的 cancelled 事件永远到不了前端，于是「停止到底生效没有」前端永远不知道。
test('abort 不拆本地 SSE：后端的 cancelled 事件必须还能到得了前端', async () => {
  const timers = timerStub()
  const state = executeAbort(async () => ({ ok: true, status: 200, json: async () => ({ status: 'ok', cancelled: true }) }), timers)
  await state.promise
  assert.deepEqual(state.aborted, ['message'],
    '只放弃发送中的 POST /chat；拆掉 SSE 的话 cancelled 事件就永远收不到了')
})

test('取消网络请求还在等待时，本地流与思考计时已收尾；超时后明确未确认', async () => {
  const timers = timerStub()
  const state = executeAbort((_url, options) => new Promise((_resolve, reject) => {
    options.signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
  }), timers)
  assert.equal(state.isStreaming.value, false, '本地解锁不等网络')
  assert.equal(state.bubble.thinking.status, 'done')
  assert.equal(state.bubble.stopNotice, 'agentStream.stopPending')
  assert.equal(typeof timers.fired[10000], 'function', '取消请求要有 10 秒超时')
  timers.fired[10000]()
  await state.promise
  assert.equal(state.bubble.stopNotice, 'agentStream.stopUnconfirmed')
  assert.ok(timers.cleared.includes('timer-10000'), '超时计时器要清掉')
})

test('取消被服务端拒绝时不显示已发送；打中活跃轮次后口径停在「正在停止」', async () => {
  const rejected = executeAbort(async () => ({ ok: false, status: 403 }), timerStub())
  await rejected.promise
  assert.equal(rejected.bubble.stopNotice, 'agentStream.stopUnconfirmed')

  // 计划 K4 ⑦：供应商那头会不会继续计费我们承诺不了，收到 cancelled 之前一律只说「正在停止」
  const timers = timerStub()
  const accepted = executeAbort(
    async () => ({ ok: true, status: 200, json: async () => ({ status: 'ok', cancelled: true }) }), timers)
  await accepted.promise
  assert.equal(accepted.bubble.stopNotice, 'agentStream.stopPending',
    '还没收到 cancelled 事件就说「已停止」是在替后端打包票')
  assert.equal(typeof timers.fired[15000], 'function', '等 cancelled 事件要有兜底时限')
  timers.fired[15000]()
  assert.equal(accepted.bubble.stopNotice, 'agentStream.stopRequested',
    '等不到回执时如实说「指令发了、没收到确认」')
})

// 计划 K4 ⑤ / 审计 D-10：没打中任何活跃轮次时不能照样写「已发送停止指令」，
// 那会把「停止根本没生效」这类真实失效一起掩盖掉。
test('/cancel 回 cancelled=false 时换成「该轮次已经结束」', async () => {
  const timers = timerStub()
  const state = executeAbort(
    async () => ({ ok: true, status: 200, json: async () => ({ status: 'ok', cancelled: false }) }), timers)
  await state.promise
  assert.equal(state.bubble.stopNotice, 'agentStream.stopAlreadyFinished')
  assert.equal(timers.fired[15000], undefined, '什么都没停，不必等 cancelled 事件')
})

// 旧后端不带 cancelled 字段（以及响应体读不出来时）：按「打中了」处理，行为与改造前一致。
test('响应体没有 cancelled 字段时按打中处理，不回退成未确认', async () => {
  const timers = timerStub()
  const state = executeAbort(
    async () => ({ ok: true, status: 200, json: async () => ({ status: 'ok' }) }), timers)
  await state.promise
  assert.equal(state.bubble.stopNotice, 'agentStream.stopPending')

  const broken = timerStub()
  const unreadable = executeAbort(
    async () => ({ ok: true, status: 200, json: async () => { throw new Error('not json') } }), broken)
  await unreadable.promise
  assert.equal(unreadable.bubble.stopNotice, 'agentStream.stopPending',
    '响应体读不出来不改变结论：停止请求本身是成功的')
})

// 两条 cancelled 分支都要把「正在停止…」落成终态文案，缺一条就会一直停在「正在停止」。
test('cancelled 事件的两条分支都调 noteStopConfirmed', () => {
  assert.match(CODE, /evt === 'cancelled'\) noteStopConfirmed\(\)/,
    '气泡指针为 null 的兜底分支也要认这条事件')
  const cancelledBranch = CODE.slice(CODE.indexOf("} else if (evt === 'cancelled') {"))
  assert.match(cancelledBranch.slice(0, 900), /noteStopConfirmed\(\)/,
    '正常分支要把 stopNotice 落成「已停止生成」')
})
