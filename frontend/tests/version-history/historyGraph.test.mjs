// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 泳道图排版契约（dev-board#624）。跑法：cd frontend && node --test tests/version-history/*.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { layoutGraph, laneCountOf } from '../../src/utils/historyGraph.js'

const e = (sha, parents = [], extra = {}) => ({ sha, parents, ...extra })
const byS = (rows) => Object.fromEntries(rows.map((r) => [r.sha, r]))

test('一条直线：所有提交都在 0 号泳道，连线全是 straight', () => {
  const rows = layoutGraph([e('c', ['b']), e('b', ['a']), e('a', [])])
  assert.deepEqual(rows.map((r) => r.lane), [0, 0, 0])
  assert.deepEqual(rows[0].connections, [{ fromLane: 0, toLane: 0, kind: 'straight', colorKey: 'mainline' }])
  assert.deepEqual(rows[1].connections, [{ fromLane: 0, toLane: 0, kind: 'straight', colorKey: 'mainline' }])
  // 最早那条没有父提交（或父提交在窗口外）：不画通向空白的线
  assert.deepEqual(rows[2].connections, [])
  assert.equal(laneCountOf(rows), 1)
})

test('分叉：稿与主线各占一条泳道，两条线最后汇进分叉点那一行', () => {
  // 显示序（新→旧）：m2（主线尖端）、d1（稿，比 m1 新）、m1、base
  const rows = layoutGraph([
    e('m2', ['m1'], { refs: [{ type: 'mainline', name: 'mainline' }] }),
    e('d1', ['base'], { refs: [{ type: 'draft', name: '意见书稿' }] }),
    e('m1', ['base']),
    e('base', []),
  ])
  const r = byS(rows)
  assert.equal(r.m2.lane, 0)
  assert.notEqual(r.d1.lane, r.m2.lane, '稿不能压在主线那条泳道上')
  assert.equal(r.m1.lane, 0)
  assert.equal(r.d1.colorKey, 'draft')
  // 两条线都通到 base 那一行，且落在同一条泳道上（= 它们在这里汇合）
  const d1ToBase = r.d1.connections.find((c) => c.fromLane === r.d1.lane)
  const m1ToBase = r.m1.connections.find((c) => c.toLane === r.base.lane)
  assert.equal(d1ToBase.toLane, r.base.lane)
  assert.equal(m1ToBase.toLane, r.base.lane)
  // 后到的那条要拐过去（kind 不是 straight），这就是「汇合」的视觉
  assert.equal(m1ToBase.kind, 'merge')
  assert.equal(laneCountOf(rows), 2)
})

test('合并：双亲提交的第二个双亲另开一条道（fork），回到主线时是 merge', () => {
  //  M(parents a,b) -> a -> b -> base
  const rows = layoutGraph([
    e('M', ['a', 'b']),
    e('a', ['base']),
    e('b', ['base']),
    e('base', []),
  ])
  const r = byS(rows)
  assert.equal(r.M.lane, 0)
  const [first, second] = r.M.connections
  assert.deepEqual({ from: first.fromLane, to: first.toLane, kind: first.kind }, { from: 0, to: 0, kind: 'straight' })
  assert.equal(second.kind, 'fork')
  assert.equal(second.toLane, 1)
  assert.equal(r.a.lane, 0)
  assert.equal(r.b.lane, 1)
  // b 的父提交 base 已被 a 预定在 0 号道 → 拐回去汇合
  assert.deepEqual(r.b.connections, [{ fromLane: 1, toLane: 0, kind: 'merge', colorKey: 'mainline' }])
})

test('双亲跨多行：中间每一行都留一条过路线，线不断', () => {
  //  M(parents a, z) ; a -> b -> c -> z
  const rows = layoutGraph([
    e('M', ['a', 'z']),
    e('a', ['b']),
    e('b', ['c']),
    e('c', ['z']),
    e('z', []),
  ])
  const r = byS(rows)
  assert.equal(r.M.connections.find((c) => c.kind === 'fork').toLane, 1)
  for (const sha of ['a', 'b']) {
    const pass = r[sha].connections.filter((c) => c.fromLane === 1 && c.toLane === 1)
    assert.equal(pass.length, 1, `${sha} 这一行要有 1 号道的过路线`)
  }
  // c 的父提交 z 已经被 M 预定在 1 号道 → 从 0 拐到 1 汇合
  assert.ok(r.c.connections.some((c) => c.fromLane === 0 && c.toLane === 1 && c.kind === 'merge'))
  assert.equal(r.z.lane, 1)
})

test('远端独有的行用案件库色，主线用主线色，稿用稿色，并往下继承', () => {
  const rows = layoutGraph([
    e('r1', ['m1'], { remote: true }),
    e('m1', ['m0'], { refs: [{ type: 'mainline', name: 'mainline' }] }),
    e('m0', []),
  ])
  const r = byS(rows)
  assert.equal(r.r1.colorKey, 'remote')
  assert.equal(r.m1.colorKey, 'mainline')
  assert.equal(r.m0.colorKey, 'mainline', '没有 ref 的行继承泳道颜色')
})

test('父提交落在分页窗口之外时不画线（宁可不画，也不画一条通向空白的线）', () => {
  const rows = layoutGraph([e('only', ['gone-beyond-this-page'])])
  assert.deepEqual(rows[0].connections, [])
  assert.equal(rows[0].lane, 0)
})

test('空输入与脏数据不炸', () => {
  assert.deepEqual(layoutGraph(null), [])
  assert.deepEqual(layoutGraph([]), [])
  assert.deepEqual(layoutGraph([null, { }, e('a')]).map((r) => r.sha), ['a'])
  assert.equal(laneCountOf(null), 1)
})
