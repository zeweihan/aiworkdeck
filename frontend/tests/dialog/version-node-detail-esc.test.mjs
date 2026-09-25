// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机 BUG-31（C4-06）：版本详情弹窗（version/VersionNodeDetail.vue）按 Esc 不关。
//
// 约定同 AwdDialog（utils/dialogCore.js 的 resolveDialogKey：Esc = 取消）与
// calendar/TaskDialog.vue：监听挂 window 捕获段；全局确认框（.awd-dlg-mask，比如
// 「退回到这一版」的二次确认）开着时让它先处理；内层的命名小框开着时 Esc 只关内层。
// 本用例把 <script> 剥出来当普通对象跑，用假 window/document 真派发按键。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/version/VersionNodeDetail.vue', import.meta.url), 'utf8')

function makeEnv() {
  const listeners = []
  const win = {
    addEventListener: (type, fn, capture) => listeners.push({ type, fn, capture }),
    removeEventListener: (type, fn) => {
      const i = listeners.findIndex((l) => l.type === type && l.fn === fn)
      if (i >= 0) listeners.splice(i, 1)
    },
  }
  let globalDialogOpen = false
  const doc = { querySelector: (sel) => (sel === '.awd-dlg-mask' && globalDialogOpen ? {} : null) }
  return {
    win, doc, listeners,
    setGlobalDialog(v) { globalDialogOpen = v },
    press(key) {
      const ev = { key, defaultPrevented: false, stopped: false,
        preventDefault() { this.defaultPrevented = true }, stopPropagation() { this.stopped = true } }
      for (const l of listeners.slice()) if (l.type === 'keydown') l.fn(ev)
      return ev
    },
  }
}

function makeVm(env) {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  // eslint-disable-next-line no-new-func
  const component = new Function('getVersionChanges', 'createVersionActions', 'window', 'document', 'uni',
    script.replace('export default', 'return'))(
    async () => ({ data: { changes: [] } }),
    () => ({}),
    env.win, env.doc, { showToast() {} },
  )
  const emitted = []
  const base = { $t: (k) => k, $emit: (name) => emitted.push(name), projectId: 1, version: { sha: 'abc', when: 0 } }
  const vm = Object.assign(base, component.data.call(base))
  // Vue 会把 methods 绑到实例上（this.onKeydown 直接当监听器传出去也认得 this）
  for (const [k, fn] of Object.entries(component.methods)) vm[k] = fn.bind(vm)
  vm.emitted = emitted
  vm.hooks = component
  return vm
}

test('Esc 关闭版本详情弹窗（监听挂在 window 捕获段）', () => {
  const env = makeEnv()
  const vm = makeVm(env)
  vm.hooks.mounted.call(vm)
  const kd = env.listeners.filter((l) => l.type === 'keydown')
  assert.equal(kd.length, 1, '弹窗挂上后没有注册任何 keydown 监听——Esc 无人接')
  assert.equal(kd[0].capture, true, '要挂捕获段，免得工作台快捷键先把 Esc 吃掉')
  const ev = env.press('Escape')
  assert.deepEqual(vm.emitted, ['close'])
  assert.ok(ev.defaultPrevented && ev.stopped)
})

test('内层命名小框开着时，Esc 只关内层', () => {
  const env = makeEnv()
  const vm = makeVm(env)
  vm.hooks.mounted.call(vm)
  vm.milestoneNaming = true
  env.press('Escape')
  assert.equal(vm.milestoneNaming, false)
  assert.deepEqual(vm.emitted, [])
  vm.draftNaming = true
  env.press('Escape')
  assert.equal(vm.draftNaming, false)
  assert.deepEqual(vm.emitted, [])
  env.press('Escape')
  assert.deepEqual(vm.emitted, ['close'])
})

test('全局确认框（退回二次确认）开着时让它先处理，不连带关掉详情', () => {
  const env = makeEnv()
  const vm = makeVm(env)
  vm.hooks.mounted.call(vm)
  env.setGlobalDialog(true)
  const ev = env.press('Escape')
  assert.deepEqual(vm.emitted, [])
  assert.equal(ev.stopped, false)
})

test('其他键不处理；卸载后监听摘掉', () => {
  const env = makeEnv()
  const vm = makeVm(env)
  vm.hooks.mounted.call(vm)
  env.press('Enter')
  assert.deepEqual(vm.emitted, [])
  vm.hooks.beforeUnmount.call(vm)
  assert.equal(env.listeners.filter((l) => l.type === 'keydown').length, 0)
})
