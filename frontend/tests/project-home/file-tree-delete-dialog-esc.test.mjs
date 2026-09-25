// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-10（v0.49.0 真机批次 I，C3-01）：资源管理器批量选择后点工具栏「删除」弹出的
// 「移入回收站」确认框按 Esc 不能取消——它是内联在 .file-tree 根节点内的 awd-dialog-mask，
// 只认鼠标 @tap，不接键盘，违反 AwdDialog 全局约定（Esc=取消、危险态取消按钮默认焦点）。
//
// 修复：单个/批量、软删/硬删共用的确认框全部改走应用内对话框 AwdDialog
// （utils/dialog.js 的 showDialog，danger:true），Esc→取消、危险态默认焦点落在取消
// 都是 AwdDialog 内置的键位语义（utils/dialogCore.js 的 resolveDialogKey，
// 已由 tests/dialog/awd-dialog.test.mjs 钉住），这里只钉「FileTree 是否真的把删除确认
// 交给了它」：showDialog 解出 cancel（等价于用户按了 Esc）时，任何一条删除路径都不能真的
// 调用后端删除接口。跑法：npm run test:project-home
//
// 复用 file-tree-new-file-naming.test.mjs 的抠脚本套路：把 FileTree.vue 的 <script> 整段
// 抠出来真跑。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')

const IMPORT_NAMES = [
  'getProjectFiles', 'createFolder', 'createFile', 'renameFile', 'deleteFile', 'deleteFilePerm',
  'restoreFileApi', 'getRecycleBinFiles', 'moveFile', 'batchDeleteFiles', 'batchMoveFiles',
  'batchCopyFiles', 'getApiBaseUrl', 'getContributedTemplates', 'createFileFromContributedTemplate',
  'getSessionId', 'host', 'findTopmostDeletedAncestor', 'summarizeDeleteResults', 'collapseToTopmostSelected',
  'groupByParent', 'buildTreeFromGroups', 'evidenceRefCounts', 'createRefCountsFetcher',
  'warmDragImage', 'applyDragImage', 'FileTypeIcon', 'TagChip', 'TagSelector',
  'TagManager', 'AwdDatePicker', 'ICONS', 'getProjectTags', 'addTagToFile', 'removeTagFromFile',
  'createTag', 'createTask', 'importLocalFile',
  'nativeDataTransfer', 'isExternalFileDrag', 'claimExternalDrop', 'isAudioFileName',
  'showDialog',
]

// replies：依次回给每一次 showDialog 的结果；dialogs 记下每次弹框的参数
function makeVm({ replies = [] } = {}) {
  const calls = { deleteFile: [], deleteFilePerm: [], batchDeleteFiles: [], dialogs: [] }
  const queue = [...replies]
  const stubs = {
    deleteFile: async (...a) => { calls.deleteFile.push(a) },
    deleteFilePerm: async (...a) => { calls.deleteFilePerm.push(a) },
    batchDeleteFiles: async (...a) => { calls.batchDeleteFiles.push(a) },
    showDialog: async (opts) => {
      calls.dialogs.push(opts)
      return queue.length ? queue.shift() : { confirm: false, cancel: true }
    },
    ICONS: {},
    host: {},
  }
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+['"][^'"]+['"]\s*$/gm, '')
    .replace(/export default \{/, 'return {')
  const options = new Function(...IMPORT_NAMES, body)(...IMPORT_NAMES.map(n => (n in stubs ? stubs[n] : (() => {}))))
  const vm = Object.assign({}, options.data.call({}))
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.projectId = 42
  vm.parentId = null
  vm.showTree = true
  vm.displayFiles = []
  vm.allFiles = []
  vm.recycleBin = []
  vm.checkedIds = []
  vm.$t = (k, params) => (params ? `${k}:${JSON.stringify(params)}` : k)
  vm.$emit = () => {}
  vm.loadFiles = async () => {}
  vm.showErrorModal = (msg) => { throw new Error('unexpected error modal: ' + msg) }
  vm.calls = calls
  return vm
}

globalThis.uni = { showToast() {}, showActionSheet() {}, $emit() {}, $on() {}, $off() {} }

test('资源管理器里不再有内联的删除确认框（.file-tree 内的 awd-dialog-mask），只走 AwdDialog', () => {
  assert.doesNotMatch(SRC, /showDeleteDialog/, '不应再有 showDeleteDialog 这个自绘弹窗开关')
  assert.match(SRC, /async showDeleteConfirmDialog\s*\(\)\s*\{/, '应该有统一的删除确认方法')
  const methodBody = SRC.slice(SRC.indexOf('async showDeleteConfirmDialog'))
  assert.match(methodBody.slice(0, methodBody.indexOf('\n    },')), /await showDialog\(\{/, '删除确认必须走 showDialog（AwdDialog），不是内联 awd-dialog-mask')
})

test('单个软删除（移入回收站）：确认框 danger 样式，取消（等价于按 Esc）不调用删除接口', async () => {
  const vm = makeVm() // 默认队列为空 → showDialog 解出 cancel，模拟 Esc
  const item = { id: 1, name: '起诉状.docx', isFolder: false }
  await vm.handleDelete(item)

  assert.equal(vm.calls.dialogs.length, 1)
  assert.equal(vm.calls.dialogs[0].danger, true, '删除是危险动作，必须用 danger 让 AwdDialog 把默认焦点落在取消上')
  assert.equal(vm.calls.deleteFile.length, 0, 'Esc/取消不能真的删除')
})

test('单个软删除：确认后才调用 deleteFile', async () => {
  const vm = makeVm({ replies: [{ confirm: true, cancel: false }] })
  const item = { id: 1, name: '起诉状.docx', isFolder: false }
  await vm.handleDelete(item)

  assert.equal(vm.calls.deleteFile.length, 1)
  assert.equal(vm.calls.deleteFile[0][1], 1)
})

test('回收站「彻底删除」单个文件：取消（Esc）不调用 deleteFilePerm', async () => {
  const vm = makeVm()
  const item = { id: 2, name: '证据.docx', isFolder: false }
  await vm.permDeleteFile(item)

  assert.equal(vm.calls.dialogs.length, 1)
  assert.equal(vm.calls.dialogs[0].danger, true)
  assert.equal(vm.calls.deleteFilePerm.length, 0)
})

test('回收站「彻底删除」单个文件：确认后才调用 deleteFilePerm', async () => {
  const vm = makeVm({ replies: [{ confirm: true, cancel: false }] })
  const item = { id: 2, name: '证据.docx', isFolder: false }
  await vm.permDeleteFile(item)

  assert.equal(vm.calls.deleteFilePerm.length, 1)
})

test('工具栏批量删除（非回收站视图=移入回收站）：取消（Esc）不调用 batchDeleteFiles', async () => {
  const vm = makeVm()
  vm.viewMode = 'files'
  vm.checkedIds = [10, 11, 12]
  vm.openBatchAction('delete')
  await new Promise((r) => setTimeout(r, 0))

  assert.equal(vm.calls.dialogs.length, 1)
  assert.equal(vm.calls.dialogs[0].danger, true)
  assert.equal(vm.calls.batchDeleteFiles.length, 0, 'Esc/取消不能真的批量删除')
})

test('工具栏批量删除（非回收站视图）：确认后才调用 batchDeleteFiles', async () => {
  const vm = makeVm({ replies: [{ confirm: true, cancel: false }] })
  vm.viewMode = 'files'
  vm.checkedIds = [10, 11, 12]
  vm.openBatchAction('delete')
  await new Promise((r) => setTimeout(r, 0))
  await new Promise((r) => setTimeout(r, 0))

  assert.equal(vm.calls.batchDeleteFiles.length, 1)
  assert.deepEqual(vm.calls.batchDeleteFiles[0][1], [10, 11, 12])
})

test('回收站视图批量彻底删除：取消（Esc）不调用 deleteFilePerm', async () => {
  const vm = makeVm()
  vm.viewMode = 'recycle'
  vm.recycleBin = [
    { id: 20, parentId: null, name: 'a.docx' },
    { id: 21, parentId: null, name: 'b.docx' },
  ]
  vm.checkedIds = [20, 21]
  vm.openBatchAction('delete')
  await new Promise((r) => setTimeout(r, 0))

  assert.equal(vm.calls.dialogs.length, 1)
  assert.equal(vm.calls.dialogs[0].danger, true)
  assert.equal(vm.calls.deleteFilePerm.length, 0, 'Esc/取消不能真的彻底删除')
})
