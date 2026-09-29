// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1061：思考超过 60 秒时 ThinkingCard 给副文案与「停止」按钮。
//
// 三句话分开：还没收到任何增量（只有保活）= 服务商排队中；后端已换一家供应商重发、仍没字 =
// 已换一家重试；已在收思考/正文 = 模型仍在推理。判据来自 useAgentStream 写在 bubble.thinking 上的
// receivedDelta / providerRetried，停止复用 ChatInterface 现有的 handleAbort（不新造取消逻辑）。
//
// ThinkingCard 用真实 Vue 响应式跑（与 thinking-card-ghost-collapse 同一套挂载法），把 liveSeconds
// 直接拨到 61，不真等一分钟；接线与 useAgentStream 的两处写入做源码级断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, watch, computed, onMounted, onUnmounted } from 'vue'

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8')
const CARD = read('../../src/components/AgentMessage/ThinkingCard.vue')
const ROOT = read('../../src/components/AgentMessage/RootBubble.vue')
const CHAT_UI = read('../../src/components/ChatInterface.vue')
const STREAM = read('../../src/composables/useAgentStream.js')
const ZH = read('../../src/locales/zh-CN/chat.js')
const EN = read('../../src/locales/en-US/chat.js')

const realSetInterval = globalThis.setInterval
globalThis.setInterval = (fn, ms, ...args) => {
  const t = realSetInterval(fn, ms, ...args)
  if (t && typeof t.unref === 'function') t.unref()
  return t
}

function mount(props) {
  const body = CARD.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const factory = new Function(
    'ref', 'watch', 'computed', 'onMounted', 'onUnmounted', 't', 'defineProps', 'defineEmits', 'defineExpose',
    body + '\nreturn { liveSeconds, longWaitKey }')
  return factory(ref, watch, computed, onMounted, onUnmounted, (k) => k, () => props, () => () => {}, () => {})
}

const base = { status: 'thinking', variant: 'ghost', duration: 0, content: '', startTime: Date.now() }

test('不到 60 秒不出副文案', () => {
  const c = mount({ ...base })
  c.liveSeconds.value = 59
  assert.equal(c.longWaitKey.value, '')
})

test('61 秒且只有保活：服务商排队中', () => {
  const c = mount({ ...base, receivedDelta: false, providerRetried: false })
  c.liveSeconds.value = 61
  assert.equal(c.longWaitKey.value, 'chat.thinkingQueued')
})

test('61 秒且已在收思考：模型仍在推理', () => {
  const c = mount({ ...base, receivedDelta: true, providerRetried: false })
  c.liveSeconds.value = 61
  assert.equal(c.longWaitKey.value, 'chat.thinkingLong')
})

test('61 秒、已换一家但还没字：已换一家供应商重试；来字之后改说仍在推理', () => {
  assert.equal((() => { const c = mount({ ...base, providerRetried: true }); c.liveSeconds.value = 61; return c.longWaitKey.value })(),
    'chat.thinkingRequeued')
  assert.equal((() => { const c = mount({ ...base, providerRetried: true, receivedDelta: true }); c.liveSeconds.value = 61; return c.longWaitKey.value })(),
    'chat.thinkingLong')
})

test('思考结束或内联变体不出副文案', () => {
  const done = mount({ ...base, status: 'done' })
  done.liveSeconds.value = 120
  assert.equal(done.longWaitKey.value, '')
  const inline = mount({ ...base, variant: 'inline' })
  inline.liveSeconds.value = 120
  assert.equal(inline.longWaitKey.value, '')
})

test('停止按钮只在 stoppable 时渲染，点击发 stop；RootBubble → ChatInterface 接到 handleAbort', () => {
  assert.match(CARD, /<button v-if="stoppable"[^>]*@click\.stop="emit\('stop'\)"/)
  assert.match(CARD, /defineEmits\(\['stop'\]\)/)
  assert.match(ROOT, /:stoppable="!!\(isLatest && bubble\.isStreaming\)"/)
  assert.match(ROOT, /@stop="\$emit\('stop-generation'\)"/)
  assert.match(ROOT, /defineEmits\(\[[^\]]*'stop-generation'[^\]]*\]\)/)
  assert.match(CHAT_UI, /@stop-generation="handleAbort"/)
})

test('useAgentStream：receivedDelta 随思考与正文置真，provider_retry 在气泡守卫之前处理', () => {
  assert.match(STREAM, /thinking: \{[^}]*receivedDelta: false, providerRetried: false \}/)
  const append = STREAM.slice(STREAM.indexOf('const appendReasoning = (text) => {'))
  assert.match(append.slice(0, 300), /bubble\.thinking\.receivedDelta = true/)
  const textDelta = STREAM.slice(STREAM.indexOf("} else if (evt === 'text_delta') {"))
  assert.match(textDelta.slice(0, 300), /thinking\.receivedDelta = true/)
  const retryAt = STREAM.indexOf("if (evt === 'provider_retry')")
  const guardAt = STREAM.indexOf('if (!currentAssistantBubble.value) {')
  assert.ok(retryAt > 0 && retryAt < guardAt, 'provider_retry 必须在气泡守卫之前')
  assert.match(STREAM.slice(retryAt, retryAt + 200), /b\.thinking\.providerRetried = true/)
})

test('三句文案两套语言都有', () => {
  for (const key of ['thinkingQueued', 'thinkingLong', 'thinkingRequeued']) {
    assert.match(ZH, new RegExp(`${key}: '`), `zh-CN 缺 ${key}`)
    assert.match(EN, new RegExp(`${key}: '`), `en-US 缺 ${key}`)
  }
  assert.match(ZH, /thinkingQueued: '服务商排队中，可以停止后换个模型再试'/)
  assert.match(ZH, /thinkingLong: '模型仍在推理，可以停止后换个模型'/)
})
