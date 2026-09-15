// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「这两版之间的改动」窗格的占位状态（手工走查 2026-09-14）。
 *
 * 走查里看到的样子：按住 Cmd 点第二行，右侧标题立刻写上「这两版之间的改动 A → B」，
 * 下面却是「这一版没有文件改动」——那是一句还没去问后端就先下的结论。
 * 现在选够两版就自己去拉，没拉回来之前一律说「正在对比」。
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { comparePaneState } from '../../src/utils/historyRows.js'

test('没选够两版 = 不显示这个窗格', () => {
  assert.equal(comparePaneState({ selectedCount: 0 }), 'idle')
  assert.equal(comparePaneState({ selectedCount: 1, loaded: true }), 'idle')
  assert.equal(comparePaneState(), 'idle')
})

test('刚选够两版、还没拉过 = 正在对比，不许说「没有改动」', () => {
  assert.equal(comparePaneState({ selectedCount: 2 }), 'loading')
  assert.equal(comparePaneState({ selectedCount: 2, loading: true }), 'loading')
  // 上一对版本的清单还挂着也一样：那是别人的结果，不能当这一对的答案
  assert.equal(comparePaneState({ selectedCount: 2, changes: [{ path: 'a' }] }), 'loading')
})

test('拉回来了才分「有改动」与「真的没有改动」', () => {
  assert.equal(comparePaneState({ selectedCount: 2, loaded: true, changes: [] }), 'empty')
  assert.equal(comparePaneState({ selectedCount: 2, loaded: true, changes: [{ path: 'a.docx' }] }), 'list')
})
