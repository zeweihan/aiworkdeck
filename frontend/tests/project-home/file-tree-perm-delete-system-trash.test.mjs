// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1051：桌面壳里回收站「彻底删除」把磁盘上的文件送进系统废纸篓，而不是让后端直接删。
// 本机文件夹项目的文件就是律师自己文件夹里的真文件，Files.delete 之后找不回来。
//
// 顺序即保证：取后端报的物理路径 → 主进程 trashItems → 全部成功才 deleteFilePerm(diskHandled)。
// 送废纸篓失败时行必须留在回收站（不调 deleteFilePerm）；非桌面壳走原路。
// 抠脚本套路同 file-tree-delete-dialog-esc.test.mjs。跑法：npm run test:project-home
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')

const IMPORT_NAMES = [
  'getProjectFiles', 'createFolder', 'createFile', 'renameFile', 'deleteFile', 'deleteFilePerm',
  'getFilePermDiskPaths',
  'restoreFileApi', 'getRecycleBinFiles', 'moveFile', 'batchDeleteFiles', 'batchMoveFiles',
  'batchCopyFiles', 'getApiBaseUrl', 'getContributedTemplates', 'createFileFromContributedTemplate',
  'getSessionId', 'host', 'findTopmostDeletedAncestor', 'summarizeDeleteResults', 'collapseToTopmostSelected',
  'groupByParent', 'buildTreeFromGroups', 'evidenceRefCounts', 'createRefCountsFetcher',
  'warmDragImage', 'applyDragImage', 'FileTypeIcon', 'TagChip', 'TagSelector',
  'TagManager', 'AwdDatePicker', 'ICONS', 'getProjectTags', 'addTagToFile', 'removeTagFromFile',
  'createTag', 'createTask', 'importLocalFile',
  'nativeDataTransfer', 'isExternalFileDrag', 'claimExternalDrop', 'isTranscribableMediaItem',
  'showDialog',
]

const toasts = []
globalThis.uni = { showToast(o) { toasts.push(o.title) }, showActionSheet() {}, $emit() {}, $on() {}, $off() {} }

// desktop：是否带 host.fs.trashItems；diskPaths：id → 后端报回的路径；trashOk：trashItems 的整体结果
function makeVm({ desktop = true, diskPaths = {}, trashFailOn = [] } = {}) {
  const log = []
  const host = desktop
    ? { fs: {
        showItemInFolder() {},
        trashItems: async (paths) => {
          log.push(['trash', paths])
          const results = paths.map((p) => ({ path: p, ok: !trashFailOn.includes(p) }))
          return { ok: results.every((r) => r.ok), results }
        },
      } }
    : {}
  const stubs = {
    host,
    deleteFilePerm: async (...a) => { log.push(['purge', ...a]) },
    getFilePermDiskPaths: async (pid, id) => {
      log.push(['paths', pid, id])
      return { code: 0, data: { paths: diskPaths[id] || [] } }
    },
    showDialog: async (opts) => { log.push(['dialog', opts]); return { confirm: true, cancel: false } },
    summarizeDeleteResults: (results) => ({
      succeededIds: results.filter((r) => r.ok).map((r) => r.id),
      failedIds: results.filter((r) => !r.ok).map((r) => r.id),
    }),
    collapseToTopmostSelected: (ids) => ({ roots: ids, coveredBy: new Map() }),
    ICONS: {},
  }
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+['"][^'"]+['"]\s*$/gm, '')
    .replace(/export default \{/, 'return {')
  const options = new Function(...IMPORT_NAMES, body)(...IMPORT_NAMES.map(n => (n in stubs ? stubs[n] : (() => {}))))
  const vm = Object.assign({}, options.data.call({}))
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.isDesktopShell = options.computed.isDesktopShell.call(vm)
  vm.trashesToSystem = options.computed.trashesToSystem.call(vm)
  vm.projectId = 42
  vm.viewMode = 'recycle'
  vm.recycleBin = [{ id: 7, name: '证据.docx' }, { id: 8, name: '卷宗' }]
  vm.$t = (k) => k
  vm.$emit = () => {}
  vm.loadFiles = async () => {}
  vm.clearChecked = () => {}
  vm.showErrorModal = (msg) => { throw new Error('unexpected error modal: ' + msg) }
  vm.log = log
  return vm
}

test('桌面壳：先取路径、再送废纸篓、最后只清行（diskHandled）', async () => {
  toasts.length = 0
  const vm = makeVm({ diskPaths: { 7: ['/Users/x/案卷/证据.docx'] } })
  await vm.permDeleteFile({ id: 7, name: '证据.docx', isFolder: false })

  const steps = vm.log.filter((e) => e[0] !== 'dialog')
  assert.deepEqual(steps, [
    ['paths', 42, 7],
    ['trash', ['/Users/x/案卷/证据.docx']],
    ['purge', 42, 7, { diskHandled: true }],
  ])
  const dialog = vm.log.find((e) => e[0] === 'dialog')[1]
  assert.match(dialog.content, /fileTree\.systemTrashNote/, '桌面确认文案要说「移到系统废纸篓」')
  assert.doesNotMatch(dialog.content, /irreversibleNote/)
  assert.deepEqual(toasts, ['fileTree.permDeleteSuccess'])
})

test('送废纸篓失败：不清行，行留在回收站，提示专门的失败文案', async () => {
  toasts.length = 0
  const vm = makeVm({ diskPaths: { 7: ['/Users/x/案卷/证据.docx'] }, trashFailOn: ['/Users/x/案卷/证据.docx'] })
  await vm.permDeleteFile({ id: 7, name: '证据.docx', isFolder: false })

  assert.equal(vm.log.filter((e) => e[0] === 'purge').length, 0, '字节还在盘上时行不能先没了')
  assert.equal(vm.recycleBin.length, 2)
  assert.deepEqual(toasts, ['fileTree.systemTrashFailed'])
})

test('后端报空路径（行已不在/无物理文件）：不调 trashItems，照常清行', async () => {
  const vm = makeVm({ diskPaths: {} })
  await vm.permDeleteFile({ id: 7, name: '证据.docx', isFolder: false })

  assert.equal(vm.log.filter((e) => e[0] === 'trash').length, 0)
  assert.deepEqual(vm.log.filter((e) => e[0] === 'purge'), [['purge', 42, 7, { diskHandled: true }]])
})

test('非桌面壳：原路——不取路径、不送废纸篓，deleteFilePerm 不带 diskHandled，确认文案仍是不可撤销', async () => {
  const vm = makeVm({ desktop: false })
  assert.equal(vm.trashesToSystem, false)
  await vm.permDeleteFile({ id: 7, name: '证据.docx', isFolder: false })

  assert.equal(vm.log.filter((e) => e[0] === 'paths' || e[0] === 'trash').length, 0)
  assert.deepEqual(vm.log.filter((e) => e[0] === 'purge'), [['purge', 42, 7]])
  const dialog = vm.log.find((e) => e[0] === 'dialog')[1]
  assert.match(dialog.content, /fileTree\.irreversibleNote/)
})

test('批量彻底删除（清空回收站）：逐项同样三步，送废纸篓失败的那一项不清行、计入失败', async () => {
  toasts.length = 0
  const vm = makeVm({
    diskPaths: { 7: ['/Users/x/案卷/证据.docx'], 8: ['/Users/x/案卷/卷宗'] },
    trashFailOn: ['/Users/x/案卷/卷宗'],
  })
  vm.deleteMode = 'hard'
  vm.deleteBatchIds = [7, 8]
  await vm.executeBatchDelete()

  assert.deepEqual(vm.log.filter((e) => e[0] === 'purge'), [['purge', 42, 7, { diskHandled: true }]])
  assert.deepEqual(vm.recycleBin.map((f) => f.id), [8], '失败的那一项留在回收站')
  assert.equal(toasts.length, 1)
  assert.match(toasts[0], /permDeletePartialFail/)
})
