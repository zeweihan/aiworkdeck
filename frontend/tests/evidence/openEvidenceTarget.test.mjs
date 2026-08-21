// 复核 F1：Web 插件 evidence.locate 经 uni 事件 awd:open-evidence-target 到工作台，
// 监听器必须把整个 payload（{fileId, locator, linkKey}）交给 openFileLinkTarget(target, side)。
// 此前传的是裸 fileId，openFileLinkTarget 读 target.fileId 得 NaN 静默返回，SDK 却回 ok:true。
// 把 project-overview.vue 里那两段源码抠出来真跑一遍，不依赖 Vue。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

function pickListenerInstaller() {
  const m = SRC.match(/this\._onOpenEvidenceTarget = \(p\) => [^\n]*\n/)
  assert.ok(m, 'project-overview.vue 里找不到 _onOpenEvidenceTarget 监听器')
  return new Function(m[0])
}

function pickHandler() {
  const m = SRC.match(/onOpenEvidenceTarget\(payload\) \{([\s\S]*?)\n    \},/)
  assert.ok(m, 'project-overview.vue 里找不到 onOpenEvidenceTarget 方法')
  return new Function('payload', m[1])
}

function makeVm() {
  const calls = []
  const vm = {
    focusedPane: 'right',
    openFileLinkTarget(target, side) { calls.push({ target, side }) },
  }
  vm.onOpenEvidenceTarget = pickHandler().bind(vm)
  pickListenerInstaller().call(vm)
  return { vm, calls }
}

test('awd:open-evidence-target 监听器把带 locator 的整个 payload 交给 openFileLinkTarget', () => {
  const { vm, calls } = makeVm()
  const payload = { fileId: 77, locator: { type: 'pdf', page: 3 }, linkKey: 'EVID_X' }
  vm._onOpenEvidenceTarget(payload)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].target, payload, '必须是整个 payload 对象，不是裸 fileId')
  assert.equal(calls[0].target.fileId, 77)
  assert.deepEqual(calls[0].target.locator, { type: 'pdf', page: 3 })
  assert.equal(calls[0].side, 'right')
})

test('payload 缺 fileId / 为空时不调 openFileLinkTarget', () => {
  const { vm, calls } = makeVm()
  vm._onOpenEvidenceTarget(null)
  vm._onOpenEvidenceTarget({ locator: { page: 1 } })
  assert.equal(calls.length, 0)
})
