// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reanchorComment, buildPlanReviewPrompt } from '../../src/utils/planReview.js'

test('文字上移后按引用原文重锚定', () => {
  const r = reanchorComment({ fromLine: 5, toLine: 5, quotedText: '第二步' }, '新加\n第二步\n第三步')
  assert.deepEqual(r, { fromLine: 2, toLine: 2, found: true })
})
test('引用原文被删：found=false 且保留原行号', () => {
  const r = reanchorComment({ fromLine: 2, toLine: 2, quotedText: '第二步' }, '第一步\n第三步')
  assert.deepEqual(r, { fromLine: 2, toLine: 2, found: false })
})
test('有改动有批注：message 含修订全文、改动摘要、逐条批注；displayText 是人话', () => {
  const { message, displayText } = buildPlanReviewPrompt({
    lang: 'zh', currentText: '# 计划\n- 改后的第一步',
    diff: { hunks: 1, added: 1, removed: 1 },
    comments: [{ quotedText: '改后的第一步', body: '要引用合同第 3 条', found: true }]
  })
  assert.match(message, /共 1 处（\+1 行 \/ -1 行）/)
  assert.match(message, /针对「改后的第一步」：要引用合同第 3 条/)
  assert.match(message, /修订版计划全文：\n# 计划\n- 改后的第一步$/)
  assert.equal(displayText, '已按修订版推进（1 处改动、1 条批注）')
})
test('0 改动 0 批注退化为既有确认语', () => {
  const { message, displayText } = buildPlanReviewPrompt({ lang: 'zh', currentText: 'x', diff: { hunks: 0, added: 0, removed: 0 }, comments: [] })
  assert.equal(message, '已确认实施计划，请按此推进。')
  assert.equal(displayText, '按此推进')
})
