// dev-board#363：把 Finder / 微信里的文件直接拖进左栏「资源管理器」的目录节点。
//   cd frontend && npm run test:project-home
//
// 两处根因：
// ① FileTree.vue 的 handleDrop / onRootDrop 只认得应用内拖拽的两种 payload
//    （application/x-checkba-file JSON、document.__checkbaDraggedFile 兜底），
//    dataTransfer.files 这份 OS 原生 File 列表从来没人读——拖进来什么都不发生。
// ② uni-h5 的 $nne 把 <view> 上的事件重建成普通对象（createNativeEvent），只补
//    click/mouse/touch 三类字段，drag 系事件的 dataTransfer 直接丢了；要从正在派发的
//    原生事件 window.event 上取（同 fileOpenTabs.js 的 mouseButtonOf 老地雷）。
//
// 上传通道复用既有的 confirmUpload（读 selectedFiles / selectedUploadParent，与上传对话框、
// 暂存区 onStagingDropFiles 同一条路），不另起一套。FileTree.vue 是 Options API，照
// staging-drop-external-files.test.mjs 的套路把 <script> 抠出来 new Function 求值，真跑
// handleDrop / onRootDrop / onTreeDrop。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  nativeDataTransfer,
  isExternalFileDrag,
  collectDroppedFiles,
  claimExternalDrop,
} from '../../src/utils/fileTreeExternalDrop.js'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')

// <script> 里的 import 有单行也有多行块（api.js 那组 tag 接口），一并剥掉；
// 被引用的符号全部由 new Function 的形参喂桩。
const IMPORT_NAMES = [
  'getProjectFiles', 'createFolder', 'createFile', 'renameFile', 'deleteFile', 'deleteFilePerm',
  'restoreFileApi', 'getRecycleBinFiles', 'moveFile', 'batchDeleteFiles', 'batchMoveFiles',
  'batchCopyFiles', 'getApiBaseUrl', 'getContributedTemplates', 'createFileFromContributedTemplate',
  'getAuthHeaders', 'getSessionId', 'host', 'findTopmostDeletedAncestor', 'summarizeDeleteResults',
  'groupByParent', 'buildTreeFromGroups', 'evidenceRefCounts', 'createRefCountsFetcher',
  'CircularProgress', 'warmDragImage', 'applyDragImage', 'FileTypeIcon', 'TagChip', 'TagSelector',
  'TagManager', 'AwdDatePicker', 'ICONS', 'getProjectTags', 'addTagToFile', 'removeTagFromFile',
  'createTag', 'createTask',
  'nativeDataTransfer', 'isExternalFileDrag', 'collectDroppedFiles', 'claimExternalDrop',
]

function loadOptions(stubs) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+['"][^'"]+['"]\s*$/gm, '')
    .replace(/export default \{/, 'return {')
  const factory = new Function(...IMPORT_NAMES, body)
  const args = IMPORT_NAMES.map(n => (n in stubs ? stubs[n] : (() => {})))
  return factory(...args)
}

function makeVm({ moveFile, createFolder } = {}) {
  const calls = { moveFile: [], confirmUpload: 0 }
  const stubs = {
    moveFile: async (...a) => { calls.moveFile.push(a); return { parentId: a[2] } },
    createFolder: createFolder || (async () => ({ id: 999 })),
    ICONS: {},
    host: {},
    nativeDataTransfer, isExternalFileDrag, collectDroppedFiles, claimExternalDrop,
  }
  if (moveFile) stubs.moveFile = moveFile
  const options = loadOptions(stubs)
  const vm = Object.assign({}, options.data.call({}))
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.projectId = 42
  vm.$t = (k) => k
  vm.$emit = () => {}
  vm.$el = null
  vm.loadFiles = async () => {}
  vm.confirmUpload = async () => { calls.confirmUpload += 1 }
  vm.calls = calls
  return vm
}

function installGlobals() {
  const prior = { uni: globalThis.uni, window: globalThis.window, document: globalThis.document }
  globalThis.uni = { showToast() {}, $emit() {}, $on() {}, $off() {} }
  globalThis.window = { event: null }
  globalThis.document = {}
  return () => {
    globalThis.uni = prior.uni
    globalThis.window = prior.window
    globalThis.document = prior.document
  }
}

const folder = { id: 7, name: '合同', isFolder: true, parentId: null, sortOrder: 3 }
const doc = { id: 1, name: '起诉状.docx', isFolder: false, parentId: 7, sortOrder: 1 }

function osFile(name, size = 10) {
  return { name, size, slice() {} }
}

// 真实 OS 拖拽的 dataTransfer：dragover 阶段 files 为空、types 里只有 'Files'，
// drop 阶段 files 才有内容。items 给空数组 = 不支持 webkitGetAsEntry 的环境。
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

test('collectDroppedFiles：目录项（webkitGetAsEntry）递归展开，relativePath 带目录前缀；readEntries 分批读到空为止', async () => {
  const fileEntry = (name) => ({
    isFile: true, isDirectory: false, name,
    file: (ok) => ok(osFile(name)),
  })
  const dirEntry = (name, children) => {
    let served = false
    return {
      isFile: false, isDirectory: true, name,
      createReader: () => ({
        readEntries: (ok) => { const batch = served ? [] : children; served = true; ok(batch) },
      }),
    }
  }
  const tree = dirEntry('证据', [fileEntry('1.pdf'), dirEntry('子目录', [fileEntry('2.pdf')])])
  const dt = osDataTransfer([], [{ kind: 'file', webkitGetAsEntry: () => tree }])
  const items = await collectDroppedFiles(dt)
  assert.deepEqual(items.map(i => i.relativePath).sort(), ['证据/1.pdf', '证据/子目录/2.pdf'])
  assert.equal(items[0].fileObject.name, '1.pdf')
})

test('collectDroppedFiles：没有 items 支持时退回 dataTransfer.files，relativePath 就是文件名', async () => {
  const items = await collectDroppedFiles(osDataTransfer([osFile('a.pdf'), osFile('b.docx')]))
  assert.deepEqual(items.map(i => i.relativePath), ['a.pdf', 'b.docx'])
})

test('claimExternalDrop：同一个原生事件只认领一次（节点与容器各收一次 drop 时不重复上传）', () => {
  const native = {}
  assert.equal(claimExternalDrop(native), true)
  assert.equal(claimExternalDrop(native), false)
  assert.equal(claimExternalDrop(null), true, '拿不到原生事件时不拦')
})

// ---------- FileTree 接线 ----------

test('OS 文件 drop 到文件夹节点：走 confirmUpload 上传通道，目标目录是该文件夹，不调 moveFile，落点文件夹被展开', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder, doc]
    vm.displayFiles = [folder, doc]
    const file = osFile('证据.pdf')
    const e = wrappedEvent({ dataTransfer: osDataTransfer([file]) })
    await vm.handleDrop(e, 0)
    assert.equal(vm.calls.confirmUpload, 1, '必须走既有上传通道')
    assert.equal(vm.selectedUploadParent, 7, '目标目录必须是被投放的文件夹')
    assert.equal(vm.selectedFiles.length, 1)
    assert.equal(vm.selectedFiles[0].fileObject, file)
    assert.equal(vm.selectedFiles[0].relativePath, '证据.pdf')
    assert.equal(vm.isFolderUpload, false)
    assert.deepEqual(vm.calls.moveFile, [], '外部文件不是移动')
    assert.equal(vm.expandedFolders.has(7), true, '落点文件夹展开，传完能看见')
    assert.equal(vm.dragOverIndex, -1)
    assert.equal(vm.externalDragActive, false)
  } finally { restore() }
})

test('uni 重建的事件没有 dataTransfer：从 window.event 上取原生 dataTransfer，照常上传', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    globalThis.window.event = { dataTransfer: osDataTransfer([osFile('a.pdf')]) }
    await vm.handleDrop(wrappedEvent(), 0)
    assert.equal(vm.calls.confirmUpload, 1)
    assert.equal(vm.selectedUploadParent, 7)
  } finally { restore() }
})

test('OS 文件 drop 到文件节点：落到该文件所在目录（同级）', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder, doc]
    vm.displayFiles = [folder, doc]
    await vm.handleDrop(wrappedEvent({ dataTransfer: osDataTransfer([osFile('a.pdf')]) }), 1)
    assert.equal(vm.calls.confirmUpload, 1)
    assert.equal(vm.selectedUploadParent, 7, 'doc 的 parentId 是 7')
  } finally { restore() }
})

test('OS 文件 drop 到根投放区 / 树空白区：落到项目根（parent=null）', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    await vm.onRootDrop(wrappedEvent({ dataTransfer: osDataTransfer([osFile('a.pdf')]) }))
    assert.equal(vm.calls.confirmUpload, 1)
    assert.equal(vm.selectedUploadParent, null)
    assert.deepEqual(vm.calls.moveFile, [])

    const vm2 = makeVm()
    vm2.externalDragActive = true
    await vm2.onTreeDrop(wrappedEvent({ dataTransfer: osDataTransfer([osFile('b.pdf')]) }))
    assert.equal(vm2.calls.confirmUpload, 1)
    assert.equal(vm2.selectedUploadParent, null)
    assert.equal(vm2.externalDragActive, false)
  } finally { restore() }
})

test('拖入整个文件夹：relativePath 带目录前缀交给 confirmUpload 建目录，isFolderUpload=true', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    const entry = {
      isFile: false, isDirectory: true, name: '证据',
      createReader: () => {
        let served = false
        return { readEntries: (ok) => { const b = served ? [] : [{ isFile: true, isDirectory: false, name: '1.pdf', file: (cb) => cb(osFile('1.pdf')) }]; served = true; ok(b) } }
      },
    }
    const dt = osDataTransfer([osFile('证据')], [{ kind: 'file', webkitGetAsEntry: () => entry }])
    await vm.handleDrop(wrappedEvent({ dataTransfer: dt }), 0)
    assert.equal(vm.calls.confirmUpload, 1)
    assert.equal(vm.selectedUploadParent, 7)
    assert.deepEqual(vm.selectedFiles.map(f => f.relativePath), ['证据/1.pdf'])
    assert.equal(vm.isFolderUpload, true)
  } finally { restore() }
})

test('同一次 drop 节点与容器各收到一次：只上传一次', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    const native = { dataTransfer: osDataTransfer([osFile('a.pdf')]) }
    globalThis.window.event = native
    await vm.handleDrop(wrappedEvent(), 0)
    await vm.onTreeDrop(wrappedEvent())
    assert.equal(vm.calls.confirmUpload, 1)
    assert.equal(vm.selectedUploadParent, 7, '第一次认领的落点（文件夹）生效，容器那次不许改成根')
  } finally { restore() }
})

test('树内节点 drop 仍走原来的 moveFile 移动逻辑，不碰上传通道', async () => {
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
    assert.equal(vm.calls.confirmUpload, 0)
    assert.equal(vm.draggedIndex, -1)
  } finally { restore() }
})

test('回收站视图不接收外部文件', async () => {
  const restore = installGlobals()
  try {
    const vm = makeVm()
    vm.viewMode = 'recycle'
    vm.windowedDisplayFiles = [folder]
    vm.displayFiles = [folder]
    const dt = osDataTransfer([osFile('a.pdf')])
    await vm.handleDrop(wrappedEvent({ dataTransfer: dt }), 0)
    await vm.onTreeDrop(wrappedEvent({ dataTransfer: dt }))
    vm.onTreeDragEnter(wrappedEvent({ dataTransfer: dt }))
    assert.equal(vm.calls.confirmUpload, 0)
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
