// dev-board#364：思考型模型（Kimi K3 等）思考几百秒期间前端零提示。
//
// 三条契约：
//  1. 等待首 token 的活性计时——ThinkingCard 从「发送」那一刻起读秒（startTime 由
//     useAgentStream 在 sendMessage 里写入），文案走 chat.thinkingLive 两套 locale；
//  2. reasoning_delta 事件实时进思考卡，不过标签解析器；
//  3. 心跳超时/流断了要给用户看得见的提示（linkStatus → chat.linkReconnecting），
//     且前端判死阈值与后端心跳间隔（15s）保持 3 倍关系。
//
// useAgentStream.js 带 @/ 别名与 uni 全局，node 直接 import 不进来，那一侧做源码级
// 契约断言；ThinkingCard 的读秒用真实 Vue 响应式跑（与 thinking-card-ghost-collapse
// 同一套挂载法）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8')
const STREAM = read('../../src/composables/useAgentStream.js')
const CHAT_UI = read('../../src/components/ChatInterface.vue')
const CARD = read('../../src/components/AgentMessage/ThinkingCard.vue')
const ZH = read('../../src/locales/zh-CN/chat.js')
const EN = read('../../src/locales/en-US/chat.js')
const SSE_JAVA = read('../../../backend/src/main/java/com/checkba/service/ai/SseEmitterService.java')

const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(STREAM)

// 与 thinking-card-ghost-collapse.test.mjs 同款：真实 setInterval 会让进程退不出去
const realSetInterval = globalThis.setInterval
globalThis.setInterval = (fn, ms, ...args) => {
  const t = realSetInterval(fn, ms, ...args)
  if (t && typeof t.unref === 'function') t.unref()
  return t
}

function mountThinkingCard(props) {
  const body = CARD.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const factory = new Function(
    'ref', 'watch', 'computed', 'onMounted', 'onUnmounted', 't', 'defineProps',
    body + '\nreturn { liveSeconds, displayDuration, isExpanded }')
  return factory(ref, watch, computed, onMounted, onUnmounted, (k, p) => `${k}:${JSON.stringify(p)}`, () => props)
}

// 从函数体的 `{`（箭头函数取 `=> {` 之后那个，避免把参数解构的花括号当函数体）配平截取
function fnBody(code, header) {
  const start = code.indexOf(header)
  if (start < 0) return null
  const arrow = code.indexOf('=> {', start)
  const open = header.endsWith('{') ? start + header.length - 1
    : (arrow >= 0 && arrow - start < 400 ? arrow + 3 : code.indexOf('{', start))
  let depth = 0
  for (let i = open; i < code.length; i++) {
    if (code[i] === '{') depth++
    else if (code[i] === '}') { depth--; if (depth === 0) return code.slice(start, i + 1) }
  }
  return null
}

// ---------- 1. 等待首 token 的活性计时 ----------

test('sendMessage 一发出就把助手气泡置为 thinking 并记 startTime（计时从发送起算，不等首 token）', () => {
  const body = fnBody(CODE, 'const sendMessage = async')
  assert.ok(body, '找不到 sendMessage')
  assert.match(body, /newBubble\.thinking\.status\s*=\s*'thinking'/)
  assert.match(body, /newBubble\.thinking\.startTime\s*=\s*Date\.now\(\)/)
})

test('ThinkingCard 在 thinking 态按 startTime 实时读秒：发送 5 秒后显示 5', () => {
  const { liveSeconds, displayDuration } = mountThinkingCard({
    status: 'thinking', duration: 0, content: '', startTime: Date.now() - 5000,
  })
  assert.equal(liveSeconds.value, 5, '挂载即按 startTime 算出已等待秒数，而不是从 0 起')
  assert.equal(displayDuration.value, 'chat.secondsUnit:{"n":5}')
})

test('ThinkingCard 模板在 thinking 态渲染 chat.thinkingLive，两套 locale 都带 {seconds} 占位', () => {
  assert.match(CARD, /status === 'thinking'">\{\{ \$t\('chat\.thinkingLive', \{ seconds: liveSeconds \}\) \}\}/)
  for (const [name, src] of [['zh-CN', ZH], ['en-US', EN]]) {
    const m = src.match(/thinkingLive:\s*'([^']*)'/)
    assert.ok(m, `${name} 缺 chat.thinkingLive`)
    assert.ok(m[1].includes('{seconds}'), `${name} 的 thinkingLive 必须带 {seconds}，否则计时器有值没处显示`)
  }
})

test('RootBubble 在还没有任何产出时渲染 ghost 态的 ThinkingCard（计时器有落点）', () => {
  const root = read('../../src/components/AgentMessage/RootBubble.vue')
  assert.match(root, /v-if="!isReady && bubble\.thinking\.status === 'thinking'"/)
})

// ---------- 2. reasoning_delta 实时进思考卡 ----------

test('handleEvent 认 reasoning_delta，走 appendReasoning 而不是 processTextStream', () => {
  const branch = CODE.indexOf("evt === 'reasoning_delta'")
  assert.ok(branch > 0, '缺 reasoning_delta 分支：后端已经把思考增量转发过来了，前端不认等于白转')
  const seg = CODE.slice(branch, CODE.indexOf("evt === 'text_delta'", branch))
  assert.match(seg, /appendReasoning\(/)
  assert.ok(!/processTextStream\(/.test(seg), '思考文本不许过标签解析器：里面的 <final> 字样只是模型自言自语')
})

test('appendReasoning：无过程卡时写顶层思考卡并置 thinking；有过程卡时挂到最后一个过程卡', () => {
  const body = fnBody(CODE, 'const appendReasoning = (text) =>')
  assert.ok(body, '找不到 appendReasoning')
  assert.match(body, /bubble\.thinking\.content\s*\+=\s*text/)
  assert.match(body, /bubble\.thinking\.status\s*=\s*'thinking'/)
  assert.match(body, /lastProc\.items\.push\(\{\s*type:\s*'thinking'/)
})

// ---------- 3. 断连提示与心跳契约 ----------

test('scheduleReconnect 写 linkStatus=reconnecting（含次数），建连成功与 resetSSE 回到 live', () => {
  const sched = fnBody(CODE, 'const scheduleReconnect = (reason) =>')
  assert.ok(sched)
  assert.match(sched, /linkStatus\.value\s*=\s*\{\s*state:\s*'reconnecting',\s*attempt:\s*reconnectAttempts\s*\}/)
  const liveWrites = CODE.match(/linkStatus\.value\s*=\s*\{\s*state:\s*'live'/g) || []
  assert.ok(liveWrites.length >= 2, '建连成功与 resetSSE 两处都要回 live，否则提示条会挂死')
  assert.match(fnBody(CODE, 'return {'), /linkStatus,/, 'linkStatus 要从 composable 导出')
})

test('ChatInterface 在 reconnecting 态渲染提示条，文案走 chat.linkReconnecting 两套 locale', () => {
  assert.match(CHAT_UI, /v-if="linkStatus && linkStatus\.state === 'reconnecting'"/)
  assert.match(CHAT_UI, /\$t\('chat\.linkReconnecting', \{ attempt: linkStatus\.attempt \}\)/)
  for (const [name, src] of [['zh-CN', ZH], ['en-US', EN]]) {
    const m = src.match(/linkReconnecting:\s*'([^']*)'/)
    assert.ok(m, `${name} 缺 chat.linkReconnecting`)
    assert.ok(m[1].includes('{attempt}'), `${name} 的 linkReconnecting 要带 {attempt}`)
  }
})

test('前端判死阈值 = 后端心跳间隔的 3 倍（15s × 3 = 45000ms）', () => {
  const stale = Number((CODE.match(/HEARTBEAT_STALE_MS\s*=\s*(\d+)/) || [])[1])
  const interval = Number((SSE_JAVA.match(/HEARTBEAT_INTERVAL_SECONDS\s*=\s*(\d+)/) || [])[1])
  assert.equal(interval, 15)
  assert.equal(stale, interval * 3 * 1000, '两边任一改动都要同步，否则不是误判死连接就是死了半天不报')
})
