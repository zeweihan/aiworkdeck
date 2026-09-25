// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#885 附带现象：资源管理器里选中一个文件夹，再点工具栏「新建文档」，
// 新文档却落在根目录。   cd frontend && npm run test:project-home
//
// 根因：handleCreateFolder 的落点是 activeFolderId || parentId（选中项所在的文件夹），
// 而 createBlankWord / 模板新建两条路径只认 parentId（当前浏览层级，树模式下恒为根）。
// 这里照 file-tree-external-drop.test.mjs 的套路把 FileTree.vue 的 <script> 抠出来真跑
// handleItemClick → createBlankWord，而不是只测一个抽出来的纯函数。
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

function makeVm() {
  const calls = { createFile: [], createFolder: [] }
  const stubs = {
    createFile: async (...a) => { calls.createFile.push(a); return { id: 500 } },
    createFolder: async (...a) => { calls.createFolder.push(a); return { id: 501 } },
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
  vm.$t = (k) => k
  vm.$emit = () => {}
  vm.loadFiles = async () => {}
  vm.showErrorModal = (msg) => { throw new Error('unexpected error modal: ' + msg) }
  vm.calls = calls
  return vm
}

globalThis.uni = { showToast() {}, $emit() {}, $on() {}, $off() {} }

const folder = { id: 7, name: 'QA目录刷新复验', isFolder: true, parentId: null, sortOrder: 3 }
const doc = { id: 1, name: 'newdocument.docx', isFolder: false, parentId: null, sortOrder: 1 }
const inner = { id: 2, name: 'newdocument.docx', isFolder: false, parentId: 7, sortOrder: 0 }

test('选中文件夹后新建文档，落在该文件夹里', async () => {
  const vm = makeVm()
  vm.allFiles = [folder, doc]
  vm.handleItemClick(folder, {})
  await vm.createBlankWord()
  assert.equal(vm.calls.createFile.length, 1)
  assert.equal(vm.calls.createFile[0][1], 7, '父节点应为选中的文件夹')
})

test('选中文件夹里的文件后新建文档，落在该文件所在的文件夹', async () => {
  const vm = makeVm()
  vm.allFiles = [folder, doc, inner]
  vm.handleItemClick(inner, {})
  // BUG-27 修复后 createBlankWord 接收命名对话框给的名字，这里显式传入以隔离本测试关心的
  // 落点逻辑，不依赖 $t 桩对默认名的翻译产出。
  await vm.createBlankWord('newdocument')
  assert.equal(vm.calls.createFile[0][1], 7)
  // 目标文件夹里已有 newdocument.docx（折叠着、不在 displayFiles 里）也要避开重名
  assert.equal(vm.calls.createFile[0][2], 'newdocument (1).docx')
})

test('新建文档与新建文件夹用同一个落点', async () => {
  const vm = makeVm()
  vm.allFiles = [folder, doc]
  vm.handleItemClick(folder, {})
  vm.newFolderName = '子目录'
  await vm.handleCreateFolder()
  await vm.createBlankWord()
  assert.equal(vm.calls.createFolder[0][1], vm.calls.createFile[0][1])
})

test('选中的文件夹已进回收站时退回当前浏览层级', async () => {
  const vm = makeVm()
  vm.allFiles = [folder, doc]
  vm.handleItemClick(folder, {})
  vm.recycleBin = [folder]
  vm.newFolderName = '子目录'
  await vm.handleCreateFolder()
  await vm.createBlankWord()
  assert.equal(vm.calls.createFile[0][1], null)
  assert.equal(vm.calls.createFolder[0][1], null)
})

test('没有选中任何东西时仍落在当前浏览层级', async () => {
  const vm = makeVm()
  vm.allFiles = [folder, doc]
  vm.displayFiles = [folder, doc]
  await vm.createBlankWord('newdocument')
  assert.equal(vm.calls.createFile[0][1], null)
  assert.equal(vm.calls.createFile[0][2], 'newdocument (1).docx')
})
