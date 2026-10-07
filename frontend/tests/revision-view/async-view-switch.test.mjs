// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import fs from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'
import assert from 'node:assert/strict'
const source = fs.readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
function harness(initial = 'all') {
  let mode = initial, author = ''
  const results = []
  const switches = []
  const ctx = vm.createContext({
    xModel: {}, INFLIGHT: {}, CANCELLED: {}, AI_AUTHOR: 'AI WorkDeck', humanAuthor: 'Editor',
    setRedlineAuthor: value => { author = value }, log() {}, errStr: String,
    post: (kind, data) => { if (kind === 'result') results.push(data) },
    isWriterDoc: () => true, revisionViewState: () => ({ mode }), hasAnyRedline: () => true,
    withViewOnlyChange: fn => fn(), applyRevisionView(next) { switches.push(next); mode = next; return { mode } },
    ctrl: { getViewCursor: () => ({}) }, paragraphTextOf: () => '',
    REVISION_VIEWS: ['all', 'balloons', 'margin', 'final'], supportsReviewGeometry: () => true,
    installReviewCommentInterceptor: () => true, tableFail: message => ({ success: false, message }),
  })
  const guard = source.slice(source.indexOf('const FINAL_TEXT_ACTIONS = '), source.indexOf('\nconst RESOLVE_REVISION_ACTIONS'))
  const command = source.slice(source.indexOf('  set_revision_view(p) {'), source.indexOf('  // [spike] Phase B:', source.indexOf('  set_revision_view(p) {')))
  vm.runInContext(guard + '\nconst EXEC = {' + command + '};', ctx)
  vm.runInContext('const RESOLVE_REVISION_ACTIONS = new Set();\n' + source.slice(source.indexOf('function execCommand('), source.indexOf('// ---- message loop')), ctx)
  return { switchDocument: () => { ctx.xModel = {}; mode = 'all' }, cancel: id => { ctx.CANCELLED[id] = true }, dispatch: vm.runInContext('execCommand', ctx), actions: vm.runInContext('EXEC', ctx), author: () => author, results, run: vm.runInContext('runAgentCommandInMarginView', ctx), set: vm.runInContext('EXEC.set_revision_view', ctx), mode: () => mode, switches }
}
for (const fail of [false, true]) test(`user view waits for async final-text edits and survives ${fail ? 'failure' : 'success'}`, async () => {
  const h = harness()
  let finish
  const gate = new Promise(resolve => { finish = resolve })
  const batch = h.run('find_replace', async () => {
    await gate
    assert.equal(h.mode(), 'final', 'the rest of the batch must edit final text, even after a user selection')
    if (fail) throw new Error('edit failed')
    return { success: true }
  })
  const checked = fail ? assert.rejects(batch, /edit failed/) : batch
  const selection = h.set({ mode: 'balloons' })
  assert.equal(h.mode(), 'final', 'a display selection must not change paragraph membership mid-batch')
  finish()
  await checked
  assert.equal((await selection).mode, 'balloons')
  assert.equal(h.mode(), 'balloons', 'batch restoration must not overwrite the user selection')
  assert.deepEqual(h.switches, ['final', 'all', 'balloons'])
  assert.equal(h.set({ mode: 'all' }).mode, 'all', 'the gate is released after completion')
})
test('multiple requested views retain order and synchronous commands need no gate', async () => {
  const h = harness()
  let finish
  const batch = h.run('find_replace', () => new Promise(resolve => { finish = resolve }))
  const first = h.set({ mode: 'balloons' }), last = h.set({ mode: 'all' })
  finish({ success: true })
  await batch; await first; await last
  assert.equal(h.mode(), 'all')
  assert.deepEqual(h.switches, ['final', 'all', 'balloons', 'all'])
  h.run('modify_paragraph', () => ({ success: true }))
  assert.equal(h.set({ mode: 'balloons' }).mode, 'balloons')
})

for (const initial of ['balloons', 'margin', 'final']) test(`a batch already in ${initial} keeps final text until it settles`, async () => {
  const h = harness(initial)
  let finish
  const gate = new Promise(resolve => { finish = resolve })
  const batch = h.run('find_replace', async () => { await gate; assert.equal(h.mode(), initial); return { success: true } })
  const selection = h.set({ mode: 'all' })
  assert.equal(h.mode(), initial)
  finish()
  await batch; await selection
  assert.equal(h.mode(), 'all')
})

test("overlapping final-text commands cannot outlive each other's view guard", async () => {
  const h = harness()
  let finishFirst, finishSecond, secondStarted = false
  const first = h.run('find_replace', () => new Promise(resolve => { finishFirst = resolve }))
  const second = h.run('apply_house_style', () => {
    secondStarted = true
    assert.equal(h.mode(), 'final')
    return new Promise(resolve => { finishSecond = resolve })
  })
  const selection = h.set({ mode: 'balloons' })
  assert.equal(secondStarted, false)
  finishFirst({ success: true })
  await first
  await Promise.resolve()
  assert.equal(secondStarted, true)
  assert.equal(h.mode(), 'final')
  finishSecond({ success: true })
  await second; await selection
  assert.equal(h.mode(), 'balloons')
})

for (const firstIsAi of [true, false]) test(`queued commands set their own author after ${firstIsAi ? 'AI' : 'human'} edits`, async () => {
  const h = harness()
  let finish
  const authors = []
  h.actions.apply_review_edit = () => { authors.push(h.author()); return new Promise(resolve => { finish = resolve }) }
  h.actions.accept_completion = () => { authors.push(h.author()); return { success: true } }
  h.dispatch('first', 'apply_review_edit', { __agent: firstIsAi })
  h.dispatch('second', 'accept_completion', { __agent: !firstIsAi })
  assert.deepEqual(authors, [firstIsAi ? 'AI WorkDeck' : 'Editor'])
  assert.equal(h.author(), authors[0], 'enqueueing must not change the running edit author')
  finish({ success: true })
  for (let i = 0; i < 10; i++) await Promise.resolve()
  assert.deepEqual(authors, firstIsAi ? ['AI WorkDeck', 'Editor'] : ['Editor', 'AI WorkDeck'])
  assert.equal(h.results.length, 2)
  assert.ok(h.results.every(r => r.result.success))
})

test('switching documents drops queued edits and view choices without restoring the old view', async () => {
  const h = harness()
  let finish, edited = false
  const first = h.run('find_replace', () => new Promise(resolve => { finish = resolve }))
  const queued = h.run('apply_review_edit', () => { edited = true; return { success: true } })
  const selection = h.set({ mode: 'balloons' })
  h.switchDocument()
  finish({ success: true })
  await first
  assert.equal((await queued).success, false)
  assert.equal((await selection).success, false)
  assert.equal(edited, false)
  assert.deepEqual(h.switches, ['final'], 'do not restore a stale view on the newly opened document')
})

test('a command cancelled while queued never mutates the document', async () => {
  const h = harness()
  let finish, edited = false
  h.actions.apply_review_edit = () => new Promise(resolve => { finish = resolve })
  h.actions.accept_completion = () => { edited = true; return { success: true } }
  h.dispatch('first', 'apply_review_edit', { __agent: true })
  h.dispatch('cancelled', 'accept_completion', {})
  h.cancel('cancelled')
  finish({ success: true })
  for (let i = 0; i < 10; i++) await Promise.resolve()
  assert.equal(edited, false)
  assert.equal(h.results.find(r => r.reqId === 'cancelled').result.success, false)
})
