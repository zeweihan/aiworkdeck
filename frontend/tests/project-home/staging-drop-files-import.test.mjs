// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 审计（dev-board#74）HIGH 的落地一半：FileStagingArea 发出 drop-files 之后，
// project-overview 的消费端 stagingArea.js#onStagingDropFiles 是否真的把文件
// 接进了导入通道，而不是也一样悄悄什么都不做。
//
// dev-board#513 起资源管理器只剩「导入本机路径」一条通道，暂存区跟着改借
// FileTree.importDroppedLocalFiles（顶层条目逐个 import-local，目录由后端递归展开）。
// import-local 返回时字节已经在目录里，所以补拉一次 loadStagingFiles 就是最终形态，
// 不再需要旧的两次 setTimeout 补拉。
//
// stagingArea.js 依赖不多（api.js 四个函数 + externalLink.js/siteLinks.js 各一个，
// 后两个只在配额超限分支用到，与本条无关），照本仓一贯套路抠 <script> 求值。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/stagingArea.js', import.meta.url), 'utf8')

function loadMethods() {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const stagingAreaMethods = \{/, 'return {')
  const factory = new Function(
    'getProjectFiles', 'createFolder', 'batchMoveFiles', 'getStageUsage',
    'openExternalUrl', 'accountPageUrl', 'uni', body)
  return factory(
    async () => ({ data: [] }), async () => ({ id: 'stage-1' }), async () => {}, async () => null,
    () => {}, () => '', { showToast: () => {} })
}

function makeVm(methods, { importImpl } = {}) {
  const imports = []
  const vm = {
    projectId: 1,
    stagingFolderId: 'stage-1', // 已经建好暂存目录（ensureStagingFolder 早退分支）
    stagingPinned: false,
    stagingFiles: [],
    loadStagingFilesCalls: 0,
    imports,
    $refs: {
      fileTree: {
        importDroppedLocalFiles: importImpl || (async (...a) => { imports.push(a) }),
      },
    },
    $t: (k) => k,
  }
  for (const [k, fn] of Object.entries(methods)) vm[k] = fn.bind(vm)
  vm.loadStagingFiles = async () => { vm.loadStagingFilesCalls++ }
  return vm
}

test('拖入的真实文件必须接进导入通道：顶层条目原样交给 FileTree.importDroppedLocalFiles，落点是暂存目录', async () => {
  const methods = loadMethods()
  const vm = makeVm(methods)
  const fileA = { name: 'a.pdf' }
  const dirB = { name: '证据' }
  await vm.onStagingDropFiles([fileA, dirB])

  assert.equal(vm.imports.length, 1, '整批一次调用，逐条 import-local 在 FileTree 里做')
  assert.deepEqual(Array.from(vm.imports[0][0]), [fileA, dirB],
    '拖进来的顶层 File 列表（文件或目录）原样传下去')
  assert.equal(vm.imports[0][1], 'stage-1', '落点必须是暂存目录，不能传去别的地方')
  assert.equal(vm.stagingPinned, true, '暂存面板应该自动展开，让用户看到结果')
})

test('导入完补拉一次暂存列表（import-local 返回即最终形态，不再靠延时补拉）', async () => {
  const methods = loadMethods()
  const order = []
  const vm = makeVm(methods, { importImpl: async () => { order.push('import') } })
  vm.loadStagingFiles = async () => { order.push('load'); vm.loadStagingFilesCalls++ }
  await vm.onStagingDropFiles([{ name: 'a.pdf' }])
  assert.deepEqual(order, ['import', 'load'], '先导入再刷新，且只刷一次')
  assert.equal(vm.loadStagingFilesCalls, 1)
})

test('空文件列表直接早退，不建目录、不碰 FileTree', async () => {
  const methods = loadMethods()
  let called = false
  const vm = makeVm(methods, { importImpl: async () => { called = true } })
  await vm.onStagingDropFiles([])
  await vm.onStagingDropFiles(null)
  assert.equal(called, false)
  assert.equal(vm.stagingPinned, false)
})

test('FileTree 的 ref 还没挂载好时不抛异常，只记警告', async () => {
  const methods = loadMethods()
  const vm = makeVm(methods)
  vm.$refs.fileTree = null
  await assert.doesNotReject(() => vm.onStagingDropFiles([{ name: 'x.pdf' }]))
})

test('拖入时暂存目录还没建过（stagingFolderId 为空）会先建目录再导入', async () => {
  const methods = loadMethods()
  let capturedParent = null
  const vm = makeVm(methods, { importImpl: async (_list, parentId) => { capturedParent = parentId } })
  vm.stagingFolderId = null
  vm.ensureStagingFolder = async function () { this.stagingFolderId = 'newly-created' }
  await vm.onStagingDropFiles([{ name: 'x.pdf' }])
  assert.equal(capturedParent, 'newly-created')
})
