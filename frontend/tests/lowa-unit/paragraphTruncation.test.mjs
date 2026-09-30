// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1087: exercise the actual worker methods at both excerpt boundaries.
import test from 'node:test'
import assert from 'node:assert/strict'
import vm from 'node:vm'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
function workerMethods(paragraph) {
  const realm = vm.createContext({
    xModel: { createSearchDescriptor: () => ({ setSearchString() {}, setPropertyValue() {} }),
      findFirst: () => ({ getString: () => '旧' }), findNext: () => null, setPropertyValue() {} },
    anchorBookmark: () => 'anchor', contextAround: () => ({ before: '', after: '' }),
    paragraphTextOf: () => paragraph,
    anchorRange: () => ({}), selectVisibly() {}, applyMinimalRedline: () => true,
  })
  const find = source.slice(source.indexOf('  find_text_locations(p) {'), source.indexOf('  // [verified-extend] replace the Nth'))
  const replace = source.slice(source.indexOf('  replace_at_position(p) {'), source.indexOf('  // [verified-extend] insert a paragraph break'))
  return vm.runInContext('({' + find + replace + '})', realm)
}

for (const length of [0, 159, 160, 161, 199, 200, 201, 800]) {
  test(`paragraph excerpts report complete length and truncation at ${length} characters`, () => {
    const paragraph = '文'.repeat(length)
    const worker = workerMethods(paragraph)
    const found = JSON.parse(JSON.stringify(worker.find_text_locations({ keyword: '旧' }))).matches[0]
    assert.equal(found.paragraph, paragraph.slice(0, 160))
    assert.equal(found.paragraphLength, length)
    assert.equal(found.paragraphTruncated, length > 160)
    assert.equal(found.text, '旧', 'excerpt metadata must not widen the matched range')
    const result = JSON.parse(JSON.stringify(worker.replace_at_position({ anchor: 'anchor', newText: '新' })))
    assert.equal(result.paragraphAfterEdit, paragraph.slice(0, 200))
    assert.equal(result.paragraphAfterEditLength, length)
    assert.equal(result.paragraphAfterEditTruncated, length > 200)
  })
}
