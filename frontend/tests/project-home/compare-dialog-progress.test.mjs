// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// CompareDocDialog 比对进度区（dev-board#1123）。
//
// 组件 <script> 切出来真跑（@/ 别名跑不进 node，按本目录既有方式处理），
// 用假时钟驱动 setInterval/Date.now：跨 180s 仍计时、阶段变化不重置、
// 重试从 0 开始、关闭/卸载清 interval、saving 不可取消。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const VUE_SRC = readFileSync(
  new URL('../../src/components/CompareDocDialog.vue', import.meta.url), 'utf8')
const SCRIPT = VUE_SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace("import { ICONS } from '@/config/icons.js'", '')
  .replace('export default', 'return')
const getComponentOptions = new Function('ICONS', SCRIPT)

const T = (k, p) => (p ? `${k}:${JSON.stringify(p)}` : k)

function installFakeClock() {
  const timers = new Map()
  let nextId = 1
  let now = 1_000_000
  const origSetInterval = globalThis.setInterval
  const origClearInterval = globalThis.clearInterval
  const origNow = Date.now
  globalThis.setInterval = (fn, ms) => {
    const id = nextId++
    timers.set(id, { fn, ms })
    return id
  }
  globalThis.clearInterval = (id) => { timers.delete(id) }
  Date.now = () => now
  return {
    advance(ms) {
      now += ms
      for (const { fn } of [...timers.values()]) fn()
    },
    activeCount: () => timers.size,
    restore() {
      globalThis.setInterval = origSetInterval
      globalThis.clearInterval = origClearInterval
      Date.now = origNow
    }
  }
}

function makeVm(documents = [{ id: 1, name: 'A.docx' }, { id: 2, name: 'B.docx' }]) {
  const opts = getComponentOptions({})
  const events = []
  const vm = {
    busy: false,
    stage: '',
    visible: false,
    documents,
    events,
    $t: T,
    $emit: (...args) => { events.push(args) },
    ...opts.data(),
    _compareTimer: null,
    _compareStartedAt: 0
  }
  for (const [key, fn] of Object.entries(opts.computed)) {
    Object.defineProperty(vm, key, { get: () => fn.call(vm), configurable: true })
  }
  for (const [key, fn] of Object.entries(opts.methods)) vm[key] = fn.bind(vm)
  Object.defineProperty(vm, '__hooks', { value: opts })
  return vm
}

function setProp(vm, key, value) {
  const old = vm[key]
  vm[key] = value
  const watcher = vm.__hooks.watch[key]
  if (watcher) watcher.call(vm, value, old)
}

function startBusy(vm) {
  setProp(vm, 'visible', true)
  setProp(vm, 'busy', true)
}

test('busy&&visible false→true 启动计时；连续跨 180s 仍持续计时', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm)
    assert.equal(vm.compareElapsedMs, 0)
    assert.equal(clock.activeCount(), 1)
    clock.advance(90_000)
    assert.equal(vm.compareElapsedMs, 90_000)
    clock.advance(102_000) // 跨过 180s
    assert.equal(vm.compareElapsedMs, 192_000)
    assert.match(vm.elapsedText, /"minutes":3,"seconds":12/)
    clock.advance(5_000)
    assert.equal(vm.compareElapsedMs, 197_000)
  } finally { clock.restore() }
})

test('阶段变化不重置计时', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm)
    clock.advance(60_000)
    for (const stage of ['reading', 'loading', 'normalizing', 'comparing', 'exporting']) {
      setProp(vm, 'stage', stage)
      assert.equal(vm.compareElapsedMs, 60_000, `stage=${stage} 不应重置`)
      assert.equal(clock.activeCount(), 1)
    }
    clock.advance(10_000)
    assert.equal(vm.compareElapsedMs, 70_000)
  } finally { clock.restore() }
})

test('busy 结束清理 interval 且清零；重试从 0 重新计时', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm)
    clock.advance(30_000)
    setProp(vm, 'busy', false)
    assert.equal(clock.activeCount(), 0)
    assert.equal(vm.compareElapsedMs, 0)
    // 重试
    setProp(vm, 'busy', true)
    assert.equal(vm.compareElapsedMs, 0)
    clock.advance(5_000)
    assert.equal(vm.compareElapsedMs, 5_000)
  } finally { clock.restore() }
})

test('visible 关闭清理 interval', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm)
    clock.advance(2_000)
    setProp(vm, 'visible', false)
    assert.equal(clock.activeCount(), 0)
    assert.equal(vm.compareElapsedMs, 0)
  } finally { clock.restore() }
})

test('卸载（beforeUnmount）清理 interval', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm)
    vm.__hooks.beforeUnmount.call(vm)
    assert.equal(clock.activeCount(), 0)
    assert.equal(vm.compareElapsedMs, 0)
    // 幂等：再次调用不炸
    vm.__hooks.beforeUnmount.call(vm)
  } finally { clock.restore() }
})

test('saving 阶段取消仍禁用；其他阶段可取消', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm)
    setProp(vm, 'stage', 'saving')
    vm.handleCancel()
    assert.equal(vm.events.length, 0)
    setProp(vm, 'stage', 'comparing')
    vm.handleCancel()
    assert.deepEqual(vm.events, [['cancel'], ['update:visible', false]])
  } finally { clock.restore() }
})

test('原确认/选择行为保持', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm)
    vm.handleConfirm()
    assert.equal(vm.events.length, 0, 'busy 中不可确认')
    setProp(vm, 'busy', false)
    vm.handleConfirm()
    assert.deepEqual(vm.events, [['confirm', { source: { id: 1, name: 'A.docx' }, target: { id: 2, name: 'B.docx' } }], ['update:visible', false]])
    // 选择守卫
    const vm2 = makeVm()
    setProp(vm2, 'visible', true)
    vm2.selectSource(1)
    assert.equal(vm2.sourceIndex, 1)
    assert.equal(vm2.targetIndex, 0, '撞车自动换 target')
    vm2.selectTarget(1)
    assert.equal(vm2.targetIndex, 0, '不可选成与 source 相同')
  } finally { clock.restore() }
})

test('模板结构：进度区在 body 内、footer 无 stage 文本、含进度语义与 reduce 支持', () => {
  const body = VUE_SRC.match(/<view class="dialog-body">([\s\S]*?)<\/view>\s*\n\s*<view class="dialog-footer">/)[1]
  assert.ok(body.includes('class="compare-progress"'), '进度区在 dialog-body 底部')
  assert.ok(body.includes('role="progressbar"'), '静态进度条语义（非每秒 aria-live）')
  assert.ok(body.includes('aria-live="polite"'), '阶段变化才播报')
  assert.ok(!body.match(/aria-live[^\n]*elapsed/), '已用时间不做 aria-live')
  const footer = VUE_SRC.slice(VUE_SRC.indexOf('dialog-footer'))
  assert.ok(!footer.slice(0, footer.indexOf('</template>')).includes('dialog-stage'), 'footer 不再放 stage 文本')
  assert.match(VUE_SRC, /prefers-reduced-motion:\s*reduce/, '尊重 reduce 动效')
  assert.match(VUE_SRC, /:disabled="stage === 'saving'"/, 'saving 取消禁用保持')
})

test('locale：七阶段 + 进度文案两语言齐备', async () => {
  const zh = (await import('../../src/locales/zh-CN/editor.js')).default.compare
  const en = (await import('../../src/locales/en-US/editor.js')).default.compare
  const keys = ['reading', 'starting', 'loading', 'normalizing', 'comparing', 'exporting', 'saving']
    .map((s) => 'stage_' + s)
    .concat(['progressLabel', 'elapsedFormat', 'slowHint'])
  for (const k of keys) {
    assert.ok(typeof zh[k] === 'string' && zh[k], `zh 缺 ${k}`)
    assert.ok(typeof en[k] === 'string' && en[k], `en 缺 ${k}`)
  }
})


test('5 minutes without a new stage shows a warning; continuing never restarts the task or total clock', () => {
  const clock = installFakeClock()
  try {
    const vm = makeVm()
    startBusy(vm); setProp(vm, 'stage', 'comparing')
    clock.advance(299000)
    assert.equal(vm.comparisonStalled, false)
    clock.advance(1000)
    assert.equal(vm.comparisonStalled, true)
    vm.continueWaiting()
    assert.equal(vm.comparisonStalled, false)
    assert.equal(vm.compareElapsedMs, 300000)
    assert.deepEqual(vm.events, [], 'keep waiting must not dispatch confirm/compare again')
    assert.equal(clock.activeCount(), 1)
    clock.advance(300000)
    assert.equal(vm.comparisonStalled, true, 'another quiet 5 minutes prompts again')
    setProp(vm, 'stage', 'exporting')
    assert.equal(vm.comparisonStalled, false, 'real stage advancement clears the warning')
    assert.equal(vm.compareElapsedMs, 600000)
    vm.handleCancel()
    assert.deepEqual(vm.events, [['cancel'], ['update:visible', false]])
    setProp(vm, 'busy', false)
    assert.equal(clock.activeCount(), 0)
    startBusy(vm)
    assert.equal(vm.compareIdleMs, 0)
    assert.equal(vm.comparisonStalled, false)
  } finally { clock.restore() }
})
