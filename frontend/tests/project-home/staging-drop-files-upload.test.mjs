// 审计（dev-board#74）HIGH 的落地一半：FileStagingArea 发出 drop-files 之后，
// project-overview 的消费端 stagingArea.js#onStagingDropFiles 是否真的把文件
// 接进了 FileTree 的上传队列（selectedFiles/selectedUploadParent + confirmUpload），
// 而不是也一样悄悄什么都不做。
//
// stagingArea.js 依赖不多（api.js 四个函数 + externalLink.js/siteLinks.js 各一个，
// 后两个只在配额超限分支用到，与本条无关），照本仓一贯套路抠 <script> 求值。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// onStagingDropFiles 补拉暂存列表用的是真实 setTimeout(…, 2500)（详见实现里的注释：
// 尽力而为的补拉，不是完成通知）——测试不关心这 2.5s 本身，短路成 0ms 避免拖慢套件。
const realSetTimeout = globalThis.setTimeout
globalThis.setTimeout = (fn, ms, ...args) => realSetTimeout(fn, 0, ...args)

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

function makeVm(methods, { confirmUploadImpl } = {}) {
  const vm = {
    projectId: 1,
    stagingFolderId: 'stage-1', // 已经建好暂存目录（ensureStagingFolder 早退分支）
    stagingPinned: false,
    stagingFiles: [],
    loadStagingFilesCalls: 0,
    $refs: {
      fileTree: {
        selectedFiles: null,
        selectedUploadParent: null,
        isFolderUpload: true, // 故意设个"错"的初值，验证方法会把它归零
        confirmUpload: confirmUploadImpl || (async () => {}),
      },
    },
    $t: (k) => k,
  }
  for (const [k, fn] of Object.entries(methods)) vm[k] = fn.bind(vm)
  vm.loadStagingFiles = async () => { vm.loadStagingFilesCalls++ }
  return vm
}

test('拖入的真实文件必须接进 FileTree 的上传队列（selectedFiles/selectedUploadParent），而不是只弹个提示就结束', async () => {
  const methods = loadMethods()
  const vm = makeVm(methods)
  const fileA = { name: 'a.pdf' }
  const fileB = { name: 'b.docx' }
  await vm.onStagingDropFiles([fileA, fileB])

  assert.deepEqual(vm.$refs.fileTree.selectedFiles, [fileA, fileB],
    '必须把拖进来的原生 File 列表塞进 FileTree 的 selectedFiles')
  assert.equal(vm.$refs.fileTree.selectedUploadParent, 'stage-1',
    '上传目标必须是暂存目录，不能传去别的地方')
  assert.equal(vm.$refs.fileTree.isFolderUpload, false, '这是文件拖拽，不是文件夹上传模式')
  assert.equal(vm.stagingPinned, true, '暂存面板应该自动展开，让用户看到上传进度')
})

test('空文件列表直接早退，不建目录、不碰 FileTree', async () => {
  const methods = loadMethods()
  let confirmUploadCalled = false
  const vm = makeVm(methods, { confirmUploadImpl: async () => { confirmUploadCalled = true } })
  await vm.onStagingDropFiles([])
  await vm.onStagingDropFiles(null)
  assert.equal(confirmUploadCalled, false)
  assert.equal(vm.stagingPinned, false)
})

test('FileTree 的 ref 还没挂载好时不抛异常，只记警告', async () => {
  const methods = loadMethods()
  const vm = makeVm(methods)
  vm.$refs.fileTree = null
  await assert.doesNotReject(() => vm.onStagingDropFiles([{ name: 'x.pdf' }]))
})

test('拖入时暂存目录还没建过（stagingFolderId 为空）会先建目录再上传', async () => {
  const methods = loadMethods()
  const vm = makeVm(methods)
  vm.stagingFolderId = null
  vm.ensureStagingFolder = async function () { this.stagingFolderId = 'newly-created' }
  let capturedParent = null
  vm.$refs.fileTree.confirmUpload = async function () { capturedParent = this.selectedUploadParent }
  await vm.onStagingDropFiles([{ name: 'x.pdf' }])
  assert.equal(capturedParent, 'newly-created')
})
