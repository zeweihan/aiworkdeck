// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 关闭分屏时右窗格标签的去向（v0.49.0 真机 C9-02）。
//
// 病灶：toggleSplitMode 只把 splitMode 置 false，rightFiles / activeFileIdRight 原样留着。
// 右窗格是 v-if="splitMode"，关掉后那些标签看不见却仍然开着：左侧关空后 AI「当前文档」
// （pickActiveContextTab 回落到 activeFileRight）仍指向看不见的文档；在资源管理器点这类
// 文件，openFile 发现它在右侧，又把分屏自己打开。
// 期望（对齐 VS Code）：关闭分屏时右窗格标签并入左窗格，右侧清空；搬走前先落盘，落不下就不关。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { pickActiveContextTab } from '../../src/pages/project-overview/activeTabContext.js'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/tabDragSplit.js', import.meta.url), 'utf8')

function loadMethods(uniStub) {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const tabDragSplitMethods = \{/, 'return {')
  const factory = new Function(
    'activityTracker', 'rightPanelMaxWidth', 'leftPanelMaxWidth', 'bindHorizontalWheelAll', 'uni', 'window', body)
  return factory({ trackActivePage: () => {} }, () => 0, () => 0, () => {}, uniStub, { event: null })
}

function makeVm(left, right, opts = {}) {
  const toasts = []
  const methods = loadMethods({ showToast: (o) => toasts.push(o) })
  const vm = {
    toasts,
    splitMode: true,
    focusedPane: opts.focused || 'right',
    leftPaneKey: 'files',
    leftFiles: left,
    rightFiles: right,
    activeFileIdLeft: opts.activeLeft !== undefined ? opts.activeLeft : ((left[0] && left[0].id) || null),
    activeFileIdRight: opts.activeRight !== undefined ? opts.activeRight : ((right[0] && right[0].id) || null),
    lastActiveIdsByMode: { left: {}, right: {} },
    _libreRefs: opts.libreRefs || {},
    _plainTextRefs: {},
    project: null,
    saveActiveIdsByMode() {},
    triggerWorkbenchResize() {},
    useLibreEditor: (f) => /\.(docx|xlsx)$/.test(f.name || ''),
    isPlainTextFile: () => false,
    $t: (k) => k,
    $nextTick(fn) { fn && fn() },
  }
  for (const k of Object.keys(methods)) vm[k] = methods[k].bind(vm)
  Object.defineProperty(vm, 'activeFileLeft', { get() { return this.leftFiles.find(f => f.id === this.activeFileIdLeft) || null } })
  Object.defineProperty(vm, 'activeFileRight', { get() { return this.rightFiles.find(f => f.id === this.activeFileIdRight) || null } })
  return vm
}
const ids = (list) => list.map(f => f.id)

test('关闭分屏：右窗格标签并入左窗格末尾，右侧清空', async () => {
  const vm = makeVm([{ id: '1', name: 'a.docx' }], [{ id: '2', name: 'b.xlsx' }, { id: '3', name: 'c.pdf' }], { activeRight: '3' })
  await vm.toggleSplitMode()
  assert.equal(vm.splitMode, false)
  assert.deepEqual(ids(vm.leftFiles), ['1', '2', '3'], '右窗格的标签关分屏后必须出现在左窗格，不能隐身继续开着')
  assert.deepEqual(ids(vm.rightFiles), [])
  assert.equal(vm.activeFileIdRight, null)
  assert.equal(vm.focusedPane, 'left')
})

test('关闭分屏时焦点在右侧：右侧活动标签成为左侧活动标签', async () => {
  const vm = makeVm([{ id: '1', name: 'a.docx' }], [{ id: '2', name: 'b.xlsx' }, { id: '3', name: 'c.pdf' }], { activeRight: '2', focused: 'right' })
  await vm.toggleSplitMode()
  assert.equal(vm.activeFileIdLeft, '2')
})

test('关闭分屏时焦点在左侧：左侧活动标签不变', async () => {
  const vm = makeVm([{ id: '1', name: 'a.docx' }], [{ id: '2', name: 'b.xlsx' }], { focused: 'left' })
  await vm.toggleSplitMode()
  assert.equal(vm.activeFileIdLeft, '1')
})

test('左右双开同一份：并入后左侧只留一个标签', async () => {
  const vm = makeVm([{ id: '1', name: 'a.docx' }], [{ id: '1', name: 'a.docx' }, { id: '2', name: 'b.pdf' }])
  await vm.toggleSplitMode()
  assert.deepEqual(ids(vm.leftFiles), ['1', '2'])
})

test('左侧关空之后 AI「当前文档」不再指向看不见的右侧文档', async () => {
  const vm = makeVm([], [{ id: '2', name: 'b.xlsx' }], { activeLeft: null })
  await vm.toggleSplitMode()
  const ctx = pickActiveContextTab({ focusedPane: vm.focusedPane, activeFileLeft: vm.activeFileLeft, activeFileRight: vm.activeFileRight })
  assert.equal(ctx && ctx.id, '2', '并入后它就是左侧可见的活动文档')
  vm.leftFiles.splice(0) ; vm.activeFileIdLeft = null
  const after = pickActiveContextTab({ focusedPane: vm.focusedPane, activeFileLeft: vm.activeFileLeft, activeFileRight: vm.activeFileRight })
  assert.equal(after, null, '关分屏后不许有隐身的右侧文档继续当「当前文档」')
})

test('右侧有未保存修改：先落盘再并入', async () => {
  let flushed = 0
  const inst = { ready: true, docLoadFailed: false, file: {}, dirty: true, saving: false, flushSave: async () => { flushed++; return true } }
  const vm = makeVm([], [{ id: '2', name: 'b.docx' }], { activeLeft: null, libreRefs: { 'right:2': inst } })
  await vm.toggleSplitMode()
  assert.equal(flushed, 1)
  assert.deepEqual(ids(vm.leftFiles), ['2'])
})

test('落盘失败：不关分屏、不搬标签，并提示', async () => {
  const inst = { ready: true, docLoadFailed: false, file: {}, dirty: true, saving: false, flushSave: async () => false }
  const vm = makeVm([{ id: '1', name: 'a.docx' }], [{ id: '2', name: 'b.docx' }], { libreRefs: { 'right:2': inst } })
  await vm.toggleSplitMode()
  assert.equal(vm.splitMode, true, '右侧改动没落盘就卸载引擎会丢改动')
  assert.deepEqual(ids(vm.rightFiles), ['2'])
  assert.equal(vm.toasts.length, 1)
})

test('开启分屏不受影响：聚焦右侧', async () => {
  const vm = makeVm([{ id: '1', name: 'a.docx' }], [], { focused: 'left' })
  vm.splitMode = false
  await vm.toggleSplitMode()
  assert.equal(vm.splitMode, true)
  assert.equal(vm.focusedPane, 'right')
})

test('关分屏可重入：落盘慢时连触发两次，最终仍是关闭且不重复并入', async () => {
  const inst = { ready: true, docLoadFailed: false, file: {}, dirty: true, saving: false,
    flushSave: () => new Promise((r) => setTimeout(() => r(true), 50)) }
  const vm = makeVm([{ id: '1', name: 'a.docx' }], [{ id: '2', name: 'b.docx' }], { libreRefs: { 'right:2': inst } })
  await Promise.all([vm.toggleSplitMode(), vm.toggleSplitMode()])
  assert.equal(vm.splitMode, false, '双击 / 加速键连发不能把分屏又翻回开启')
  assert.deepEqual(ids(vm.leftFiles), ['1', '2'])
  assert.deepEqual(ids(vm.rightFiles), [])
  assert.equal(vm.focusedPane, 'left')
})
