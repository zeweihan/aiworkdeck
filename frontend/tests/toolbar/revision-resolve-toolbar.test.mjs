// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// #66 PR-B：自建工具条黄金路径「接受/拒绝当前 + 全部」接线锁。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createLibreOfficeExecutor } from '../../src/composables/libreofficeExecutorClient.js'
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

test('接受/拒绝当前能经过真实 executor 白名单到达 worker port', async () => {
  let listener
  const sent = []
  const executor = createLibreOfficeExecutor()
  executor.connect({
    addEventListener: (_type, fn) => { listener = fn },
    start() {},
    postMessage(message) {
      sent.push(message)
      listener({ data: { cmd: 'result', reqId: message.reqId,
        result: { success: true, action: message.params.action, remaining: 1 } } })
    },
  })
  for (const action of ['accept', 'reject']) {
    const result = await executor.executeCommand('resolve_revision_at_cursor', { action })
    assert.equal(result.success, true, result.message)
    assert.equal(result.action, action)
    assert.equal(result.remaining, 1)
  }
  assert.deepEqual(sent.map(message => [message.action, message.params]), [
    ['resolve_revision_at_cursor', { action: 'accept' }],
    ['resolve_revision_at_cursor', { action: 'reject' }],
  ], '不能用全部接受/拒绝替代当前光标处置')
  const unknown = await executor.executeCommand('resolve_revision_at_cursor_typo', { action: 'accept' })
  assert.equal(unknown.success, false)
  assert.match(unknown.message, /Unknown action/)
  assert.equal(sent.length, 2, '未知动作仍然在客户端拦截')
})

test('当前修订命中兼容反向原生端点、折叠光标与无关选区', () => {
  const source = WORKER.slice(WORKER.indexOf('function revisionIndexAtCursor()'), WORKER.indexOf('function selectionTouchesDeletion('))
  const locate = new Function('ctrl', 'xModel', source + '; return revisionIndexAtCursor()')
  const text = { compareRegionStarts: (a, b) => Math.sign(b - a) }
  const ranges = [[4, 2], [10, 12]]
  for (const [start, end, expected] of [[1, 1, -1], [2, 2, 0], [3, 3, 0], [4, 4, 0], [5, 9, -1], [3, 11, 0], [11, 11, 1]]) {
    const ctrl = { getViewCursor: () => ({ getText: () => text, getStart: () => start, getEnd: () => end }) }
    const xModel = { getRedlines: () => ({ createEnumeration: () => {
      let next = 0
      return { hasMoreElements: () => next < ranges.length, nextElement: () => {
        const range = ranges[next++]
        return { getPropertyValue: name => range[name === 'RedlineStart' ? 0 : 1] }
      } }
    } }) }
    assert.equal(locate(ctrl, xModel), expected, `selection [${start}, ${end}]`)
  }
})
