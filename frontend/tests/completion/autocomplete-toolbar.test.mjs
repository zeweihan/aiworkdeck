// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#755：「写作辅助」改名「自动补全」，画布右下角那颗常驻按钮撤掉，入口并入
// Writer 自建工具栏、与「有据续写」并排成一组。这里锁宿主这一半（客体那一半在
// controller.test.mjs）：客体上报 → 按钮显隐/按下态，点击 → 开合指令。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/editor.js'
import en from '../../src/locales/en-US/editor.js'

const EDITOR = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const TOOLBAR = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
const GUEST = readFileSync(new URL('../../src/composables/zetaOfficeCompletion.js', import.meta.url), 'utf8')
const PRESENTATION = readFileSync(new URL('../../src/composables/writingAssistancePresentation.js', import.meta.url), 'utf8')

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
  assert.equal(vm.writingAssistanceAvailable, false, '没收到上报之前按钮不出现')
  assert.equal(vm.writingAssistanceOpen, false)
  emit({ __lo: 'lo-relay', type: 'writing-assistance-state', available: true, open: false })
  assert.equal(vm.writingAssistanceAvailable, true)
  assert.equal(vm.writingAssistanceOpen, false)
  emit({ __lo: 'lo-relay', type: 'writing-assistance-state', available: true, open: true })
  assert.equal(vm.writingAssistanceOpen, true, '客体里按 Esc/×、宿主点按钮都靠这条回报同步')
  emit({ __lo: 'lo-relay', type: 'writing-assistance-state', available: false, open: false })
  assert.equal(vm.writingAssistanceAvailable, false)
  assert.equal(vm.writingAssistanceOpen, false)
})

test('不带 lo-relay 标记的消息一个字段都不认', () => {
  const { vm, emit } = wire()
  emit({ type: 'writing-assistance-state', available: true, open: true })
  assert.equal(vm.writingAssistanceAvailable, false)
})

test('点工具栏只发开合指令，按下态等客体回报（不本地乐观翻）', () => {
  const { vm, sent } = wire()
  vm.toggleWritingAssistance()
  assert.deepEqual(sent.at(-1), { __lo: 'lo-relay', type: 'writing-assistance-panel', open: true })
  assert.equal(vm.writingAssistanceOpen, false)
  vm.writingAssistanceOpen = true
  vm.toggleWritingAssistance()
  assert.deepEqual(sent.at(-1), { __lo: 'lo-relay', type: 'writing-assistance-panel', open: false })
})

test('换文档/重载时宿主先把入口收回去，等客体重新上报', () => {
  const { vm } = wire()
  vm.writingAssistanceAvailable = true
  vm.writingAssistanceOpen = true
  vm.initWritingAssistance() // 前置条件不满足会早退——复位必须排在早退之前
  assert.equal(vm.writingAssistanceAvailable, false)
  assert.equal(vm.writingAssistanceOpen, false)
})

test('工具栏按钮：只在客体报可用时出现，按下态 = 面板开着', () => {
  const block = TOOLBAR.match(/<view v-if="writingAssistanceAvailable"[\s\S]*?<\/view>/)
  assert.ok(block, '工具栏里必须有这颗按钮，且用 writingAssistanceAvailable 控制显隐')
  assert.match(block[0], /:class="\{ on: writingAssistanceOn \}"/)
  assert.match(block[0], /@tap\.stop="\$emit\('toggle-writing-assistance'\)"/)
  assert.match(block[0], /\$t\('editor\.toolbar\.autocompleteShort'\)/)
  assert.match(block[0], /:title="\$t\('editor\.toolbar\.autocomplete'\)"/)
  assert.match(TOOLBAR, /emits: \[[^\]]*'toggle-writing-assistance'/)
})

test('写作一组：两颗并排，分隔线只在至少有一颗时才画', () => {
  const right = TOOLBAR.slice(TOOLBAR.indexOf('<view class="etb-right">'))
  const sep = right.indexOf('<view v-if="semanticWritingAvailable || writingAssistanceAvailable" class="etb-sep">')
  const semantic = right.indexOf('<view v-if="semanticWritingAvailable" class="etb-btn wide"')
  const autocomplete = right.indexOf('<view v-if="writingAssistanceAvailable" class="etb-btn wide"')
  assert.ok(sep >= 0, '两颗写作按钮之前要有一条分隔线，且两颗都不可用时不画')
  assert.ok(sep < semantic && semantic < autocomplete, '分隔线在前，「有据续写」与「自动补全」紧挨着')
})

test('画布上那颗常驻按钮连同它的样式一起撤掉', () => {
  assert.equal(GUEST.includes('awd-wa-toggle'), false)
  assert.equal(PRESENTATION.includes('awd-wa-toggle'), false)
  assert.match(PRESENTATION, /\.awd-writing-assistance\[hidden\]/, '根节点自己也要能藏')
})

test('对外文案两语言都不再有「写作辅助」', () => {
  // 只看文案表，不看注释：注释里的旧称不影响用户看到什么。
  const labels = GUEST.match(/^const LABELS = \{[\s\S]*?^\}$/m)
  assert.ok(labels, 'LABELS 文案表还在')
  assert.equal(labels[0].includes('写作辅助'), false)
  assert.equal(labels[0].includes('Writing assistance'), false)
  assert.match(labels[0], /title: '自动补全'/)
  assert.match(labels[0], /title: 'Autocomplete'/)
  assert.match(labels[0], /enabled: '自动弹出候选'/, '面板内的开关要与面板标题区分开')
  assert.match(labels[0], /enabled: 'Suggest as I type'/)
  assert.equal(zh.toolbar.autocompleteShort, '自动补全')
  assert.equal(en.toolbar.autocompleteShort, 'Autocomplete')
  assert.equal(JSON.stringify(zh).includes('写作辅助'), false)
  assert.equal(JSON.stringify(en).includes('Writing assistance'), false)
})
