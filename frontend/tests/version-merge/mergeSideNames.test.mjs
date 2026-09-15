// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 合并两侧的称呼与分桶键（utils/mergeSideNames.js）。
 *
 * 桌面端 v0.44.1 真机实测 A3：一个律师用「稿」管对方的回稿时，两侧提交都是他自己签的，
 * 于是合并比对稿的抬头、裁决总览那一行、右栏三选一按钮全都写着「你」——
 *   「你改了 11 处 · 你改了 0 处」、「用你的 / 用你的 / 自己改」。
 * 两条都要修：
 *   ① 称呼在作者相同时退到**线**上（主线 / 稿《对方第三版回稿》），不再两边都叫「你」；
 *   ② 喂给引擎的署名（= 修订分桶键）必须跟着分开，否则两边的修订全归一桶，
 *      块 2 的分组、`X-AWD-Merges` 尾注里的 M/T 一起错。
 * 一个人用「稿」管对方回稿是常见用法，不是边角。
 *
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { resolveMergeSideNames } from '../../src/utils/mergeSideNames.js'

// 假 $t：把真实文案换成键名，断言的是「挑了哪个键」
const t = (key, params) => (params === undefined ? key : `${key}(${JSON.stringify(params)})`)

const bothMe = {
  main: { authorName: '韩泽伟', self: true },
  other: { authorName: '韩泽伟', self: true },
}

test('采纳语境两侧是同一个人：称呼退到主线与稿名，不再两边都叫「你」', () => {
  const n = resolveMergeSideNames(t, bothMe, { mode: 'adopt', draftName: '对方第三版回稿' })
  assert.equal(n.main, 'version.mergeSideMainline')
  assert.equal(n.other, 'version.mergeSideDraftNamed({"name":"对方第三版回稿"})')
  assert.notEqual(n.main, n.other)
  assert.equal(n.byPerson, false)
})

test('同一个人时喂给引擎的署名也跟着分开——分桶键就是它', () => {
  const n = resolveMergeSideNames(t, bothMe, { mode: 'adopt', draftName: '对方第三版回稿' })
  assert.ok(n.mainKey, '主线侧要有署名，空串会让引擎签不上作者')
  assert.ok(n.otherKey)
  assert.notEqual(n.mainKey, n.otherKey, '两侧署名相同 = 修订全归一桶，计数与尾注一起错')
})

test('没有稿名时退回这一稿的通称，仍然与主线分得开', () => {
  const n = resolveMergeSideNames(t, bothMe, { mode: 'adopt' })
  assert.equal(n.main, 'version.mergeSideMainline')
  assert.equal(n.other, 'version.mergeSideDraft')
  assert.notEqual(n.mainKey, n.otherKey)
})

test('两个人时一个字都不变：本人说「你」、对方说展示名，署名仍是真名', () => {
  const n = resolveMergeSideNames(t, {
    main: { authorName: '韩泽伟', self: true },
    other: { authorName: '律师乙', self: false },
  }, { mode: 'adopt', draftName: '试验稿' })
  assert.equal(n.main, 'version.actorYou')
  assert.equal(n.other, '律师乙')
  assert.equal(n.byPerson, true)
  assert.equal(n.mainKey, '韩泽伟', '引擎署名必须还是真名，否则修订作者归类跟着变')
  assert.equal(n.otherKey, '律师乙')
})

test('两个同事碰巧同名（单机模式下人人都叫「本机用户」）也按线分', () => {
  const n = resolveMergeSideNames(t, {
    main: { authorName: '本机用户', self: false },
    other: { authorName: '本机用户', self: false },
  }, { mode: 'cloud' })
  assert.equal(n.main, 'version.mergeSideYours')
  assert.equal(n.other, 'version.mergeSideLibrary')
  assert.notEqual(n.mainKey, n.otherKey)
})

test('取回语境（同一个账号在另一台电脑上交了稿）按「你这边 / 案件库那边」分', () => {
  const n = resolveMergeSideNames(t, bothMe, { mode: 'cloud' })
  assert.equal(n.main, 'version.mergeSideYours')
  assert.equal(n.other, 'version.mergeSideLibrary')
})

test('结束工作撞车语境也有各自的称呼，MAIN 在这里是对方那边', () => {
  const n = resolveMergeSideNames(t, bothMe, { mode: 'session-end' })
  assert.equal(n.main, 'version.mergeSideColleague')
  assert.equal(n.other, 'version.mergeSideYou')
  assert.notEqual(n.mainKey, n.otherKey)
})

test('sides 整个缺席（老服务端）也给得出两个分得开的称呼，不回空串', () => {
  const n = resolveMergeSideNames(t, undefined, {})
  assert.ok(n.main)
  assert.ok(n.other)
  assert.notEqual(n.main, n.other)
  assert.notEqual(n.mainKey, n.otherKey)
})

test('只有一侧有署名时也按线分——「律师甲 / 另一边」这种半截称呼读不出是两条线', () => {
  const n = resolveMergeSideNames(t, {
    main: { authorName: '律师甲', self: false },
    other: null,
  }, { mode: 'adopt', draftName: '试验稿' })
  assert.equal(n.byPerson, false)
  assert.equal(n.main, 'version.mergeSideMainline')
  assert.equal(n.other, 'version.mergeSideDraftNamed({"name":"试验稿"})')
})
