// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1097：get_document_text / get_paragraph 的回执带 sourceFileId（真实来源文件）。
// 身份在派发命令那一刻从实际 executor 反查（resolveLibreExecutorFileId），不猜、
// 不拿用户传入 fileId 冒充；worker 原结果不就地污染；失败 / 无关命令不带身份。
// 桩法同 agent-editor-booting-wait：剥壳求值 agentClientActions + librePool。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'
import { actionBudgetMs } from '../../src/composables/zetaOfficeRelay.js'

const SRC = readFileSync(new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
const POOL_SRC = readFileSync(new URL('../../src/pages/project-overview/librePool.js', import.meta.url), 'utf8')

function loadMethods(sent) {
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream[\s\S]*?\n\}\n/, '')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  return new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', 'actionBudgetMs', body)(
    async (conversationId, requestId, success, data, error) => { sent.push({ requestId, success, data, error }) },
    async () => null, createSerialQueue, { showToast: () => {}, $emit: () => {} },
    DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction, actionBudgetMs)
}
function loadPool() {
  const body = POOL_SRC.replace(/^import .*$/gm, '').replace(/export const librePoolMethods = \{/, 'return {')
  return new Function('isDesktopHost', 'host', body)(() => true, {})
}

function makeVm(sent, fileId, workerResult) {
  const exec = {
    executeCommand: async (action, params) => {
      vm.executed.push({ action, params })
      if (typeof workerResult === 'function') return workerResult(action)
      return workerResult
    },
  }
  const vm = {
    projectId: 1, $refs: {}, $t: (k) => k,
    libreOfficeActive: true, libreOfficeExecutor: exec,
    _libreRefs: fileId != null ? { [`left:${fileId}`]: { file: { id: fileId }, ready: true, statusKey: 'ready' } } : {},
    _libreExecMap: fileId != null ? { [`left:${fileId}`]: exec } : {},
    executed: [],
  }
  for (const [k, fn] of Object.entries(loadMethods(sent))) vm[k] = fn.bind(vm)
  const pool = loadPool()
  for (const k of ['libreBootingInstances', 'libreLoadFailureOf', 'getLibreExecutorMap', 'resolveLibreExecutorFileId']) if (pool[k]) vm[k] = pool[k].bind(vm)
  return vm
}

const docText = { success: true, paragraphs: [{ index: 0, text: '第一条 …' }], nextStartParagraph: 1, totalParagraphs: 12, truncated: false }

test('get_document_text 成功：全文分页字段保留，附 sourceFileId', async () => {
  const sent = []
  const vm = makeVm(sent, 42, docText)
  await vm.handleEditorCommand({ action: 'get_document_text', params: { fileId: 999 }, requestId: 'r1', conversationId: 'c' })
  assert.equal(sent.length, 1)
  assert.equal(sent[0].success, true)
  assert.equal(sent[0].data.sourceFileId, '42')
  assert.equal(sent[0].data.nextStartParagraph, 1)
  assert.equal(sent[0].data.totalParagraphs, 12)
  assert.equal(sent[0].data.truncated, false)
  assert.deepEqual(sent[0].data.paragraphs, docText.paragraphs)
  // worker 原结果不被就地污染
  assert.equal('sourceFileId' in docText, false)
})

test('get_paragraph 成功：同样附 sourceFileId', async () => {
  const sent = []
  const vm = makeVm(sent, 7, { success: true, paragraph: { index: 3, text: 'x' } })
  await vm.handleEditorCommand({ action: 'get_paragraph', params: { index: 3 }, requestId: 'r2', conversationId: 'c' })
  assert.equal(sent[0].data.sourceFileId, '7')
  assert.deepEqual(sent[0].data.paragraph, { index: 3, text: 'x' })
})

test('A 读取未结束用户切到 B：回执仍标 A（派发那一刻的身份）', async () => {
  const sent = []
  const execA = { executeCommand: async () => { switchToB(); return docText } }
  const execB = { executeCommand: async () => { throw new Error('should not run') } }
  const vm = {
    projectId: 1, $refs: {}, $t: (k) => k,
    libreOfficeActive: true, libreOfficeExecutor: execA,
    _libreRefs: { 'left:11': { file: { id: 11 }, ready: true, statusKey: 'ready' }, 'right:22': { file: { id: 22 }, ready: true, statusKey: 'ready' } },
    _libreExecMap: { 'left:11': execA, 'right:22': execB },
    executed: [],
  }
  for (const [k, fn] of Object.entries(loadMethods(sent))) vm[k] = fn.bind(vm)
  const pool = loadPool()
  for (const k of ['libreBootingInstances', 'libreLoadFailureOf', 'getLibreExecutorMap', 'resolveLibreExecutorFileId']) if (pool[k]) vm[k] = pool[k].bind(vm)
  const switchToB = () => { vm.libreOfficeExecutor = execB }
  await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r3', conversationId: 'c' })
  assert.equal(sent[0].success, true)
  assert.equal(sent[0].data.sourceFileId, '11', '身份按派发时的 executor，不随用户切换变')
})

test('executor 不在池里（身份未知）：明确 null，不猜', async () => {
  const sent = []
  const vm = makeVm(sent, null, docText)
  await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r4', conversationId: 'c' })
  assert.equal(sent[0].success, true)
  assert.equal(sent[0].data.sourceFileId, null)
})

test('读取失败：保持原语义，不附身份', async () => {
  const sent = []
  const vm = makeVm(sent, 42, { success: false, message: 'match index out of range' })
  await vm.handleEditorCommand({ action: 'get_paragraph', params: { index: 99 }, requestId: 'r5', conversationId: 'c' })
  assert.equal(sent[0].success, false)
  assert.equal('sourceFileId' in sent[0].data, false)
  assert.equal(sent[0].error, 'match index out of range')
})

test('无关命令：结果不带 sourceFileId', async () => {
  const sent = []
  const vm = makeVm(sent, 42, { success: true, count: 3 })
  await vm.handleEditorCommand({ action: 'get_outline', params: {}, requestId: 'r6', conversationId: 'c' })
  assert.deepEqual(sent[0].data, { success: true, count: 3 })
})

test('executeCommand 抛错：原样回失败，不带身份', async () => {
  const sent = []
  const vm = makeVm(sent, 42, () => { throw new Error('worker 崩了') })
  await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r7', conversationId: 'c' })
  assert.deepEqual(sent, [{ requestId: 'r7', success: false, data: null, error: 'worker 崩了' }])
})
