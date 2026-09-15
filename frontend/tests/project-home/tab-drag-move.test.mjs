// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 标签跨窗格拖拽的语义（dev-board#542）。
//
// 原来 moveTabTo 里写死 `isCrossPane ? { ...source } : splice`：把标签拖到另一侧，
// 源侧那一份还留着，等于「复制」。产品决策改成——普通拖拽是**移动**（源列表摘掉），
// 按住 Alt/Option 才是「在另一侧再开一份」（保留双开与 .tab-dual-open 那套样式）。
//
// 修饰键必须在 dragstart 那一下记：uni-h5 把 <view> 上的事件重建成普通对象，
// altKey 一类一概不补（同 fileOpenTabs.js 的 mouseButtonOf），dragover/drop 阶段
// 更没有原生事件可读。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/tabDragSplit.js', import.meta.url), 'utf8')

function loadMethods(win) {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const tabDragSplitMethods = \{/, 'return {')
  const factory = new Function(
    'activityTracker', 'rightPanelMaxWidth', 'leftPanelMaxWidth', 'wheelDeltaOf', 'uni', 'window', body)
  return factory({ track: () => {} }, () => 0, () => 0, () => 0, { showToast: () => {} }, win)
}
const methods = loadMethods({ event: null })

function makeVm(left, right, opts = {}) {
  const vm = {
    splitMode: true,
    focusedPane: 'left',
    leftPaneKey: 'files',
    leftFiles: left,
    rightFiles: right,
    activeFileIdLeft: opts.activeLeft ?? (left[0] && left[0].id) ?? null,
    activeFileIdRight: opts.activeRight ?? (right[0] && right[0].id) ?? null,
    lastActiveIdsByMode: { left: {}, right: {} },
    saveActiveIdsByMode() {},
    triggerWorkbenchResize() {},
    $nextTick(fn) { fn && fn() },
  }
  vm.moveTabTo = methods.moveTabTo.bind(vm)
  vm.onTabDragStart = methods.onTabDragStart.bind(vm)
  return vm
}
const ids = (list) => list.map(f => f.id)

test('跨窗格默认是移动：源列表少一项、目标多一项，且是同一个对象', () => {
  const a = { id: 'a', name: 'a.docx' }
  const vm = makeVm([a, { id: 'b' }], [])
  vm.moveTabTo('a', 'left', 'right', null)
  assert.deepEqual(ids(vm.leftFiles), ['b'], '源侧没把标签摘掉，还是老的「复制」语义')
  assert.deepEqual(ids(vm.rightFiles), ['a'])
  assert.equal(vm.rightFiles[0], a, '移动应当搬同一个对象过去，不是拷一份')
  assert.equal(vm.activeFileIdRight, 'a')
  assert.equal(vm.focusedPane, 'right')
})

test('copy:true 时源侧保留一份（Alt/Option 拖拽 = 左右双开）', () => {
  const a = { id: 'a', name: 'a.docx' }
  const vm = makeVm([a, { id: 'b' }], [])
  vm.moveTabTo('a', 'left', 'right', null, { copy: true })
  assert.deepEqual(ids(vm.leftFiles), ['a', 'b'], 'Alt 拖拽必须保留源侧那一份')
  assert.deepEqual(ids(vm.rightFiles), ['a'])
  assert.notEqual(vm.rightFiles[0], a, '双开的两份是各自的对象（各自维护 pendingLocator 等）')
})

test('目标窗格已经开着同一个 id：只激活，不重复插入', () => {
  const vm = makeVm([{ id: 'a' }, { id: 'b' }], [{ id: 'a' }, { id: 'c' }], { activeRight: 'c' })
  vm.moveTabTo('a', 'left', 'right', null)
  assert.deepEqual(ids(vm.rightFiles), ['a', 'c'], '目标侧不许出现两个同 id 标签')
  assert.deepEqual(ids(vm.leftFiles), ['b'], '移动语义下源侧仍要摘掉')
  assert.equal(vm.activeFileIdRight, 'a')
})

test('搬走的是源侧活动标签：按关闭标签的同一条规则让相邻的顶上', () => {
  const vm = makeVm([{ id: 'a' }, { id: 'b' }, { id: 'c' }], [], { activeLeft: 'b' })
  vm.moveTabTo('b', 'left', 'right', null)
  assert.deepEqual(ids(vm.leftFiles), ['a', 'c'])
  assert.equal(vm.activeFileIdLeft, 'c', '取被搬走那一格的后继（同 closeFile 的 min(idx, len-1)）')
  assert.equal(vm.lastActiveIdsByMode.left.files, 'c', '按模式记忆的活动标签也要跟着改')
})

test('把源侧最后一个标签搬走：源侧活动标签置 null，回到既有的空窗格状态', () => {
  const vm = makeVm([{ id: 'a' }], [{ id: 'z' }], { activeLeft: 'a' })
  vm.moveTabTo('a', 'left', 'right', null)
  assert.deepEqual(ids(vm.leftFiles), [])
  assert.equal(vm.activeFileIdLeft, null)
})

test('搬走的不是源侧活动标签：源侧激活态不动', () => {
  const vm = makeVm([{ id: 'a' }, { id: 'b' }], [], { activeLeft: 'a' })
  vm.moveTabTo('b', 'left', 'right', null)
  assert.equal(vm.activeFileIdLeft, 'a')
})

test('同窗格换序不受 copy 影响，永远是移动', () => {
  const vm = makeVm([{ id: 'a' }, { id: 'b' }, { id: 'c' }], [])
  vm.moveTabTo('c', 'left', 'left', 'a', { copy: true })
  assert.deepEqual(ids(vm.leftFiles), ['c', 'a', 'b'])
})

test('未分屏时不许往右窗格搬（既有护栏没被 opts 破坏）', () => {
  const vm = makeVm([{ id: 'a' }], [])
  vm.splitMode = false
  vm.moveTabTo('a', 'left', 'right', null)
  assert.deepEqual(ids(vm.leftFiles), ['a'])
  assert.deepEqual(ids(vm.rightFiles), [])
})

// ---------- 修饰键的采集口径 ----------

test('dragstart：uni 重建的事件没有 altKey，从 window.event 上取', () => {
  const m = loadMethods({ event: { altKey: true } })
  const vm = { draggingTab: null, tabDragOver: null }
  m.onTabDragStart.call(vm, { type: 'dragstart', target: {} }, { id: 'a' }, 'left')
  assert.equal(vm.draggingTab.copy, true, 'Alt 按着起手，却没记成 copy')
})

test('dragstart：没按 Alt 就是移动', () => {
  const m = loadMethods({ event: { altKey: false } })
  const vm = { draggingTab: null, tabDragOver: null }
  m.onTabDragStart.call(vm, { type: 'dragstart', target: {} }, { id: 'a' }, 'left')
  assert.equal(vm.draggingTab.copy, false)
})

test('dragstart：回调自带 altKey 时优先用回调的', () => {
  const m = loadMethods({ event: { altKey: false } })
  const vm = { draggingTab: null, tabDragOver: null }
  m.onTabDragStart.call(vm, { altKey: true, target: {} }, { id: 'a' }, 'left')
  assert.equal(vm.draggingTab.copy, true)
})

test('drop 两个落点都经 commitTabDrop 把 copy 传给 moveTabTo', () => {
  assert.match(SRC, /moveTabTo\(payload\.fileId, payload\.fromPane, targetPane, beforeFileId, \{ copy \}\)/,
    'commitTabDrop 没把 copy 传下去，Alt 复制就退化成移动了')
  for (const h of ['onTabDropOnZone', 'onTabDropOnItem']) {
    assert.match(SRC, new RegExp(h + '[\\s\\S]{0,400}?commitTabDrop\\('), h + ' 没走 commitTabDrop')
  }
})

test('跨窗格移动前先落盘：源侧编辑器实例会被卸掉，从 beforeUnmount 保存已经太晚', () => {
  assert.match(SRC, /flushTabBeforePaneMove/, '缺少移动前的落盘闸')
  assert.match(SRC, /await this\.flushTabBeforePaneMove\(payload\.fileId, payload\.fromPane\)/)
  assert.match(SRC, /inst\.flushSave\(\{ timeoutMs: 10000 \}\)/, 'Libre 实例要按 closeFile 同一口径 flush')
})
