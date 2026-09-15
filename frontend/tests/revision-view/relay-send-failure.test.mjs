// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 传输层拒收一条命令时，relay 必须当场以失败收场，不许干等满预算。
//
// iframe 下 postMessage 遇到过不了结构化克隆的参数（例如 Vue 响应式 Proxy）同步抛
// DataCloneError；Electron <webview>.send() 则返回一个被拒的 Promise。旧实现两种都
// 没接住：前者让 executeCommand 的 Promise 直接 reject（调用方拿到异常而不是
// {success:false}），后者更糟——命令根本没发出去，却要等满 resolve_revisions 的
// 120s 才超时，审阅面板在此期间整个锁死。
//
// 用 mock timers 把 setTimeout 接管住：预算计时器永远不会自己到点，所以「几个事件
// 循环回合内就有结果」只能来自发送失败的即时收场，而不是超时。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { createRelayExecutor } from '../../src/composables/zetaOfficeRelay.js'

function transport(send) {
  let handler = null
  return { send, subscribe: (h) => { handler = h; return () => { handler = null } }, deliver: (m) => handler && handler(m) }
}

// 读一个 Promise 在几个事件循环回合之后的状态（setImmediate 不受 mock 影响）。
async function settleState(p) {
  let state = { pending: true }
  p.then((value) => { state = { value } }, (error) => { state = { error } })
  for (let i = 0; i < 5; i++) await new Promise((r) => setImmediate(r))
  return state
}

const proxyParams = () => ({ indices: [0], action: 'accept', expectedRevisions: [reactive({ index: 0, identifier: 'rv-1' })] })

test('send 同步抛出（iframe postMessage 的 DataCloneError）：立即回 {success:false}', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const tp = transport((msg) => { structuredClone(msg) })
  const relay = createRelayExecutor({ send: tp.send, subscribe: tp.subscribe })
  const state = await settleState(relay.executeCommand('resolve_revisions', proxyParams()))
  assert.equal(state.error, undefined, '不许把异常抛给调用方')
  assert.ok(state.value, '发送失败必须当场收场，不能挂着等预算')
  assert.equal(state.value.success, false)
  assert.match(state.value.message, /resolve_revisions/)
  assert.match(state.value.message, /could not be cloned/)
})

test('send 返回被拒的 Promise（Electron webview.send）：立即回 {success:false}，不等 120s', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const tp = transport(async (msg) => { structuredClone(msg) })
  const relay = createRelayExecutor({ send: tp.send, subscribe: tp.subscribe })
  const state = await settleState(relay.executeCommand('resolve_revisions', proxyParams()))
  assert.ok(state.value, '发送失败必须当场收场，不能挂着等预算')
  assert.equal(state.value.success, false)
  assert.match(state.value.message, /resolve_revisions/)
})

test('发送失败后预算计时器已清掉：到点也不会再立墓碑、不会误报迟到结果', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const late = []
  let lastReqId = null
  const tp = transport(async (msg) => { lastReqId = msg.reqId; throw new Error('boom') })
  const relay = createRelayExecutor({ send: tp.send, subscribe: tp.subscribe, onLateResult: (a, r) => late.push({ a, r }) })
  const state = await settleState(relay.executeCommand('resolve_revisions', { indices: [0] }))
  assert.equal(state.value.success, false)
  t.mock.timers.tick(200000)
  tp.deliver({ __lo: 'lo-relay', type: 'result', reqId: lastReqId, result: { success: true } })
  assert.deepEqual(late, [])
})

test('send 成功时照旧等对端回结果（Promise 形态的 send 也一样）', async (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const tp = transport(async (msg) => { structuredClone(msg); queueMicrotask(() => tp.deliver({ __lo: 'lo-relay', type: 'result', reqId: msg.reqId, result: { success: true, ok: 1 } })) })
  const relay = createRelayExecutor({ send: tp.send, subscribe: tp.subscribe })
  const state = await settleState(relay.executeCommand('resolve_revisions', { indices: [0], expectedRevisions: [{ index: 0 }] }))
  assert.deepEqual(state.value, { success: true, ok: 1 })
})
