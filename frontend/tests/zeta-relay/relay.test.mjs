/**
 * zetaOfficeRelay.js 的迟到结果（late result）机制。零依赖，用 Node 自带的
 * node:test 跑：
 *   cd frontend && npm run test:zeta-relay
 *
 * 背景（用户反馈 6 / dev-board#36）：load_document/export_document 的 relay
 * 超时预算是 180s，但 worker 侧的 loadComponentFromURL 打不断——超时后台
 * host 端已经 resolve({success:false}) 并把 reqId 从 pending 里删掉，但
 * worker 常常随后真的装载成功，迟到的 'result' 消息此前被静默丢弃。
 *
 * 这里只单测 zetaOfficeRelay.js 本身新增的「墓碑」机制（超时→登记→迟到
 * result 命中→onLateResult 触发，且只触发一次）。世代号匹配/不匹配的裁决
 * 逻辑在 LibreOfficeEditor.vue（Vue SFC，仓库里没有为 .vue 组件搭单测
 * 框架）——那部分改用手工推演矩阵，见本 PR 正文。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { createRelayExecutor, serveExecutor } from '../../src/composables/zetaOfficeRelay.js'

/** 造一对可手动摆布的 {send, subscribe}：send 记下发出的消息，subscribe 的
 * handler 存起来，测试代码直接调它模拟"对端回了一条消息"。 */
function fakeTransport() {
  const sent = []
  let handler = null
  return {
    sent,
    send: (msg) => sent.push(msg),
    subscribe: (h) => { handler = h; return () => { handler = null } },
    // 模拟 webview/iframe 对端发回一条消息
    deliver: (msg) => handler && handler(msg),
  }
}

test('超时后迟到的成功结果触发 onLateResult，而不是被静默丢弃', async () => {
  const t = fakeTransport()
  const lateCalls = []
  // 用一个不是 load_document/export_document 的 action 名，绕开生产代码里
  // 对这两个 action 强制 >=180000ms 的预算下限，让测试能用一个短超时快速
  // 触发——墓碑机制本身与具体 action 名无关。
  const relay = createRelayExecutor({
    send: t.send,
    subscribe: t.subscribe,
    timeoutMs: 20,
    onLateResult: (action, result) => lateCalls.push({ action, result }),
  })

  const p = relay.executeCommand('some_slow_action', { x: 1 })
  const timedOut = await p
  assert.equal(timedOut.success, false, '超时应先把失败结果 resolve 给调用方')

  // worker 端"迟到"发回真正的结果——reqId 就是刚刚发送的那条消息里的 reqId。
  const reqId = t.sent[0].reqId
  t.deliver({ __lo: 'lo-relay', type: 'result', reqId, result: { success: true, kind: 'writer' } })

  assert.equal(lateCalls.length, 1, 'onLateResult 应该被触发恰好一次')
  assert.equal(lateCalls[0].action, 'some_slow_action')
  assert.deepEqual(lateCalls[0].result, { success: true, kind: 'writer' })

  // 同一个 reqId 不能重复触发（墓碑命中后必须摘除）。
  t.deliver({ __lo: 'lo-relay', type: 'result', reqId, result: { success: true } })
  assert.equal(lateCalls.length, 1, '墓碑命中一次后应摘除，重复的 result 不再触发')
})

test('正常在超时预算内返回的结果走 pending 路径，不经过 onLateResult', async () => {
  const t = fakeTransport()
  const lateCalls = []
  const relay = createRelayExecutor({
    send: t.send,
    subscribe: t.subscribe,
    timeoutMs: 5000,
    onLateResult: (action, result) => lateCalls.push({ action, result }),
  })

  const p = relay.executeCommand('quick_action', {})
  const reqId = t.sent[0].reqId
  t.deliver({ __lo: 'lo-relay', type: 'result', reqId, result: { success: true } })

  const res = await p
  assert.equal(res.success, true)
  assert.equal(lateCalls.length, 0, '未超时的正常结果不应触发 onLateResult')
})

test('未提供 onLateResult 时，迟到结果被安静丢弃，不抛异常', async () => {
  const t = fakeTransport()
  const relay = createRelayExecutor({ send: t.send, subscribe: t.subscribe, timeoutMs: 20 })

  await relay.executeCommand('some_action', {})
  const reqId = t.sent[0].reqId
  assert.doesNotThrow(() => {
    t.deliver({ __lo: 'lo-relay', type: 'result', reqId, result: { success: true } })
  })
})

test('墓碑表有上限，超出后丢弃最旧的一条，不无限增长', async () => {
  const t = fakeTransport()
  const lateCalls = []
  const relay = createRelayExecutor({
    send: t.send,
    subscribe: t.subscribe,
    timeoutMs: 5,
    onLateResult: (action, result) => lateCalls.push({ action, result }),
  })

  // 连续发起 25 次都超时（上限是 20），reqId 按顺序记录下来。
  const reqIds = []
  for (let i = 0; i < 25; i++) {
    const p = relay.executeCommand('act_' + i, {})
    reqIds.push(t.sent[t.sent.length - 1].reqId)
    // eslint-disable-next-line no-await-in-loop
    await p // 等这次超时 resolve 完再发下一条，保证顺序确定
  }

  // 最早的 5 条（超出 20 条上限的部分）应该已经被挤掉，迟到结果找不到对应
  // 的墓碑，onLateResult 不会被调用。
  t.deliver({ __lo: 'lo-relay', type: 'result', reqId: reqIds[0], result: { success: true } })
  assert.equal(lateCalls.length, 0, '最旧的墓碑应已被挤出上限，迟到结果找不到归宿')

  // 最近的一条（第 25 个）应该还在表里。
  t.deliver({ __lo: 'lo-relay', type: 'result', reqId: reqIds[24], result: { success: true } })
  assert.equal(lateCalls.length, 1, '仍在上限内的墓碑应正常命中')
})

// ---- dev-board#108 复核：progress 帧与 cancel 的 reqId 映射 ----------------------

test('progress 帧只回调 onProgress，不 resolve pending；随后的 result 才 resolve', async () => {
  const t = fakeTransport()
  const frames = []
  const relay = createRelayExecutor({ send: t.send, subscribe: t.subscribe, timeoutMs: 5000, onProgress: (reqId, p) => frames.push({ reqId, ...p }) })
  const perCall = []
  let settled = false
  const p = relay.executeCommand('find_replace', { findText: 'a' }, { onProgress: (f) => perCall.push(f) }).then((r) => { settled = true; return r })
  const reqId = t.sent[0].reqId
  t.deliver({ __lo: 'lo-relay', type: 'progress', reqId, done: 30, total: 150 })
  t.deliver({ __lo: 'lo-relay', type: 'progress', reqId, done: 60, total: 150 })
  await new Promise((r) => setTimeout(r, 10))
  assert.equal(settled, false, 'progress 帧不能 resolve 命令本身')
  assert.deepEqual(perCall, [{ done: 30, total: 150 }, { done: 60, total: 150 }])
  assert.deepEqual(frames, [{ reqId, done: 30, total: 150 }, { reqId, done: 60, total: 150 }])
  t.deliver({ __lo: 'lo-relay', type: 'result', reqId, result: { success: true, replaced: 150 } })
  const r = await p
  assert.equal(settled, true)
  assert.deepEqual(r, { success: true, replaced: 150 })
  relay.dispose()
})

test('serveExecutor：在飞命令的 cancel 把宿主 reqId 换成 worker reqId；已结束的原样透传、不再映射', async () => {
  const t = fakeTransport()
  const calls = []
  let release = null
  const executor = {
    executeCommand(action, params, callOpts) {
      calls.push({ action, params })
      if (action === 'find_replace') {
        if (callOpts && callOpts.onIssued) callOpts.onIssued('lo_worker_1')
        return new Promise((resolve) => { release = () => { callOpts.onProgress({ done: 30, total: 60 }); resolve({ success: true, cancelled: true, done: 30 }) } })
      }
      return Promise.resolve({ success: true, reqId: params.reqId })
    },
  }
  serveExecutor({ executor, send: t.send, subscribe: t.subscribe })
  t.deliver({ __lo: 'lo-relay', type: 'exec', reqId: 'rly_1', action: 'find_replace', params: { findText: 'a' } })
  // 在飞：cancel 的 reqId 应被换成 worker 侧 id
  t.deliver({ __lo: 'lo-relay', type: 'exec', reqId: 'rly_2', action: 'cancel', params: { reqId: 'rly_1' } })
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls[1].action, 'cancel')
  assert.equal(calls[1].params.reqId, 'lo_worker_1', '在飞命令的 cancel 要换成 worker reqId')
  release()
  await new Promise((r) => setTimeout(r, 0))
  const progress = t.sent.find((m) => m.type === 'progress')
  assert.deepEqual(progress, { __lo: 'lo-relay', type: 'progress', reqId: 'rly_1', done: 30, total: 60 }, 'progress 往上带宿主 reqId')
  const result = t.sent.find((m) => m.type === 'result' && m.reqId === 'rly_1')
  assert.deepEqual(result.result, { success: true, cancelled: true, done: 30 })
  // 已结束：映射已删，cancel 原样透传（worker 侧按 stale 处理，不留 CANCELLED 痕迹）
  t.deliver({ __lo: 'lo-relay', type: 'exec', reqId: 'rly_3', action: 'cancel', params: { reqId: 'rly_1' } })
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls[2].action, 'cancel')
  assert.equal(calls[2].params.reqId, 'rly_1', '已结束的 reqId 不再映射（inflight 已删）')
})
