// useAgentStream 的卸载收尾（审计 dev-board#74）。
//
// 病灶：composable 里的 SSE reader 循环、心跳 interval、待触发的重连定时器全都活在闭包里，
// 没有任何生命周期钩子回收。工作台的跳转按约定一律 reLaunch（CLAUDE.md），页面栈整个销毁，
// 于是流到一半离开工作台的那个实例变成僵尸：它仍会 scheduleReconnect，与新挂载的实例
// 轮流把对方从同一会话的 SSE 上挤下去（后端每会话只留一个 emitter）；模块级单例
// activeNetworkRecoveryHook 也还指着已销毁实例的闭包。
//
// 与本目录既有用例同口径，做源码级契约断言：useAgentStream.js 带 @/ 别名与 uni 全局，
// node 直接 import 不进来（见 api-contract.test.mjs 抬头的说明）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)

test('从 vue 引入 onUnmounted 与 getCurrentInstance', () => {
  const line = CODE.split('\n').find((l) => l.includes("from 'vue'"))
  assert.ok(line, "找不到 from 'vue' 的 import")
  assert.match(line, /onUnmounted/, '没有 onUnmounted 就没有回收时机')
  assert.match(line, /getCurrentInstance/, '组件外调用时不能盲注册钩子')
})

// 钩子体：从 onUnmounted( 到该块结束，用括号配平截取，避免把后面的代码算进来
function unmountBody(code) {
  const start = code.indexOf('onUnmounted(')
  if (start < 0) return null
  let depth = 0
  for (let i = start + 'onUnmounted'.length; i < code.length; i++) {
    if (code[i] === '(') depth++
    else if (code[i] === ')') { depth--; if (depth === 0) return code.slice(start, i + 1) }
  }
  return null
}

test('注册了 onUnmounted 清理，且挂在 getCurrentInstance 守卫之后', () => {
  const body = unmountBody(CODE)
  assert.ok(body, '没有 onUnmounted 清理块')
  const guard = CODE.indexOf('getCurrentInstance()')
  assert.ok(guard >= 0 && guard < CODE.indexOf('onUnmounted('),
    'onUnmounted 必须在 getCurrentInstance() 守卫之内注册')
})

test('清理块把重连定时器、心跳、SSE 连接一并收掉', () => {
  const body = unmountBody(CODE)
  assert.match(body, /clearTimeout\(reconnectTimer\)/, '待触发的重连定时器要清掉')
  assert.match(body, /reconnectTimer\s*=\s*null/)
  assert.match(body, /stopHeartbeatMonitor\(\)/, '10s 心跳 interval 要停')
  assert.match(body, /resetSSE\(\)/, 'reader 循环靠 abort 才能结束')
})

test('清理块先清 currentConversationId，断掉 scheduleReconnect 的续命链', () => {
  const body = unmountBody(CODE)
  assert.match(body, /currentConversationId\.value\s*=\s*null/,
    'scheduleReconnect/重连回调都以 currentConversationId 为准，不清就还会重连')
})

test('只在模块级单例仍指向本实例时才释放它', () => {
  const body = unmountBody(CODE)
  assert.match(body, /activeNetworkRecoveryHook\s*===\s*myNetworkRecoveryHook/,
    '无条件置空会踩掉后挂载实例的网络恢复入口')
  assert.match(body, /activeNetworkRecoveryHook\s*=\s*null/)
  // 比对的前提：connectSSE 里赋值时要留下本实例的那份引用
  assert.match(CODE, /myNetworkRecoveryHook\s*=\s*\(reason\)\s*=>/,
    'connectSSE 要先把回调存进实例级变量再挂到模块级单例上')
  assert.match(CODE, /activeNetworkRecoveryHook\s*=\s*myNetworkRecoveryHook/)
})
