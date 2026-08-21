// 审计（dev-board#74）MEDIUM：loadArchiveEntries 没有陈旧守卫，快速切换 zip/rar/7z
// 文件会显示错的条目列表。
//
// 病灶：reloadPreview 为每个新文件重新调一次 loadArchiveEntries，方法内部既不带
// 请求代次、也没有像 loadMediaResource 那样用 _mediaReqId 判断陈旧——先发出但后
// 回来的旧文件响应会覆盖已经显示的新文件条目列表；而且 this.file 是在 await 之后
// 才读的活引用，读到的可能已经是切换后的新文件。
//
// 修法：仿 loadMediaResource 的思路，但复用已有的共享工具 utils/requestGeneration.js
// （shouldAcceptResponse，同一份已经在 PersonalSettingsPanel 的 TOTP 竞态修复里用过，
// 见 request-generation.test.mjs 覆盖该工具本身），并把 file 摘成局部变量避免读到
// 切换后的引用。
//
// FilePreview.vue 依赖不多（api.js 三个函数 + auth.js 两个函数 + ICONS 常量），
// 照 libre-editor-retry-reentrancy.test.mjs 的套路抠 <script> 求值，shouldAcceptResponse
// 直接传真实实现（不是重新发明一个假的判定逻辑）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { shouldAcceptResponse } from '../../src/utils/requestGeneration.js'

const SRC = readFileSync(new URL('../../src/components/FilePreview.vue', import.meta.url), 'utf8')

function loadMethods(getArchiveEntriesImpl) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    // uni-app 条件编译：H5/非 H5 两个分支都留着会撞名（IS_H5 declared twice）。
    // 本仓测试只跑 H5 分支的行为（loadArchiveEntries 与平台判定无关），丢非 H5 分支。
    .replace(/\/\/\s*#ifndef H5[\s\S]*?\/\/\s*#endif\n/, '')
    .replace(/\/\/\s*#ifdef H5\n/, '')
    .replace(/\/\/\s*#endif\n/, '')
    .replace(/export default \{/, 'return {')
  const factory = new Function(
    'getFileDownloadUrl', 'getArchiveEntries', 'extractArchive',
    'getAuthHeaders', 'getSessionId', 'ICONS', 'shouldAcceptResponse',
    body)
  return factory(
    () => '/download', getArchiveEntriesImpl, async () => ({}),
    () => ({}), () => 'sess', {}, shouldAcceptResponse
  ).methods
}

function makeVm(methods) {
  const vm = {
    file: null,
    archiveLoading: false,
    archiveError: '',
    archiveEntries: [],
    $t: (k) => k,
  }
  vm.loadArchiveEntries = methods.loadArchiveEntries.bind(vm)
  return vm
}

const deferred = () => {
  let resolve, reject
  const promise = new Promise((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

test('快速切换两个压缩包：先发出但后回来的旧响应不许覆盖新文件已经显示的条目列表', async () => {
  const gates = { A: deferred(), B: deferred() }
  const calls = []
  const methods = loadMethods((projectId, fileId) => {
    calls.push(fileId)
    return gates[fileId].promise
  })
  const vm = makeVm(methods)

  vm.file = { projectId: 1, id: 'A' }
  const pA = vm.loadArchiveEntries() // 用户点开 A
  vm.file = { projectId: 1, id: 'B' } // 还没等 A 回来就切到 B
  const pB = vm.loadArchiveEntries()

  // B（当前正看着的文件）先回来
  gates.B.resolve({ entries: ['b1.txt', 'b2.txt'] })
  await pB
  assert.deepEqual(vm.archiveEntries, ['b1.txt', 'b2.txt'])
  assert.equal(vm.archiveLoading, false)

  // A 这时才姗姗来迟——必须被丢弃，不能覆盖 B 已经显示的内容
  gates.A.resolve({ entries: ['a1.txt'] })
  await pA
  assert.deepEqual(vm.archiveEntries, ['b1.txt', 'b2.txt'], '陈旧响应不许覆盖当前文件的条目列表')
  assert.equal(vm.archiveLoading, false, '陈旧响应也不许把 loading 状态重新翻回 false 覆盖（虽然这里凑巧都是 false，仍要走同一条判定）')
  assert.deepEqual(calls, ['A', 'B'])
})

test('陈旧请求的失败（网络错误）同样不许覆盖当前文件已经显示的成功结果', async () => {
  const gates = { A: deferred(), B: deferred() }
  const methods = loadMethods((projectId, fileId) => gates[fileId].promise)
  const vm = makeVm(methods)

  vm.file = { projectId: 1, id: 'A' }
  const pA = vm.loadArchiveEntries()
  vm.file = { projectId: 1, id: 'B' }
  const pB = vm.loadArchiveEntries()

  gates.B.resolve({ entries: ['b1.txt'] })
  await pB
  gates.A.reject(new Error('network hiccup'))
  await pA

  assert.deepEqual(vm.archiveEntries, ['b1.txt'])
  assert.equal(vm.archiveError, '', '陈旧请求的失败不许把错误文案写进当前文件的状态')
})

test('最新一次请求失败照常显示错误（不能被判定逻辑连累成永远不报错）', async () => {
  const methods = loadMethods(async () => { throw new Error('boom') })
  const vm = makeVm(methods)
  vm.file = { projectId: 1, id: 'A' }
  await vm.loadArchiveEntries()
  assert.equal(vm.archiveError, 'boom')
  assert.equal(vm.archiveLoading, false)
})

test('没有并发时正常加载不受影响（回归保护）', async () => {
  const methods = loadMethods(async () => ({ entries: ['x.txt'] }))
  const vm = makeVm(methods)
  vm.file = { projectId: 1, id: 'A' }
  await vm.loadArchiveEntries()
  assert.deepEqual(vm.archiveEntries, ['x.txt'])
  assert.equal(vm.archiveLoading, false)
  assert.equal(vm.archiveError, '')
})
