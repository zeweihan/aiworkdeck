// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
const worker = fs.readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const method = worker.slice(worker.indexOf('  build_comparison_document(p) {'), worker.indexOf('  // ==================== 三方合并'))
function harness(failStage) {
  const settings = { Mode: undefined, UseRSID: true, IgnorePieces: true }
  let loads = 0, writes = 0
  const progress = []
  const context = {
    post: (cmd, data) => progress.push({ cmd, ...data }),
    log: () => {}, refineCalls: [],
    refineComparisonRedlines: author => { context.refineCalls.push(author); return 2 },
    toUnoByteSeq: x => Array.isArray(x) && x.length ? x : null,
    humanAuthor: 'User', shortAny: x => x, mkProp: (Name, Value) => ({ Name, Value }), errStr: String,
    docKindOf: () => 'writer', countRedlines: () => 2, xModel: { setPropertyValue() { writes++ } },
    setRedlineAuthor: name => { context.author = name },
    compareWithBytes: () => failStage === 'compare' ? { success: false, message: 'comparison failed' } : { success: true, redlineCount: 2 },
    EXEC: {
      load_document: () => ({ success: ++loads !== (failStage === 'load-revised' ? 1 : -1), message: 'load failed' }),
      resolve_all_revisions: () => ({ success: true, remaining: failStage === 'normalize-revised' ? 1 : 0 }),
      export_document: () => ({ success: true, bytes: [1] }),
    },
    context: { getServiceManager: () => ({ createInstanceWithContext: () => ({ createInstanceWithArguments: () => ({
      getByName: name => settings[name], replaceByName: (name, value) => { settings[name] = value }, commitChanges() {},
    }) }) }) },
  }
  const run = vm.runInNewContext(`({ ${method} }).build_comparison_document`, context)
  return { run, settings, context, progress, state: () => ({ loads, writes }) }
}
test('missing inputs fail before loading documents or changing engine configuration', () => {
  const h = harness()
  assert.equal(h.run({ baseBytes: [1] }).stage, 'input')
  assert.deepEqual(h.state(), { loads: 0, writes: 0 })
  assert.deepEqual(h.settings, { Mode: undefined, UseRSID: true, IgnorePieces: true })
})
for (const stage of ['load-revised', 'normalize-revised', 'compare']) test(stage+' failure restores nullable config and author', () => {
  const h = harness(stage)
  const result = h.run({ baseBytes: [1], revisedBytes: [2] })
  assert.equal(result.success, false)
  assert.equal(result.stage, stage)
  assert.equal(h.context.author, 'User')
  assert.deepEqual(h.settings, { Mode: undefined, UseRSID: true, IgnorePieces: true })
})


test('native comparison emits real stage boundaries with its request id, without invented counts', () => {
  const h = harness()
  assert.equal(h.run({ baseBytes: [1], revisedBytes: [2], __reqId: 'compare-1' }).success, true)
  assert.deepEqual(h.progress, ['loading', 'normalizing', 'comparing', 'refining'].map(stage => ({ cmd: 'progress', reqId: 'compare-1', stage })))
})
test('successful comparison runs minimal-revision refinement under the comparison author, and reports the count', () => {
  const h = harness()
  const result = h.run({ baseBytes: [1], revisedBytes: [2], authorName: '比较者' })
  assert.equal(result.success, true)
  assert.equal(result.refined, 2)
  assert.deepEqual(h.context.refineCalls, ['比较者'])
})
test('refinement failures are best-effort: the comparison still succeeds without a refined count', () => {
  const h = harness()
  h.context.refineComparisonRedlines = () => { throw new Error('boom') }
  const result = h.run({ baseBytes: [1], revisedBytes: [2] })
  assert.equal(result.success, true)
  assert.equal(result.refined, 0)
})
test('refineMinimal=false skips the refinement stage entirely', () => {
  const h = harness()
  const result = h.run({ baseBytes: [1], revisedBytes: [2], __reqId: 'compare-9', refineMinimal: false })
  assert.equal(result.success, true)
  assert.equal(result.refined, 0)
  assert.deepEqual(h.context.refineCalls, [])
  assert.deepEqual(h.progress.map(x => x.stage), ['loading', 'normalizing', 'comparing'])
})
