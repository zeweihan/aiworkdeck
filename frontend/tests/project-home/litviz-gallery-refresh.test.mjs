// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board BUG-21：诉讼可视化第二次（及后续）出图完成后，画廊「本项目的图」
// 计数不刷新。两半都要接上才成立：
// 1. 面板订阅 'awd:files-changed'（此前只订阅换风格事件）；
// 2. 这个事件平时只由 FileTree.loadFiles() 发，而左栏是 v-if/v-else-if 互斥链——
//    诉讼可视化面板显示时 FileTree 根本没挂载，refresh_files 走不到它，事件永远不发。
//    所以 agentClientActions.js 在树未挂载时要替它补发（树挂着时不发，免得重复）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createSerialQueue } from '../../src/utils/asyncSerialize.js'
import { DOC_MUTATED_EVENT, DOC_MUTATED_DEBOUNCE_MS, isDocMutatingAction } from '../../src/utils/docEvents.js'

function loadComponent(name, bindings) {
  const source = readFileSync(new URL(`../../src/components/${name}.vue`, import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  return new Function(...Object.keys(bindings), script.replace('export default', 'return'))(...Object.values(bindings))
}

function setup() {
  const listeners = {}
  const uni = {
    $emit(name, event) { for (const f of listeners[name] || []) f(event) },
    $on(name, f) { (listeners[name] ||= []).push(f) },
    $off(name, f) {
      const arr = listeners[name] || []
      const i = arr.indexOf(f)
      if (i >= 0) arr.splice(i, 1)
    }
  }
  let statusCalls = 0
  let diagramCalls = 0
  const component = loadComponent('LitigationVisualPanel', {
    uni, t: key => key,
    getLitigationVisualStatus: async () => { statusCalls++; return { available: true, graphviz: true } },
    getLitigationDiagrams: async () => { diagramCalls++; return [] },
    restyleLitigationDiagram: async () => {},
    getLitigationKickoffPrompt: async () => ({}),
    packStatus: async () => ({ status: null }),
    packInstall: async () => {}
  })
  const instance = { ...component.data(), projectId: 1, $t: k => k }
  for (const [name, method] of Object.entries(component.methods)) instance[name] = method.bind(instance)
  return { component, instance, uni, diagramCalls: () => diagramCalls, statusCalls: () => statusCalls }
}

test('mounted() 订阅了 awd:files-changed（画廊刷新的单一汇聚点），不是只订阅换风格事件', async () => {
  const s = setup()
  await s.component.mounted.call(s.instance)
  // reload() 是异步的，等它跑完（mounted 里 refreshPackStatus 不影响 diagramCalls）
  await Promise.resolve()

  const before = s.diagramCalls()
  s.uni.$emit('awd:files-changed', { projectId: 1 })
  // reload() 内部用了 Promise.all，多等两个 microtask 让它跑完
  await Promise.resolve()
  await Promise.resolve()

  assert.equal(s.diagramCalls(), before + 1, '同项目的 files-changed 事件应触发画廊重新拉取列表')
})

test('files-changed 事件的 projectId 与当前面板不一致时不重载画廊', async () => {
  const s = setup()
  await s.component.mounted.call(s.instance)
  await Promise.resolve()

  const before = s.diagramCalls()
  s.uni.$emit('awd:files-changed', { projectId: 999 })
  await Promise.resolve()
  await Promise.resolve()

  assert.equal(s.diagramCalls(), before, '其它项目的 files-changed 不该触发本项目画廊重载')
})

test('beforeUnmount() 取消订阅 awd:files-changed，卸载后事件不再触发重载', async () => {
  const s = setup()
  await s.component.mounted.call(s.instance)
  await Promise.resolve()
  s.component.beforeUnmount.call(s.instance)

  const before = s.diagramCalls()
  s.uni.$emit('awd:files-changed', { projectId: 1 })
  await Promise.resolve()
  await Promise.resolve()

  assert.equal(s.diagramCalls(), before, '卸载后不应再响应 files-changed 事件')
})

// ---- 发信端：project-overview 的 AI 指令路由 ----

const ACTIONS_SRC = readFileSync(
  new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')

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

function pageVm(refs) {
  const events = []
  const uni = { showToast() {}, $emit(name, payload) { events.push({ name, payload }) } }
  const vm = { projectId: 1, $refs: refs, $t: k => k, syncOpenTabsFromFileTree() {} }
  for (const [k, fn] of Object.entries(loadActionMethods(uni))) vm[k] = fn.bind(vm)
  return { vm, events }
}

test('refresh_files：文件树没挂载（左栏停在诉讼可视化）时补发 awd:files-changed', () => {
  const { vm, events } = pageVm({})
  vm.handleClientAction({ action: 'refresh_files' })
  const hits = events.filter(e => e.name === 'awd:files-changed')
  assert.equal(hits.length, 1, '树不在场也必须有人发出文件变更信号')
  assert.equal(String(hits[0].payload.projectId), '1')
})

test('refresh_files：文件树挂着时交给 loadFiles() 发，页面这层不重复补发', async () => {
  let loads = 0
  const { vm, events } = pageVm({ fileTree: { loadFiles: async () => { loads++ } } })
  vm.handleClientAction({ action: 'refresh_files' })
  await Promise.resolve()
  assert.equal(loads, 1)
  assert.equal(events.filter(e => e.name === 'awd:files-changed').length, 0)
})

test('端到端：树未挂载时 refresh_files 让已打开的诉讼可视化画廊重拉', async () => {
  const s = setup()
  await s.component.mounted.call(s.instance)
  await Promise.resolve()
  const before = s.diagramCalls()
  const page = { projectId: 1, $refs: {}, $t: k => k, syncOpenTabsFromFileTree() {} }
  for (const [k, fn] of Object.entries(loadActionMethods({ showToast() {}, $emit: s.uni.$emit }))) page[k] = fn.bind(page)
  page.handleClientAction({ action: 'refresh_files' })
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(s.diagramCalls(), before + 1)
})
