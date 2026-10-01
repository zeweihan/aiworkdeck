// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
const worker = fs.readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const method = worker.slice(worker.indexOf('  load_document(p) {'), worker.indexOf('  // [Track E] export the current document'))
function harness({ failLoad = false, same = false, failClose = false } = {}) {
  const calls = []
  const old = { getCurrentController: () => 'old-controller', setPropertyValue() {},
    setModified: value => calls.push(['modified', value]),
    close: value => { calls.push(['close', value]); if (failClose) throw Error('close veto') },
  }
  const loaded = same ? old : { getCurrentController: () => 'new-controller', setPropertyValue() {} }
  const context = {
    xModel: old, ctrl: 'old-controller', docSeq: 0, humanAuthor: '', IMPORT_FILTERS: { docx: 'docx' },
    css: { ucb: { SimpleFileAccess: { create: () => ({ exists: () => false, writeFile() {} }) } },
      io: { SequenceInputStream: { createStreamFromSequence: () => ({ closeInput() {} }) } } },
    context: {}, desktop: { loadComponentFromURL: () => { calls.push(['load']); return failLoad ? null : loaded } },
    mkProp: (Name, Value) => ({ Name, Value }), errStr: String, log: text => calls.push(['log', text]),
    docKindOf: () => 'writer', isWriterDoc: () => true,
    installReviewCommentInterceptor() {}, installContextMenuInterceptor() {}, ensureFullScreen() {},
    installKeyHandler() {}, installModifyListener() {}, installSelectionListener() {}, dropAiAnchors() {},
    resetRevisionView: () => calls.push(['ready', context.xModel === loaded]),
  }
  const run = vm.runInNewContext(`({ ${method} }).load_document`, context)
  return { run, old, loaded, context, calls }
}
test('successful replacement closes the old model only after the new model is ready', () => {
  const h = harness()
  assert.equal(h.run({ bytes: [1], name: 'new.docx' }).success, true)
  assert.equal(h.context.xModel, h.loaded)
  assert.equal(h.context.ctrl, 'new-controller')
  assert.deepEqual(h.calls.filter(x => ['ready','modified','close'].includes(x[0])), [['ready',true],['modified',false],['close',true]])
})
test('both import failures retain the old model and its modified state', () => {
  const h = harness({ failLoad: true })
  assert.equal(h.run({ bytes: [1], name: 'bad.docx' }).code, 'DOC_REJECTED')
  assert.equal(h.context.xModel, h.old)
  assert.equal(h.calls.filter(x => x[0] === 'load').length, 2)
  assert.equal(h.calls.some(x => ['modified','close'].includes(x[0])), false)
})
test('empty input does not close the current document', () => {
  const h = harness()
  assert.equal(h.run({ bytes: [] }).empty, true)
  assert.deepEqual(h.calls, [])
})
test('a loader returning the current model does not close it', () => {
  const h = harness({ same: true })
  assert.equal(h.run({ bytes: [1] }).success, true)
  assert.equal(h.calls.some(x => x[0] === 'close'), false)
})
test('close veto is reported without retrying import or replacing the new model', () => {
  const h = harness({ failClose: true })
  assert.equal(h.run({ bytes: [1] }).success, true)
  assert.equal(h.context.xModel, h.loaded)
  assert.equal(h.calls.filter(x => x[0] === 'load').length, 1)
  assert.ok(h.calls.some(x => x[0] === 'log' && x[1].includes('previous model close failed')))
})
