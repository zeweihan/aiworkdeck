// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 可编辑文档不许左右双开（dev-board#987）。
//
// Alt/Option 跨窗格拖拽原本是「在另一侧再开一份」（#542 保留下来的双开）。对 docx 这类
// 走 LibreOffice 引擎的文档，两侧各是一个独立的活引擎实例（_libreRefs 按 'pane:id' 记）：
// 右侧改完落盘后，左侧那个实例手里还是旧内容，之后左侧一保存就把右侧的改动整份覆盖。
// 引擎的 reloadFromBackend 会无条件丢掉本地未保存态、也不保留光标/滚动，不能拿来做「另一侧
// 落盘后同步」。所以可编辑类型（Libre 引擎 / 纯文本编辑器）的 Alt 拖拽一律按移动处理，
// 只读类型（pdf / 图片 / 浏览器等）仍可双开。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/tabDragSplit.js', import.meta.url), 'utf8')

function loadMethods(uniStub) {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const tabDragSplitMethods = \{/, 'return {')
  const factory = new Function(
    'activityTracker', 'rightPanelMaxWidth', 'leftPanelMaxWidth', 'bindHorizontalWheelAll', 'uni', 'window', body)
  return factory({ track: () => {} }, () => 0, () => 0, () => {}, uniStub, { event: null })
}

const OFFICE = ['docx', 'xlsx', 'pptx']
const TEXT = ['txt', 'md']

function makeVm(left, right) {
  const toasts = []
  const methods = loadMethods({ showToast: (o) => toasts.push(o.title) })
  const flushed = []
  const vm = {
    splitMode: true,
    focusedPane: 'left',
    leftPaneKey: 'files',
    leftFiles: left,
    rightFiles: right,
    activeFileIdLeft: left[0] ? left[0].id : null,
    activeFileIdRight: right[0] ? right[0].id : null,
    lastActiveIdsByMode: { left: {}, right: {} },
    draggingTab: null,
    tabDragOver: null,
    _libreRefs: {},
    _plainTextRefs: {},
    saveActiveIdsByMode() {},
    triggerWorkbenchResize() {},
    $nextTick(fn) { fn && fn() },
    $t: (k) => k,
    useLibreEditor: (f) => !!f && !f.tabType && OFFICE.includes(f.fileType),
    isPlainTextFile: (f) => !!f && !f.tabType && TEXT.includes(f.fileType),
    isDrawioFile: (f) => !!f && !f.tabType && f.fileType === 'drawio',
    isMergeReviewTab: (f) => !!(f && f.tabType === 'merge-review'),
  }
  for (const k of Object.keys(methods)) {
    if (typeof methods[k] === 'function') vm[k] = methods[k].bind(vm)
  }
  // 源侧的活实例：有未保存改动，flushSave 成功
  vm._libreRefs['left:1'] = {
    ready: true, docLoadFailed: false, file: { id: '1' }, dirty: true, saving: false,
    flushSave: async () => { flushed.push('left:1'); return true },
  }
  return { vm, toasts, flushed }
}
const ids = (list) => list.map(f => f.id)

test('docx：Alt 拖到另一侧按移动处理，不产生第二个引擎实例', async () => {
  const doc = { id: '1', name: 'a.docx', fileType: 'docx' }
  const { vm, toasts, flushed } = makeVm([doc, { id: '2', name: 'b.pdf', fileType: 'pdf' }], [])
  await vm.commitTabDrop({ fileId: '1', fromPane: 'left', copy: true }, 'right', null)
  assert.deepEqual(ids(vm.leftFiles), ['2'], '左侧仍留着一份 docx = 左右各一个活引擎实例')
  assert.deepEqual(ids(vm.rightFiles), ['1'])
  assert.deepEqual(flushed, ['left:1'], '降级成移动时同样要先落盘（源侧实例会被卸掉）')
  assert.deepEqual(toasts, ['editor.dualOpenEditableBlocked'], '要告诉用户为什么没双开')
})

test('xlsx / pptx 同样不许双开', async () => {
  for (const t of ['xlsx', 'pptx']) {
    const { vm } = makeVm([{ id: '1', name: 'a.' + t, fileType: t }], [])
    await vm.commitTabDrop({ fileId: '1', fromPane: 'left', copy: true }, 'right', null)
    assert.deepEqual(ids(vm.leftFiles), [], t + ' 被双开了')
    assert.deepEqual(ids(vm.rightFiles), ['1'])
  }
})

test('纯文本编辑器（md）也是可编辑实例，同样不许双开', async () => {
  const { vm } = makeVm([{ id: '1', name: 'a.md', fileType: 'md' }], [])
  await vm.commitTabDrop({ fileId: '1', fromPane: 'left', copy: true }, 'right', null)
  assert.deepEqual(ids(vm.leftFiles), [])
  assert.deepEqual(ids(vm.rightFiles), ['1'])
})

test('只读类型（pdf / 图片）Alt 拖拽仍是左右双开', async () => {
  for (const t of ['pdf', 'png']) {
    const f = { id: '9', name: 'x.' + t, fileType: t }
    const { vm, toasts } = makeVm([f], [])
    await vm.commitTabDrop({ fileId: '9', fromPane: 'left', copy: true }, 'right', null)
    assert.deepEqual(ids(vm.leftFiles), ['9'], t + ' 的双开被误伤')
    assert.deepEqual(ids(vm.rightFiles), ['9'])
    assert.deepEqual(toasts, [])
  }
})

test('docx 落盘失败：不搬也不双开，报保存失败', async () => {
  const doc = { id: '1', name: 'a.docx', fileType: 'docx' }
  const { vm, toasts } = makeVm([doc], [])
  vm._libreRefs['left:1'].flushSave = async () => false
  await vm.commitTabDrop({ fileId: '1', fromPane: 'left', copy: true }, 'right', null)
  assert.deepEqual(ids(vm.leftFiles), ['1'])
  assert.deepEqual(ids(vm.rightFiles), [])
  assert.deepEqual(toasts, ['editor.moveTabSaveFailed'])
})

test('drawio：两个 iframe 各自整份写回 xml，同样不许双开', async () => {
  const { vm, toasts } = makeVm([{ id: '1', name: 'a.drawio', fileType: 'drawio' }], [])
  await vm.commitTabDrop({ fileId: '1', fromPane: 'left', copy: true }, 'right', null)
  assert.deepEqual(ids(vm.leftFiles), [], 'drawio 被双开了')
  assert.deepEqual(ids(vm.rightFiles), ['1'])
  assert.deepEqual(toasts, ['editor.dualOpenEditableBlocked'])
})

test('合并比对稿：非只读不许双开，只读仍可双开', async () => {
  const mk = (readonly) => ({
    id: 'merge-review_7_a.docx', tabType: 'merge-review', fileType: 'merge-review',
    mergeSpec: { path: 'a.docx', readonly },
  })
  const rw = makeVm([mk(false)], [])
  await rw.vm.commitTabDrop({ fileId: 'merge-review_7_a.docx', fromPane: 'left', copy: true }, 'right', null)
  assert.deepEqual(ids(rw.vm.leftFiles), [], '可裁决的合并比对稿被双开了')
  assert.deepEqual(ids(rw.vm.rightFiles), ['merge-review_7_a.docx'])

  const ro = makeVm([mk(true)], [])
  await ro.vm.commitTabDrop({ fileId: 'merge-review_7_a.docx', fromPane: 'left', copy: true }, 'right', null)
  assert.deepEqual(ids(ro.vm.leftFiles), ['merge-review_7_a.docx'], '只读比对稿的双开被误伤')
  assert.deepEqual(ids(ro.vm.rightFiles), ['merge-review_7_a.docx'])
})
