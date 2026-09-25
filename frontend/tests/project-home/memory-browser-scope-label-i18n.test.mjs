// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board BUG-44：MemoryBrowser.vue 的空间标签曾经优先用后端下发的中文
// label（"个人记忆"/"团队记忆"/"律所记忆"），英文界面下也照样显示中文。
// user/team/firm 是固定词汇，一律交给 spaceLabel() 走 i18n；project 空间
// 的 label 是真实项目名，必须原样展示（否则英文界面下项目名会被英文兜底
// 顶掉）。这份用例在真的加载了 en-US chat.js 译文的情况下断言渲染结果，
// 不只是断言 spaceLabel() 调用了 $t。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/MemoryBrowser.vue', import.meta.url), 'utf8')
const enChat = (await import('../../src/locales/en-US/chat.js')).default

function loadOptions() {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import\s*\{[\s\S]*?\}\s*from\s*'@\/services\/api\.js'\s*/, '')
    .replace(/import\s*\{\s*markdownFileLinks\s*\}\s*from\s*'@\/composables\/memoryBrowserState\.mjs'\s*/, '')
    .replace(/import\s*\{\s*setGlobalOverlay\s*\}\s*from\s*'@\/utils\/overlayState\.js'\s*/, '')
    .replace(/export\s+default/, 'return')
  return new Function(
    'deleteMemoryFile', 'downloadMemoryFile', 'getMemoryFile', 'getMemoryFiles',
    'getMemorySpaces', 'saveMemoryFile', 'markdownFileLinks', 'setGlobalOverlay', 'uni', script,
  )(
    async () => {}, async () => {}, async () => {}, async () => [],
    async () => [], async () => ({}), () => [], () => {}, {},
  )
}

// 真的解析 chat.memoryScope* key，而不是把 $t 打桩成回显 key 本身——
// 这样才能断言渲染出来的确实是英文单词，不是 key 字符串或中文。
function translate(key, params) {
  const short = key.startsWith('chat.') ? key.slice('chat.'.length) : key
  const template = enChat[short]
  if (typeof template !== 'string') return key
  return params ? template.replace(/\{(\w+)\}/g, (m, name) => params[name] ?? m) : template
}

function makeVm() {
  const options = loadOptions()
  const vm = Object.assign({}, options.data())
  vm.$t = translate
  for (const [name, method] of Object.entries(options.methods)) vm[name] = method.bind(vm)
  return vm
}

test('en-US 下 user/team/firm 空间标签是英文固定词汇，忽略后端下发的中文 label', () => {
  const vm = makeVm()
  assert.equal(vm.spaceLabel({ scope: 'user', label: '个人记忆' }), 'Personal')
  assert.equal(vm.spaceLabel({ scope: 'team', label: '团队记忆' }), 'Team')
  assert.equal(vm.spaceLabel({ scope: 'firm', label: '律所记忆' }), 'Firm')
})

test('en-US 下 project 空间保留后端下发的真实项目名', () => {
  const vm = makeVm()
  assert.equal(vm.spaceLabel({ scope: 'project', label: '并购尽调项目' }), '并购尽调项目')
})

test('project 空间没有 label 时兜底到 i18n 的 Project', () => {
  const vm = makeVm()
  assert.equal(vm.spaceLabel({ scope: 'project', label: '' }), 'Project')
})

test('space 为空时不抛错', () => {
  const vm = makeVm()
  assert.equal(vm.spaceLabel(null), '')
})
