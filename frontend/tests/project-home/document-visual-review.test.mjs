// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { visualPageRange, runDocumentVisualReview } from '../../src/composables/documentVisualReview.mjs'
function fixture({ cancel = 0, stale = 0, active = true } = {}) {
  let dialogs = 0, reads = 0
  const commands = [], requests = [], messages = [], busy = []
  const args = {
    fileId: 7, current: () => active, t: (key, params) => key + (params ? JSON.stringify(params) : ''),
    dialog: async options => { messages.push(options); dialogs++; return { confirm: dialogs !== cancel, content: '2-3' } },
    busy: v => busy.push(v),
    execute: async action => { commands.push(action); return action === 'export_pdf'
      ? { success: true, bytes: [37,80,68,70] } : { success: true, revision: ++reads >= stale && stale ? 2 : 1 } },
    request: async data => { requests.push(data); return { revision: 1, report: '第2页序号重复', checkedPages: [2,3], totalPages: 8, complete: false } },
  }
  return { args, commands, requests, messages, busy }
}
test('page range rejects unbounded, invalid or oversized scope', () => {
  for (const text of ['0', '2-1', '1-7', '1,3', 'abc', '1.5', '9007199254740993']) assert.equal(visualPageRange(text), null)
  assert.deepEqual(visualPageRange('2-4'), { startPage: 2, endPage: 4 })
})
test('cancelled selection or consent does not export or call a model', async () => {
  for (const cancel of [1,2]) { const f = fixture({ cancel }); assert.equal(await runDocumentVisualReview(f.args), false); assert.deepEqual(f.commands, []); assert.deepEqual(f.requests, []) }
})
test('exports current PDF only after consent and reports actual pages without claiming complete', async () => {
  const f = fixture(); assert.equal(await runDocumentVisualReview(f.args), true)
  assert.equal(f.requests.length, 1); assert.equal(f.requests[0].confirmed, true)
  assert.equal(f.requests[0].base64, 'JVBERg=='); assert.equal(f.requests[0].docFileId, 7)
  assert.match(f.messages.at(-1).content, /2, 3/); assert.match(f.messages.at(-1).content, /partial/)
  assert.equal(f.busy.at(-1), false)
})
test('edits during export prevent model calls; edits during request discard results', async () => {
  for (const stale of [2,3]) { const f = fixture({ stale }); assert.equal(await runDocumentVisualReview(f.args), false); assert.equal(f.requests.length, stale === 2 ? 0 : 1); assert.equal(f.messages.at(-1).content, 'stale') }
})
test('closed or replaced editor cannot start a check', async () => {
  const f = fixture({ active: false }); assert.equal(await runDocumentVisualReview(f.args), false); assert.deepEqual(f.commands, [])
})
test('closing the editor during a revision read prevents charges or a stale report', async () => {
  for (const closeAt of [2, 3]) {
    const f = fixture(); let active = true, reads = 0
    f.args.current = () => active
    const execute = f.args.execute
    f.args.execute = async action => {
      const result = await execute(action)
      if (action === 'get_document_text' && ++reads === closeAt) active = false
      return result
    }
    assert.equal(await runDocumentVisualReview(f.args), false)
    assert.equal(f.requests.length, closeAt === 2 ? 0 : 1)
    assert.equal(f.messages.length, 2)
  }
})
