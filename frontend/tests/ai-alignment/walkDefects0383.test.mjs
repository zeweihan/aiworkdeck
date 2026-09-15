// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.38.3 真渲染走查查出的前端缺陷（PR#798 分层记忆 / 执行中插话 / 任务队列）。
//
// useAgentStream.js 带 @/ 别名与 uni 全局，node 直接 import 不进来，与 tests/project-home
// 下既有用例同口径做源码级契约断言；真渲染复走在 v0.38.3 走查证据目录里。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (p) => readFileSync(new URL(p, import.meta.url), 'utf8')
const stripComments = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const STREAM = stripComments(read('../../src/composables/useAgentStream.js'))
const INBOX = read('../../src/components/AgentInbox.vue')
const MEMORY = read('../../src/components/MemoryBrowser.vue')

// 从 `const name = ... => {` 起按花括号配平截取函数体
function fnBody(code, name) {
  const start = code.search(new RegExp(`const ${name} = (async )?\\([^)]*\\) => \\{`))
  if (start < 0) return null
  let depth = 0
  for (let i = code.indexOf('{', start); i < code.length; i++) {
    if (code[i] === '{') depth++
    else if (code[i] === '}') { depth--; if (depth === 0) return code.slice(start, i + 1) }
  }
  return null
}

const KEYWORDS = new Set(['if', 'for', 'while', 'switch', 'catch', 'return', 'typeof', 'function', 'new'])
const GLOBALS = new Set(['JSON', 'Array', 'Object', 'String', 'Number', 'Date', 'Math', 'Boolean', 'Promise', 'setTimeout', 'clearTimeout'])
const isDeclared = (code, name) =>
  new RegExp(`(?:const|let|var|function)\\s+${name}\\b`).test(code)
  || new RegExp(`\\{[^}]*\\b${name}\\b[^}]*\\}\\s*(?:=|from)`).test(code)

test('D6: state_recovery 处理器里调用的每个本地函数都真实存在（parseTags 未定义曾抛 ReferenceError）', () => {
  const body = fnBody(STREAM, 'handleStateRecovery')
  assert.ok(body, '找不到 handleStateRecovery')
  const calls = [...body.matchAll(/(?<![.\w$])([A-Za-z_$][\w$]*)\s*\(/g)].map((m) => m[1])
  const undefinedCalls = [...new Set(calls)]
    .filter((name) => !KEYWORDS.has(name) && !GLOBALS.has(name) && !isDeclared(STREAM, name))
  assert.deepEqual(undefinedCalls, [], `handleStateRecovery 调用了未声明的函数：${undefinedCalls.join(', ')}`)
  assert.match(body, /processTextStream\(/, '快照要走与 text_delta 同一个解析入口 processTextStream')
})

test('D3: 「立即发送」恢复执行前，本地没有 SSE 就先重连，再发 PATCH', () => {
  const body = fnBody(STREAM, 'updateInbox')
  assert.ok(body, '找不到 updateInbox')
  const connectAt = body.search(/await connectSSE\(conversationId\)/)
  const patchAt = body.indexOf('updateAgentInboxItem(')
  assert.ok(connectAt >= 0, '恢复执行的补丁（submissionMode=steer）必须确保本地 SSE 在线：停止时本地流已断开，'
    + '不重连的话后端跑完一整轮前端都收不到任何事件')
  assert.ok(connectAt < patchAt, '必须先连上再发 PATCH：后端恢复的那一轮的 input_applied 可能在 PATCH 返回前就发出，'
    + '连晚了就丢了（前端不带 Last-Event-ID，不会补发）')
  assert.match(body.slice(0, connectAt), /submissionMode\s*===\s*'steer'/, '只有让队列恢复执行的补丁才需要重连')
  assert.match(body.slice(0, connectAt), /isConnected\.value/, '已经在线时不许重复建连')
})

test('D4: 队列面板声明 data-awd-keep-clear，反馈浮钮要避开它', () => {
  const root = INBOX.match(/<template>(?:\s|<!--[\s\S]*?-->)*(<view\b[^>]*>)/)
  assert.ok(root, '找不到 AgentInbox 根节点')
  assert.match(root[1], /class="agent-inbox"/)
  assert.match(root[1], /data-awd-keep-clear/, '队列在输入卡外面，输入卡的声明管不到它')
})

test('D2: #798 新增的每个 textarea/input 都显式声明 maxlength（uni-h5 缺省 140 字）', () => {
  for (const [name, src] of [['AgentInbox.vue', INBOX], ['MemoryBrowser.vue', MEMORY]]) {
    const tags = src.match(/<(?:textarea|input)\b[^>]*>/g) || []
    assert.ok(tags.length > 0, `${name} 里应有输入控件`)
    for (const tag of tags) {
      assert.match(tag, /:?maxlength=/, `${name} 的 ${tag} 没声明 maxlength，uni-h5 会按 140 截断输入`)
    }
  }
})

// 真按键 e2e 抓到的同族缺陷：队列行内编辑打完字立刻点保存，v-model 还是旧值，改动整个丢掉
function inboxVm(liveValue) {
  const script = INBOX.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/export\s+default/, 'return')
  const options = new Function(script)()
  const emitted = []
  const vm = Object.assign({ $emit: (name, payload) => emitted.push([name, payload]) }, options.data())
  for (const [name, method] of Object.entries(options.methods)) vm[name] = method.bind(vm)
  vm.$el = { querySelector: () => (liveValue == null ? null : { value: liveValue }) }
  return { vm, emitted }
}

test('D5: queue row Save right after typing emits what the input shows, not the throttled v-model value', () => {
  const { vm, emitted } = inboxVm('queued follow up edited')
  const item = { id: 'm1', message: 'queued follow up' }
  vm.beginEdit(item)
  vm.saveEdit(item)
  assert.deepEqual(emitted, [['edit', { item, message: 'queued follow up edited' }]])
})

test('D5: queue row confirm event value wins over a stale v-model value', () => {
  const { vm, emitted } = inboxVm(null)
  const item = { id: 'm1', message: 'queued follow up' }
  vm.beginEdit(item)
  vm.saveEdit(item, { detail: { value: 'queued follow up via enter' } })
  assert.deepEqual(emitted, [['edit', { item, message: 'queued follow up via enter' }]])
})
