// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#748：「有据续写」的唯一入口搬进 Writer 自建工具栏，画布右上角那颗
// 固定按钮撤掉。这里锁宿主这一半：客体上报 → 按钮显隐/按下态，点击 → 开合指令。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/editor.js'
import en from '../../src/locales/en-US/editor.js'

const EDITOR = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const TOOLBAR = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')

function makeVm() {
  const body = EDITOR.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')
  const options = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar',
    'createAuthorNameResolver', 'getCurrentUser', 'fetchAuthUser', body)(
    null, null, null, () => ({ current: () => '' }), () => null, async () => null)
  const vm = { $emit() {}, appendLog() {} }
  Object.assign(vm, options.data.call(vm))
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  return vm
}

function wire() {
  const vm = makeVm()
  const sent = []
  let listener = null
  vm.subscribeHostEvents({ send: (m) => sent.push(m), subscribe: (l) => { listener = l; return () => {} } })
  return { vm, sent, emit: (m) => listener(m) }
}

test('客体上报的可用状态与开合状态驱动宿主的按钮', () => {
  const { vm, emit } = wire()
  assert.equal(vm.semanticWritingAvailable, false, '没收到上报之前按钮不出现')
  assert.equal(vm.semanticWritingOpen, false)
  emit({ __lo: 'lo-relay', type: 'semantic-writing-state', available: true, open: false })
  assert.equal(vm.semanticWritingAvailable, true)
  assert.equal(vm.semanticWritingOpen, false)
  emit({ __lo: 'lo-relay', type: 'semantic-writing-state', available: true, open: true })
  assert.equal(vm.semanticWritingOpen, true, '客体里按「关闭」/宿主点按钮都靠这条回报同步')
  emit({ __lo: 'lo-relay', type: 'semantic-writing-state', available: false, open: false })
  assert.equal(vm.semanticWritingAvailable, false)
  assert.equal(vm.semanticWritingOpen, false)
})

test('不带 lo-relay 标记的消息一个字段都不认', () => {
  const { vm, emit } = wire()
  emit({ type: 'semantic-writing-state', available: true, open: true })
  assert.equal(vm.semanticWritingAvailable, false)
})

test('点工具栏只发开合指令，按下态等客体回报（不本地乐观翻）', () => {
  const { vm, sent } = wire()
  vm.toggleSemanticWriting()
  assert.deepEqual(sent.at(-1), { __lo: 'lo-relay', type: 'semantic-writing-panel', open: true })
  assert.equal(vm.semanticWritingOpen, false)
  vm.semanticWritingOpen = true
  vm.toggleSemanticWriting()
  assert.deepEqual(sent.at(-1), { __lo: 'lo-relay', type: 'semantic-writing-panel', open: false })
})

test('换文档/重载时宿主先把入口收回去，等客体重新上报', () => {
  const { vm } = wire()
  vm.semanticWritingAvailable = true
  vm.semanticWritingOpen = true
  vm.initWritingAssistance() // 前置条件不满足会早退——复位必须排在早退之前
  assert.equal(vm.semanticWritingAvailable, false)
  assert.equal(vm.semanticWritingOpen, false)
})

test('工具栏按钮：只在客体报可用时出现，按下态 = 面板开着', () => {
  const block = TOOLBAR.match(/<view v-if="semanticWritingAvailable"[\s\S]*?<\/view>/)
  assert.ok(block, '工具栏里必须有这颗按钮，且用 semanticWritingAvailable 控制显隐')
  assert.match(block[0], /:class="\{ on: semanticWritingOn \}"/)
  assert.match(block[0], /@tap\.stop="\$emit\('toggle-semantic-writing'\)"/)
  assert.match(block[0], /\$t\('editor\.toolbar\.semanticWritingShort'\)/)
  assert.match(block[0], /:title="\$t\('editor\.toolbar\.semanticWriting'\)"/)
  assert.match(TOOLBAR, /emits: \[[^\]]*'toggle-semantic-writing'/)
  assert.match(TOOLBAR, /semanticWritingOn: \{ type: Boolean, default: false \}/)
  assert.match(TOOLBAR, /semanticWritingAvailable: \{ type: Boolean, default: false \}/)
})

test('宿主把状态喂给工具栏并接住点击', () => {
  assert.match(EDITOR, /:semantic-writing-on="semanticWritingOpen"/)
  assert.match(EDITOR, /:semantic-writing-available="semanticWritingAvailable"/)
  assert.match(EDITOR, /@toggle-semantic-writing="toggleSemanticWriting"/)
})

test('文案只有「有据续写」一个名字，中英各有一份', () => {
  assert.equal(zh.toolbar.semanticWritingShort, '有据续写')
  assert.ok(zh.toolbar.semanticWriting.includes('有据续写'))
  assert.ok(en.toolbar.semanticWritingShort)
  assert.ok(en.toolbar.semanticWriting)
  const panel = readFileSync(new URL('../../src/composables/semanticWritingPanel.js', import.meta.url), 'utf8')
  for (const file of [EDITOR, TOOLBAR, panel]) assert.equal(file.includes('语义补全'), false, '旧名字不许再出现')
})
