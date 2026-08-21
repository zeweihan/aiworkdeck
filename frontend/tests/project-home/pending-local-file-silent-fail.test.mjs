// 审计（dev-board#74）MEDIUM：openPendingLocalFile 找不到文件、或拉取文件列表出错
// 时完全静默——只有 console.warn，用户在界面上看不到任何反应。
//
// 触发场景：newproject 页带 openFileId 查询参数落地工作台，project-overview.vue
// 用 setTimeout(() => this.openPendingLocalFile(pendingId), 600) 打开刚创建的文件；
// 如果后端还没写完/索引没跟上（600ms 窗口不够），或 getProjectFiles 网络抖动，
// 调用方与用户都分不清"文件真不存在"还是"再试一次就好"还是"网络错了"。
//
// 修法：找不到与请求失败两个分支都补上 uni.showToast，复用同文件里
// handleOpenFileFromChat（紧邻的姊妹方法）已经在用的同款 i18n key，不新造文案。
//
// fileOpenTabs.js 带 @/ 别名 import，照本仓一贯套路抠出来 new Function 求值；
// uni 作为显式参数传入（同 search-panel-stale-toast.test.mjs 的做法），不用
// globalThis 全局污染。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')

function loadMethods(getProjectFilesImpl, uniStub) {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const fileOpenTabsMethods = \{/, 'return {')
  const factory = new Function('getProjectFiles', 'activityTracker', 'GLYPHS', 'fileGlyph', 'uni', body)
  return factory(getProjectFilesImpl, { track: () => {} }, {}, () => '', uniStub)
}

// 返回 { vm, toasts }：toasts 是 uni.showToast 调用记录，供各用例断言"到底弹没弹"。
function makeVm(getProjectFilesImpl) {
  const toasts = []
  const methods = loadMethods(getProjectFilesImpl, { showToast: (opts) => toasts.push(opts) })
  const vm = {
    projectId: 1,
    openedFiles: [],
    $t: (k) => k,
    openFile(f) { vm.openedFiles.push(f) },
  }
  vm.openPendingLocalFile = methods.openPendingLocalFile.bind(vm)
  return { vm, toasts }
}

test('文件确实不存在时必须提示用户，不能只是 console.warn', async () => {
  const { vm, toasts } = makeVm(async () => ({ data: [{ id: 999, name: 'other.docx', isFolder: false }] }))
  await vm.openPendingLocalFile(42)
  assert.deepEqual(vm.openedFiles, [], '不该打开任何文件')
  assert.equal(toasts.length, 1, '找不到文件必须弹一次提示')
})

test('getProjectFiles 请求失败时必须提示用户，不能只是 console.warn', async () => {
  const { vm, toasts } = makeVm(async () => { throw new Error('network hiccup') })
  await vm.openPendingLocalFile(42)
  assert.deepEqual(vm.openedFiles, [])
  assert.equal(toasts.length, 1, '请求失败必须弹一次提示')
})

test('文件存在时正常打开，不弹多余的提示（回归保护）', async () => {
  const { vm, toasts } = makeVm(async () => ({ data: [{ id: 42, name: 'brief.docx', isFolder: false }] }))
  await vm.openPendingLocalFile(42)
  assert.deepEqual(vm.openedFiles, [{ id: 42, name: 'brief.docx', isFolder: false }])
  assert.equal(toasts.length, 0)
})

test('fileId 为空时直接返回，不请求也不提示（回归保护，原有早退语义不变）', async () => {
  let called = false
  const { vm, toasts } = makeVm(async () => { called = true; return { data: [] } })
  await vm.openPendingLocalFile(null)
  assert.equal(called, false)
  assert.equal(toasts.length, 0)
})

test('文件夹与目标 id 相同不算命中（isFolder 必须被排除，回归保护，原有过滤条件不变）', async () => {
  const { vm, toasts } = makeVm(async () => ({ data: [{ id: 42, name: 'a-folder', isFolder: true }] }))
  await vm.openPendingLocalFile(42)
  assert.deepEqual(vm.openedFiles, [])
  assert.equal(toasts.length, 1)
})
