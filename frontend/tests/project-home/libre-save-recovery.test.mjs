// A6/C10：运行真实组件保存方法，控制导出/上传的在途顺序。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const body = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')
function makeVm(extra = {}) {
  const options = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'getFileUploadUrl', body)(null, null, null, id => '/upload/' + id)
  const vm = {
    ready: true, file: { id: 7, name: '证据清单.docx' }, statusKey: 'ready',
    dirty: true, saving: false, _dirtySince: Date.now(),
    appendLog() {}, scheduleAnchorCheck() {}, $t: k => k,
    executor: { executeCommand: async () => ({ success: true, bytes: [1, 2, 3] }) },
    uploadBytes: async () => {},
  }
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  Object.assign(vm, { appendLog() {}, scheduleAnchorCheck() {}, uploadBytes: async () => {} }, extra)
  return vm
}
const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
const tick = () => new Promise(resolve => setTimeout(resolve, 0))

test('保存失败留脏并暂停自动重试；继续输入也不重新排队，手动重试恢复', async () => {
  let exports = 0
  const vm = makeVm({
    executor: { executeCommand: async () => { exports++; return { success: true, bytes: [1] } } },
    uploadBytes: async () => { throw new Error('offline') },
  })
  await vm.autoSave()
  assert.equal(vm.dirty, true)
  assert.equal(vm.statusKey, 'saveFailed')
  assert.equal(vm._savePaused, true)
  vm.onDocModified()
  await vm.autoSave()
  assert.equal(exports, 1, '失败后的自动保存不得继续把导出堆到 worker 上')
  vm.uploadBytes = async () => {}
  assert.equal(await vm.retrySave(), true)
  assert.equal(vm.statusKey, 'ready')
  assert.equal(vm.dirty, false)
  assert.equal(vm._savePaused, false)
})

test('关闭等待有上限：保存仍在途就返回 false，迟到成功仍能落盘且不并发重试', async () => {
  const gate = deferred()
  let uploads = 0
  const vm = makeVm({ uploadBytes: async () => { uploads++; await gate.promise } })
  const pending = vm.autoSave()
  await tick()
  assert.equal(await vm.flushSave({ timeoutMs: 5 }), false)
  assert.equal(vm.saving, true)
  assert.equal(await vm.flushSave({ timeoutMs: 5 }), false)
  assert.equal(uploads, 1)
  gate.resolve()
  await pending
  await vm._flushPromise
  assert.equal(vm.saving, false)
  assert.equal(vm.dirty, false)
})

test('明确放弃后，迟到的导出不得上传；已经在途的上传会被取消', async () => {
  const gate = deferred()
  let uploads = 0, aborted = 0
  const vm = makeVm({
    executor: { executeCommand: () => gate.promise },
    uploadBytes: async () => { uploads++ },
    _saveXhr: { abort: () => { aborted++ } },
  })
  const pending = vm.autoSave()
  vm.discardPendingSave()
  gate.resolve({ success: true, bytes: [1] })
  await pending
  vm.onDocModified()
  await vm.autoSave()
  assert.equal(uploads, 0)
  assert.equal(aborted, 1)
  assert.equal(vm.saving, false)
})

test('关闭保存失败不得清掉 dirty，重试时保存当前完整内容', async () => {
  const vm = makeVm({ uploadBytes: async () => { throw new Error('offline') } })
  assert.equal(await vm.flushSave(), false)
  assert.equal(vm.dirty, true)
  vm.uploadBytes = async () => {}
  assert.equal(await vm.retrySave(), true)
  assert.equal(vm.dirty, false)
})

test('上传请求具备超时和中止终态，不会永远保持 saving', async () => {
  let xhr
  class Xhr {
    constructor() { xhr = this }
    open() {} setRequestHeader() {} send() {}
  }
  const opts = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'getAuthHeaders', 'XMLHttpRequest', body)(null, null, null, () => ({}), Xhr)
  const vm = { $t: k => k }
  const pending = opts.methods.uploadBytes.call(vm, '/upload/7', new Uint8Array([1]), 'a.docx')
  assert.equal(xhr.timeout, 60000)
  xhr.ontimeout()
  await assert.rejects(pending, /saveTimeout/)
  xhr.onloadend()
  assert.equal(vm._saveXhr, null)
})

function makeCloseVm(confirm) {
  const s = readFileSync(new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace('export const fileOpenTabsMethods = {', 'return {')
  const modals = []
  const methods = new Function('uni', 'activityTracker', s)({
    showModal: opts => { modals.push(opts); opts.success({ confirm }) },
  }, { stopActivity() {} })
  let flushed = 0, discarded = 0
  const inst = { ready: true, isError: true, dirty: true, file: { id: 7 },
    flushSave: async () => { flushed++; return false },
    discardPendingSave: () => { discarded++ },
  }
  const vm = { leftFiles: [{ id: 7 }], rightFiles: [], activeFileIdLeft: 7,
    _libreRefs: { 'left:7': inst }, useLibreEditor: () => true,
    isBrowserTab: () => false, $t: k => k, lastActiveIdsByMode: { left: {} },
    saveActiveIdsByMode() {},
  }
  return { vm, close: methods.closeFile.bind(vm), modals,
    counts: () => ({ flushed, discarded }) }
}

test('保存失败状态也必须尝试落盘；取消放弃时文档留在标签页', async () => {
  const { vm, close, modals, counts } = makeCloseVm(false)
  await close(7, 'left')
  assert.equal(vm.leftFiles.length, 1)
  assert.equal(modals.length, 1)
  assert.deepEqual(counts(), { flushed: 1, discarded: 0 })
})

test('只有明确选择放弃才取消在途保存并关闭标签', async () => {
  const { vm, close, counts } = makeCloseVm(true)
  await close(7, 'left')
  assert.equal(vm.leftFiles.length, 0)
  assert.deepEqual(counts(), { flushed: 1, discarded: 1 })
})

test('关闭等待期间继续输入：保留标签后新改动必须重新进入自动保存队列', async () => {
  const gate = deferred()
  let uploads = 0
  const vm = makeVm({ uploadBytes: async () => { uploads++; if (uploads === 1) await gate.promise } })
  const pending = vm.flushSave()
  await tick()
  vm.onDocModified()
  assert.equal(vm.dirty, true)
  assert.equal(vm._saveTimer, undefined, 'flush 期间暂不并发排自动保存')
  gate.resolve()
  assert.equal(await pending, false, '关标签者必须知道仍有后来输入没保存')
  assert.ok(vm._saveTimer, '解除 flush 闸后必须给后来输入补排保存，不能一直 dirty + ready')
  clearTimeout(vm._saveTimer)
  await vm.autoSave()
  assert.equal(uploads, 2)
  assert.equal(vm.dirty, false)
})
