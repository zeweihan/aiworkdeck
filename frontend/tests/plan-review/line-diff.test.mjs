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

// ---- 最终修复波 C-1：超阈值退化不能把相同文本报成一处改动 ----
const LONG = Array.from({ length: 800 }, (_, k) => `第 ${k + 1} 行`).join('\n')
test('相同长文本（>700 行）返回全零', () => {
  const d = lineDiff(LONG, LONG)
  assert.equal(d.hunks, 0); assert.equal(d.added, 0); assert.equal(d.removed, 0)
  assert.deepEqual(d.changedLines, []); assert.deepEqual(d.deletions, [])
})
test('长文本只改中间一行：剪掉公共前后缀后精确到那一行', () => {
  const cur = LONG.replace('第 400 行', '第 400 行（改）')
  const d = lineDiff(LONG, cur)
  assert.equal(d.hunks, 1); assert.equal(d.added, 1); assert.equal(d.removed, 1)
  assert.deepEqual(d.changedLines, [400])
})
test('长文本中间删两行：deletions 行号按全文算', () => {
  const lines = LONG.split('\n')
  const cur = [...lines.slice(0, 99), ...lines.slice(101)].join('\n')
  const d = lineDiff(LONG, cur)
  assert.deepEqual(d.deletions, [{ beforeLine: 100, count: 2, text: '第 100 行\n第 101 行' }])
  assert.equal(d.hunks, 1)
})
