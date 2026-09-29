// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { lineDiff, lineDiffStats } from '../../src/utils/lineDiff.js'

test('改一行：changedLines 只含那一行，hunks=1', () => {
  const d = lineDiff('a\nb\nc', 'a\nB\nc')
  assert.deepEqual(d.changedLines, [2])
  assert.equal(d.hunks, 1); assert.equal(d.added, 1); assert.equal(d.removed, 1)
})
test('中间删两行：deletions 记在当前第 2 行之前', () => {
  const d = lineDiff('a\nb\nc\nd', 'a\nd')
  assert.deepEqual(d.changedLines, [])
  assert.deepEqual(d.deletions, [{ beforeLine: 2, count: 2, text: 'b\nc' }])
})
test('末尾追加两行：changedLines 是新行号', () => {
  const d = lineDiff('a', 'a\nb\nc')
  assert.deepEqual(d.changedLines, [2, 3])
})
test('lineDiffStats 与旧 ArtifactCard 形状一致', () => {
  assert.deepEqual(lineDiffStats('a\nb', 'a\nb\nc'), { hunks: 1, added: 1, removed: 0 })
})
