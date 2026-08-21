// 审计（dev-board#74）MEDIUM：幽灵 ThinkingCard 永不自动折叠。
//
// 病灶：RootBubble.vue 把 ThinkingCard 的 card/ghost 两个变体渲染在结构不同的
// v-if/v-else 分支里（不是同一个组件切 prop，是"卸载旧的、挂载全新一个"）。
// isReady 一旦从 false 翻真，就从 card 分支切到 ghost 分支——挂载那一刻如果
// bubble.thinking.status 已经是 'done'（很常见：标题/正文往往紧跟在思考结束
// 后出现），ThinkingCard 内部"自动折叠"的 watch(() => props.status, ...) 没有
// immediate:true 就一次都不会跑——isExpanded 停在 ref(true) 的默认值，ghost 卡
// 永远摊开显示模型原始思维链，而不是折成一行"Thought for Ns"。
//
// 修法：watch 加 immediate:true。**附带修复**：immediate 回调在 watch() 这条
// 语句执行的当下就同步跑，而 startTimer/stopTimer 原来声明在 watch(...) 之后——
// 单纯加 immediate:true 会让回调在这两个 const 完成初始化之前就去引用它们，踩中
// 暂时性死区抛 ReferenceError（用 @vue/server-renderer 真实挂载复现过，不是
// 空想的边界情况）。已把这两个函数（连同它们依赖的 updateTime）挪到 watch 之前，
// 纯移动顺序、函数体一字未改。
//
// ThinkingCard.vue 是 <script setup>，逻辑只依赖 ref/watch/computed/onMounted/
// onUnmounted（真实 'vue' export，在非组件上下文里 ref/watch/computed 正常工作，
// onMounted/onUnmounted 只是 warn+no-op，不会抛异常）与 defineProps（编译宏，
// 桩成"忽略 schema、直接返回外部传入的 props"）。用真实 Vue 响应式系统跑，直接
// 验证"挂载时 status 已经是 done，isExpanded 是不是立刻为 false"这个行为本身。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, watch, computed, onMounted, onUnmounted, nextTick } from 'vue'

const SRC = readFileSync(
  new URL('../../src/components/AgentMessage/ThinkingCard.vue', import.meta.url), 'utf8')

// startTimer 会真的 setInterval(updateTime, 1000)——不 unref 的话，任何一个
// status:'thinking' 的用例都会让 node --test 进程挂着退不出去（本条缺陷本身
// 与这个计时器无关，纯粹是让测试进程能正常收尾）。
const realSetInterval = globalThis.setInterval
globalThis.setInterval = (fn, ms, ...args) => {
  const t = realSetInterval(fn, ms, ...args)
  if (t && typeof t.unref === 'function') t.unref()
  return t
}

function extractScriptSetupBody() {
  return SRC.match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
}

function mountThinkingCard(props) {
  const body = extractScriptSetupBody()
  const factory = new Function(
    'ref', 'watch', 'computed', 'onMounted', 'onUnmounted', 't', 'defineProps',
    body + '\nreturn { isExpanded, toggle }')
  return factory(ref, watch, computed, onMounted, onUnmounted, (k) => k, () => props)
}

test('CRITICAL 行为：挂载时 status 已经是 done（ghost 变体重挂载的常见场景），isExpanded 必须立刻是 false', () => {
  const { isExpanded } = mountThinkingCard({ status: 'done', duration: 12.3, content: 'reasoning...', startTime: 0 })
  assert.equal(isExpanded.value, false,
    '没有 immediate:true 的话这里会停在 ref(true) 的默认值——卡片永远摊开显示')
})

test('挂载时仍在 thinking，保持展开（回归保护）', () => {
  const { isExpanded } = mountThinkingCard({ status: 'thinking', duration: 0, content: '', startTime: Date.now() })
  assert.equal(isExpanded.value, true)
})

test('挂载时是 idle（初始占位态），不触发任何一支分支，保持默认展开（回归保护）', () => {
  const { isExpanded } = mountThinkingCard({ status: 'idle', duration: 0, content: '', startTime: 0 })
  assert.equal(isExpanded.value, true, 'idle 分支没有对应的 if/else-if，isExpanded 应保持默认值')
})

test('挂载后 status 从 thinking 变为 done，照常触发折叠（回归保护，非 immediate 场景不受影响）', async () => {
  // watch 需要一个真实的、值会变的响应式 source 才能在"挂载后"这个时间点触发；
  // 用一个 ref 包一层模拟 Vue 组件在 props.status 变化时重新求值的效果。
  const statusRef = ref('thinking')
  const body = extractScriptSetupBody()
  const factory = new Function(
    'ref', 'watch', 'computed', 'onMounted', 'onUnmounted', 't', 'defineProps',
    body + '\nreturn { isExpanded }')
  const { isExpanded } = factory(ref, watch, computed, onMounted, onUnmounted, (k) => k,
    () => ({ get status() { return statusRef.value }, duration: 0, content: '', startTime: Date.now() }))
  assert.equal(isExpanded.value, true)
  statusRef.value = 'done'
  // watch 默认 flush:'pre'，回调不是同步触发的——要等一轮 Vue 的调度队列
  // flush 完才看得到效果，同真实组件里 props 变化到 watcher 回调之间的关系一致。
  await nextTick()
  assert.equal(isExpanded.value, false, '非 immediate 场景（挂载后的真实状态变化）本来就能正常折叠，本条修复不能破坏它')
})

test('toggle() 手动开合仍然可用（回归保护）', () => {
  const { isExpanded, toggle } = mountThinkingCard({ status: 'done', duration: 1, content: 'x', startTime: 0 })
  assert.equal(isExpanded.value, false)
  toggle()
  assert.equal(isExpanded.value, true)
  toggle()
  assert.equal(isExpanded.value, false)
})
