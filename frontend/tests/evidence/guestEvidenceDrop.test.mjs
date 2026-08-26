// 拖拽建链的 guest 代收转发（dev-board#171）。
//
// 病灶：Electron 原生 DnD 的命中测试把拖拽路由进 <webview> 客体，宿主 DOM 的
// .libre-evidence-drop 对真实鼠标拖拽永远收不到 drop——此前所有测试都用
// dispatchEvent 合成事件（绕过原生路由），于是「测试全绿、真实手势全断」。
// 修法：客体页（editor-main.js）代收 drop 经 lo-relay 转发，宿主在
// onGuestEvidenceDrop 收口。本文件钉住宿主收口的四条语义；真 webview 全链路
// 由 desktop-e2e 的拖拽步骤覆盖。
//
// 与 libre-editor-retry-reentrancy.test.mjs 同法：把 .vue 的 <script> 抠出来
// new Function 求值拿真 methods，用桩 this 跑。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')

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

function makeVm({ armed, dragEndedAt } = {}) {
  const vm = {
    evidenceDropArmed: !!armed,
    evidenceDropOver: false,
    _dragEndedAt: dragEndedAt || 0,
    emitted: [],
    $emit(ev, payload) { this.emitted.push({ ev, payload }) },
  }
  for (const name of ['onGuestEvidenceDrop', 'onEvidenceDrop']) vm[name] = METHODS[name].bind(vm)
  return vm
}

// onEvidenceDrop 的全局兜底读的是 document.__checkbaDraggedFile；node 环境没有
// document，按用例喂一个最小对象。
function withDocument(globalFile, fn) {
  const had = 'document' in globalThis
  const prev = globalThis.document
  globalThis.document = { __checkbaDraggedFile: globalFile }
  try { return fn(globalThis.document) }
  finally { if (had) globalThis.document = prev; else delete globalThis.document }
}

test('armed 时带 payload 的转发 drop 建链（走 dataTransfer 路径）', () => {
  const vm = makeVm({ armed: true })
  withDocument(null, () => {
    vm.onGuestEvidenceDrop(JSON.stringify({ fileId: 42, name: '底稿.pdf', fileType: 'pdf' }))
  })
  assert.equal(vm.emitted.length, 1)
  assert.equal(vm.emitted[0].ev, 'evidence-drop')
  assert.equal(vm.emitted[0].payload.file.id, 42)
})

test('dragend 先于 IPC 到达：宽限窗内空 payload 走全局兜底并消费之', () => {
  const vm = makeVm({ armed: false, dragEndedAt: Date.now() - 300 })
  withDocument({ fileId: 7, name: '合同.docx', fileType: 'docx' }, (doc) => {
    vm.onGuestEvidenceDrop('')
    assert.equal(vm.emitted.length, 1, 'drop 的转发比 dragend 晚到几百毫秒是常态，不能因此丢建链')
    assert.equal(vm.emitted[0].payload.file.id, 7)
    assert.equal(doc.__checkbaDraggedFile, null, '兜底消费即清，防止陈文件被下一次落空的 drop 捡走')
  })
})

test('既不 armed 也不在宽限窗内：外部（系统）拖放一律忽略', () => {
  const vm = makeVm({ armed: false, dragEndedAt: Date.now() - 60000 })
  withDocument({ fileId: 9, name: '旧拖拽残留.txt', fileType: 'txt' }, (doc) => {
    vm.onGuestEvidenceDrop('')
    assert.equal(vm.emitted.length, 0, '从系统拖文件进编辑器不该借道全局兜底建出链接')
    assert.ok(doc.__checkbaDraggedFile, '未消费——残留由拖拽自身的清理路径负责')
  })
})

test('文件夹 payload 不建链', () => {
  const vm = makeVm({ armed: true })
  withDocument(null, () => {
    vm.onGuestEvidenceDrop(JSON.stringify({ fileId: 3, name: '证据夹', isFolder: true }))
  })
  assert.equal(vm.emitted.length, 0)
})
