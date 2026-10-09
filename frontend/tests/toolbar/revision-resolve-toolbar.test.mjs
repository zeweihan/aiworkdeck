// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// #66 PR-B：自建工具条黄金路径「接受/拒绝当前 + 全部」接线锁。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/editor.js'
import en from '../../src/locales/en-US/editor.js'

const TOOLBAR = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
const WORKER = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')

const I18N_KEYS = [
  'acceptCurrent', 'acceptCurrentShort', 'rejectCurrent', 'rejectCurrentShort',
  'acceptAll', 'acceptAllShort', 'rejectAll', 'rejectAllShort',
  'resolveCurrentFailed', 'resolveAllFailed',
]

test('zh/en toolbar 修订处置键对齐', () => {
  for (const k of I18N_KEYS) {
    assert.equal(typeof zh.toolbar[k], 'string', `zh missing toolbar.${k}`)
    assert.equal(typeof en.toolbar[k], 'string', `en missing toolbar.${k}`)
    assert.ok(zh.toolbar[k].trim(), `zh toolbar.${k} empty`)
    assert.ok(en.toolbar[k].trim(), `en toolbar.${k} empty`)
  }
})

test('工具条挂四颗处置钮并走 i18n', () => {
  assert.match(TOOLBAR, /@tap\.stop="resolveAtCursor\('accept'\)"/)
  assert.match(TOOLBAR, /@tap\.stop="resolveAtCursor\('reject'\)"/)
  assert.match(TOOLBAR, /@tap\.stop="resolveAllRevisions\('accept'\)"/)
  assert.match(TOOLBAR, /@tap\.stop="resolveAllRevisions\('reject'\)"/)
  assert.match(TOOLBAR, /\$t\('editor\.toolbar\.acceptCurrentShort'\)/)
  assert.match(TOOLBAR, /\$t\('editor\.toolbar\.rejectCurrentShort'\)/)
  assert.match(TOOLBAR, /\$t\('editor\.toolbar\.acceptAllShort'\)/)
  assert.match(TOOLBAR, /\$t\('editor\.toolbar\.rejectAllShort'\)/)
})

test('工具条方法调用 worker 原语', () => {
  assert.match(TOOLBAR, /resolve_revision_at_cursor/)
  assert.match(TOOLBAR, /resolve_all_revisions/)
  assert.match(TOOLBAR, /async resolveAtCursor\(action\)/)
  assert.match(TOOLBAR, /async resolveAllRevisions\(action\)/)
})

test('worker 提供 at-cursor 原语并纳入修订处置守卫', () => {
  assert.match(WORKER, /function revisionIndexAtCursor\(/)
  assert.match(WORKER, /resolve_revision_at_cursor\(p\)/)
  assert.match(WORKER, /RESOLVE_REVISION_ACTIONS = new Set\(\[[^\]]*resolve_revision_at_cursor/)
})
