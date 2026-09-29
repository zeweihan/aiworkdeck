// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildDecorationSpecs, selectionSnapshot } from '../../src/utils/planReviewSpecs.js'

test('改动行 + 删除段 + 批注区间一次算齐', () => {
  const specs = buildDecorationSpecs({
    baseline: '一\n二\n三\n四', current: '一\n二改\n四',
    comments: [{ id: 1, fromLine: 4, toLine: 4, quotedText: '四' }]
  })
  assert.deepEqual(specs.editedLines, [2])
  assert.deepEqual(specs.deletions, [{ beforeLine: 3, count: 1, text: '三' }])
  assert.deepEqual(specs.commentRanges, [{ id: 1, fromLine: 3, toLine: 3, found: true }])
})
test('批注原文被删时 found=false 但仍返回', () => {
  const specs = buildDecorationSpecs({ baseline: 'a\nb', current: 'a', comments: [{ id: 2, fromLine: 2, toLine: 2, quotedText: 'b' }] })
  assert.equal(specs.commentRanges[0].found, false)
})
test('只改一行：记为改动，不再冒删除标记', () => {
  const specs = buildDecorationSpecs({ baseline: 'a\nb\nc', current: 'a\nB\nc' })
  assert.deepEqual(specs.editedLines, [2])
  assert.deepEqual(specs.deletions, [])
})
test('被配对成改动行的原文按当前行号返回', () => {
  const specs = buildDecorationSpecs({ baseline: '一\n二\n三\n四', current: '一\n二改\n四' })
  assert.equal(specs.editedOriginals[2], '二')
  assert.deepEqual(specs.deletions, [{ beforeLine: 3, count: 1, text: '三' }])
})
test('纯删除两行：原样保留删除段', () => {
  const specs = buildDecorationSpecs({ baseline: 'a\nb\nc\nd', current: 'a\nd' })
  assert.deepEqual(specs.deletions, [{ beforeLine: 2, count: 2, text: 'b\nc' }])
})
test('批注缺省与空基线不抛', () => {
  const specs = buildDecorationSpecs({ baseline: 'a', current: 'a' })
  assert.deepEqual(specs, { editedLines: [], editedOriginals: {}, deletions: [], commentRanges: [] })
})

// 最小的 EditorState 桩：只实现 selectionSnapshot 读到的三样
function fakeState(text, from, to) {
  const lineAt = (pos) => {
    const number = text.slice(0, pos).split('\n').length
    return { number, from: text.split('\n').slice(0, number - 1).reduce((n, l) => n + l.length + 1, 0) }
  }
  return { selection: { main: { from, to, empty: from === to } }, doc: { lineAt }, sliceDoc: (a, b) => text.slice(a, b) }
}
test('selectionSnapshot：空选区返回 null', () => {
  assert.equal(selectionSnapshot(fakeState('abc\ndef', 2, 2)), null)
})
test('selectionSnapshot：跨行选区给出行号与原文', () => {
  assert.deepEqual(selectionSnapshot(fakeState('abc\ndef\nghi', 1, 6)), { fromLine: 1, toLine: 2, quotedText: 'bc\nde' })
})
test('selectionSnapshot：三击选整行（to 落在下一行行首）不多算一行、不带尾部换行', () => {
  // 'abc\ndef\nghi'：选中第 2 行整行 = [4, 8)，8 是第 3 行行首
  assert.deepEqual(selectionSnapshot(fakeState('abc\ndef\nghi', 4, 8)), { fromLine: 2, toLine: 2, quotedText: 'def' })
})

// ---- 最终修复波 UX-1 / UX-2：主题规则（计算样式在走查脚本里真渲染断言） ----
import { readFileSync } from 'node:fs'
import { PLAN_REVIEW_TOKENS } from '../../src/utils/appTheme.js'
const EXT = readFileSync(new URL('../../src/utils/planReviewExtensions.js', import.meta.url), 'utf8')
test('UX-1：光标所在的改动行，琥珀底要盖过 activeLine（更高特异性规则）', () => {
  assert.match(EXT, /'\.cm-line\.cm-activeLine\.cm-review-edited':\s*\{[^}]*backgroundColor:\s*'var\(--awd-review-edited-bg\)'/)
})
test('UX-2：批注高亮加深为 #CFEBDD 并带 2px 强调色下划线', () => {
  assert.equal(PLAN_REVIEW_TOKENS.light['--awd-review-comment-bg'], '#CFEBDD')
  assert.match(EXT, /'\.cm-review-commented':\s*\{[^}]*borderBottom:\s*'2px solid var\(--awd-accent\)'/)
})
