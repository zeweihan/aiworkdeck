// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#989：树内文件从某个文件夹拖到树的空白区，原来什么都不发生（容器的 dragover
// 只对外部文件与暂存区拖回 preventDefault，树内拖拽在空白区不被当作合法落点）。
// VS Code / Finder 都把空白区当作「放到根目录」。修法：树内拖拽落到空白区 = 移到项目根，
// 复用 onRootDrop；已在根目录的不发请求也不提示。
// 误移防护（dev-board#916：2px 微拖也会形成完整的 drag-drop）：dragstart 记起点，
// drop 时位移 < 8px 视为误拖，不移动、不提示；取不到坐标时不判误拖、照原行为放行。
// 暂存区拖回共用 onTreeDrop，套同一阈值。
//   cd frontend && node --test tests/project-home/file-tree-blank-drop.test.mjs
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  nativeDataTransfer,
  isExternalFileDrag,
  claimExternalDrop,
} from '../../src/utils/fileTreeExternalDrop.js'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')

const IMPORT_NAMES = [
  'getProjectFiles', 'createFolder', 'createFile', 'renameFile', 'deleteFile', 'deleteFilePerm',
  'restoreFileApi', 'getRecycleBinFiles', 'moveFile', 'batchDeleteFiles', 'batchMoveFiles',
  'batchCopyFiles', 'getApiBaseUrl', 'getContributedTemplates', 'createFileFromContributedTemplate',
  'getSessionId', 'host', 'findTopmostDeletedAncestor', 'summarizeDeleteResults',
  'groupByParent', 'buildTreeFromGroups', 'evidenceRefCounts', 'createRefCountsFetcher',
  'warmDragImage', 'applyDragImage', 'FileTypeIcon', 'TagChip', 'TagSelector',
  'TagManager', 'AwdDatePicker', 'ICONS', 'getProjectTags', 'addTagToFile', 'removeTagFromFile',
  'createTag', 'createTask', 'importLocalFile',
  'nativeDataTransfer', 'isExternalFileDrag', 'claimExternalDrop',
]

function loadOptions(stubs) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+['"][^'"]+['"]\s*$/gm, '')
    .replace(/export default \{/, 'return {')
  const factory = new Function(...IMPORT_NAMES, body)
  const args = IMPORT_NAMES.map(n => (n in stubs ? stubs[n] : (() => {})))
  return factory(...args)
}

function makeVm() {
  const calls = { moveFile: [], loadFiles: 0, emits: [], toasts: [] }
  const options = loadOptions({
    moveFile: async (...a) => { calls.moveFile.push(a); return { parentId: a[2] } },
    ICONS: {},
    host: {},
    nativeDataTransfer, isExternalFileDrag, claimExternalDrop,
  })
  const vm = Object.assign({}, options.data.call({}))
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.projectId = 42
  vm.showTree = true
  vm.$t = (k) => k
  vm.$emit = (...a) => { calls.emits.push(a) }
  vm.$el = null
  vm.loadFiles = async () => { calls.loadFiles += 1 }
  vm.calls = calls
  globalThis.__awdToasts = calls.toasts
  return vm
}

function installGlobals() {
  const prior = { uni: globalThis.uni, window: globalThis.window, document: globalThis.document }
  globalThis.uni = {
    showToast(o) { if (globalThis.__awdToasts) globalThis.__awdToasts.push(o && o.title) },
    $emit() {}, $on() {}, $off() {},
  }
  globalThis.window = { event: null }
  globalThis.document = {}
  return () => {
    globalThis.uni = prior.uni
    globalThis.window = prior.window
    globalThis.document = prior.document
    globalThis.__awdToasts = null
  }
}

function targetInside(selectorHit) {
  return { closest: (sel) => (selectorHit && sel.includes(selectorHit) ? { className: selectorHit } : null) }
}
// uni 重建的 <view> 事件：drag 系不在补字段分支里，没有 clientX / dataTransfer / target
function wrappedEvent() {
  const e = { prevented: false, preventDefault() { e.prevented = true }, stopPropagation() {} }
  return e
}
function treeDataTransfer() {
  return { types: ['text/plain', 'application/x-checkba-file'], files: [], getData: () => '', dropEffect: 'none' }
}

const folder = { id: 7, name: '合同', isFolder: true, parentId: null, sortOrder: 3 }
const nested = { id: 1, name: '起诉状.docx', isFolder: false, parentId: 7, sortOrder: 1 }
const rootDoc = { id: 9, name: '委托书.docx', isFolder: false, parentId: null, sortOrder: 2 }

// 树内拖拽：dragstart 的原生事件在 window.event 上（坐标从那里取）
function startTreeDrag(vm, item, index, x = 100, y = 100) {
  vm.windowedDisplayFiles = [folder, nested, rootDoc]
  vm.displayFiles = [folder, nested, rootDoc]
  globalThis.window.event = { type: 'dragstart', clientX: x, clientY: y }
  vm.handleDragStart(wrappedEvent(), item, index)
  vm.isAnyDragging = true
}
function nativeDropAt(x, y, selectorHit = null, dt = treeDataTransfer()) {
  globalThis.window.event = { type: 'drop', dataTransfer: dt, target: targetInside(selectorHit), clientX: x, clientY: y }
}

test('树内文件拖到空白区、位移 >= 8px：dragover 放行并点亮，drop 移到项目根并提示成功', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, nested, 1)
    globalThis.window.event = { type: 'dragover', dataTransfer: treeDataTransfer(), target: targetInside(null), clientX: 100, clientY: 160 }
    const over = wrappedEvent()
    vm.onTreeDragOver(over)
    assert.equal(over.prevented, true, '空白区不 preventDefault 浏览器就不派发 drop')
    assert.equal(vm.stagingDragOver, true, '空白区高亮复用 #1005 的样式开关')
    nativeDropAt(100, 160)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 1, null, 0]])
    assert.deepEqual(vm.calls.toasts, ['fileTree.moveToRootSuccess'])
    assert.equal(vm.stagingDragOver, false, 'drop 后熄灭')
  } finally { restore() }
})

test('树内文件拖到节点上时容器不点亮（节点自己有 drop 高亮）', () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, nested, 1)
    globalThis.window.event = { type: 'dragover', dataTransfer: treeDataTransfer(), target: targetInside('.tree-item'), clientX: 100, clientY: 130 }
    vm.onTreeDragOver(wrappedEvent())
    assert.equal(vm.stagingDragOver, false)
  } finally { restore() }
})

test('树内文件拖到空白区、位移只有 3px：视为误拖，不移动也不提示', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, nested, 1, 100, 100)
    nativeDropAt(102, 102)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [])
    assert.deepEqual(vm.calls.toasts, [])
    assert.equal(vm.stagingDragOver, false)
  } finally { restore() }
})

test('取不到坐标（起点缺失）不判误拖：照常移到根', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder, nested, rootDoc]
    vm.displayFiles = [folder, nested, rootDoc]
    globalThis.window.event = null
    vm.handleDragStart(wrappedEvent(), nested, 1)
    vm.isAnyDragging = true
    nativeDropAt(300, 300)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 1, null, 0]])
    assert.deepEqual(vm.calls.toasts, ['fileTree.moveToRootSuccess'])
  } finally { restore() }
})

test('取不到坐标（落点缺失）不判误拖：节点 drop 照常移入文件夹', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, rootDoc, 2, 100, 100)
    globalThis.window.event = { type: 'drop', dataTransfer: treeDataTransfer(), target: targetInside('.tree-item') }
    await vm.handleDrop(wrappedEvent(), 0)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 9, 7, 0]])
    assert.deepEqual(vm.calls.toasts, ['fileTree.moveSuccess'])
  } finally { restore() }
})

test('已在根目录的文件拖到空白区：不发请求，也不提示错误', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, rootDoc, 2)
    nativeDropAt(100, 200)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [])
    assert.deepEqual(vm.calls.toasts, [])
  } finally { restore() }
})

test('树内文件落到文件夹节点上：仍走节点 drop（移入该文件夹），容器那次不重复处理', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, rootDoc, 2)
    nativeDropAt(100, 200, '.tree-item')
    await vm.handleDrop(wrappedEvent(), 0)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 9, 7, 0]])
    assert.deepEqual(vm.calls.toasts, ['fileTree.moveSuccess'])
  } finally { restore() }
})

test('落到「加载更多」行也算空白区', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, nested, 1)
    globalThis.window.event = {
      type: 'drop', dataTransfer: treeDataTransfer(), clientX: 100, clientY: 400,
      target: { closest: (sel) => (sel.includes(':not(.tree-item-load-more)') ? null : {}) },
    }
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 1, null, 0]])
  } finally { restore() }
})

test('暂存区拖回空白区、位移只有 3px：同样不移动、不提示', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    globalThis.document.__checkbaDraggedFile = { fileId: 2447, name: 'README.txt', fileType: 'txt' }
    // 暂存区的 dragstart 经 uni 的 file-drag-start 通知本树（mounted 里的 _onDragStart）
    globalThis.window.event = { type: 'dragstart', clientX: 50, clientY: 500 }
    vm.recordDragStartPoint()
    vm.isAnyDragging = true
    nativeDropAt(53, 500, null, { types: [], files: [], getData: () => '' })
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [])
    assert.deepEqual(vm.calls.toasts, [])
  } finally { restore() }
})

test('mounted 的 file-drag-start 监听记下起点；拖拽结束清掉', () => {
  assert.match(SRC, /this\._onDragStart = \(\) => \{[^}]*this\.recordDragStartPoint\(\)/)
  assert.match(SRC, /this\._onDragEnd = \(\) => \{[^}]*this\.dragStartPoint = null/)
})

// #916 的微拖不只落在空白区：落在相邻的文件夹节点、或拖拽时临时出现的「移至根目录」
// 区块上，节点/区块自己的 drop 同样要套阈值；判定后冒泡到容器的那次也不许再处理。
test('3px 微拖落到文件夹节点：不移入、不提示、清高亮，冒泡到容器也不再处理', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, rootDoc, 2, 100, 100)
    vm.dragOverIndex = 0
    nativeDropAt(101, 103, '.tree-item')
    await vm.handleDrop(wrappedEvent(), 0)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [])
    assert.deepEqual(vm.calls.toasts, [])
    assert.equal(vm.dragOverIndex, -1)
    assert.equal(vm.stagingDragOver, false)
  } finally { restore() }
})

test('3px 微拖落到「移至根目录」区块：不移动、不提示、清高亮', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startTreeDrag(vm, nested, 1, 100, 100)
    vm.rootDropActive = true
    nativeDropAt(103, 100, '.root-drop-zone')
    await vm.onRootDrop(wrappedEvent())
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [])
    assert.deepEqual(vm.calls.toasts, [])
    assert.equal(vm.rootDropActive, false)
  } finally { restore() }
})

test('暂存区拖回 3px 落到文件夹节点 / 根投放区：同一判据，不移动', async () => {
  const restore = installGlobals()
  try {
    for (const hit of ['.tree-item', '.root-drop-zone']) {
      const vm = makeVm()
      vm.windowedDisplayFiles = [folder, nested, rootDoc]
      vm.displayFiles = [folder, nested, rootDoc]
      globalThis.document.__checkbaDraggedFile = { fileId: 2447, name: 'README.txt', fileType: 'txt' }
      globalThis.window.event = { type: 'dragstart', clientX: 50, clientY: 500 }
      vm.recordDragStartPoint()
      vm.isAnyDragging = true
      nativeDropAt(52, 501, hit, { types: [], files: [], getData: () => '' })
      if (hit === '.tree-item') await vm.handleDrop(wrappedEvent(), 0)
      else await vm.onRootDrop(wrappedEvent())
      await vm.onTreeDrop(wrappedEvent())
      assert.deepEqual(vm.calls.moveFile, [], hit)
      assert.deepEqual(vm.calls.toasts, [], hit)
    }
  } finally { restore() }
})
