// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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
  const calls = { list: [], del: [], toasts: [], native: [], timers: [], copied: [] }
  let rows = over.rows || [{ id: 1, type: 'TEXT', text: 'a' }, { id: 2, type: 'TEXT', text: 'b' }]
  const deps = {
    listClipboard: async (q, limit) => {
      calls.list.push([q, limit])
      return { items: rows.slice(), limited: false, hiddenCount: 0 }
    },
    deleteClipboardItem: async (id) => {
      calls.del.push(id)
      if (over.deleteGate) await over.deleteGate
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
    uni: { showToast: (o) => calls.toasts.push(o), $on: () => {}, $off: () => {}, setClipboardData: (o) => calls.copied.push(o.data) },
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

// BUG-63：真机上「确定」约六成点成了卡片的「复制」。整张卡片本身绑着 @tap 复制，
// 气泡开着时点偏一点、或者确认后到重拉完成前补点一下，都会落在卡片上变成复制。
test('卡片本体的点击走 onCardTap（不是直接 copy）', () => {
  const card = SRC.match(/<view[^>]*class="clip-card"[^>]*>/)[0]
  assert.match(card, /@tap="onCardTap\(it\)"/, '卡片 @tap 必须经过确认态守卫')
})

test('确认气泡开着时点卡片本体：不复制，只收起气泡', async () => {
  const { vm, calls } = makeVm()
  await vm.refresh()
  vm.requestDelete(2)
  await vm.onCardTap(vm.items[1])
  assert.deepEqual(calls.copied, [], '气泡开着时点偏落到卡片上不该变成复制')
  assert.equal(vm.confirmDeleteId, null, '点卡片其它地方应收起气泡')
  await vm.onCardTap(vm.items[0])
  // 条件编译注释在 node 里不生效，H5 与非 H5 两支都会写一次，只看写进去的内容
  assert.ok(calls.copied.length > 0 && calls.copied.every((t) => t === 'a'), '气泡收起后卡片照常可点复制')
})

test('确认删除后、重拉完成前补点该卡片：不复制', async () => {
  let release
  const gate = new Promise((r) => { release = r })
  const { vm, calls } = makeVm({ deleteGate: gate })
  await vm.refresh()
  vm.requestDelete(2)
  const pending = vm.confirmDelete(2)
  await vm.onCardTap(vm.items.find((i) => i.id === 2))
  assert.deepEqual(calls.copied, [], '删除进行中的卡片不该再响应复制')
  release()
  await pending
  assert.deepEqual(vm.items.map((i) => i.id), [1])
})
