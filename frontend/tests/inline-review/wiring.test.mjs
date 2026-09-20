// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#749：「审校」改名「AI 审校」，开关从工具栏挪到正文浮球。
//
// 这份用例盯的是接线与文案，不是行为（行为在 inline-review-host / guest 两份里）：
//   1. 工具栏上不许再有「审校」按钮——开关必须和它控制的东西待在一起；
//   2. 菜单/命令面板那一条跟着改名，run 与编辑器上的方法名对得上；
//   3. 两种语言的键齐全（少一个键界面上就是一行原始 key）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/editor.js'
import en from '../../src/locales/en-US/editor.js'
import { DOCUMENT_COMMANDS } from '../../src/config/commands/document.js'

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')

test('工具栏只剩「审阅」：「审校」按钮、它的 props 与 emit 一并撤掉', () => {
  const toolbar = read('../../src/components/EditorToolbar.vue')
  const template = toolbar.split('<script>')[0]
  assert.equal(template.includes("$emit('toggle-inline-review')"), false)
  assert.equal(template.includes('inlineReviewAvailable'), false)
  assert.equal(template.includes('toolbar.inlineReviewShort'), false)
  assert.equal(toolbar.includes('toggle-inline-review'), false, 'emits 声明也要摘掉')
  assert.equal(toolbar.includes('inlineReviewOn'), false)
  assert.ok(template.includes("$emit('toggle-review')"), '「审阅」按钮留着')
  const editor = read('../../src/components/LibreOfficeEditor.vue')
  assert.equal(editor.includes('inline-review-on'), false, '宿主那一侧的接线不许留死代码')
  assert.equal(editor.includes('inline-review-available'), false)
  for (const locale of ['zh-CN', 'en-US']) {
    const src = read(`../../src/locales/${locale}/editor.js`)
    assert.equal(src.includes('inlineReviewShort'), false, '没人用的文案键要删掉')
  }
})

test('菜单与命令面板那一条改名，run 与编辑器上的方法名对得上', () => {
  const command = DOCUMENT_COMMANDS.find((c) => c.id === 'doc.aiReview')
  assert.ok(command, '命令 id 改成 doc.aiReview')
  assert.deepEqual(command.label, { zh: 'AI 审校', en: 'AI Review' })
  assert.equal(command.checked, 'aiReview')
  assert.equal(command.run, 'wb:toggleAiReview')
  assert.equal(DOCUMENT_COMMANDS.some((c) => c.id === 'doc.inlineReview'), false)
  const menu = read('../../src/pages/project-overview/menuCommands.js')
  assert.ok(menu.includes("case 'toggleAiReview'"))
  assert.ok(menu.includes('ed.menuToggleAiReview()'))
  assert.ok(menu.includes('aiReview: edState.aiReview !== false'))
  const editor = read('../../src/components/LibreOfficeEditor.vue')
  assert.ok(editor.includes('menuToggleAiReview()'), '菜单调的方法必须真的存在')
  assert.ok(editor.includes('aiReview: this.aiReviewEnabled'), 'menuState 上报的键与 checked 同名')
})

test('两种语言的 AI 审校文案齐全且互相对齐', () => {
  assert.equal(zh.review.checkTab, 'AI 审校 {count}')
  assert.equal(en.review.checkTab, 'AI Review {count}')
  const keys = (o) => Object.keys(o).sort()
  assert.deepEqual(keys(zh.inlineReview), keys(en.inlineReview))
  assert.deepEqual(keys(zh.inlineReview.bucket), keys(en.inlineReview.bucket))
  assert.deepEqual(keys(zh.inlineReview.err), keys(en.inlineReview.err))
  assert.deepEqual(keys(zh.inlineReview.deepReason), keys(en.inlineReview.deepReason))
  for (const key of ['aiEnable', 'aiDisable', 'autoPaused', 'deep', 'offTitle', 'offHint']) {
    assert.ok(zh.inlineReview[key] && en.inlineReview[key], key)
  }
  assert.equal(zh.inlineReview.enable, undefined, '旧的「开启即时检查」不再有人用')
  assert.equal(zh.inlineReview.disable, undefined)
  assert.equal(zh.toolbar.inlineReview, undefined)
  assert.equal(en.toolbar.inlineReview, undefined)
})

test('面板与浮球读的是同一份 ai 状态，不各记一份', () => {
  const panel = read('../../src/components/InlineReviewPanel.vue')
  assert.ok(panel.includes("this.state.ai !== false"))
  assert.ok(panel.includes("action: 'ai'"))
  assert.equal(panel.includes("state.enabled"), false)
  const guest = read('../../src/composables/zetaOfficeInlineReview.js')
  assert.ok(guest.includes('state.ai === false'))
  assert.equal(guest.includes('state.enabled'), false)
})
