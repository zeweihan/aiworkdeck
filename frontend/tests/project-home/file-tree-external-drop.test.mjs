// dev-board#363 / #409 / #513：把 Finder / 资源管理器 / 微信里的文件或文件夹直接拖进左栏
// 「资源管理器」。   cd frontend && npm run test:project-home
//
// 两处根因（#363）：
// ① FileTree.vue 的 handleDrop / onRootDrop 只认得应用内拖拽的两种 payload
//    （application/x-checkba-file JSON、document.__checkbaDraggedFile 兜底），
//    dataTransfer.files 这份 OS 原生 File 列表从来没人读——拖进来什么都不发生。
// ② uni-h5 的 $nne 把 <view> 上的事件重建成普通对象（createNativeEvent），只补
//    click/mouse/touch 三类字段，drag 系事件的 dataTransfer 直接丢了；要从正在派发的
//    原生事件 window.event 上取（同 fileOpenTabs.js 的 mouseButtonOf 老地雷）。
//
// #513 起资源管理器只剩「导入本机路径」一条通道：dataTransfer.files 的**顶层条目**
// （可能是文件，也可能是目录）逐个解析出本机绝对路径，调 import-local 让后端复制进来，
// 目录由后端递归建行。解析不出路径（非桌面端）只弹一句「拖入导入仅桌面端支持」。
// FileTree.vue 是 Options API，照 staging-drop-external-files.test.mjs 的套路把
// <script> 抠出来 new Function 求值，真跑 handleDrop / onRootDrop / onTreeDrop。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  nativeDataTransfer,
  isExternalFileDrag,
  claimExternalDrop,
} from '../../src/utils/fileTreeExternalDrop.js'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')

// <script> 里的 import 有单行也有多行块（api.js 那组 tag 接口），一并剥掉；
// 被引用的符号全部由 new Function 的形参喂桩。
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

// desktopPaths: File 对象 → 本机绝对路径的映射（桌面壳 host.fs.getPathForFile 的桩）。
// 不传 = 浏览器态，host.fs 整个缺席，一条都导不进去。
function makeVm({ moveFile, createFolder, desktopPaths, importLocalFails } = {}) {
  const calls = { moveFile: [], importLocal: [], loadFiles: 0, emits: [], toasts: [] }
  const stubs = {
    moveFile: async (...a) => { calls.moveFile.push(a); return { parentId: a[2] } },
    createFolder: createFolder || (async () => ({ id: 999 })),
    ICONS: {},
    host: desktopPaths
      ? { fs: { getPathForFile: (f) => desktopPaths.get(f) || '' } }
      : {},
    importLocalFile: async (...a) => {
      calls.importLocal.push(a)
      if (importLocalFails) throw new Error('boom')
      return { id: 100 + calls.importLocal.length }
    },
    nativeDataTransfer, isExternalFileDrag, claimExternalDrop,
  }
  if (moveFile) stubs.moveFile = moveFile
  const options = loadOptions(stubs)
  const vm = Object.assign({}, options.data.call({}))
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.projectId = 42
  vm.$t = (k) => k
  vm.$emit = (...a) => { calls.emits.push(a) }
  vm.$el = null
  vm.loadFiles = async () => { calls.loadFiles += 1 }
  vm.showErrorModal = (msg, title) => { calls.toasts.push(title || msg) }
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

const folder = { id: 7, name: '合同', isFolder: true, parentId: null, sortOrder: 3 }
const doc = { id: 1, name: '起诉状.docx', isFolder: false, parentId: 7, sortOrder: 1 }

function osFile(name, size = 10) {
  return { name, size, slice() {} }
}

// 真实 OS 拖拽的 dataTransfer：dragover 阶段 files 为空、types 里只有 'Files'，
// drop 阶段 files 才有内容（顶层条目，目录也在里面占一条）。
function osDataTransfer(files, items = []) {
  return { types: ['Files'], files, items, getData: () => '' }
}

function wrappedEvent(extra = {}) {
  // uni 重建后的事件对象：没有 dataTransfer，只有转发方法
  return { preventDefault() {}, stopPropagation() {}, ...extra }
}

// ---------- 纯函数 ----------

test('isExternalFileDrag：dragover 阶段 files 为空但 types 含 Files 也算外部文件拖拽；应用内拖拽不算', () => {
  assert.equal(isExternalFileDrag({ types: ['Files'], files: [] }), true)
  assert.equal(isExternalFileDrag({ types: ['text/plain', 'application/x-checkba-file'], files: [] }), false)
  assert.equal(isExternalFileDrag(null), false)
})

test('nativeDataTransfer：包装事件没有 dataTransfer 时从 window.event 上取', () => {
  const restore = installGlobals()
  try {
    const dt = osDataTransfer([osFile('a.pdf')])
    globalThis.window.event = { dataTransfer: dt }
    assert.equal(nativeDataTransfer(wrappedEvent()), dt)
    assert.equal(nativeDataTransfer({ dataTransfer: 'own' }), 'own', '回调自带的优先')
  } finally { restore() }
})

test('claimExternalDrop：同一个原生事件只认领一次（节点与容器各收一次 drop 时不重复导入）', () => {
  const native = {}
  assert.equal(claimExternalDrop(native), true)
  assert.equal(claimExternalDrop(native), false)
  assert.equal(claimExternalDrop(null), true, '拿不到原生事件时不拦')
})

// ---------- FileTree 接线：顶层条目逐个 import-local ----------

test('桌面端拖文件到文件夹节点：import-local 到该文件夹，不调 moveFile，落点文件夹被展开，不插乐观行', async () => {
  const restore = installGlobals()
  try {
    const file = osFile('证据.pdf')
    const vm = makeVm({ desktopPaths: new Map([[file, '/Users/me/tmp/证据.pdf']]) })
    vm.windowedDisplayFiles = [folder, doc]
    vm.displayFiles = [folder, doc]
    vm.files = []
    vm.allFiles = []

    await vm.handleDrop(wrappedEvent({ dataTransfer: osDataTransfer([file]) }), 0)

    assert.deepEqual(vm.calls.importLocal, [[42, '/Users/me/tmp/证据.pdf', 7]],
      'projectId / 绝对路径 / 落点目录三样都要传对')
    assert.deepEqual(vm.calls.moveFile, [], '外部文件不是移动')
    assert.equal(vm.calls.loadFiles, 1, '导入完刷新文件树')
    assert.deepEqual(vm.files, [], '没有传输阶段就没有占位行')
    assert.deepEqual(vm.allFiles, [])
    assert.equal(vm.expandedFolders.has(7), true, '落点文件夹展开，导完能看见')
    assert.equal(vm.dragOverIndex, -1)
    assert.equal(vm.externalDragActive, false)
  } finally { restore() }
})

test('拖整个文件夹进来：直接把目录路径交给 import-local（目录递归由后端做），不再前端展开', async () => {
  const restore = installGlobals()
  try {
    // 目录在 dataTransfer.files 里也是一个 File 条目，webUtils 解析得出它的绝对路径
    const dir = osFile('证据')
    const vm = makeVm({ desktopPaths: new Map([[dir, '/Users/me/证据']]) })
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    await vm.handleDrop(wrappedEvent({ dataTransfer: osDataTransfer([dir]) }), 0)
    assert.deepEqual(vm.calls.importLocal, [[42, '/Users/me/证据', 7]])
    assert.equal(vm.calls.loadFiles, 1)
  } finally { restore() }
})

test('一次拖多个顶层条目（文件 + 目录混着）：逐个调 import-local，落点一致', async () => {
  const restore = installGlobals()
  try {
    const f1 = osFile('a.pdf')
    const dir = osFile('证据')
    const vm = makeVm({
      desktopPaths: new Map([[f1, '/Users/me/a.pdf'], [dir, '/Users/me/证据']]),
    })
    await vm.onRootDrop(wrappedEvent({ dataTransfer: osDataTransfer([f1, dir]) }))
    assert.deepEqual(vm.calls.importLocal, [
      [42, '/Users/me/a.pdf', null],
      [42, '/Users/me/证据', null],
    ])
    assert.equal(vm.calls.loadFiles, 1, '整批导完只刷一次树')
  } finally { restore() }
})

test('uni 重建的事件没有 dataTransfer：从 window.event 上取原生 dataTransfer，照常导入', async () => {
  const restore = installGlobals()
  try {
    const file = osFile('a.pdf')
    const vm = makeVm({ desktopPaths: new Map([[file, '/Users/me/a.pdf']]) })
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    globalThis.window.event = { dataTransfer: osDataTransfer([file]) }
    await vm.handleDrop(wrappedEvent(), 0)
    assert.deepEqual(vm.calls.importLocal, [[42, '/Users/me/a.pdf', 7]])
  } finally { restore() }
})

test('drop 到文件节点：落到该文件所在目录（同级）', async () => {
  const restore = installGlobals()
  try {
    const file = osFile('a.pdf')
    const vm = makeVm({ desktopPaths: new Map([[file, '/Users/me/a.pdf']]) })
    vm.windowedDisplayFiles = [folder, doc]
    vm.displayFiles = [folder, doc]
    await vm.handleDrop(wrappedEvent({ dataTransfer: osDataTransfer([file]) }), 1)
    assert.deepEqual(vm.calls.importLocal, [[42, '/Users/me/a.pdf', 7]], 'doc 的 parentId 是 7')
  } finally { restore() }
})

test('drop 到根投放区 / 树空白区：落到项目根（parent=null）', async () => {
  const restore = installGlobals()
  try {
    const a = osFile('a.pdf')
    const vm = makeVm({ desktopPaths: new Map([[a, '/Users/me/a.pdf']]) })
    await vm.onRootDrop(wrappedEvent({ dataTransfer: osDataTransfer([a]) }))
    assert.deepEqual(vm.calls.importLocal, [[42, '/Users/me/a.pdf', null]])
    assert.deepEqual(vm.calls.moveFile, [])

    const b = osFile('b.pdf')
    const vm2 = makeVm({ desktopPaths: new Map([[b, '/Users/me/b.pdf']]) })
    vm2.externalDragActive = true
    await vm2.onTreeDrop(wrappedEvent({ dataTransfer: osDataTransfer([b]) }))
    assert.deepEqual(vm2.calls.importLocal, [[42, '/Users/me/b.pdf', null]])
    assert.equal(vm2.externalDragActive, false)
  } finally { restore() }
})

test('非桌面端（解析不出本机路径）：只提示「仅桌面端支持」，一条都不导、树也不刷', async () => {
  const restore = installGlobals()
  try {
    // 浏览器：host.fs 整个缺席
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    await vm.handleDrop(wrappedEvent({ dataTransfer: osDataTransfer([osFile('a.pdf')]) }), 0)
    assert.deepEqual(vm.calls.importLocal, [])
    assert.equal(vm.calls.loadFiles, 0)
    assert.deepEqual(vm.calls.toasts, ['fileTree.importDesktopOnly'])

    // 桌面壳在，但这个 File 解析不出路径（webUtils 返回空串）：同样只提示一次
    const vm2 = makeVm({ desktopPaths: new Map() })
    vm2.windowedDisplayFiles = [folder]
    vm2.displayFiles = [folder]
    await vm2.handleDrop(
      wrappedEvent({ dataTransfer: osDataTransfer([osFile('b.pdf'), osFile('c.pdf')]) }), 0)
    assert.deepEqual(vm2.calls.importLocal, [])
    assert.deepEqual(vm2.calls.toasts, ['fileTree.importDesktopOnly'], '一次 drop 只提示一次')
  } finally { restore() }
})

test('同一次 drop 节点与容器各收到一次：只导入一次', async () => {
  const restore = installGlobals()
  try {
    const file = osFile('a.pdf')
    const vm = makeVm({ desktopPaths: new Map([[file, '/Users/me/a.pdf']]) })
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    const native = { dataTransfer: osDataTransfer([file]) }
    globalThis.window.event = native
    await vm.handleDrop(wrappedEvent(), 0)
    await vm.onTreeDrop(wrappedEvent())
    assert.deepEqual(vm.calls.importLocal, [[42, '/Users/me/a.pdf', 7]],
      '第一次认领的落点（文件夹）生效，容器那次不许再导一遍到根')
  } finally { restore() }
})

test('import-local 失败：照样刷新文件树，且不偷偷退回别的通道', async () => {
  const restore = installGlobals()
  try {
    const file = osFile('证据.pdf')
    const vm = makeVm({ desktopPaths: new Map([[file, '/Users/me/证据.pdf']]), importLocalFails: true })
    await vm.onRootDrop(wrappedEvent({ dataTransfer: osDataTransfer([file]) }))
    assert.equal(vm.calls.importLocal.length, 1)
    assert.equal(vm.calls.loadFiles, 1)
    assert.ok(vm.calls.toasts.includes('fileTree.importFailed'))
  } finally { restore() }
})

test('树内节点 drop 仍走原来的 moveFile 移动逻辑，不碰导入通道', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [doc, folder]
    vm.displayFiles = [doc, folder]
    vm.draggedIndex = 0
    vm.draggedFileId = 1
    const e = wrappedEvent({ dataTransfer: { types: ['text/plain', 'application/x-checkba-file'], files: [], getData: () => '' } })
    await vm.handleDrop(e, 1)
    assert.deepEqual(vm.calls.moveFile, [[42, 1, 7, 0]])
    assert.deepEqual(vm.calls.importLocal, [])
    assert.equal(vm.draggedIndex, -1)
  } finally { restore() }
})

test('回收站视图不接收外部文件', async () => {
  const restore = installGlobals()
  try {
    const file = osFile('a.pdf')
    const vm = makeVm({ desktopPaths: new Map([[file, '/Users/me/a.pdf']]) })
    vm.viewMode = 'recycle'
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    const dt = osDataTransfer([file])
    await vm.handleDrop(wrappedEvent({ dataTransfer: dt }), 0)
    await vm.onTreeDrop(wrappedEvent({ dataTransfer: dt }))
    vm.onTreeDragEnter(wrappedEvent({ dataTransfer: dt }))
    assert.deepEqual(vm.calls.importLocal, [])
    assert.equal(vm.externalDragActive, false)
  } finally { restore() }
})

test('dragenter 带外部文件时点亮 externalDragActive（根投放区据此出现），应用内拖拽不点亮', () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.onTreeDragEnter(wrappedEvent({ dataTransfer: { types: ['text/plain'], files: [] } }))
    assert.equal(vm.externalDragActive, false)
    vm.onTreeDragEnter(wrappedEvent({ dataTransfer: { types: ['Files'], files: [] } }))
    assert.equal(vm.externalDragActive, true)
    // 离开整个容器（relatedTarget 为 null = 拖出窗口）后复位
    globalThis.window.event = { relatedTarget: null }
    vm.onTreeDragLeave(wrappedEvent())
    assert.equal(vm.externalDragActive, false)
  } finally { restore() }
})

// ---------- 暂存区借用的入口 ----------

test('importDroppedLocalFiles 是暂存区能直接调的公开方法：吃 File 列表 + 落点 id', async () => {
  const restore = installGlobals()
  try {
    const a = osFile('a.pdf')
    const vm = makeVm({ desktopPaths: new Map([[a, '/Users/me/a.pdf']]) })
    assert.equal(typeof vm.importDroppedLocalFiles, 'function')
    await vm.importDroppedLocalFiles([a], 'stage-1')
    assert.deepEqual(vm.calls.importLocal, [[42, '/Users/me/a.pdf', 'stage-1']])
    assert.equal(vm.calls.loadFiles, 1)
  } finally { restore() }
})
