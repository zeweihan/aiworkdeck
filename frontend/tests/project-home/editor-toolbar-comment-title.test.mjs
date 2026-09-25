// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机 BUG-29（C4-02）：插入批注弹框标题恒为「对「」批注」。
//
// 病灶：被批注文字 selText 只在工具栏「插入」下拉**打开**的那一下读一次
// （openInsert → get_selection）。文档菜单「插入批注」走 LibreOfficeEditor
// .menuInsertComment()，直接把下拉置开再调 startComment()，一次 get_selection 都不发，
// selText 是空串（或者上一次打开下拉时的旧选区）。取不到文字时标题还硬套着引号壳。
//
// 修法：进批注表单时（startComment）现读一次选区；标题在取不到文字时换成不带引号的
// 「添加批注」。本用例把 <script> 剥出来当普通对象跑，不起引擎。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
const TEMPLATE = SRC.slice(SRC.indexOf('<template>'), SRC.lastIndexOf('</template>'))

function makeToolbarVm(executor) {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  // eslint-disable-next-line no-new-func
  const component = new Function('bindHorizontalWheel', script.replace('export default', 'return'))(() => () => {})
  const base = { $t: (k, p) => k + (p ? JSON.stringify(p) : ''), $emit: () => {}, $nextTick: (fn) => fn && fn(), executor }
  const vm = Object.assign(base, component.data.call(base), component.methods)
  for (const [k, fn] of Object.entries(component.computed || {})) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  return vm
}

function engine(selection) {
  const calls = []
  return {
    calls,
    async executeCommand(action) {
      calls.push(action)
      await Promise.resolve()
      if (action === 'get_selection') return { success: true, text: selection, hasSelection: !!selection }
      if (action === 'get_ui_state') return { success: true, character: {}, paragraph: {}, view: {}, selection: { collapsed: !selection }, undo: null }
      return { success: true }
    },
  }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

test('文档菜单直进批注表单（不经「插入」下拉）：进表单时现读选区，标题带上被批注文字', async () => {
  const ex = engine('中英混排')
  const vm = makeToolbarVm(ex)
  vm.state = { character: {}, paragraph: {}, view: {}, selection: { collapsed: false }, undo: null }
  // menuInsertComment 的走法：直接置开下拉 + startComment，不调 openInsert
  vm.menu = 'insert'
  vm.startComment()
  await flush()
  assert.ok(ex.calls.includes('get_selection'), '进批注表单时一次 get_selection 都没发，标题只能拿到空串')
  assert.equal(vm.selText, '中英混排')
  assert.equal(vm.commentFormTitle, 'editor.toolbar.commentTitle' + JSON.stringify({ sel: '中英混排' }))
})

test('上一次打开下拉时的旧选区不许串到这一次的标题里', async () => {
  const ex = engine('English words')
  const vm = makeToolbarVm(ex)
  vm.state = { character: {}, paragraph: {}, view: {}, selection: { collapsed: false }, undo: null }
  vm.selText = '上一次的选区'
  vm.startComment()
  await flush()
  assert.equal(vm.selText, 'English words')
})

test('取不到选区文字时标题不再套空引号壳「对「」批注」', () => {
  const vm = makeToolbarVm(engine(''))
  vm.selText = ''
  assert.equal(vm.commentFormTitle, 'editor.toolbar.commentTitleNoSel')
})

test('模板里批注表单标题走 commentFormTitle，而不是直接拼 commentTitle', () => {
  assert.match(TEMPLATE, /insertMode === 'comment'[\s\S]*?\{\{\s*commentFormTitle\s*\}\}/)
})

test('zh-CN / en-US 都有无选区时的批注标题文案', async () => {
  const zh = (await import('../../src/locales/zh-CN/editor.js')).default
  const en = (await import('../../src/locales/en-US/editor.js')).default
  assert.ok(zh.toolbar.commentTitleNoSel && !/「」/.test(zh.toolbar.commentTitleNoSel))
  assert.ok(en.toolbar.commentTitleNoSel)
})
