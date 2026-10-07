// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
function fixture() {
  const reads = []
  const rows = Array.from({ length: 600 }, (_, index) => ({ getPropertyValue(name) {
    reads.push(index)
    return { RedlineAuthor: 'Reviewer ' + index, RedlineType: 'Insert', RedlineIdentifier: String(index), RedlineText: { getString: () => 'Change ' + index } }[name]
  } }))
  let enumerations = 0
  const model = { getRedlines: () => ({ getCount: () => rows.length, getByIndex: index => rows[index], createEnumeration: () => {
    enumerations++; let i = 0
    return { hasMoreElements: () => i < rows.length, nextElement: () => rows[i++] }
  } }) }
  const start = source.indexOf('  list_revisions(p) {'), end = source.indexOf('\n  },', start) + 5
  const read = new Function('xModel', 'rangeStartsEqual', 'applyLocator', 'tableFail', 'errStr', 'currentReviewRevision', 'docSeq',
    `return ({${source.slice(start, end)}}).list_revisions`)(model, () => false, () => {}, message => ({success:false,message}), String, () => 'revision-2', 7)
  return {read, reads, enumerations: () => enumerations}
}
test('hover at a late revision reads metadata only for that hit', () => {
  const f = fixture(), result = f.read({index:499,locate:false,documentSeq:7,revision:'revision-2'})
  assert.equal(result.success,true)
  assert.equal(result.count,1)
  assert.deepEqual(result.revisions.map(r => [r.index,r.author,r.text]), [[499,'Reviewer 499','Change 499']])
  assert.deepEqual([...new Set(f.reads)], [499])
  assert.equal(f.enumerations(),0, 'single hit uses indexed access rather than walking preceding revisions')
})
test('stale geometry and invalid indices never read redlines', () => {
  for (const params of [{index:0,documentSeq:6},{index:0,revision:'revision-1'},{index:-1},{index:1.5}]) {
    const f=fixture(); assert.equal(f.read(params).success,false); assert.equal(f.enumerations(),0)
  }
})
test('normal list and missing hit retain truthful indices and counts', () => {
  const f=fixture()
  assert.deepEqual(f.read({limit:3,locate:false}).revisions.map(r=>r.index),[0,1,2])
  assert.equal(f.read({index:700,locate:false}).count,0)
})
