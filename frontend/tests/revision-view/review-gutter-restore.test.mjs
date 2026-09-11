// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Temporary revision-view switches (export, revision resolution, AI final-text
// reads) must restore balloons with exactly the gutter the host chose. The
// worker's real view functions run in a vm realm over a mock Writer view.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const slice = (from, to) => {
  const start = source.indexOf(from), stop = source.indexOf(to, start)
  assert.ok(start >= 0 && stop > start, 'worker contains ' + from)
  return source.slice(start, stop)
}
const declaration = name => slice('\nfunction ' + name + '(', '\n}\n') + '\n}\n'

function view() {
  const state = { showChanges: true, inMargin: false, width: 0, writes: [], refreshes: 0 }
  const realm = vm.createContext({
    xModel: {
      getPropertyValue(name) { assert.equal(name, 'ShowChanges'); return state.showChanges },
      setPropertyValue(name, any) { assert.equal(name, 'RedlineDisplayType'); state.showChanges = any.val === 2 },
      isSetModifiedEnabled: () => true, disableSetModified() {}, enableSetModified() {},
      refresh() { state.refreshes++ },
    },
    ctrl: {
      getViewSettings: () => ({
        getPropertyValue(name) { assert.equal(name, 'ShowChangesInMargin'); return state.inMargin },
        setPropertyValue(name, value) { state.inMargin = value },
      }),
      getPropertyValue(name) { assert.equal(name, 'AwdReviewSidebarWidth'); return state.width },
      setPropertyValue(name, value) { assert.equal(name, 'AwdReviewSidebarWidth'); state.writes.push(value); state.width = value },
    },
    shortAny: n => ({ val: n }), errStr: String, dispatchUno() { throw new Error('toggle not expected') },
    installReviewCommentInterceptor: () => true, supportsReviewGeometry: () => true, invalidateParaIndex() {},
    isWriterDoc: () => true, isReviewWritable: () => true, viewChangeInFlight: 0,
    tableFail: message => ({ success: false, message }),
  })
  vm.runInContext(slice('const REVISION_VIEWS = ', '// 批注插入不该被记成修订') + declaration('runRevisionResolution')
    + declaration('runAgentCommandInMarginView') + 'const AGENT_VIEW_EXEMPT = {};\n'
    + 'const actions = ({' + slice('  set_revision_view(p) {', '\n  // [spike] Phase B') + '});', realm)
  const run = code => vm.runInContext(code, realm)
  const mode = () => run('revisionViewState().mode')
  // The user picks balloons; the host then releases the empty gutter (only insertions, no comments).
  function releasedBalloons() {
    assert.equal(run("actions.set_revision_view({ mode: 'balloons' })").success, true)
    assert.deepEqual(state.writes, [280], 'a user switch into balloons reserves the default gutter')
    state.width = 0; state.writes.length = 0
    assert.equal(mode(), 'balloons')
  }
  return { state, run, mode, releasedBalloons }
}

test('an export in balloons view keeps a released gutter released', () => {
  const v = view(); v.releasedBalloons()
  let seen
  v.run('withInlineMarkupForExport')(() => { seen = v.mode() })
  assert.equal(seen, 'all', 'the export itself sees inline markup')
  assert.equal(v.mode(), 'balloons')
  assert.deepEqual(v.state.writes, [], 'no gutter write, so no relayout or page jump')
  assert.equal(v.state.width, 0)
})

test('an export keeps a reserved gutter at the width the host chose', () => {
  const v = view(); v.releasedBalloons()
  v.state.width = 320   // host-set width other than the default
  v.run('withInlineMarkupForExport')(() => {})
  assert.deepEqual(v.state.writes, []); assert.equal(v.state.width, 320)
})

test('revision resolution restores balloons without reserving the gutter again', () => {
  const v = view(); v.releasedBalloons()
  v.state.showChanges = false   // markup hidden behind the worker's back (e.g. Writer's own menu)
  let seen
  const result = v.run('runRevisionResolution')(() => { seen = v.state.showChanges; return { success: true } }, {}, 'resolve_revision')
  assert.deepEqual(JSON.parse(JSON.stringify(result)), { success: true })
  assert.equal(seen, true, 'resolution runs with markup shown')
  assert.deepEqual(v.state.writes, []); assert.equal(v.state.width, 0)
})

test('AI final-text reads in all-markup view never touch the gutter', () => {
  const v = view(); v.state.width = 0
  let seen
  v.run('runAgentCommandInMarginView')('get_document_text', () => { seen = v.mode(); return { success: true } })
  assert.equal(seen, 'margin'); assert.equal(v.mode(), 'all')
  assert.deepEqual(v.state.writes, [])
})

test('choosing balloons again while already in balloons keeps the host gutter', () => {
  const v = view(); v.releasedBalloons()
  assert.equal(v.run("actions.set_revision_view({ mode: 'balloons' })").success, true)
  assert.deepEqual(v.state.writes, []); assert.equal(v.state.width, 0)
  assert.equal(v.run("actions.set_revision_view({ mode: 'all' })").success, true)
  assert.equal(v.run("actions.set_revision_view({ mode: 'balloons' })").success, true)
  assert.deepEqual(v.state.writes, [280], 'a real switch from another mode reserves the default again')
})
