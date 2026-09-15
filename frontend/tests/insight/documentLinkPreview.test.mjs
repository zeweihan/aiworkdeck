// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { reactive, watch } from 'vue'
import { build } from 'esbuild'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { documentLinkPreviewMethods } from '../../src/pages/project-overview/documentLinkPreview.js'
import { attachDocumentLinkClicks } from '../../src/composables/zetaOfficeLinkClick.js'

function host(side = 'left') {
  return Object.assign({ splitMode: side === 'right', focusedPane: side, activeFileIdLeft: 1, activeFileIdRight: 2,
    opened: [], closeInsightHoverCard() {}, handleFileLinkClick(url, preview) { this.evidence = { url, preview } },
    openBrowserTab(url, pane) { this.opened.push({ url, pane }) }, openFileLinkTarget(target, pane) { this.opened.push({ target, pane }) },
  }, documentLinkPreviewMethods)
}
test('external links preview first, then split without replacing the source pane', async () => {
  for (const side of ['left', 'right']) {
    const h = host(side)
    h.openDocumentLinkPreview({ url: 'https://example.com/doc', fileId: side === 'left' ? 1 : 2, meta: { hostX: 350, hostY: 220 } })
    assert.equal(h.opened.length, 0)
    assert.equal(h.documentLinkPreview.x, 350)
    await h.openDocumentLinkAside()
    assert.equal(h.splitMode, true)
    assert.equal(h.opened[0].pane, side === 'left' ? 'right' : 'left')
    assert.equal(h.activeFileIdLeft, 1)
    assert.equal(h.activeFileIdRight, 2)
  }
})
test('bookmark preview does not navigate; evidence goes through preview lookup', async () => {
  const h = reactive(host())
  h.openDocumentLinkPreview({ url: '#target', fileId: 1, target: { name: 'target', text: 'Original target' } })
  assert.equal(h.documentLinkPreview.target.text, 'Original target')
  assert.equal(h.opened.length, 0)
  h.openDocumentLinkPreview({ url: 'checkba://filelink?k=EVID_1&projectId=8', fileId: 1 })
  assert.equal(h.evidence.preview, h.documentLinkPreview)
  await h.openDocumentLinkAside({ fileId: 1 })
  assert.equal(h.opened.length, 0, 'same-file source position is preserved')
  await h.openDocumentLinkAside({ fileId: 5, locator: { quote: 'Source' } })
  assert.equal(h.opened[0].pane, 'right')
})
function guest(execute = async () => ({ success: true, url: 'https://example.com' })) {
  const dom = new JSDOM('<canvas></canvas>')
  const canvas = dom.window.document.querySelector('canvas'), sent = [], contexts = [], qt = []
  const dispose = attachDocumentLinkClicks({ canvas, execute, send: m => sent.push(m), cursorContext: m => contexts.push(m) })
  canvas.addEventListener('mousedown', e => qt.push({ ctrl: e.ctrlKey, meta: e.metaKey }))
  const click = (mods = {}, drag = false) => {
    for (const type of ['mousedown', 'mouseup']) canvas.dispatchEvent(new dom.window.MouseEvent(type,
      { bubbles: true, cancelable: true, clientX: type === 'mouseup' && drag ? 180 : 100, clientY: 200, button: 0, ...mods }))
  }
  return { dom, click, sent, contexts, qt, dispose }
}
const settle = () => new Promise(r => setTimeout(r, 180))
test('Cmd/Ctrl activation positions Qt without modifiers and opens one preview; ordinary click and drag keep editing', async () => {
  const g = guest()
  g.click(); await settle(); assert.equal(g.sent.length, 0)
  g.click({ ctrlKey: true }); await settle()
  assert.equal(g.sent.length, 1)
  assert.equal(g.sent[0].meta.ctrlKey, true)
  assert.deepEqual(g.qt[1], { ctrl: false, meta: false })
  assert.equal(g.contexts.length, 1, 'hyperlink does not concurrently open an entity card')
  g.click({ metaKey: true }, true); await settle(); assert.equal(g.sent.length, 1)
  g.dispose(); g.dom.window.close()
})
test('continued typing discards an in-flight click lookup', async () => {
  let resolve
  const g = guest(() => new Promise(r => { resolve = r }))
  g.click({ metaKey: true }); await settle()
  g.dom.window.document.dispatchEvent(new g.dom.window.KeyboardEvent('keydown', { key: 'a' }))
  resolve({ success: true, url: '#old' }); await settle()
  assert.equal(g.sent.length, 0)
  g.dispose(); g.dom.window.close()
})

// Bundle the actual route, openFile deduplication, and tab mover. Only IO is
// stubbed; reproducing the dedup logic in a fake openFile would miss this bug.
const sourceRoot = fileURLToPath(new URL('../../src/', import.meta.url))
const built = await build({ stdin: { contents: `
  export { evidenceLinkMethods } from './pages/project-overview/evidenceLinkActions.js';
  export { fileOpenTabsMethods } from './pages/project-overview/fileOpenTabs.js';
  export { tabDragSplitMethods } from './pages/project-overview/tabDragSplit.js';
`, resolveDir: sourceRoot }, bundle: true, write: false, format: 'esm', platform: 'node', plugins: [{ name: 'io-only', setup(b) {
  b.onResolve({ filter: /^@\/(services\/api|utils\/activityTracker|config\/icons)\.js$/ }, args => ({ path: args.path, namespace: 'stub' }))
  b.onLoad({ filter: /.*/, namespace: 'stub' }, args => ({ contents: args.path.includes('api')
    ? 'export const getFileDetail=(...a)=>globalThis.__documentLinkTestIo.detail(...a); export const getEvidenceLink=(...a)=>globalThis.__documentLinkTestIo.evidence(...a); export const createEvidenceLink=()=>{}; export const addEvidenceTargets=()=>{}; export const updateEvidenceTarget=()=>{}; export const getProjectFiles=()=>{};'
    : args.path.includes('activityTracker') ? 'export const activityTracker={trackActivePage(){}}' : 'export const ICONS={}; export const fileGlyph=()=>"";' }))
  b.onResolve({ filter: /^@\// }, args => ({ path: path.join(sourceRoot, args.path.slice(2)) }))
} }] })
const routes = await import('data:text/javascript;base64,' + Buffer.from(built.outputFiles[0].text).toString('base64'))
function realHost(io = {}) {
  globalThis.__documentLinkTestIo = { detail: async () => ({ id: 2, name: 'B.docx', fileType: 'docx' }), evidence: async () => ({ targets: [{ id: 5, fileId: 2, file: { name: 'B.docx' } }] }), ...io }
  const h = reactive(Object.assign(host(), routes.fileOpenTabsMethods, routes.tabDragSplitMethods, routes.evidenceLinkMethods, {
    projectId: 8, project: { id: 8 }, leftFiles: [{ id: 1, name: 'A.docx' }, { id: 2, name: 'B.docx' }], rightFiles: [],
    fileLinkPicker: { visible: false }, lastActiveIdsByMode: { left: {}, right: {} },
    $t: key => key, $nextTick: fn => fn(), isFileTypeSupported: () => true, saveActiveIdsByMode() {}, triggerWorkbenchResize() {},
    useLibreEditor: () => true, _libreRefs: {},
  }))
  h.$watch = (read, cb, options) => watch(read, cb, options)
  return h
}
const pending = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
const drain = async () => { await Promise.resolve(); await Promise.resolve(); await Promise.resolve() }

test('real openFile/moveTabTo move background B alongside A, after saving its live editor', async () => {
  const h = realHost(); let saved = 0
  h._libreRefs['left:2'] = { ready: true, dirty: true, file: { id: 2 }, flushSave: async () => { saved++; return true } }
  h.openDocumentLinkPreview({ fileId: 1, url: '#local', target: { text: 'excerpt' } })
  await h.openDocumentLinkAside({ fileId: 2, locator: { quote: 'B target' } })
  assert.equal(saved, 1)
  assert.equal(h.activeFileIdLeft, 1)
  assert.equal(h.activeFileIdRight, 2)
  assert.deepEqual(h.leftFiles.map(f => f.id), [1])
  assert.deepEqual(h.rightFiles.map(f => f.id), [2])
  assert.equal(h.rightFiles[0].pendingLocator.quote, 'B target')
})

test('a failed background save leaves source and destination untouched and shows the error', async () => {
  const h = realHost()
  h._libreRefs['left:2'] = { ready: true, dirty: true, file: { id: 2 }, flushSave: async () => false }
  h.openDocumentLinkPreview({ fileId: 1, url: '#local' })
  await h.openDocumentLinkAside({ fileId: 2 })
  assert.equal(h.activeFileIdLeft, 1); assert.equal(h.splitMode, false); assert.equal(h.rightFiles.length, 0)
  assert.equal(h.documentLinkPreview.error, 'editor.moveTabSaveFailed')
  h.closeDocumentLinkPreview()
})

test('source tab A -> B -> A closes preview and rejects the late evidence response', async () => {
  const response = pending(), h = realHost({ evidence: () => response.promise })
  h.openDocumentLinkPreview({ fileId: 1, url: 'checkba://filelink?k=EVID_1&projectId=8' })
  const old = h.documentLinkPreview
  h.activeFileIdLeft = 2; h.activeFileIdLeft = 1
  assert.equal(h.documentLinkPreview, null)
  response.resolve({ targets: [{ id: 99, fileId: 9 }] }); await drain()
  assert.deepEqual(old.targets, []); assert.equal(h.documentLinkPreview, null)
})

test('project change while file detail is pending cannot open or move a tab', async () => {
  const response = pending(), h = realHost({ detail: () => response.promise })
  h.openDocumentLinkPreview({ fileId: 1, url: '#local' })
  const opening = h.openDocumentLinkAside({ fileId: 2 })
  h.projectId = 9; h.project = { id: 9 }
  h.projectId = 8; h.project = { id: 8 } // returning cannot revive the previous request
  response.resolve({ id: 2, name: 'Old project B.docx' }); await opening
  assert.equal(h.documentLinkPreview, null); assert.equal(h.activeFileIdLeft, 1)
  assert.equal(h.splitMode, false); assert.equal(h.rightFiles.length, 0)
})

test('source change during the save await also prevents the pending tab move', async () => {
  const save = pending(), h = realHost()
  h._libreRefs['left:2'] = { ready: true, dirty: true, file: { id: 2 }, flushSave: () => save.promise }
  h.openDocumentLinkPreview({ fileId: 1, url: '#local' })
  const opening = h.openDocumentLinkAside({ fileId: 2 }); await drain()
  h.activeFileIdLeft = 2
  save.resolve(true); await opening
  assert.equal(h.activeFileIdLeft, 2); assert.equal(h.rightFiles.length, 0); assert.equal(h.splitMode, false)
})
