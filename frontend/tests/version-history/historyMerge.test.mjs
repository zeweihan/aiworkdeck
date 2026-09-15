// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 提交历史列表的行合并、按日分组与事件行文案（dev-board#624）。
 *
 * 为什么测在这一层而不是组件层：仓里没有 @vue/test-utils，挂不起 .vue；而这三件事
 * （版本行与事件行怎么排、跨零点怎么分日、「谁」说成「你」还是同事）恰恰是最容易
 * 悄悄错的部分，所以它们被抽进 utils/historyRows.js 这个零依赖模块里。
 * 跑法：cd frontend && node --test tests/version-history/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  mergeHistoryRows, groupRowsByDay, dayKeyOf, actorKind, eventRowText,
} from '../../src/utils/historyRows.js'

const t = (key, params) => `${key}(${JSON.stringify(params || {})})`
const ev = (id, kind, extra = {}) => ({ id, kind, createdAt: '2026-09-14T08:00:00Z', ...extra })

// ---------------- 合并与排序 ----------------

test('版本行与事件行按时间倒序合成一条流', () => {
  const rows = mergeHistoryRows(
    [
      { sha: 'new', when: '2026-09-14T10:00:00Z' },
      { sha: 'old', when: '2026-09-12T10:00:00Z' },
    ],
    [ev(1, 'PUSH', { createdAt: '2026-09-13T10:00:00Z' })]
  )
  assert.deepEqual(rows.map((r) => r.key), ['new', 'ev-1', 'old'])
  assert.deepEqual(rows.map((r) => r.kind), ['version', 'event', 'version'])
})

test('同一时刻时版本行排在事件行前面（事件是对刚交上去那几版的旁白）', () => {
  const at = '2026-09-14T10:00:00Z'
  const rows = mergeHistoryRows([{ sha: 'a', when: at }], [ev(1, 'PUSH', { createdAt: at })])
  assert.deepEqual(rows.map((r) => r.key), ['a', 'ev-1'])
})

test('没有 sha / 没有 id 的脏数据不进列表', () => {
  const rows = mergeHistoryRows([{ when: 1 }, null], [{ kind: 'PUSH' }, null])
  assert.deepEqual(rows, [])
  assert.deepEqual(mergeHistoryRows(null, null), [])
})

test('两类行的 key 不会撞（版本用 sha、事件用 ev- 前缀）', () => {
  const rows = mergeHistoryRows([{ sha: '1', when: '2026-09-14T10:00:00Z' }], [ev(1, 'PUSH')])
  assert.equal(new Set(rows.map((r) => r.key)).size, 2)
})

// ---------------- 按日分组 ----------------

test('按本地日期分组，保持倒序', () => {
  const rows = mergeHistoryRows(
    [
      { sha: 'a', when: '2026-09-14T10:00:00Z' },
      { sha: 'b', when: '2026-09-14T02:00:00Z' },
      { sha: 'c', when: '2026-09-12T10:00:00Z' },
    ],
    []
  )
  const groups = groupRowsByDay(rows)
  assert.equal(groups.length >= 2, true)
  assert.equal(groups[0].rows[0].key, 'a')
  assert.equal(groups[groups.length - 1].rows.slice(-1)[0].key, 'c')
  // 组内行数之和 = 总行数（一行都不能掉）
  assert.equal(groups.reduce((n, g) => n + g.rows.length, 0), rows.length)
})

test('dayKeyOf 按本地时区算（用 UTC 算会让跨零点的版本串到前一天）', () => {
  const d = new Date(2026, 8, 14, 23, 30) // 本地 9/14 23:30
  assert.equal(dayKeyOf(d.getTime()), '2026-09-14')
  assert.equal(dayKeyOf('not a date'), '')
})

// ---------------- 「谁」的三态 ----------------

const ctx = { selfUserId: 7, selfTokenId: 'tok-this' }

test('本人 + 本机这枚令牌 = 「你」', () => {
  const e = ev(1, 'PUSH', { actor: { userId: 7, displayName: '韩泽伟' }, device: { tokenId: 'tok-this', name: 'MacBook Pro' } })
  assert.equal(actorKind(e, ctx), 'self')
  const out = eventRowText(t, { ...e, commitCount: 2 }, ctx)
  assert.ok(out.startsWith('version.eventPushedWithCount('), out)
  assert.ok(out.includes('version.actorYou'), out)
  assert.ok(out.includes('"count":2'), out)
})

test('本人 + 别的设备 = 「你（设备名）」，这正是 #623 那个被报成「同事」的场景', () => {
  const e = ev(1, 'PUSH', { actor: { userId: 7, displayName: '韩泽伟' }, device: { tokenId: 'tok-other', name: '办公室台式机' } })
  assert.equal(actorKind(e, ctx), 'selfOtherDevice')
  const out = eventRowText(t, e, ctx)
  assert.ok(out.includes('version.actorYouOnDevice'), out)
  assert.ok(out.includes('办公室台式机'), out)
})

test('本人 + 别的设备但没有设备名 = 「你（另一台电脑）」', () => {
  const e = ev(1, 'CHECKOUT', { actor: { userId: 7, displayName: '韩泽伟' }, device: { tokenId: 'tok-other' } })
  const out = eventRowText(t, e, ctx)
  assert.ok(out.startsWith('version.eventCheckedOut('), out)
  assert.ok(out.includes('version.actorYouOtherDevice'), out)
})

test('别人 = 展示名；**永远不显示 username**', () => {
  const e = ev(1, 'PULLED', { actor: { userId: 9, username: 'u3f8a', displayName: '张三' }, device: { tokenId: 'x' } })
  assert.equal(actorKind(e, ctx), 'other')
  const out = eventRowText(t, e, ctx)
  assert.ok(out.includes('张三'))
  assert.ok(!out.includes('u3f8a'), '用户名是 uid，不是名字')
})

test('展示名缺席时用「未具名的同事」占位，不退回 username', () => {
  const e = ev(1, 'PULLED', { actor: { userId: 9, username: 'u3f8a' } })
  const out = eventRowText(t, e, ctx)
  assert.ok(out.includes('version.unnamedColleague'))
  assert.ok(!out.includes('u3f8a'))
})

// ---------------- 七类事件的文案 ----------------

test('七类事件各自挑对键', () => {
  const actor = { userId: 9, displayName: '张三' }
  const device = { tokenId: 'x', name: 'PC' }
  const cases = [
    [ev(1, 'PUSH', { actor, device }), 'version.eventPushed('],
    [ev(2, 'CHECKOUT', { actor, device }), 'version.eventCheckedOut('],
    [ev(3, 'PULLED', { actor, device }), 'version.eventPulled('],
    [ev(4, 'SHARED', { actor, device }), 'version.eventShared('],
    [ev(5, 'MEMBER_ADDED', { actor, target: { displayName: '李四' } }), 'version.eventMemberAdded('],
    [ev(6, 'MEMBER_REMOVED', { actor, target: { displayName: '李四' } }), 'version.eventMemberRemoved('],
    [ev(7, 'MEMBER_ROLE_CHANGED', { actor, target: { displayName: '李四' }, detail: { role: 'ADMIN' } }), 'version.eventMemberRoleChanged('],
  ]
  for (const [e, expectKey] of cases) {
    assert.ok(eventRowText(t, e, ctx).startsWith(expectKey), `${e.kind} 应该用 ${expectKey}`)
  }
})

test('交稿带版数与不带版数是两句话（没有「· 0 版」这种说法）', () => {
  const base = { actor: { userId: 9, displayName: '张三' }, device: { tokenId: 'x' } }
  assert.ok(eventRowText(t, ev(1, 'PUSH', { ...base, commitCount: 3 }), ctx).startsWith('version.eventPushedWithCount('))
  assert.ok(eventRowText(t, ev(1, 'PUSH', { ...base, commitCount: 0 }), ctx).startsWith('version.eventPushed('))
  assert.ok(eventRowText(t, ev(1, 'PUSH', base), ctx).startsWith('version.eventPushed('))
})

test('改角色那句用的是角色展示名，不是后端枚举值', () => {
  const e = ev(1, 'MEMBER_ROLE_CHANGED', {
    actor: { userId: 9, displayName: '张三' }, target: { displayName: '李四' }, detail: { role: 'ADMIN' },
  })
  const out = eventRowText(t, e, { ...ctx, roleLabel: (r) => (r === 'ADMIN' ? '案件管理员' : r) })
  assert.ok(out.includes('案件管理员'))
  assert.ok(!out.includes('ADMIN'))
})

test('认不出的 kind 返回空串（老/新服务端各自多出来的类型不该渲染成半句话）', () => {
  assert.equal(eventRowText(t, ev(1, 'SOMETHING_NEW', { actor: { userId: 9, displayName: '张三' } }), ctx), '')
  assert.equal(eventRowText(t, {}, ctx), '')
})
