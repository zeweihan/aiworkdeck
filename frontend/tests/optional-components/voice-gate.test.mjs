// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 语音面板的三态（设计 §4.2）。0.38.0 起多了一态：运行时本身没装。
// 「运行时没装」和「模型没下」的下一步不同（前者 200MB，后者 300MB，且必须先装前者），
// 合并成一句「引擎没就绪」会让用户点了下载还是用不了。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/panels.js'
import en from '../../src/locales/en-US/panels.js'

const source = readFileSync(new URL('../../src/components/EasyVoicePane.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')

// 顶层 import 被剥掉之后，脚本里引用到的名字要靠形参补回来（与 recorder-gate 同型）。
const HARNESS_ARGS = ['host', 'ICONS', 'reactive', 'createOptionalComponentsController',
  'optionalComponents', 'packInstall', 'packStatus', 'packInfo']

function pane(over) {
  const factory = new Function(...HARNESS_ARGS, script.replace('export default', 'return'))
  const component = factory(
    { model: {}, services: {} }, {},
    (o) => o,
    () => ({ state: { items: [] }, load: async () => {}, fillSizes: async () => {}, installOne: async () => true }),
    async () => ({ components: [] }), async () => ({}), async () => ({}), async () => ({}),
  )
  const vm = Object.assign(component.data(), component.methods, { $t: (k) => k }, over)
  for (const [key, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm) })
  }
  return vm
}

test('运行时没装 → gate 说的是「组件还没装」，按钮是下载组件', () => {
  const vm = pane({ voicesLoaded: true, voices: [], runtimeInstalled: false, modelState: 'absent' })
  assert.equal(vm.engineGateVisible, true)
  assert.equal(vm.gateMessage, 'panels.evRuntimeMissing')
})

test('运行时装了、模型没下 → 仍是原来的「模型没下」文案', () => {
  const vm = pane({ voicesLoaded: true, voices: [], runtimeInstalled: true, modelState: 'absent' })
  assert.equal(vm.gateMessage, 'panels.evModelMissing')
})

test('都装了但拿不到音色 → 「引擎没跑起来」，指向重新检测而不是再下一次', () => {
  const vm = pane({ voicesLoaded: true, voices: [], runtimeInstalled: true, modelState: 'installed' })
  assert.equal(vm.gateMessage, 'panels.evEngineNotRunning')
})

test('都装好了就不再给下载按钮（否则用户会以为还差点什么）', () => {
  const vm = pane({ voicesLoaded: true, voices: [], runtimeInstalled: true, modelState: 'installed' })
  assert.equal(vm.canDownloadModel, false)
})

test('两语言都有新增的运行时缺失文案', () => {
  assert.ok(zh.evRuntimeMissing)
  assert.ok(zh.evDownloadComponent)
  assert.ok(en.evRuntimeMissing)
  assert.ok(en.evDownloadComponent)
})

test('下载按钮走 useOptionalComponents 的编排，不再直接调 host.model.download', () => {
  assert.match(script, /installOne/)
  assert.ok(!/host\.model\.download\(TTS_MODEL_ID\)/.test(script),
    '直接下模型会跳过运行时，模型下载器本身就跑在运行时的 venv 里')
})
