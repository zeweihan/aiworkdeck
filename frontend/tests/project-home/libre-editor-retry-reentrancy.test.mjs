// 审计（dev-board#74）：LibreOfficeEditor 的「重试」没有重入闸。
//
// 病灶：下载挂起（弱网 / 代理收了头就不给正文，XHR 的 60s 超时还没到）时，
// startBootTrickle 的 30s stuck 计时器先把「重试」按钮亮出来。用户一点，
// retryLoad → finishDocLoad → loadDocument 就在**原来那条仍在途的链路**旁边
// 又起了一条，两条各自 dispatch 一次 load_document。后完成的那条按最后写者赢
// 覆盖 ready / statusKey / docKind：迟到的失败把重试的成功盖成 loadFailed
// （docLoadFailed 同时是保存闸，一置位 autosave 就全部拒绝），迟到的成功把
// 重试装好的文档连同其间的编辑整个换掉，ready 也会重复发一次。
//
// 这份用例不是源码文本断言：把 .vue 的 <script> 抠出来 new Function 求值，
// 拿到真的 methods，用桩 this 真跑 finishDocLoad / retryLoad 的并发时序。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')

// 组件带 @/ 别名，import 不进来；只取 <script> 主体，把 import 行剥掉、
// export default 换成 return，外部符号由 new Function 的形参喂桩。
function loadMethods() {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace(/export default \{/, 'return {')
  const factory = new Function(
    'getFileDownloadUrl', 'getCurrentUser', 'createRelayExecutor',
    'webviewTransport', 'iframeTransport', 'ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar',
    'getAuthHeaders', 'host', body)
  return factory((id) => '/download/' + id, () => ({ name: '测试用户' })).methods
}

const METHODS = loadMethods()
const tick = () => new Promise((r) => setTimeout(r, 0))
function deferred() {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

// 引擎已就绪、正在装真文档的那一刻的组件状态。
function makeVm() {
  const vm = {
    file: { id: 7, name: '起诉状.docx', fileType: 'docx', fileSize: 4096 },
    ready: false,
    statusKey: 'loadingDoc',
    docKind: 'writer',
    docLoadFailed: false,
    bootPct: 75, bootCap: 95, bootStageKey: 'openingDoc',
    dlLoaded: 0, dlTotal: 0, stuck: true,
    _endpointUp: true,
    emitted: [], dispatched: [],
    $emit(ev) { this.emitted.push(ev) },
    appendLog() {},
    startBootTrickle() {},   // 定时器与本条无关，停掉以免拖住测试进程
    initEvidence() {},       // EvidenceLink 首轮核对是 ready 之后的后台事，与装载重入无关
    executor: {
      executeCommand: async (action) => { vm.dispatched.push(action); return { success: true, kind: 'writer' } },
    },
  }
  for (const name of ['finishDocLoad', 'loadDocument', 'retryLoad', 'bootMilestone']) vm[name] = METHODS[name].bind(vm)
  return vm
}

test('迟到的失败不许把重试装载好的文档判成 loadFailed（关掉保存闸）', async () => {
  const vm = makeVm()
  const hung = deferred()
  vm._bytesPromise = hung.promise            // 初次预取挂在半路
  const stale = vm.finishDocLoad()
  await tick()
  assert.deepEqual(vm.dispatched, [], '原始链路还卡在下载上')

  vm.fetchArrayBuffer = async () => new ArrayBuffer(4096)   // 重试这次秒下完
  vm.retryLoad()
  await tick(); await tick()
  assert.equal(vm.statusKey, 'ready')
  assert.equal(vm.docLoadFailed, false)

  hung.resolve(new ArrayBuffer(0))           // 挂起的那笔最终回了个空响应
  await stale
  assert.equal(vm.docLoadFailed, false, '被取代的那次失败不得关掉保存闸')
  assert.equal(vm.statusKey, 'ready', '被取代的那次不得把状态盖成 loadFailed')
})

test('重试期间只推一次 load_document，ready 也只发一次', async () => {
  const vm = makeVm()
  const hung = deferred()
  vm._bytesPromise = hung.promise
  const stale = vm.finishDocLoad()
  await tick()

  vm.fetchArrayBuffer = async () => new ArrayBuffer(4096)
  vm.retryLoad()
  await tick(); await tick()

  hung.resolve(new ArrayBuffer(4096))        // 挂起的那笔最终也成功回来了
  await stale
  assert.equal(vm.dispatched.filter((a) => a === 'load_document').length, 1,
    '两条 load_document 会被单线程 office 依次执行，后到的把先装好的文档整个换掉')
  assert.equal(vm.emitted.filter((e) => e === 'ready').length, 1)
})

test('没有并发时正常装载不受影响', async () => {
  const vm = makeVm()
  vm.fetchArrayBuffer = async () => new ArrayBuffer(4096)
  await vm.finishDocLoad()
  assert.deepEqual(vm.dispatched, ['load_document'])
  assert.equal(vm.statusKey, 'ready')
  assert.equal(vm.ready, true)
  assert.equal(vm.emitted.filter((e) => e === 'ready').length, 1)
})

test('装载真失败时仍然置位保存闸（重入闸不许吞掉真失败）', async () => {
  const vm = makeVm()
  vm.fetchArrayBuffer = async () => { throw new Error('boom') }
  await vm.finishDocLoad()
  assert.equal(vm.docLoadFailed, true)
  assert.equal(vm.statusKey, 'loadFailed')
})
