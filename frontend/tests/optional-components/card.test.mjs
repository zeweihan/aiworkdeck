// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 卡片必须把设计 §4.3 的四件事同时说出来：下载什么 / 多大 / 解锁什么 / 不装则什么不可用。
// 手法同 litigation-visual/packUpdate.test.mjs：抠出 <script> 用 new Function 起一个纯对象。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/components.js'

const source = readFileSync(new URL('../../src/components/OptionalComponentCard.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')

function t(key, params) {
  const raw = key.split('.').reduce((o, k) => (o || {})[k], { components: zh })
  if (typeof raw !== 'string') return key
  return raw.replace(/\{(\w+)\}/g, (_, k) => String((params || {})[k]))
}

function card(item) {
  const component = new Function(script.replace('export default', 'return'))()
  const vm = Object.assign({}, component.methods, { item, $t: t })
  for (const [key, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm) })
  }
  return vm
}

const kokoro = {
  packId: 'kokoro-runtime', localeKey: 'kokoroRuntime', service: 'kokoro-service',
  installed: false, downloadBytes: 209715200, unpackedBytes: 800000000,
  modelId: 'kokoro-models', modelInstalled: false, modelBytes: 314572800,
  featureKeys: ['ttsPanel'], phase: 'idle', percent: 0, error: '',
}

test('有模型的组件：体积行同时给「运行时约 N MB」与「含模型约 M MB」', () => {
  const line = card(kokoro).sizeLine
  assert.match(line, /语音合成/)
  assert.match(line, /200/, '运行时 200MB')
  assert.match(line, /500/, '含模型 200+300=500MB')
  assert.match(line, /~\/\.aiworkdeck/)
})

test('无模型的组件不出现「含模型约」', () => {
  const line = card({ ...kokoro, packId: 'pptx-runtime', localeKey: 'pptxRuntime', modelId: null, modelBytes: 0, downloadBytes: 173015040 }).sizeLine
  assert.ok(!line.includes('含模型'), line)
  assert.match(line, /165/)
})

test('用途行同时写解锁功能与不装的影响', () => {
  const line = card(kokoro).usageLine
  assert.match(line, /语音面板/)
  assert.match(line, /语音合成不可用/)
  assert.match(line, /其余功能不受影响/)
})

test('体积未知时显示占位文案而不是「约 0 MB」', () => {
  assert.match(card({ ...kokoro, downloadBytes: 0 }).sizeLine, /体积获取中/)
})

test('状态行按 phase 分档，失败带原因', () => {
  assert.match(card({ ...kokoro, phase: 'idle' }).stateLine, /未安装/)
  assert.match(card({ ...kokoro, phase: 'runtime', percent: 42 }).stateLine, /运行时 42%/)
  assert.match(card({ ...kokoro, phase: 'model', percent: 7 }).stateLine, /模型 7%/)
  assert.match(card({ ...kokoro, phase: 'starting' }).stateLine, /正在启动/)
  assert.match(card({ ...kokoro, phase: 'ready' }).stateLine, /已就绪/)
  assert.match(card({ ...kokoro, phase: 'failed', error: '镜像不可达' }).stateLine, /镜像不可达/)
})

// ==================== 两个宿主复用的前提：卡片只抛事件、不发请求 ====================

test('复选抛 toggle(packId, checked)，声明的三个 emit 齐备', () => {
  const component = new Function(script.replace('export default', 'return'))()
  assert.deepEqual(component.emits, ['toggle', 'install', 'retry'])
  const seen = []
  const vm = Object.assign({}, component.methods, {
    item: { ...kokoro, selected: false },
    $emit: (...a) => seen.push(a),
  })
  vm.onToggle()
  assert.deepEqual(seen, [['toggle', 'kokoro-runtime', true]])
  assert.ok(source.includes("$emit('install', item.packId)"), '单卡下载没接 install')
  assert.ok(source.includes("$emit('retry', item.packId)"), '失败态没接 retry')
  assert.ok(!/from '@\/services\/api\.js'/.test(source), '卡片不许直接调 api.js')
})

test('单卡的下载按钮用单数文案，不借面板的「立即下载所选」', () => {
  assert.ok(source.includes("components.installOne"), '单卡按钮该用 components.installOne')
  assert.ok(!source.includes('components.installSelected'), '「立即下载所选」是面板底部的批量按钮，不是单卡的')
  assert.equal(zh.installOne, '下载此组件')
})

test('外壳保持浅色：颜色一律走 --awd-* 令牌，不写死色值', () => {
  const style = source.slice(source.indexOf('<style'))
  const hardcoded = [...style.matchAll(/:\s*(#[0-9a-fA-F]{3,8})\b/g)].map((m) => m[1])
  assert.deepEqual(hardcoded, [], '写死了色值：' + hardcoded.join(', '))
})
