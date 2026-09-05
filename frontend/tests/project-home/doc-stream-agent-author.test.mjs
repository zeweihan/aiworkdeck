// dev-board#367：AI 流式落字（doc_start_stream → doc_stream_data → stream_insert）
// 这一路曾经不带 __agent 标记，worker 据此把修订署成当前用户名——用户在 Word 里
// 看到 AI 写的内容记在自己名下，分不清哪些是 AI 改的。handleEditorCommand 一直带
// 标记，只有流式这条漏了。锁住：stream_insert / stream_flush 下发的参数必须带
// __agent:true。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
// dev-board#460：写入完成通知的依赖同样要喂进来（剥壳后 import 行被去掉）
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')

function loadMethods() {
  // shouldFlushDocStream 留在函数体内（flushDocStreamBuffer 要调它判目标文件一致）
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream/, 'function shouldFlushDocStream')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  const factory = new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', body)
  return factory(async () => {}, async () => null, createSerialQueue, { showToast: () => {} },
    DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction)
}

function makeVm(calls) {
  const vm = {
    projectId: 1,
    libreOfficeActive: true,
    libreOfficeExecutor: { executeCommand: async (action, params) => { calls.push({ action, params }); return { success: true } } },
    resolveLibreExecutorFileId: () => 7,
    _docStreamTargetFileId: 7,
    $refs: {},
    $t: (k) => k,
  }
  for (const [k, fn] of Object.entries(loadMethods())) vm[k] = fn.bind(vm)
  return vm
}

test('stream_insert 下发的参数带 __agent:true（修订署名 AI WorkDeck）', async () => {
  const calls = []
  const vm = makeVm(calls)
  vm._docStreamBuffer = '第一条 服务内容\n'
  await vm.flushDocStreamBuffer()
  const ins = calls.filter((c) => c.action === 'stream_insert')
  assert.equal(ins.length, 1)
  assert.equal(ins[0].params.text, '第一条 服务内容\n')
  assert.equal(ins[0].params.__agent, true)
})

test('stream_flush 收尾同样带 __agent:true（尾行/尾表也是 AI 写的）', async () => {
  const calls = []
  const vm = makeVm(calls)
  await vm.handleDocStreamEnd()
  const fl = calls.filter((c) => c.action === 'stream_flush')
  assert.equal(fl.length, 1)
  assert.equal(fl[0].params.__agent, true)
})
