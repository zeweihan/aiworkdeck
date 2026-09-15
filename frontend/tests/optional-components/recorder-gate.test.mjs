// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「录音不出本机」的四态出路（设计 §4.2）。四条下一步各不相同：
// RUNTIME_MISSING 下组件 / MODEL_MISSING 下模型 / SERVICE_DOWN 重启 / READY 可用。
// 给 SERVICE_DOWN 一个「下载模型」按钮是错的指路——模型下完了照样没人来跑它。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/meeting.js'
import en from '../../src/locales/en-US/meeting.js'

const source = readFileSync(new URL('../../src/components/MeetingRecordingPanel.vue', import.meta.url), 'utf8')
const script0 = source.match(/<script>([\s\S]*?)<\/script>/)[1]
const script = script0.replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')

// 顶层 import 被剥掉之后，脚本引用到的名字要补回来。逐个列形参太脆（这个组件的
// import 列表还在长），所以直接从源码里把导入名抓出来，统一喂一份桩表；测试真正
// 关心的那几个再覆盖成有行为的桩。NOTICE_COPY 这类脚本自己的 const 不在其中。
const IMPORTED = []
for (const m of script0.matchAll(/^import\s+([\s\S]*?)\s+from\s+'[^']+'\s*;?\s*$/gm)) {
  const clause = m[1]
  const braced = clause.match(/\{([\s\S]*?)\}/)
  if (braced) {
    for (const part of braced[1].split(',')) {
      const name = part.split(/\s+as\s+/).pop().trim()
      if (name) IMPORTED.push(name)
    }
  }
  const def = clause.replace(/\{[\s\S]*?\}/, '').replace(/,/g, '').trim()
  if (def) IMPORTED.push(def)
}

function panel(probe, over = {}) {
  const stubs = {}
  for (const name of IMPORTED) stubs[name] = () => ({})
  Object.assign(stubs, {
    host: { model: {}, services: {} },
    localTierReady: () => probe.status === 'READY',
    localAsrProbeResult: () => probe,
    refreshLocalAsrReadiness: async () => probe,
    recorderState: {},
    isRecordingActive: () => false,
    reactive: (o) => o,
    createOptionalComponentsController: () => ({
      state: { items: [] },
      load: async () => {},
      fillSizes: async () => {},
      installOne: async () => true,
    }),
  })
  const body = 'const { ' + IMPORTED.join(', ') + " } = __stubs;\n" + script.replace('export default', 'return')
  const component = new Function('__stubs', body)(stubs)
  const vm = Object.assign(component.data(), component.methods,
    { $t: (k) => k, localGateOpen: true, asrProvider: 'local', modelState: 'absent' }, over)
  for (const [key, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm) })
  }
  return vm
}

test('RUNTIME_MISSING：给「下载组件」，不给「下载模型」', () => {
  const vm = panel({ status: 'RUNTIME_MISSING', message: 'm', nextStep: 'n' })
  assert.equal(vm.canInstallRuntime, true)
  assert.equal(vm.canDownloadModel, false)
})

test('MODEL_MISSING：给「下载模型」，不给「下载组件」', () => {
  const vm = panel({ status: 'MODEL_MISSING', message: 'm', nextStep: 'n' })
  assert.equal(vm.canInstallRuntime, false)
  assert.equal(vm.canDownloadModel, true)
})

test('SERVICE_DOWN：两个下载按钮都不给（下一步是重启，不是再下一遍）', () => {
  const vm = panel({ status: 'SERVICE_DOWN', message: 'm', nextStep: 'n' })
  assert.equal(vm.canInstallRuntime, false)
  assert.equal(vm.canDownloadModel, false)
})

test('READY：整块 gate 不出现', () => {
  assert.equal(panel({ status: 'READY' }).localGate, null)
})

test('模板里四态各有一条出路，且组件安装走顺序编排', () => {
  assert.match(script, /canInstallRuntime/)
  assert.match(script, /installOne/)
  assert.match(source, /meeting\.downloadRuntime/)
})

test('两语言都有新增的运行时下载文案', () => {
  assert.ok(zh.downloadRuntime)
  assert.ok(en.downloadRuntime)
})

test('platformServices 的注释把四态写全（读它的人才知道不能合并）', () => {
  const cfg = readFileSync(new URL('../../src/config/platformServices.js', import.meta.url), 'utf8')
  for (const s of ['RUNTIME_MISSING', 'MODEL_MISSING', 'SERVICE_DOWN', 'READY']) {
    assert.ok(cfg.includes(s), 'platformServices.js 没提到 ' + s)
  }
})
