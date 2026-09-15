// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

// Execute the production clause recognizer with synthetic paragraph ranges.
// This verifies enumeration/result semantics, not LOWA rendering or UNO timing.
const source = fs.readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const from = source.indexOf('  get_clauses() {')
const to = source.indexOf('  // [verified-extend] move the view cursor', from)
assert.ok(from >= 0 && to > from)
const method = source.slice(from, to).trim().replace(/,$/, '')
function clauses(texts) {
  return vm.runInNewContext(`({ ${method} }).get_clauses()`, {
    eachParagraph: fn => texts.forEach((text, i) => fn({ getString: () => text }, i)),
  })
}

test('350 条合同不会伪装成只有 300 条，续读起点准确到第 301 条', () => {
  const texts = ['合同首部']
  for (let i = 1; i <= 350; i++) texts.push(`第${i}条 条款标题`, `这是条款 ${i} 的正文说明`)
  const result = clauses(texts)
  assert.equal(result.totalClauseCount, 350)
  assert.equal(result.clauseCount, 300)
  assert.equal(result.truncated, true)
  assert.equal(result.nextStartParagraph, 601)
  assert.equal(texts[result.nextStartParagraph], '第301条 条款标题')
  assert.equal(result.clauses[299].endParagraph, 600)
  assert.match(result.note, /get_document_text/)
})

test('恰好 300 条保持完整；无编号文档不编造续读起点', () => {
  const full = clauses(Array.from({ length: 300 }, (_, i) => `第${i + 1}条 标题`))
  assert.equal(full.totalClauseCount, 300)
  assert.equal(full.truncated, false)
  assert.equal(full.nextStartParagraph, undefined)
  const empty = clauses(['普通正文'])
  assert.equal(empty.totalClauseCount, 0)
  assert.equal(empty.truncated, false)
  assert.equal(empty.nextStartParagraph, undefined)
})
