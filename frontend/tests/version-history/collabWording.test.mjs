// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「同事交了新稿」的三态文案（dev-board#623）。跑法：node --test tests/version-history/*.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { remoteAheadText } from '../../src/utils/collabWording.js'

// 假 $t：把键与参数原样拼出来，断言的是「挑了哪个键、喂了什么参数」
const t = (key, params) => `${key}(${JSON.stringify(params || {})})`

test('全部是本人在另一台电脑交的：说「你」，不说「同事」', () => {
  const out = remoteAheadText(t, {
    remoteAhead: true, remoteAheadCount: 2, remoteAheadBySelf: true, remoteAheadAuthors: ['韩泽伟'],
  })
  assert.equal(out, 'version.remoteAheadSelf({"count":2})')
})

test('一个同事：带名字与版数', () => {
  const out = remoteAheadText(t, {
    remoteAhead: true, remoteAheadCount: 1, remoteAheadBySelf: false, remoteAheadAuthors: ['张三'],
  })
  assert.equal(out, 'version.remoteAheadOne({"name":"张三","count":1})')
})

test('多个同事：「张三等 2 人」', () => {
  const out = remoteAheadText(t, {
    remoteAhead: true, remoteAheadCount: 5, remoteAheadBySelf: false, remoteAheadAuthors: ['张三', '李四'],
  })
  assert.equal(out, 'version.remoteAheadMany({"name":"张三","people":2,"count":5})')
})

test('超过 3 个人：「等 N 人」的 N 用 remoteAheadAuthorCount，不是名单长度', () => {
  const out = remoteAheadText(t, {
    remoteAhead: true, remoteAheadCount: 9, remoteAheadBySelf: false,
    remoteAheadAuthors: ['张三', '李四', '王五'], remoteAheadAuthorCount: 5,
  })
  assert.equal(out, 'version.remoteAheadMany({"name":"张三","people":5,"count":9})')
})

test('老服务端不回 remoteAheadAuthorCount：退回名单长度，不说成 0 人', () => {
  const out = remoteAheadText(t, {
    remoteAhead: true, remoteAheadCount: 5, remoteAheadBySelf: false,
    remoteAheadAuthors: ['张三', '李四'],
  })
  assert.equal(out, 'version.remoteAheadMany({"name":"张三","people":2,"count":5})')
})

test('只有一个作者时 remoteAheadAuthorCount=1：仍走单人那句', () => {
  const out = remoteAheadText(t, {
    remoteAhead: true, remoteAheadCount: 3, remoteAheadBySelf: false,
    remoteAheadAuthors: ['张三'], remoteAheadAuthorCount: 1,
  })
  assert.equal(out, 'version.remoteAheadOne({"name":"张三","count":3})')
})

test('老服务端不回 remoteAheadCount：落回调用方给的那句老文案，不编一个「· 0 版」', () => {
  assert.equal(
    remoteAheadText(t, { remoteAhead: true }, { fallbackKey: 'workbench.collabRemoteAhead' }),
    'workbench.collabRemoteAhead({})'
  )
})

test('有版数但一个作者名都算不出来时，同样落回老文案', () => {
  assert.equal(
    remoteAheadText(t, { remoteAhead: true, remoteAheadCount: 3, remoteAheadAuthors: [] }),
    'version.colleagueSubmittedNew({})'
  )
  assert.equal(
    remoteAheadText(t, { remoteAhead: true, remoteAheadCount: 3, remoteAheadAuthors: ['  ', null] }),
    'version.colleagueSubmittedNew({})'
  )
})

test('没领先时也给得出一句话（调用方一律在 remoteAhead 分支里调）', () => {
  assert.equal(remoteAheadText(t, null), 'version.colleagueSubmittedNew({})')
  assert.equal(remoteAheadText(t, {}), 'version.colleagueSubmittedNew({})')
})

test('bySelf 优先于作者名单：本人的另一台电脑不说成「同事」', () => {
  const out = remoteAheadText(t, {
    remoteAhead: true, remoteAheadCount: 4, remoteAheadBySelf: true, remoteAheadAuthors: ['韩泽伟', '韩泽伟'],
  })
  assert.equal(out, 'version.remoteAheadSelf({"count":4})')
})
