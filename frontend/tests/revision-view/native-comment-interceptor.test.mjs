// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const begin = source.indexOf('// ---- Native comment requests')
const end = source.indexOf('// ---- boot:', begin)
class UnoAny { constructor(val) { this.val = val } }

function environment({ width = 280, fails = false } = {}) {
  const calls = [], events = [], status = [], registrations = [], releases = []
  const delegate = {
    dispatch(url, args) { calls.push({ command: url.Complete, args }) },
    addStatusListener(listener, url) { status.push(['add', listener, url]) },
    removeStatusListener(listener, url) { status.push(['remove', listener, url]) },
  }
  const slave = { queryDispatch: () => delegate }
  const frame = {
    registerDispatchProviderInterceptor(interceptor) {
      registrations.push(interceptor)
      if (fails) throw new Error('not supported')
      interceptor.setSlaveDispatchProvider(slave)
    },
    releaseDispatchProviderInterceptor(interceptor) { releases.push(interceptor) },
  }
  const controller = { getFrame: () => frame, getPropertyValue: () => width }
  const realm = vm.createContext({ ctrl: controller, docSeq: 4,
    zetajs: { unoObject: (_interfaces, implementation) => implementation, fromAny: value => value instanceof UnoAny ? value.val : value },
    css: { frame: { XDispatch: 'dispatch', XDispatchProviderInterceptor: 'interceptor', XInterceptorInfo: 'info' } },
    post: (cmd, payload) => events.push({ cmd, ...payload }), log() {}, errStr: e => e.message,
  })
  assert.ok(begin >= 0 && end > begin, 'worker contains the native comment interception seam')
  vm.runInContext(source.slice(begin, end), realm)
  const install = () => vm.runInContext('installReviewCommentInterceptor(ctrl)', realm)
  return { calls, events, status, registrations, releases, controller, frame, slave, realm, install,
    width(value) { width = value },
    query(command = '.uno:InsertAnnotation') {
      return registrations.at(-1).queryDispatch({ Complete: command }, '', 0)
    },
  }
}

test('native empty-comment dispatch requests the existing form before any document insertion', () => {
  const e = environment(); assert.equal(e.install(), true)
  for (const args of [[], [{ Name: 'Author', Value: 'Reviewer' }], [{ Name: 'Text', Value: '' }], [{ Name: 'Text', Value: new UnoAny('') }]]) {
    e.query().dispatch({ Complete: '.uno:InsertAnnotation' }, args)
  }
  assert.equal(e.calls.length, 0)
  assert.deepEqual(e.events, Array.from({ length: 4 }, () => ({ cmd: 'comment-request', documentSeq: 4 })))
})

test('comments with supplied text and unrelated commands retain native dispatch and status listeners', () => {
  const e = environment(); e.install()
  const args = [{ Name: 'Text', Value: new UnoAny('Confirmed comment') }], url = { Complete: '.uno:InsertAnnotation' }
  const dispatch = e.query(), listener = {}
  dispatch.addStatusListener(listener, url)
  dispatch.dispatch(url, args)
  dispatch.removeStatusListener(listener, url)
  e.query('.uno:Bold').dispatch({ Complete: '.uno:Bold' }, [])
  assert.deepEqual(e.calls, [{ command: '.uno:InsertAnnotation', args }, { command: '.uno:Bold', args: [] }])
  assert.deepEqual(e.status, [['add', listener, url], ['remove', listener, url]])
  assert.equal(e.events.length, 0)
  assert.deepEqual(Array.from(e.registrations[0].getInterceptedURLs()), ['.uno:InsertAnnotation'])
})

test('a cached dispatch checks current native gutter state rather than the state when the menu was built', () => {
  const e = environment({ width: 0 }); e.install(); const dispatch = e.query(), url = { Complete: '.uno:InsertAnnotation' }
  dispatch.dispatch(url, [])
  e.width(280); dispatch.dispatch(url, [])
  e.width(0); dispatch.dispatch(url, [])
  assert.equal(e.calls.length, 2); assert.equal(e.events.length, 1)
})

test('retargeting releases the old frame interceptor and cached old menu actions cannot target the new document', () => {
  const e = environment(); e.install(); const old = e.query()
  const nextFrame = { ...e.frame }
  e.realm.ctrl = { getFrame: () => nextFrame, getPropertyValue: () => 280 }; e.realm.docSeq = 5
  assert.equal(e.install(), true); assert.equal(e.releases.length, 1)
  old.dispatch({ Complete: '.uno:InsertAnnotation' }, [])
  assert.equal(e.calls.length, 0); assert.equal(e.events.length, 0)
  e.query().dispatch({ Complete: '.uno:InsertAnnotation' }, [])
  assert.deepEqual(e.events, [{ cmd: 'comment-request', documentSeq: 5 }])
})

test('registration is idempotent and failure stays unavailable without repeatedly retrying the same controller', () => {
  const good = environment(); assert.equal(good.install(), true); assert.equal(good.install(), true)
  assert.equal(good.registrations.length, 1)
  const bad = environment({ fails: true }); assert.equal(bad.install(), false); assert.equal(bad.install(), false)
  assert.equal(bad.registrations.length, 1); assert.equal(bad.events.length, 0)
})


test('an unavailable input interceptor cannot hide the native annotation editor', () => {
  const e = environment({ fails: true }); const writes = []
  e.controller.setPropertyValue = (...args) => writes.push(args)
  Object.assign(e.realm, { isWriterDoc: () => true, supportsReviewGeometry: () => true,
    tableFail: message => ({ success: false, message }), withViewOnlyChange: fn => fn() })
  const start = source.indexOf('  set_review_balloons(p) {')
  const stop = source.indexOf('\n  get_review_context()', start)
  vm.runInContext('const actions = ({' + source.slice(start, stop) + '});', e.realm)
  assert.equal(vm.runInContext('actions.set_review_balloons({enabled:true})', e.realm).success, false)
  assert.deepEqual(writes, [])
  assert.equal(vm.runInContext('actions.set_review_balloons({enabled:false})', e.realm).available, true)
  assert.deepEqual(writes, [['AwdReviewSidebarWidth', 0]])
})
