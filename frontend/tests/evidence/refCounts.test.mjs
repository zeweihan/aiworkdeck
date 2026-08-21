// FileTree.vue 引用角标拉取调度（#550 复核 M2）：
//   cd frontend && npm run test:evidence
import test from 'node:test'
import assert from 'node:assert/strict'
import { createRefCountsFetcher } from '../../src/utils/fileTreeRefCounts.js'

// 手动推进的假定时器：只跑被 schedule 排进来的回调
function fakeTimers() {
  let seq = 0
  const pending = new Map()
  return {
    setTimeout(fn, ms) { const id = ++seq; pending.set(id, { fn, ms }); return id },
    clearTimeout(id) { pending.delete(id) },
    get size() { return pending.size },
    // 不 await 回调本身（在途请求可能永远不回来），只让微任务队列跑空
    async fire() {
      const entries = Array.from(pending.entries())
      pending.clear()
      for (const [, { fn }] of entries) fn()
      await new Promise(r => setImmediate(r))
    },
  }
}

function harness({ responder } = {}) {
  const timers = fakeTimers()
  const calls = []
  const applied = []
  let resolvers = []
  const fetch = (projectId, ids) => {
    calls.push({ projectId, ids: [...ids] })
    if (responder) return Promise.resolve(responder(projectId, ids))
    return new Promise(resolve => resolvers.push(resolve))
  }
  const fetcher = createRefCountsFetcher({ fetch, apply: c => applied.push(c), delayMs: 300, batchSize: 2, timers })
  return { timers, calls, applied, fetcher, resolveNext: v => resolvers.shift()(v) }
}

test('连续 schedule 在 300ms 内只发一轮请求（防抖）', async () => {
  const h = harness({ responder: (_, ids) => Object.fromEntries(ids.map(id => [id, 1])) })
  h.fetcher.schedule(1, [11])
  h.fetcher.schedule(1, [11, 12])
  assert.equal(h.timers.size, 1, '第二次 schedule 应重置同一个定时器，而不是再排一个')
  assert.equal(h.calls.length, 0, '防抖期内不发请求')
  await h.timers.fire()
  assert.equal(h.calls.length, 1)
  assert.deepEqual(h.calls[0].ids, [11, 12])
})

test('已拿到计数的 id 不再重复请求；reload 后才重新全量请求', async () => {
  const h = harness({ responder: (_, ids) => Object.fromEntries(ids.map(id => [id, 2])) })
  h.fetcher.schedule(1, [11, 12])
  await h.timers.fire()
  assert.equal(h.calls.length, 1)

  h.fetcher.schedule(1, [11, 12, 13]) // 展开更多：只多了 13
  await h.timers.fire()
  assert.equal(h.calls.length, 2)
  assert.deepEqual(h.calls[1].ids, [13])

  h.fetcher.schedule(1, [11, 12, 13]) // 没有新 id：连定时器都不该排
  assert.equal(h.timers.size, 0)

  h.fetcher.schedule(1, [11, 12, 13], { reload: true })
  await h.timers.fire()
  assert.equal(h.calls.length, 4, 'reload 后全量重拉，batchSize=2 分两批')
  assert.deepEqual(h.calls[2].ids, [11, 12])
  assert.deepEqual(h.calls[3].ids, [13])
})

test('响应里缺失的 id 视为 0，覆盖旧值（只增不减的角标是错的）', async () => {
  let round = 0
  const h = harness({ responder: (_, ids) => (round++ === 0 ? { 11: 3, 12: 1 } : { 12: 1 }) })
  h.fetcher.schedule(1, [11, 12])
  await h.timers.fire()
  assert.deepEqual(h.applied[0], { 11: 3, 12: 1 })

  h.fetcher.schedule(1, [11, 12], { reload: true })
  await h.timers.fire()
  assert.deepEqual(h.applied[1], { 11: 0, 12: 1 }, '11 这次没回来，必须显式写 0 把旧的 3 盖掉')
})

test('reload / 切项目后丢弃在途的过期响应', async () => {
  const h = harness()
  h.fetcher.schedule(1, [11])
  await h.timers.fire()
  assert.equal(h.calls.length, 1)

  h.fetcher.schedule(2, [21], { reload: true }) // 切到项目 2
  h.resolveNext({ 11: 9 })                       // 项目 1 的响应这时才回来
  await new Promise(r => setImmediate(r))
  assert.equal(h.applied.length, 0, '过期响应不得写进角标')

  await h.timers.fire()
  assert.equal(h.calls.length, 2)
  assert.equal(h.calls[1].projectId, 2)
  h.resolveNext({ 21: 4 })
  await new Promise(r => setImmediate(r))
  assert.deepEqual(h.applied, [{ 21: 4 }])
})

test('分批拉取，某批失败只放开那一批，下次能重试', async () => {
  let n = 0
  const h = harness({ responder: (_, ids) => { if (++n === 1) throw new Error('boom'); return Object.fromEntries(ids.map(id => [id, 1])) } })
  h.fetcher.schedule(1, [11, 12, 13])
  await h.timers.fire()
  assert.equal(h.calls.length, 2, 'batchSize=2：三个 id 分两批')
  assert.deepEqual(h.applied, [{ 13: 1 }])

  h.fetcher.schedule(1, [11, 12, 13])
  await h.timers.fire()
  assert.deepEqual(h.calls[2].ids, [11, 12], '失败的那批要重发，成功过的 13 不重发')
})

test('兼容 {code, data} 信封', async () => {
  const h = harness({ responder: () => ({ code: 0, data: { 11: 5 } }) })
  h.fetcher.schedule(1, [11])
  await h.timers.fire()
  assert.deepEqual(h.applied, [{ 11: 5 }])
})
