// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// SSE 双轨摘旧名（dev-board#816 / 计划 K36）：后端从此只发新名，前端这层的
// 「先见新名就丢旧名」latch（_editorContractV2）与全部 wps_* 分支一起摘掉。
//
// 为什么可以摘：旧名的唯一消费者就是这个文件——桌面端与后端同一个安装包一起发版；
// Office/WPS 任务窗格的 chatSession.handleClientAction 第一行就
// `action.tool !== 'office_command'` 直接 return，从来不读 editor_command/wps_command。
//
// 这份用例同时守住「新名仍然照常分发」：摘旧名最容易犯的错是顺手把新名的分支也删了。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'

const SRC_URL = new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url)
const SRC = readFileSync(SRC_URL, 'utf8')

function loadMethods() {
  const body = SRC
    .replace(/^import .*$/gm, '')
    .replace(/export function shouldFlushDocStream[\s\S]*?\n\}\n/, '')
    .replace(/export const agentClientActionMethods = \{/, 'return {')
  const factory = new Function('sendEditorResult', 'getFileDetail', 'createSerialQueue', 'uni',
    'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction', body)
  return factory(async () => {}, async () => null, createSerialQueue, { showToast: () => {} },
    DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction)
}

/**
 * 只保留 handleClientAction 这一层的真实路由，下游处理函数换成探针——
 * 本用例要验的是「哪些事件名还会被路由出去」，不是编辑器本身。
 * 探针必须在真实方法绑定<b>之后</b>覆盖上去，否则会被同名真方法顶掉。
 */
function makeVm() {
  const calls = []
  const vm = { $t: (k) => k, $refs: {}, calls }
  for (const [k, fn] of Object.entries(loadMethods())) vm[k] = fn.bind(vm)
  vm.handleEditorOpenFile = (a) => calls.push(['open', a])
  vm.handleEditorReloadFile = (a) => calls.push(['reload', a])
  vm.handleTextReloadFile = (a) => calls.push(['textReload', a])
  vm.handleEditorOpenFileSync = (a) => calls.push(['openSync', a])
  vm.handleEditorCommand = (a) => calls.push(['command', a])
  vm.handleDocStreamData = (c) => calls.push(['stream', c])
  vm.openFileLinkTarget = () => {}
  return vm
}

test('新名照常分发：editor_command / doc_open_file / doc_reload_file / doc_stream_data', () => {
  const vm = makeVm()
  vm.handleClientAction({ tool: 'editor_command', action: 'find_replace', params: {} })
  vm.handleClientAction({ tool: 'editor_command', action: 'doc_open_file_sync', params: {} })
  vm.handleClientAction({ action: 'doc_open_file', fileId: 1 })
  vm.handleClientAction({ action: 'doc_reload_file', fileId: 1 })
  vm.handleClientAction({ action: 'doc_stream_data', content: '正文' })
  assert.deepEqual(vm.calls.map(c => c[0]), ['command', 'openSync', 'open', 'reload', 'stream'])
})

test('旧名一律不再被识别：wps_command / wps_open_file / wps_reload_file / wps_stream_data', () => {
  const vm = makeVm()
  vm.handleClientAction({ tool: 'wps_command', action: 'find_replace', params: {} })
  vm.handleClientAction({ tool: 'wps_command', action: 'wps_open_file_sync', params: {} })
  vm.handleClientAction({ action: 'wps_open_file', fileId: 1 })
  vm.handleClientAction({ action: 'wps_reload_file', fileId: 1 })
  vm.handleClientAction({ action: 'wps_stream_data', content: '正文' })
  assert.deepEqual(vm.calls, [], '旧名事件不该再触发任何处理：' + JSON.stringify(vm.calls))
})

test('latch 与 wps_* 字面量都不在源码里了（留着就是下一个人照抄的双轨）', () => {
  assert.ok(!SRC.includes('_editorContractV2'),
    '双轨去重 latch 还在：后端已经只发一份，latch 只剩误判风险')
  for (const legacy of ['wps_command', 'wps_open_file', 'wps_reload_file', 'wps_stream_data', 'wps_open_file_sync']) {
    assert.ok(!SRC.includes(legacy), `旧名 ${legacy} 还在 agentClientActions.js 里`)
  }
})
