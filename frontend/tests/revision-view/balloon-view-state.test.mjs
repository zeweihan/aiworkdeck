// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const declaration = name => source.slice(source.indexOf(`function ${name}(`), source.indexOf('\n}', source.indexOf(`function ${name}(`)) + 2)

test('an engine without native margins can still hide inline markup for external balloons', () => {
  let writes = 0
  const apply = new Function('ctrl', 'readShowChangesInMargin', 'errStr', `${declaration('applyShowChangesInMargin')};return applyShowChangesInMargin`)(
    { getViewSettings: () => ({ setPropertyValue() { writes++; throw new Error('unsupported') } }) }, () => null, String)
  assert.equal(apply(false), null)
  assert.equal(writes, 0, 'absent native margins do not need to be disabled')
  assert.ok(apply(true), 'legacy native-margin mode remains unavailable')
})

for (const failAt of [1, 2]) {
  test(`revision resolution preserves balloon mode when refresh ${failAt} fails`, () => {
    let mode = 'balloons', refreshes = 0, edits = 0
    const resolve = new Function('isWriterDoc', 'readShowChanges', 'revisionViewState', 'withViewOnlyChange', 'applyRevisionView', 'xModel', 'isReviewWritable',
      `${declaration('runRevisionResolution')};return runRevisionResolution`)(
      () => true, () => false, () => ({ mode }), fn => fn(), next => { mode = next },
      { refresh() { if (++refreshes === failAt) throw new Error('refresh failed') } }, () => true)
    const edit = () => { edits++; return { success: true } }
    if (failAt === 1) assert.throws(() => resolve(edit), /refresh failed/)
    else assert.deepEqual(resolve(edit), { success: true })
    assert.equal(mode, 'balloons')
    assert.equal(edits, failAt === 1 ? 0 : 1)
  })
}

for (const blocked of ['stale', 'readonly']) {
  test(`revision commands reject ${blocked} input before changing views or editing`, () => {
    let edits = 0, views = 0
    const resolve = new Function('isWriterDoc', 'readShowChanges', 'revisionViewState', 'withViewOnlyChange', 'applyRevisionView', 'xModel', 'isReviewWritable', 'currentReviewRevision', 'tableFail',
      `${declaration('runRevisionResolution')};return runRevisionResolution`)(
      () => true, () => false, () => ({mode:'balloons'}), fn => fn(), () => {views++}, {refresh(){}},
      () => blocked !== 'readonly', () => 10, message => ({success:false,message}))
    const result = resolve(() => { edits++;return {success:true} }, {revision:blocked==='stale'?9:10})
    assert.equal(result.success,false)
    assert.equal(edits,0)
    assert.equal(views,0)
  })
}
