// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-27（v0.49.0 真机批次 H）：工具栏「新建文件」此前静默创建英文默认名
// newdocument.docx，既没有类型选择也没有命名对话框，与紧邻的「新建文件夹」
// （点了就弹命名输入框）体验不一致。
//
// 修复：新建空白文档弹命名框（openCreateFileDialog → createBlankWord(name)），默认名走
// i18n fileTree.defaultDocumentName（中文「新建文档」/英文 "New document"），取消不创建。
// 命名框必须是应用内对话框 AwdDialog（utils/dialog.js 的 showDialog，挂在 <body> 下）：
// 复核发现初版复制的内联 awd-dialog-mask 在 .file-tree 根节点内，Esc 关不掉、方向键被
// handleKeyDown 抢去折叠文件夹/改选中项（= 改新建落点）。新建文件夹同一处模式一并改掉。
//
// 复用 file-tree-create-target.test.mjs 的抠脚本套路：把 FileTree.vue 的
// <script> 整段抠出来真跑，而不是只测一个孤立的纯函数。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

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
  'nativeDataTransfer', 'isExternalFileDrag', 'claimExternalDrop', 'isAudioFileName',
  'showDialog',
]

// replies：依次回给每一次 showDialog 的结果；opened 记下每次弹框的参数
function makeVm({ templates = [], replies = [] } = {}) {
  const calls = { createFile: [], createFolder: [], dialogs: [] }
  const queue = [...replies]
  const stubs = {
    createFile: async (...a) => { calls.createFile.push(a); return { id: 500 } },
    createFolder: async (...a) => { calls.createFolder.push(a); return { id: 501 } },
    getContributedTemplates: async () => ({ templates }),
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
  vm.$t = (k) => k
  vm.$emit = () => {}
  vm.loadFiles = async () => {}
  vm.showErrorModal = (msg) => { throw new Error('unexpected error modal: ' + msg) }
  vm.calls = calls
  return vm
}

let toasts = []
globalThis.uni = { showToast(o) { toasts.push(o.title) }, showActionSheet() {}, $emit() {}, $on() {}, $off() {} }

test('命名框走 AwdDialog（showDialog），不再有挂在 .file-tree 内的内联命名框', () => {
  const tpl = SRC.match(/<template>([\s\S]*)<\/template>/)[1]
  assert.doesNotMatch(tpl, /v-model="newFileName"|v-model="newFolderName"/, '内联命名输入框在 .file-tree 内，方向键会被 handleKeyDown 抢走')
  assert.match(SRC, /import \{ showDialog \} from '@\/utils\/dialog\.js'/)
})

test('没有贡献模板时，点「新建文件」先弹可编辑命名框，默认名走 i18n，不静默创建', async () => {
  const vm = makeVm()
  await vm.handleCreateWord()

  assert.equal(vm.calls.dialogs.length, 1, '应该弹出命名对话框')
  const d = vm.calls.dialogs[0]
  assert.equal(d.editable, true)
  assert.equal(d.content, 'fileTree.defaultDocumentName', '默认名走 i18n，不是硬编码英文 newdocument')
  assert.equal(d.title, 'fileTree.newFileDialogTitle')
  assert.equal(vm.calls.createFile.length, 0, '取消（Esc）不应该创建任何文件')
})

test('命名对话框确认后，按输入的名字创建文档', async () => {
  const vm = makeVm({ replies: [{ confirm: true, cancel: false, content: '起诉状' }] })
  await vm.handleCreateWord()

  assert.equal(vm.calls.createFile.length, 1)
  assert.equal(vm.calls.createFile[0][2], '起诉状.docx')
})

test('名字留空点确认：不创建文件，弹提示并重开命名框', async () => {
  toasts = []
  const vm = makeVm({ replies: [{ confirm: true, cancel: false, content: '   ' }] })
  await vm.handleCreateWord()

  assert.equal(vm.calls.createFile.length, 0)
  assert.deepEqual(toasts, ['fileTree.fileNamePlaceholder'])
  assert.equal(vm.calls.dialogs.length, 2, '应该重开命名框让用户接着改')
  assert.equal(vm.calls.dialogs[1].content, '')
})

test('用户在名字里已经带了 .docx 后缀：不重复拼接成 .docx.docx', async () => {
  const vm = makeVm({ replies: [{ confirm: true, cancel: false, content: '证据清单.docx' }] })
  await vm.handleCreateWord()

  assert.equal(vm.calls.createFile[0][2], '证据清单.docx')
})

test('有贡献模板时，actionsheet 第一项「空白文档」同样先弹命名对话框', async () => {
  const vm = makeVm({ templates: [{ id: 't1', name: '起诉状模板', pluginId: 'p1' }] })
  let actionSheetOpts = null
  globalThis.uni.showActionSheet = (opts) => { actionSheetOpts = opts; opts.success({ tapIndex: 0 }) }

  await vm.handleCreateWord()
  await new Promise((r) => setTimeout(r, 0))

  assert.ok(actionSheetOpts, '应该弹出模板选择')
  assert.equal(vm.calls.dialogs.length, 1)
  assert.equal(vm.calls.dialogs[0].editable, true)
  assert.equal(vm.calls.createFile.length, 0)
})

test('新建文件夹同样走 AwdDialog：确认才建，取消不建', async () => {
  const vm = makeVm({ replies: [{ confirm: true, cancel: false, content: '证据' }] })
  await vm.showCreateFolderDialog()
  assert.equal(vm.calls.dialogs[0].editable, true)
  assert.equal(vm.calls.dialogs[0].title, 'fileTree.newFolder')
  assert.equal(vm.calls.createFolder.length, 1)
  assert.equal(vm.calls.createFolder[0][2], '证据')

  const vm2 = makeVm()
  await vm2.showCreateFolderDialog()
  assert.equal(vm2.calls.createFolder.length, 0)
})

test('新建文件夹用了保留名：提示后带着原输入重开，不建', async () => {
  toasts = []
  const vm = makeVm({ replies: [{ confirm: true, cancel: false, content: '.stagezone' }] })
  await vm.showCreateFolderDialog()
  assert.equal(vm.calls.createFolder.length, 0)
  assert.deepEqual(toasts, ['fileTree.reservedNameNotAllowed'])
  assert.equal(vm.calls.dialogs.length, 2)
  assert.equal(vm.calls.dialogs[1].content, '.stagezone')
})

test('默认名中英文案：中文「新建文档」、英文 "New document"', async () => {
  const zh = (await import('../../src/locales/zh-CN/fileTree.js')).default
  const en = (await import('../../src/locales/en-US/fileTree.js')).default
  assert.equal(zh.defaultDocumentName, '新建文档')
  assert.equal(en.defaultDocumentName, 'New document')
})
