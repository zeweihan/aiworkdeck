// ClipboardPanel.vue 的删除链路（dev-board#455 复现 1「确认后卡片仍在」）。
//
// 把 <script> 剥出来当普通对象跑（同 tests/insight/insightPane.test.mjs 的路子），
// 走完 requestDelete → confirmDelete → DELETE → refresh 这条真链，钉三条：
//   ① 确认后真的发出 DELETE，并整表重拉，列表里不再有那一条；
//   ② 确认态不再有 5 秒自动收起——超时后点「确定」点到的是卡片本身的
//      @tap="copy(it.text)"，用户看到的就是「卡片仍在、什么也没发生」；
//   ③ 删除失败走原生弹窗而不是 toast——工作台开着浏览器标签时 toast 被原生
//      BrowserView 整个盖住（见 .claude/agents/utility-tools.md），失败等于无反馈。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { shouldAcceptResponse } from '../../src/utils/requestGeneration.js'

const SRC = readFileSync(new URL('../../src/components/ClipboardPanel.vue', import.meta.url), 'utf8')

function makePanel(deps) {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const names = [
    'listClipboard', 'deleteClipboardItem', 'getApiBaseUrl', 'getClipboardTypeMeta',
    'getSessionId', 'ICONS', 'UnlockHint', 'shouldAcceptResponse', 'host',
    'uni', 'setTimeout', 'clearTimeout',
  ]
  // eslint-disable-next-line no-new-func
  return new Function(...names, script.replace('export default', 'return'))(...names.map((n) => deps[n]))
}

function makeVm(over = {}) {
  const calls = { list: [], del: [], toasts: [], native: [], timers: [] }
  let rows = over.rows || [{ id: 1, type: 'TEXT', text: 'a' }, { id: 2, type: 'TEXT', text: 'b' }]
  const deps = {
    listClipboard: async (q, limit) => {
      calls.list.push([q, limit])
      return { items: rows.slice(), limited: false, hiddenCount: 0 }
    },
    deleteClipboardItem: async (id) => {
      calls.del.push(id)
      if (over.deleteFails) throw new Error('boom')
      rows = rows.filter((r) => r.id !== id)
      return { code: 0 }
    },
    getApiBaseUrl: () => '',
    getClipboardTypeMeta: () => ({ label: 'TEXT', tone: 'neutral' }),
    getSessionId: () => 's',
    ICONS: {},
    UnlockHint: {},
    shouldAcceptResponse,
    host: { app: { confirm: async (p) => { calls.native.push(p) } } },
    uni: { showToast: (o) => calls.toasts.push(o), $on: () => {}, $off: () => {}, setClipboardData: () => {} },
    setTimeout: (fn, ms) => { calls.timers.push([fn, ms]); return calls.timers.length },
    clearTimeout: () => {},
  }
  const component = makePanel(deps)
  const base = { $t: (k) => k, $emit: () => {} }
  const vm = Object.assign(base, component.data.call(base), component.methods)
  for (const [k, fn] of Object.entries(component.computed || {})) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  return { vm, calls, rows: () => rows }
}

test('确认删除：发出 DELETE → 重拉列表 → 该条不再出现', async () => {
  const { vm, calls } = makeVm()
  await vm.refresh()
  assert.deepEqual(vm.items.map((i) => i.id), [1, 2])

  vm.requestDelete(2)
  assert.equal(vm.confirmDeleteId, 2, '× 应进入确认态')

  await vm.confirmDelete(2)
  assert.deepEqual(calls.del, [2], 'DELETE 必须真的发出去')
  assert.equal(calls.list.length, 2, '删除后必须整表重拉一次')
  assert.deepEqual(vm.items.map((i) => i.id), [1], '重拉后列表里不该还有被删的那条')
  assert.equal(vm.confirmDeleteId, null)
})

test('再点一次 × 取消确认态', () => {
  const { vm } = makeVm()
  vm.requestDelete(1)
  vm.requestDelete(1)
  assert.equal(vm.confirmDeleteId, null)
})

test('确认态不自动收起：定时器不许把它清掉（超时后「确定」会点成卡片的复制）', () => {
  const { vm, calls } = makeVm()
  vm.requestDelete(1)
  // 把 requestDelete 排过的定时器全部触发一遍，模拟等待任意长时间
  for (const [fn] of calls.timers) fn()
  assert.equal(vm.confirmDeleteId, 1, '确认态被自动收起了，用户点「确定」时点到的是卡片本身')
})

test('删除失败走原生弹窗（toast 会被原生 BrowserView 整个盖住）', async () => {
  const { vm, calls } = makeVm({ deleteFails: true })
  await vm.refresh()
  vm.requestDelete(1)
  await vm.confirmDelete(1)
  assert.equal(calls.native.length, 1, '失败必须用 host.app.confirm 这类原生弹窗提示')
  assert.deepEqual(calls.toasts, [], '不该再退回 uni.showToast')
})
