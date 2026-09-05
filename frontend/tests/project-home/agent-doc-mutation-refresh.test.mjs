// dev-board#460：Agent 在文档里写完内容后，编辑器工具条上的「修订 / 批注 / 底稿」
// 计数不立即刷新，切一次标签页才更新。
//
// 病灶：审阅面板唯一的刷新信号是引擎广播的 modified 边沿（editor-main.js 里
// 500ms 前沿节流、没有尾随），这条信号服务的是自动保存，**刻意做成有损**——
// 一批写入的最后一次 modified 落在节流窗口里就永久丢弃。宿主这边其实握着一个
// 无损接缝：AI 的每条编辑命令都是宿主自己发出去的，命令返回 = 这一笔写完了。
// 本用例锁住这条接缝真的被接上：写入类命令跑完（以及流式落字收尾之后）宿主
// 广播 awd:doc-mutated，编辑器实例据此给自己开着的审阅面板 bump 一次 refreshKey。
//
// 加载方式同 doc-stream-agent-author.test.mjs / libre-editor-retry-reentrancy.test.mjs：
// @/ 别名 import 进不来，抠出 <script> 主体 new Function 求值，外部符号用形参喂。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'

const ACTIONS_SRC = readFileSync(
  new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
const EDITOR_SRC = readFileSync(
  new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')

function loadActionMethods(uni) {
  const body = ACTIONS_SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream/, 'function shouldFlushDocStream')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  const factory = new Function(
    'sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', body)
  return factory(async () => {}, async () => null, createSerialQueue, uni,
    DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction)
}

function loadEditorMethods() {
  const body = EDITOR_SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace(/export default \{/, 'return {')
  const factory = new Function(
    'getFileDownloadUrl', 'getCurrentUser', 'createRelayExecutor',
    'webviewTransport', 'iframeTransport', 'ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar',
    'getAuthHeaders', 'host', 'DOC_MUTATED_EVENT', body)
  return factory((id) => '/download/' + id, () => ({ name: '测试用户' }),
    null, null, null, null, null, null, null, null, DOC_MUTATED_EVENT).methods
}

// ---- 宿主发信端（project-overview 的 AI 指令路由）----

function makeUni(events) {
  return {
    showToast: () => {},
    $emit: (name, payload) => { events.push({ name, payload }) },
  }
}

function makeVm(events, execResult) {
  const uni = makeUni(events)
  const vm = {
    projectId: 1,
    libreOfficeActive: true,
    libreOfficeExecutor: {
      executeCommand: async () => (execResult || { success: true }),
    },
    resolveLibreExecutorFileId: () => 7,
    _docStreamTargetFileId: 7,
    $refs: {},
    $t: (k) => k,
  }
  for (const [k, fn] of Object.entries(loadActionMethods(uni))) vm[k] = fn.bind(vm)
  return vm
}

const settle = () => new Promise((r) => setTimeout(r, DOC_MUTATED_DEBOUNCE_MS + 80))
const mutated = (events) => events.filter((e) => e.name === DOC_MUTATED_EVENT)

test('写入类命令跑完，宿主广播一次 awd:doc-mutated（面板据此重读清单）', async () => {
  const events = []
  const vm = makeVm(events)
  await vm.handleEditorCommand({ action: 'find_replace', params: { findText: '甲', replaceText: '乙' }, requestId: 'r1', conversationId: 'c1' })
  await settle()
  const hits = mutated(events)
  assert.equal(hits.length, 1, 'AI 写完一笔，宿主没有告诉编辑器——面板只能等有损的 modified 边沿')
  assert.equal(hits[0].payload.fileId, 7, '要带上写的是哪份文档，否则保活池里别的实例也会跟着重读')
})

test('一批命令只广播一次（300ms 防抖，别让每条 doc_* 各打一轮读命令）', async () => {
  const events = []
  const vm = makeVm(events)
  for (const a of ['replace_nth_match', 'insert_at_cursor', 'add_comment']) {
    await vm.handleEditorCommand({ action: a, params: {}, requestId: 'r', conversationId: 'c' })
  }
  await settle()
  assert.equal(mutated(events).length, 1)
})

test('只读命令不广播（读正文 / 挪光标不改内容，重读清单是白费的往返）', async () => {
  const events = []
  const vm = makeVm(events)
  for (const a of ['get_document_text', 'find_text_locations', 'get_ui_state']) {
    await vm.handleEditorCommand({ action: a, params: {}, requestId: 'r', conversationId: 'c' })
  }
  await settle()
  assert.equal(mutated(events).length, 0)
})

test('命令失败不广播（什么都没写成，别去打无谓的读命令）', async () => {
  const events = []
  const vm = makeVm(events, { success: false, message: 'match index out of range' })
  await vm.handleEditorCommand({ action: 'replace_nth_match', params: {}, requestId: 'r', conversationId: 'c' })
  await settle()
  assert.equal(mutated(events).length, 0)
})

test('流式落字收尾（doc_stream_end）之后同样广播——整篇起草走的正是这一路', async () => {
  const events = []
  const vm = makeVm(events)
  await vm.handleDocStreamEnd()
  await settle()
  assert.equal(mutated(events).length, 1)
})

// ---- 编辑器收信端（LibreOfficeEditor）----

const METHODS = loadEditorMethods()

function makeEditorVm(extra) {
  const vm = Object.assign({
    file: { id: 7, name: '意见书.docx', fileType: 'docx', fileSize: 4096 },
    ready: true,
    reviewOpen: true,
    reviewRefreshKey: 0,
    uiRefreshKey: 0,
    docLoadFailed: false,
    appendLog() {},
  }, extra || {})
  vm.onDocMutatedEvent = METHODS.onDocMutatedEvent.bind(vm)
  return vm
}

test('收到 awd:doc-mutated 且是自己这份文档：面板 refreshKey 自增', () => {
  const vm = makeEditorVm()
  vm.onDocMutatedEvent({ fileId: 7 })
  assert.equal(vm.reviewRefreshKey, 1)
})

test('别人那份文档的写入不刷自己（保活池里同时挂着好几个实例）', () => {
  const vm = makeEditorVm()
  vm.onDocMutatedEvent({ fileId: 8 })
  assert.equal(vm.reviewRefreshKey, 0)
})

test('面板没开就不刷（面板是 v-if，没挂载时重读纯属浪费 office 线程）', () => {
  const vm = makeEditorVm({ reviewOpen: false })
  vm.onDocMutatedEvent({ fileId: 7 })
  assert.equal(vm.reviewRefreshKey, 0)
})

test('换文档（版本退回 / 检查点恢复 / AI 直改文件）之后，面板也要重读', async () => {
  const vm = Object.assign(makeEditorVm(), {
    _docLoadSeq: 0,
    docKind: 'writer',
    fetchArrayBuffer: async () => new ArrayBuffer(4096),
    bootMilestone() {},
    executor: { executeCommand: async () => ({ success: true, kind: 'writer' }) },
  })
  vm.loadDocument = METHODS.loadDocument.bind(vm)
  const loaded = await vm.loadDocument()
  assert.equal(loaded, true)
  assert.equal(vm.uiRefreshKey, 1, '工具栏一直是刷的')
  assert.equal(vm.reviewRefreshKey, 1, '换了文档，面板还端着上一份的修订清单')
})
