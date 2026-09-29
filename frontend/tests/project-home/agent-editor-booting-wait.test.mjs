// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1018：AI 生成文档后自动开新标签，引擎还在 boot/装载，活跃执行器指针是 null。
// handleEditorCommand 以前**立刻**回「编辑器未就绪，请先打开一个文档」，模型拿着这句话
// 只能瞎重试。现在：有实例在启动中（librePool.libreBootingInstances 非空）就轮询等它就绪，
// 等满该实例的装载预算仍未就绪才回结构化 EDITOR_BOOTING（与后端约定的契约，字面量不许改）。
// 桩法同 doc-open-sync-reentrancy：剥壳求值，setTimeout 短路成 0ms。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'
import { actionBudgetMs } from '../../src/composables/zetaOfficeRelay.js'

const SRC = readFileSync(new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
const POOL_SRC = readFileSync(new URL('../../src/pages/project-overview/librePool.js', import.meta.url), 'utf8')

function loadMethods(sent, getFileDetail = async () => null) {
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream[\s\S]*?\n\}\n/, '')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  return new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', 'actionBudgetMs', body)(
    async (conversationId, requestId, success, data, error) => { sent.push({ requestId, success, data, error }) },
    getFileDetail, createSerialQueue, { showToast: () => {}, $emit: () => {} },
    DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction, actionBudgetMs)
}
function loadPool() {
  const body = POOL_SRC.replace(/^import .*$/gm, '').replace(/export const librePoolMethods = \{/, 'return {')
  return new Function('isDesktopHost', 'host', body)(() => true, {})
}

// 模拟时钟：setTimeout 立即回调、Date.now 每次轮询推进 500ms，预算用例不必真等。
async function withFakeClock(fn) {
  const realST = globalThis.setTimeout
  const realNow = Date.now
  let now = realNow()
  globalThis.setTimeout = (cb, ms, ...a) => { now += ms || 0; return realST(cb, 0, ...a) }
  Date.now = () => now
  try { return await fn() } finally { globalThis.setTimeout = realST; Date.now = realNow }
}

function makeVm(sent, editor) {
  const vm = {
    projectId: 1, $refs: {}, $t: (k) => k,
    libreOfficeActive: false, libreOfficeExecutor: null,
    _libreRefs: editor ? { 'left:7': editor } : {},
    executed: [],
  }
  for (const [k, fn] of Object.entries(loadMethods(sent))) vm[k] = fn.bind(vm)
  const pool = loadPool()
  for (const k of ['libreBootingInstances', 'libreLoadFailureOf', 'getLibreExecutorMap', 'resolveLibreExecutorFileId']) if (pool[k]) vm[k] = pool[k].bind(vm)
  return vm
}
const bootingEditor = (over = {}) => Object.assign({ file: { id: 7, fileSize: 9618 }, ready: false, statusKey: 'loadingDoc', loadWaitBudgetMs: () => 30000 }, over)

test('没有任何实例在启动：保持原文案，立即回未就绪', async () => {
  const sent = []
  const vm = makeVm(sent, null)
  await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r0', conversationId: 'c' })
  assert.deepEqual(sent, [{ requestId: 'r0', success: false, data: null, error: '编辑器未就绪，请先打开一个文档' }])
})

test('实例在启动中：等它就绪后照常执行命令，不回未就绪', async () => {
  await withFakeClock(async () => {
    const sent = []
    const ed = bootingEditor()
    const vm = makeVm(sent, ed)
    let polls = 0
    const exec = { executeCommand: async (action, params) => { vm.executed.push(action); return { success: true, text: 'x' } } }
    // 第 3 次轮询时编辑器就绪（onLibreReady → syncLibreExecutor 置指针）
    const origBooting = vm.libreBootingInstances
    vm.libreBootingInstances = () => {
      if (++polls === 3) { ed.ready = true; vm.libreOfficeActive = true; vm.libreOfficeExecutor = exec }
      return origBooting()
    }
    await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r1', conversationId: 'c' })
    assert.deepEqual(vm.executed, ['get_document_text'])
    assert.equal(sent.length, 1)
    assert.equal(sent[0].success, true)
  })
})

test('等满装载预算仍未就绪：回结构化 EDITOR_BOOTING（契约字面量）', async () => {
  await withFakeClock(async () => {
    const sent = []
    const vm = makeVm(sent, bootingEditor())
    const t0 = Date.now()
    await vm.handleEditorCommand({ action: 'insert_at_cursor', params: { text: 'a' }, requestId: 'r2', conversationId: 'c' })
    assert.equal(sent.length, 1)
    assert.equal(sent[0].success, false)
    assert.deepEqual(sent[0].data, { error: '编辑器仍在启动，请稍后重试同一步', code: 'EDITOR_BOOTING', retryable: true })
    assert.equal(sent[0].error, '编辑器仍在启动，请稍后重试同一步')
    const waited = Date.now() - t0
    assert.ok(waited >= 30000 && waited <= 31000, '等待上限对齐该实例的装载预算（30s），实际 ' + waited)
  })
})

test('等待期间实例落了失败态：提前收手，回未就绪而不是 EDITOR_BOOTING', async () => {
  await withFakeClock(async () => {
    const sent = []
    const ed = bootingEditor({ loadWaitBudgetMs: () => 120000 })
    const vm = makeVm(sent, ed)
    let polls = 0
    const orig = vm.libreBootingInstances
    vm.libreBootingInstances = () => { if (++polls === 2) ed.statusKey = 'docOpenFailed'; return orig() }
    const t0 = Date.now()
    await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r3', conversationId: 'c' })
    assert.equal(sent[0].error, '编辑器未就绪，请先打开一个文档')
    assert.equal(sent[0].data, null)
    assert.ok(Date.now() - t0 < 5000, '不许等满 120s')
  })
})

test('libreBootingInstances：已就绪 / 失败态 / 空白备胎不算在启动中', () => {
  const vm = { _libreRefs: {
    'left:1': { file: { id: 1 }, ready: true, statusKey: 'ready' },
    'left:2': { file: { id: 2 }, ready: false, statusKey: 'bootFailed' },
    'left:3': { file: null, ready: false, statusKey: 'booting' },
    'right:4': { file: { id: 4 }, ready: false, statusKey: 'booting', loadWaitBudgetMs: () => 120000 },
  } }
  const list = loadPool().libreBootingInstances.call(vm)
  assert.deepEqual(list, [{ key: 'right:4', fileId: 4, budgetMs: 120000 }])
})

test('等待上限不超过 100s（必须低于后端 editor_command 的 120s），哪怕装载预算是 270s', async () => {
  const cap = Number(SRC.match(/const EDITOR_BOOT_WAIT_CAP_MS = (\d+)/)[1])
  assert.ok(cap <= 100000, 'EDITOR_BOOT_WAIT_CAP_MS 必须 ≤ 100s，实际 ' + cap)
  await withFakeClock(async () => {
    const sent = []
    const vm = makeVm(sent, bootingEditor({ loadWaitBudgetMs: () => 270000 }))
    const t0 = Date.now()
    await vm.handleEditorCommand({ action: 'insert_at_cursor', params: { text: 'a' }, requestId: 'r4', conversationId: 'c' })
    const waited = Date.now() - t0
    assert.ok(waited <= 100000 && waited >= 99000, '应在 100s 封顶，实际 ' + waited)
    assert.equal(sent[0].data.code, 'EDITOR_BOOTING')
  })
})

test('表外命令后端只等 30s：本地等待按命令收紧到 20s，仍回 EDITOR_BOOTING', async () => {
  await withFakeClock(async () => {
    const sent = []
    const vm = makeVm(sent, bootingEditor({ loadWaitBudgetMs: () => 120000 }))
    const t0 = Date.now()
    await vm.handleEditorCommand({ action: 'set_selection', params: {}, requestId: 'r5', conversationId: 'c' })
    const waited = Date.now() - t0
    assert.ok(waited <= 20000, '表外命令必须赶在后端 30s 之前回话，实际 ' + waited)
    assert.equal(sent[0].data.code, 'EDITOR_BOOTING')
  })
})

// ---- 失败态编辑器（dev-board#1018）：空白占位 + 保存闸落下，写进去即静默丢失 ----
function failedVm(sent, over = {}) {
  const exec = { executeCommand: async (action) => { vm.executed.push(action); return { success: true } } }
  const ed = Object.assign({ file: { id: 7 }, ready: true, statusKey: 'docOpenFailed', docLoadFailed: true, openFailCode: 'DOC_REJECTED' }, over)
  const vm = makeVm(sent, ed)
  vm._libreExecMap = { 'left:7': exec }
  vm.libreOfficeActive = true
  vm.libreOfficeExecutor = exec
  return vm
}

test('目标编辑器装载失败：不执行，回结构化 EDITOR_LOAD_FAILED（带诊断码，不可重试）', async () => {
  const sent = []
  const vm = failedVm(sent)
  await vm.handleEditorCommand({ action: 'insert_at_cursor', params: { text: 'a' }, requestId: 'r6', conversationId: 'c' })
  assert.deepEqual(vm.executed, [], '一个字都不许写进空白占位文档')
  const msg = '文档未能打开（DOC_REJECTED），这一步没有执行；请告知用户并停止对该文档的编辑'
  assert.deepEqual(sent, [{ requestId: 'r6', success: false, data: { error: msg, code: 'EDITOR_LOAD_FAILED', retryable: false }, error: msg }])
})

test('loadFailed（无诊断码）同样拦下，诊断码退到 statusKey', async () => {
  const sent = []
  const vm = failedVm(sent, { statusKey: 'loadFailed', openFailCode: '' })
  await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r7', conversationId: 'c' })
  assert.deepEqual(vm.executed, [])
  assert.equal(sent[0].data.code, 'EDITOR_LOAD_FAILED')
  assert.match(sent[0].error, /（loadFailed）/)
})

test('装载成功的编辑器照常执行（闸只认 docLoadFailed）', async () => {
  const sent = []
  const vm = failedVm(sent, { statusKey: 'ready', docLoadFailed: false, openFailCode: '' })
  await vm.handleEditorCommand({ action: 'get_document_text', params: {}, requestId: 'r8', conversationId: 'c' })
  assert.deepEqual(vm.executed, ['get_document_text'])
  assert.equal(sent[0].success, true)
})

test('失败态不在「启动中」清单里（不会让 AI 命令干等）', () => {
  const vm = { _libreRefs: {
    'left:1': { file: { id: 1 }, ready: true, statusKey: 'docOpenFailed', docLoadFailed: true },
    'left:2': { file: { id: 2 }, ready: false, statusKey: 'docOpenFailed', docLoadFailed: true },
    'left:3': { file: { id: 3 }, ready: false, statusKey: 'loadFailed', docLoadFailed: true },
  } }
  assert.deepEqual(loadPool().libreBootingInstances.call(vm), [])
})

test('流式落字同样被失败态挡住', () => {
  const sent = []
  const vm = failedVm(sent)
  vm._docStreamTargetFileId = '7'
  assert.match(vm.docStreamBlockReason(), /文档未能打开/)
})

test('doc_open_file_sync：编辑器「就绪」但文档没打开，回 EDITOR_LOAD_FAILED，不接着流式落字', async () => {
  await withFakeClock(async () => {
    const sent = []
    const vm = failedVm(sent)
    const m = loadMethods(sent, async () => ({ id: 7, name: 'AI生成.docx' }))
    vm._handleEditorOpenFileSyncImpl = m._handleEditorOpenFileSyncImpl.bind(vm)
    vm.openFile = async () => {}
    vm.$refs = {}
    vm._docStreamBuffer = 'keep'
    await vm._handleEditorOpenFileSyncImpl({ params: { fileId: 7 }, requestId: 'r9', conversationId: 'c' })
    assert.equal(sent.length, 1)
    assert.equal(sent[0].data.code, 'EDITOR_LOAD_FAILED')
    assert.deepEqual(vm.executed, [])
  })
})
