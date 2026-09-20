// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 窗格心跳（dev-board#717）回归用例。
 *   node --test office-addin/taskpane/lib/paneHeartbeat.test.js
 *
 * 心跳让云端知道「这个账号现在开着哪些文档的窗格」，别的窗格才能跨文档读写。
 * 三条要钉住的行为：启动即发一次（不等 30 秒）、未登录不发、失败一律吞掉
 * （它跑在 setInterval 里，抛出去就是一条没人接的 unhandled rejection）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { startHeartbeat } from './paneHeartbeat.js'

test('beats immediately, on interval, and skips when state is null', async () => {
  const sent = []
  let tick = null
  let state = { paneId: 'p1', host: 'word', family: 'office', docName: 'A.docx', projectId: 11, conversationId: 'c1' }
  const hb = startHeartbeat({
    getState: () => state,
    post: async (body) => { sent.push(body) },
    setIntervalFn: (fn) => { tick = fn; return 1 },
    clearIntervalFn: () => { tick = null }
  })
  await Promise.resolve()
  assert.equal(sent.length, 1)
  assert.deepEqual(sent[0], { paneId: 'p1', host: 'word', family: 'office', docName: 'A.docx', projectId: 11, conversationId: 'c1' })
  state = null
  await tick()
  assert.equal(sent.length, 1)
  state = { ...state, paneId: 'p1', conversationId: 'c2' }
  hb.beatNow()
  await Promise.resolve()
  assert.equal(sent.at(-1).conversationId, 'c2')
  hb.stop()
  assert.equal(tick, null)
})

test('interval defaults to 30 seconds', () => {
  let seenMs = null
  const hb = startHeartbeat({
    getState: () => null,
    post: async () => {},
    setIntervalFn: (fn, ms) => { seenMs = ms; return 1 },
    clearIntervalFn: () => {}
  })
  hb.stop()
  assert.equal(seenMs, 30000)
})

test('state without paneId is not sent', async () => {
  const sent = []
  const hb = startHeartbeat({
    getState: () => ({ paneId: '', host: 'word' }),
    post: async (body) => { sent.push(body) },
    setIntervalFn: () => 1, clearIntervalFn: () => {}
  })
  await hb.beatNow()
  hb.stop()
  assert.equal(sent.length, 0)
})

test('post failures are swallowed', async () => {
  const hb = startHeartbeat({
    getState: () => ({ paneId: 'p1' }),
    post: async () => { throw new Error('offline') },
    setIntervalFn: () => 1, clearIntervalFn: () => {}
  })
  await hb.beatNow()
})

test('getState failures are swallowed too', async () => {
  // 取文档名要碰宿主 API，宿主半初始化时可能抛——心跳不能因此炸出 unhandled rejection
  const hb = startHeartbeat({
    getState: () => { throw new Error('host not ready') },
    post: async () => { throw new Error('should not be called') },
    setIntervalFn: () => 1, clearIntervalFn: () => {}
  })
  await hb.beatNow()
})
