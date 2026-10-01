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
  const context = {
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
  return { run, settings, context, state: () => ({ loads, writes }) }
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
