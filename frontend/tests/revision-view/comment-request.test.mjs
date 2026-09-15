// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { JSDOM } from 'jsdom'
import { attachImeOverlay } from '../../src/composables/zetaOfficeImeOverlay.js'

const read = path => readFileSync(new URL(path, import.meta.url), 'utf8')
const tick = () => new Promise(resolve => setTimeout(resolve, 0))
function component(path, dependencies = {}) {
  const body = read(path).match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')
  return new Function(...Object.keys(dependencies), body)(...Object.values(dependencies))
}
const toolbarComponent = component('../../src/components/EditorToolbar.vue')

function hostFixture(t, execute) {
  const dom = new JSDOM('<button></button><div class="toolbar"><div class="etb-form"><textarea></textarea></div></div>', { pretendToBeVisual: true })
  t.after(() => dom.window.close())
  const calls = [], toasts = []
  const editor = component('../../src/components/LibreOfficeEditor.vue', {
    ReviewPanel: null, EditorToolbar: null, EvidenceStaleBar: null,
    uni: { showToast: toast => toasts.push(toast) },
  })
  const executor = { executeCommand: async (action, params) => {
    calls.push({ action, params })
    return execute ? execute(action, params) : { success: true, documentSeq: 3, selection: { collapsed: false } }
  } }
  const toolbar = { ...toolbarComponent.data(), executor, $emit() {}, $t: key => key,
    $el: dom.window.document.querySelector('.toolbar'), $refs: { trig_insert: dom.window.document.querySelector('button') } }
  Object.assign(toolbar, Object.fromEntries(Object.entries(toolbarComponent.methods).map(([key, fn]) => [key, fn.bind(toolbar)])))
  Object.defineProperty(toolbar, 'noSelection', { get: () => toolbarComponent.computed.noSelection.call(toolbar) })
  const vm = { ready: true, executor, file: { id: 7 }, _loadGen: 1, $refs: { toolbar },
    $t: key => key, $nextTick: fn => Promise.resolve().then(fn) }
  for (const name of ['menuInsertComment', 'onCommentRequest', 'subscribeHostEvents']) vm[name] = editor.methods[name].bind(vm)
  return { vm, toolbar, calls, toasts, dom }
}

test('worker request relays its document fence without a modified event', () => {
  const source = read('../../src/zetaoffice/editor-main.js')
  const body = source.slice(source.indexOf('let inlineReview = null'), source.indexOf('// (自建工具栏)'))
  const messages = []
  const relay = new Function('hostTransport', 'reviewBalloons', 'relaySelection', body + '\nreturn { relayModified, relayCommentRequest }')(
    { send: msg => messages.push(msg) }, null, () => {})
  relay.relayModified({ cmd: 'comment-request', documentSeq: 3 })
  relay.relayCommentRequest()
  assert.deepEqual(messages, [
    { __lo: 'lo-relay', type: 'comment-request', documentSeq: 3 },
    { __lo: 'lo-relay', type: 'comment-request' },
  ])
})

test('native request refreshes stale selection, opens and focuses the existing form, and writes only on confirmation', async t => {
  const f = hostFixture(t)
  assert.equal(f.toolbar.noSelection, true)
  let receive
  f.vm.subscribeHostEvents({ send() {}, subscribe(fn) { receive = fn; return () => {} } })
  receive({ __lo: 'lo-relay', type: 'comment-request', documentSeq: 3 })
  await tick()
  assert.equal(f.toolbar.menu, 'insert')
  assert.equal(f.toolbar.insertMode, 'comment')
  assert.ok(f.toolbar.popPos)
  assert.equal(f.dom.window.document.activeElement.tagName, 'TEXTAREA')
  assert.deepEqual(f.calls.map(c => c.action), ['get_ui_state'])
  f.toolbar.commentText = '核对付款期限'
  await f.toolbar.doComment()
  assert.deepEqual(f.calls.find(c => c.action === 'add_comment_at_selection'), {
    action: 'add_comment_at_selection', params: { comment: '核对付款期限' },
  })
  assert.equal(f.toasts.length, 0)
})

test('fresh empty selection shows the existing hint and never creates an annotation', async t => {
  const f = hostFixture(t, () => ({ success: true, documentSeq: 3, selection: { collapsed: true } }))
  f.toolbar.state.selection.collapsed = false
  await f.vm.onCommentRequest({ documentSeq: 3 })
  assert.equal(f.toolbar.menu, '')
  assert.equal(f.toolbar.insertMode, '')
  assert.deepEqual(f.toasts, [{ title: 'workbench.menuSelectTextFirst', icon: 'none' }])
  assert.deepEqual(f.calls.map(c => c.action), ['get_ui_state'])
})

test('duplicate requests preserve a draft and coalesce while selection is pending', async t => {
  let resolve
  const f = hostFixture(t, () => new Promise(r => { resolve = r }))
  const pending = f.vm.onCommentRequest({ documentSeq: 3 })
  await f.vm.onCommentRequest({ documentSeq: 3 })
  assert.equal(f.calls.length, 1)
  resolve({ success: true, documentSeq: 3, selection: { collapsed: false } })
  await pending
  f.toolbar.commentText = '已经写下的草稿'
  assert.deepEqual(f.vm.menuInsertComment(), { ok: true }, 'host menu contract stays synchronous')
  await tick()
  assert.equal(f.toolbar.commentText, '已经写下的草稿')
})

test('requests for another document or a replaced editor cannot open a dialog', async t => {
  const f = hostFixture(t)
  await f.vm.onCommentRequest({ documentSeq: 2 })
  assert.equal(f.toolbar.menu, '')
  let resolve
  const late = hostFixture(t, () => new Promise(r => { resolve = r }))
  const pending = late.vm.onCommentRequest({ documentSeq: 3 })
  late.vm._loadGen++
  resolve({ success: true, documentSeq: 3, selection: { collapsed: false } })
  await pending
  assert.equal(late.toolbar.menu, '')
  assert.equal(late.toasts.length, 0)
})

test('selection read failures show an error instead of using an old selection', async t => {
  const f = hostFixture(t, () => { throw new Error('disconnected') })
  f.toolbar.state.selection.collapsed = false
  await f.vm.onCommentRequest()
  assert.equal(f.toolbar.menu, '')
  assert.deepEqual(f.toasts, [{ title: 'editor.toolbar.opFailed', icon: 'none' }])
  assert.equal(f.vm._commentRequestPending, null)
})

test('IME Ctrl/Cmd+Alt+C requests the form, suppresses repeat and Copy, and respects composition', async t => {
  const dom = new JSDOM('<div><canvas></canvas></div>', { pretendToBeVisual: true })
  const saved = Object.fromEntries(['document', 'navigator', 'getComputedStyle'].map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  const writes = [], commands = [], commits = [], requests = []
  Object.defineProperty(dom.window.navigator, 'clipboard', { value: { writeText: async text => writes.push(text) } })
  for (const [key, value] of Object.entries({ document: dom.window.document, navigator: dom.window.navigator, getComputedStyle: dom.window.getComputedStyle })) {
    Object.defineProperty(globalThis, key, { configurable: true, value })
  }
  const overlay = attachImeOverlay({ canvas: document.querySelector('canvas'), commit: text => commits.push(text),
    sendCommand: async action => { commands.push(action); return { text: '正文选区' } },
    onCommentRequested: () => requests.push(true) })
  t.after(() => {
    overlay.destroy(); dom.window.close()
    for (const [key, descriptor] of Object.entries(saved)) {
      if (descriptor) Object.defineProperty(globalThis, key, descriptor)
      else delete globalThis[key]
    }
  })
  let bubbled = 0
  document.addEventListener('keydown', () => bubbled++)
  const key = props => {
    const e = new dom.window.KeyboardEvent('keydown', { key: 'c', code: 'KeyC', bubbles: true, cancelable: true, ...props })
    overlay.element.dispatchEvent(e)
    return e
  }
  assert.equal(key({ ctrlKey: true, altKey: true }).defaultPrevented, true)
  assert.equal(key({ metaKey: true, altKey: true, key: 'ç' }).defaultPrevented, true)
  assert.equal(key({ ctrlKey: true, altKey: true, repeat: true }).defaultPrevented, true)
  assert.equal(requests.length, 2)
  assert.equal(bubbled, 0)
  assert.deepEqual(commands, [])
  assert.deepEqual(commits, [])
  assert.equal(key({ ctrlKey: true, altKey: true, isComposing: true }).defaultPrevented, false)
  assert.equal(requests.length, 2)
  assert.equal(key({ ctrlKey: true }).defaultPrevented, true)
  await tick()
  assert.deepEqual(commands, ['get_selection'])
  assert.deepEqual(writes, ['正文选区'])
})
