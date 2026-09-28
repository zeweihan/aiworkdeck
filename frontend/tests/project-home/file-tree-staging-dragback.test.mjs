// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#974（v0.49.0 BUG-78 / C9-09）：从「文件暂存区」把文件拖回资源管理器，
// 只有拖拽时临时出现的「拖拽到此处移至根目录」小区块生效，放到树的空白处静默无效。
//   cd frontend && node --test tests/project-home/file-tree-staging-dragback.test.mjs
//
// 根因：.tree-content 容器上的 onTreeDragOver 只对外部文件（types 含 'Files'）调
// preventDefault，应用内拖拽（暂存区 → 树）在空白区不被当作合法落点，浏览器根本不派发
// drop；onTreeDrop 也只认外部文件。修法：暂存区拖来的文件在树空白区 = 移到项目根
// （复用 onRootDrop），节点上的 drop 仍归节点自己的 handleDrop（文件夹 = 移入）；
// 回收站视图或认不出拖的是什么时给一条 toast，不再静默。
// 加载方式同 file-tree-external-drop.test.mjs：抠出 <script> new Function 求值。
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

// 暂存区条目是 <view>，uni 重建的 dragstart 没有 dataTransfer，setData 那段根本不跑：
// 原生 dataTransfer 的 types 是空的，只剩全局兜底 + uni 的 file-drag-start 信号
// （FileTree mounted 里 $on 到 isAnyDragging）。
function stagingDataTransfer() {
  return { types: [], files: [], getData: () => '', dropEffect: 'none' }
}
function startStagingDrag(vm, file = { fileId: 2447, name: 'README.txt', fileType: 'txt' }) {
  globalThis.document.__checkbaDraggedFile = { ...file }
  vm.isAnyDragging = true
}
// 原生事件的 target：closest 按选择器判断是不是落在某个节点里
function targetInside(selectorHit) {
  return { closest: (sel) => (selectorHit && sel.includes(selectorHit) ? { className: selectorHit } : null) }
}
function wrappedEvent() {
  const e = { prevented: false, preventDefault() { e.prevented = true }, stopPropagation() {} }
  return e
}

const folder = { id: 7, name: '合同', isFolder: true, parentId: null, sortOrder: 3 }
const doc = { id: 1, name: '起诉状.docx', isFolder: false, parentId: 7, sortOrder: 1 }

test('暂存区文件拖过树的空白区：dragover 要 preventDefault，否则浏览器不派发 drop（静默无效的根因）', () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startStagingDrag(vm)
    const dt = stagingDataTransfer()
    globalThis.window.event = { dataTransfer: dt, target: targetInside(null) }
    const e = wrappedEvent()
    vm.onTreeDragOver(e)
    assert.equal(e.prevented, true)
    assert.equal(dt.dropEffect, 'move')
    assert.equal(vm.stagingDragOver, true, '容器点亮（与外部文件拖入同一个 external-drag-over 样式类）')
    // 拖出整个容器后熄灭
    globalThis.window.event = { relatedTarget: null }
    vm.onTreeDragLeave(wrappedEvent())
    assert.equal(vm.stagingDragOver, false)
  } finally { restore() }
})

// dev-board#989 起树内拖拽在空白区也放行（= 移到根，见 file-tree-blank-drop.test.mjs），
// 这里只守「不当成暂存区拖回」。
test('树内自己的拖拽（draggedIndex 有值）在空白区不当成暂存区拖回', () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.draggedIndex = 0
    vm.draggedFileId = 1
    vm.isAnyDragging = true
    globalThis.window.event = { dataTransfer: stagingDataTransfer(), target: targetInside(null) }
    assert.equal(vm.isStagingFileDrag(), false)
  } finally { restore() }
})

test('上一次树内拖拽留下的全局兜底残留、此刻并没有文件在拖：空白区不接', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    globalThis.document.__checkbaDraggedFile = { fileId: 1, name: '起诉状.docx' }
    globalThis.window.event = { dataTransfer: { types: ['application/json'], files: [] }, target: targetInside(null) }
    const e = wrappedEvent()
    vm.onTreeDragOver(e)
    assert.equal(e.prevented, false)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [])
  } finally { restore() }
})

test('暂存区文件放到树空白区：移到项目根（parentId=null），提示成功并消费全局兜底', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startStagingDrag(vm)
    globalThis.window.event = { dataTransfer: stagingDataTransfer(), target: targetInside(null) }
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 2447, null, 0]])
    assert.equal(vm.calls.loadFiles, 1)
    assert.deepEqual(vm.calls.toasts, ['fileTree.moveToRootSuccess'])
    assert.equal(globalThis.document.__checkbaDraggedFile, null)
    assert.ok(vm.calls.emits.some(a => a[0] === 'files-changed'), '通知工作台刷新暂存区')
    assert.equal(vm.stagingDragOver, false, 'drop 后容器熄灭')
  } finally { restore() }
})

test('放到文件夹节点上：节点的 handleDrop 移入该文件夹，冒泡到容器那次不再重复移到根', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder, doc]
    vm.displayFiles = [folder, doc]
    startStagingDrag(vm)
    globalThis.window.event = { dataTransfer: stagingDataTransfer(), target: targetInside('.tree-item') }
    await vm.handleDrop(wrappedEvent(), 0)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 2447, 7, 0]])
    assert.deepEqual(vm.calls.toasts, ['fileTree.moveSuccess'])
  } finally { restore() }
})

test('放到「移至根目录」区块：区块自己处理，容器不重复处理', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    startStagingDrag(vm)
    globalThis.window.event = { dataTransfer: stagingDataTransfer(), target: targetInside('.root-drop-zone') }
    await vm.onRootDrop(wrappedEvent())
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [[42, 2447, null, 0]])
    // 去重守卫失效时容器会再走一遍 onRootDrop，全局兜底已被消费，于是多弹一条「无法识别」
    assert.deepEqual(vm.calls.toasts, ['fileTree.moveToRootSuccess'])
  } finally { restore() }
})

test('回收站视图里放下：不移动，给一条提示而不是静默', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.viewMode = 'recycle'
    startStagingDrag(vm)
    globalThis.window.event = { dataTransfer: stagingDataTransfer(), target: targetInside(null) }
    const over = wrappedEvent()
    vm.onTreeDragOver(over)
    assert.equal(over.prevented, true, '要让 drop 派发出来才能提示')
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.moveFile, [])
    assert.deepEqual(vm.calls.toasts, ['fileTree.dropInRecycleBin'])
    assert.equal(globalThis.document.__checkbaDraggedFile, null, '兜底也要清掉，免得下一次落空的 drop 捡到它')
  } finally { restore() }
})

test('节点上 drop 但认不出拖的是哪份文件：提示而不是静默', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    globalThis.window.event = { dataTransfer: { types: ['application/json'], files: [], getData: () => '' }, target: targetInside('.tree-item') }
    await vm.handleDrop(wrappedEvent(), 0)
    assert.deepEqual(vm.calls.moveFile, [])
    assert.deepEqual(vm.calls.toasts, ['fileTree.dropInvalidTarget'])
  } finally { restore() }
})

const STAGING_SRC = readFileSync(new URL('../../src/components/FileStagingArea.vue', import.meta.url), 'utf8')
function loadStagingOptions() {
  const body = STAGING_SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace(/export default \{/, 'return {')
  return new Function('ICONS', 'UnlockHint', 'FileTypeIcon', 'warmDragImage', 'applyDragImage', body)({}, {}, {}, () => {}, () => {})
}

test('暂存区拖拽被取消（dragend 无人消费）后，再把编辑器标签拖到文件夹行上：不会把陈旧的暂存文件移进去', async () => {
  const restore = installGlobals()
  try {
    const staging = loadStagingOptions()
    const stagingVm = {}
    for (const [k, fn] of Object.entries(staging.methods)) stagingVm[k] = fn.bind(stagingVm)
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder, doc]
    vm.displayFiles = [folder, doc]
    startStagingDrag(vm)
    // 拖出窗口 / Esc：没有任何 drop，只有源上的 dragend
    stagingVm.onDragEnd()
    vm.isAnyDragging = false
    assert.equal(globalThis.document.__checkbaDraggedFile, null, '暂存区 dragend 清掉全局兜底')
    // 接着拖一个编辑器标签（application/json）落在文件夹行上
    globalThis.window.event = { dataTransfer: { types: ['application/json'], files: [], getData: () => '' }, target: targetInside('.tree-item') }
    await vm.handleDrop(wrappedEvent(), 0)
    assert.deepEqual(vm.calls.moveFile, [], '陈旧的暂存文件不许被移进文件夹')
    assert.deepEqual(vm.calls.toasts, ['fileTree.dropInvalidTarget'])
  } finally { restore() }
})

test('树内拖拽的 dragend 同样清掉全局兜底', () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    globalThis.document.__checkbaDraggedFile = { fileId: 1, name: '起诉状.docx' }
    vm.draggedIndex = 1
    vm.handleDragEnd()
    assert.equal(globalThis.document.__checkbaDraggedFile, null)
  } finally { restore() }
})

test('zh-CN / en-US 都有两条新提示文案', () => {
  const zh = readFileSync(new URL('../../src/locales/zh-CN/fileTree.js', import.meta.url), 'utf8')
  const en = readFileSync(new URL('../../src/locales/en-US/fileTree.js', import.meta.url), 'utf8')
  for (const src of [zh, en]) {
    assert.match(src, /dropInvalidTarget:/)
    assert.match(src, /dropInRecycleBin:/)
  }
})
