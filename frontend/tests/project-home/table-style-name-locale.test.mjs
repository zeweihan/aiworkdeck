// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-36：表格内样式名显示英文「Table Contents」，重开文档后才对。
//
// 根因：`Table Contents`/`Table Heading` 是 LibreOffice 引擎内置样式的程序名
// （跨语言固定），工具栏本该优先查 editor.toolbar.styleNames 本地化表
// （EditorToolbar.vue 的 localStyleName），查不到才退到引擎的 DisplayName——
// 而 DisplayName 在同一会话里新建样式定义时可能沿用还没绑完 UI locale 的取值，
// 重新打开文档触发一次干净的 locale 绑定才对。这两个内置样式名原来没有进本地化表，
// 于是每次都要吃这个时机坑。断言直接读本地化表本身，不用重新实现 localStyleName
// 的查表逻辑（EditorToolbar.vue 已有）。
import test from 'node:test'
import assert from 'node:assert/strict'
import zhEditor from '../../src/locales/zh-CN/editor.js'
import enEditor from '../../src/locales/en-US/editor.js'

test('zh-CN 本地化表里补上了 Table Contents / Table Heading，不再依赖引擎 DisplayName 的绑定时机', () => {
  const names = zhEditor.toolbar.styleNames
  assert.equal(names['Table Contents'], '表格内容')
  assert.equal(names['Table Heading'], '表格标题')
})

test('en-US 本地化表同步补上（zh-CN/en-US 文案必须同步落地）', () => {
  const names = enEditor.toolbar.styleNames
  assert.equal(names['Table Contents'], 'Table Contents')
  assert.equal(names['Table Heading'], 'Table Heading')
})
