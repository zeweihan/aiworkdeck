// 审计（dev-board#74）MEDIUM：doc_open_file_sync 没有重入闸，重复/重试的同步打开
// 请求会冲掉在途请求的流式缓冲。
//
// 病灶：handleClientAction 同步分发，不认在飞标记。后端丢失 ack 后重试、或新一轮
// 生成在上一轮最长 90s 的 editor-ready 等待还没完时就到达，两次 handleEditorOpenFileSync
// 会并发跑，第二次跑到"第 5 步：重置流式缓冲"时会把第一次仍在使用的缓冲区清空/丢弃，
// 静默丢字。
//
// 修法：把整段实现挪进 _handleEditorOpenFileSyncImpl，对外的 handleEditorOpenFileSync
// 只是一层薄封装，接进 createSerialQueue()（与 DrawioEditor.persist 同款串行化工具，
// 见 async-serialize.test.mjs 覆盖该工具本身的正确性），保证两次调用绝不交叉。
//
// agentClientActions.js 带 @/ 别名 import，本仓一贯限制是这类文件 import 不进来
// （见 audit-b2-source-assertions.test.mjs 文件头）；用 new Function 抠 <script> 主体
// 求值，createSerialQueue 直接引用真实实现（该工具自身的正确性已有独立测试覆盖）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
// dev-board#460：写入完成通知的依赖同样要喂进来（剥壳后 import 行被去掉）
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')

function loadMethods(sendEditorResultImpl, getFileDetailImpl) {
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream[\s\S]*?\n\}\n/, '')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  const factory = new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', body)
  return factory(sendEditorResultImpl, getFileDetailImpl, createSerialQueue, { showToast: () => {} },
    DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction)
}

function makeVm(methods) {
  const vm = {
    projectId: 1,
    $refs: { fileTree: { loadFiles: async () => {} } },
    openedFiles: [],
    openFile: async (file) => { vm.openedFiles.push(file.id) },
    libreOfficeActive: true,
    libreOfficeExecutor: { executeCommand: async () => ({ success: true }) },
    $t: (k) => k,
  }
  for (const [k, fn] of Object.entries(methods)) vm[k] = fn.bind(vm)
  return vm
}

// 编辑器就绪轮询里的 setTimeout(resolve, 500) 每次迭代都会真等 500ms（就算一开始
// libreOfficeActive/libreOfficeExecutor 已经就绪，循环体也是"先 sleep 再判断"）。
// 把 setTimeout 短路成 0ms，测试跑起来不用真等。
function withFastTimers(fn) {
  const real = globalThis.setTimeout
  globalThis.setTimeout = (cb, ms, ...args) => real(cb, 0, ...args)
  return fn().finally(() => { globalThis.setTimeout = real })
}

test('第二个 doc_open_file_sync 必须排队等第一个完全跑完，不能在中途插进来执行', async () => {
  await withFastTimers(async () => {
    const events = []
    let releaseA
    const gateA = new Promise((resolve) => { releaseA = resolve })

    const getFileDetailImpl = async (projectId, fileId) => {
      events.push('getFileDetail:' + fileId)
      if (fileId === 'A') await gateA // A 卡在这里，直到测试手动放行
      return { id: fileId, name: fileId + '.docx' }
    }
    const sendEditorResultImpl = async (conversationId, requestId) => {
      events.push('ack:' + requestId)
    }

    const methods = loadMethods(sendEditorResultImpl, getFileDetailImpl)
    const vm = makeVm(methods)

    const pA = vm.handleEditorOpenFileSync({ params: { fileId: 'A' }, requestId: 'req-A', conversationId: 'c1' })
    // 让 A 跑到卡住的 getFileDetail（经过串行队列的 .then 跳转 + loadFiles 的 await）
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    const pB = vm.handleEditorOpenFileSync({ params: { fileId: 'B' }, requestId: 'req-B', conversationId: 'c1' })
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve()

    assert.deepEqual(events, ['getFileDetail:A'],
      'B 必须排队等待——A 还卡着的时候 B 不许已经跑到它自己的 getFileDetail，' +
      '否则两条请求会交叉执行，B 先跑完的第 5 步会把 A 仍在用的流式缓冲清空/丢弃')

    releaseA()
    await Promise.all([pA, pB])

    assert.deepEqual(events, ['getFileDetail:A', 'ack:req-A', 'getFileDetail:B', 'ack:req-B'],
      '两次调用必须完整地一前一后跑完，各自的 ack 都要送达，且不交叉')
    assert.deepEqual(vm.openedFiles, ['A', 'B'])
  })
})

test('单次调用（没有并发）行为不受影响：正常打开成功并回一次 ack', async () => {
  await withFastTimers(async () => {
    const acks = []
    const methods = loadMethods(
      async (conversationId, requestId, ok) => { acks.push({ requestId, ok }) },
      async (projectId, fileId) => ({ id: fileId, name: fileId + '.docx' }))
    const vm = makeVm(methods)

    await vm.handleEditorOpenFileSync({ params: { fileId: 'X' }, requestId: 'req-X', conversationId: 'c1' })

    assert.deepEqual(acks, [{ requestId: 'req-X', ok: true }])
    assert.deepEqual(vm.openedFiles, ['X'])
  })
})

test('接线核实：公开方法只是薄封装，真正的逻辑在 _handleEditorOpenFileSyncImpl 里，且经过 _docOpenSyncQueue', () => {
  const src = SRC.replace(/^\s*\/\/.*$/gm, '')
  const start = src.indexOf('async handleEditorOpenFileSync(action) {')
  assert.ok(start > 0, '找不到 handleEditorOpenFileSync')
  const end = src.indexOf('async _handleEditorOpenFileSyncImpl', start)
  const body = src.slice(start, end)
  assert.match(body, /createSerialQueue\(\)/, '必须用 createSerialQueue 建队列')
  assert.match(body, /this\._docOpenSyncQueue\(\(\) => this\._handleEditorOpenFileSyncImpl\(action\)\)/,
    '必须把真正的实现接进队列，而不是直接跑')
})
