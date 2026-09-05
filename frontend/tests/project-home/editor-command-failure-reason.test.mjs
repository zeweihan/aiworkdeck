// 尽调起草实测（dev-board#100）：worker 的失败分支大多只填 message（如 delete_match 的
// 「match index out of range」），而回传只取 result.error —— 模型收到的是 {"error": "null"}，
// 等于没告诉它哪里错了，只能瞎猜着重试（实测因此触发了一串无效的删改）。
// 修法：失败时 error 缺省退到 message。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
// dev-board#460：写入完成通知的依赖同样要喂进来（剥壳后 import 行被去掉）
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')

function loadMethods(sent) {
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream[\s\S]*?\n\}\n/, '')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  const factory = new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', body)
  return factory(
    async (conversationId, requestId, success, result, error) => { sent.push({ success, result, error }) },
    async () => null, createSerialQueue, { showToast: () => {} },
    DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction)
}

function makeVm(result, sent) {
  const vm = {
    projectId: 1,
    libreOfficeActive: true,
    libreOfficeExecutor: { executeCommand: async () => result },
    $refs: {},
    $t: (k) => k,
  }
  for (const [k, fn] of Object.entries(loadMethods(sent))) vm[k] = fn.bind(vm)
  return vm
}

test('worker 只填 message 的失败：回传的 error 要带上原因，不能是 null', async () => {
  const sent = []
  const vm = makeVm({ success: false, message: 'match index out of range: 1' }, sent)
  await vm.handleEditorCommand({ action: 'delete_match', params: {}, requestId: 'r1', conversationId: 'c1' })
  assert.equal(sent.length, 1)
  assert.equal(sent[0].success, false)
  assert.equal(sent[0].error, 'match index out of range: 1')
})

test('worker 填了 error 的失败：原样回传', async () => {
  const sent = []
  const vm = makeVm({ success: false, error: '样式不存在: Heading 9', message: '样式不存在: Heading 9' }, sent)
  await vm.handleEditorCommand({ action: 'set_style', params: {}, requestId: 'r2', conversationId: 'c2' })
  assert.equal(sent[0].error, '样式不存在: Heading 9')
})

test('成功的命令不因为带 message 就被当成失败', async () => {
  const sent = []
  const vm = makeVm({ success: true, message: 'ok', inserted: '表1-1' }, sent)
  await vm.handleEditorCommand({ action: 'insert_at_cursor', params: {}, requestId: 'r3', conversationId: 'c3' })
  assert.equal(sent[0].success, true)
  assert.equal(sent[0].error, null)
})
