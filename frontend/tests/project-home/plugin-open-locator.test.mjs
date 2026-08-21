// P1 整体复核 F1（dev-board#100）：插件宿主 Docs.openFile(fileId, locator) 追发的
// client_action `plugin_open_locator {fileId, locator}` 之前没有前端消费者——locator 被静默丢掉，
// 底稿只会打开在文首。修法：agentClientActions.js 加分支，交给 evidenceLinkActions.js 的
// openFileLinkTarget(target, side)（读 target.fileId / target.locator），与「查看底稿」同一条路。
//
// 加载方式同 doc-open-sync-reentrancy.test.mjs：@/ 别名 import 进不来，用 new Function 抠主体求值。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'

const SRC = readFileSync(
  new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')

function loadMethods() {
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream[\s\S]*?\n\}\n/, '')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  const factory = new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni', body)
  return factory(async () => {}, async () => null, createSerialQueue, { showToast: () => {} })
}

function makeVm(focusedPane) {
  const vm = {
    projectId: 1,
    focusedPane,
    $refs: {},
    calls: [],
    openFileLinkTarget(target, side) { vm.calls.push({ target, side }) },
    $t: (k) => k,
  }
  for (const [k, fn] of Object.entries(loadMethods())) vm[k] = fn.bind(vm)
  return vm
}

test('plugin_open_locator → openFileLinkTarget({fileId, locator}, focusedPane)', () => {
  const vm = makeVm('right')
  vm.handleClientAction({ action: 'plugin_open_locator', fileId: 70, locator: { type: 'pdf', page: 7 } })
  assert.deepEqual(vm.calls, [{ target: { fileId: 70, locator: { type: 'pdf', page: 7 } }, side: 'right' }])
})

test('plugin_open_locator：没有 focusedPane 时落到 left；缺 fileId 不调用', () => {
  const vm = makeVm(null)
  vm.handleClientAction({ action: 'plugin_open_locator', fileId: 3 })
  assert.deepEqual(vm.calls, [{ target: { fileId: 3, locator: null }, side: 'left' }])
  vm.handleClientAction({ action: 'plugin_open_locator', locator: { page: 1 } })
  assert.equal(vm.calls.length, 1)
})

test('接线核实：分支存在且交给 openFileLinkTarget（evidenceLinkActions.js 的签名读 target.fileId/locator）', () => {
  assert.match(SRC, /action\.action === 'plugin_open_locator'/)
  assert.match(SRC, /this\.openFileLinkTarget\(\{ fileId: action\.fileId, locator: action\.locator \|\| null \}/)
  const ela = readFileSync(new URL('../../src/pages/project-overview/evidenceLinkActions.js', import.meta.url), 'utf8')
  assert.match(ela, /async openFileLinkTarget\(target, sideOverride = null\)/)
})
