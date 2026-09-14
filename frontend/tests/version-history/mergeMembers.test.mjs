// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 参与人去重契约（dev-board#625）。跑法：cd frontend && node --test tests/version-history/*.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { mergeMembers, sameMember } from '../../src/utils/mergeMembers.js'

test('本案例：本机 hanzewei 与案件库 awd_hanzewei 同一个 accountId → 1 个人', () => {
  const local = [{ id: 7, userId: 7, username: 'hanzewei', displayName: '韩泽伟', accountId: 'acc-1', role: 'OWNER' }]
  const cloud = [{ id: 31, username: 'awd_hanzewei', displayName: '韩泽伟', accountId: 'acc-1', role: 'ADMIN', joinedAt: '2026-09-01T00:00:00Z' }]
  const out = mergeMembers(local, cloud)
  assert.equal(out.length, 1, '同一个官网账户不能显示成两个人')
  assert.equal(out[0].userId, 7, '保留本机条目（权限判定靠它）')
  assert.equal(out[0].role, 'ADMIN', '角色以案件库为准')
  assert.equal(out[0].joinedAt, '2026-09-01T00:00:00Z')
})

test('accountId 两边都没有时，靠 awd_ 前缀认出同一个人', () => {
  const local = [{ id: 7, userId: 7, username: 'hanzewei', displayName: '韩泽伟' }]
  const cloud = [{ id: 31, username: 'awd_hanzewei', displayName: '韩泽伟', role: 'ADMIN' }]
  const out = mergeMembers(local, cloud)
  assert.equal(out.length, 1)
  assert.equal(out[0].userId, 7)
  assert.equal(out[0].role, 'ADMIN')
})

test('自建多用户服务器：两边就是同一张用户表，username 字面相同即同一人', () => {
  const local = [{ id: 1, userId: 1, username: 'lisi', displayName: '李四', role: 'PARTICIPANT' }]
  const cloud = [{ id: 1, username: 'lisi', displayName: '李四', role: 'READ_ONLY' }]
  const out = mergeMembers(local, cloud)
  assert.equal(out.length, 1)
  assert.equal(out[0].role, 'READ_ONLY')
})

test('真正的两个人不合并，云端那条带 fromCloud 且 userId 抹成 null', () => {
  const local = [{ id: 7, userId: 7, username: 'hanzewei', displayName: '韩泽伟', accountId: 'acc-1' }]
  const cloud = [
    { id: 31, username: 'awd_hanzewei', displayName: '韩泽伟', accountId: 'acc-1' },
    { id: 7, username: 'awd_zhangsan', displayName: '张三', accountId: 'acc-9', role: 'PARTICIPANT' },
  ]
  const out = mergeMembers(local, cloud)
  assert.equal(out.length, 2)
  const zs = out[1]
  assert.equal(zs.displayName, '张三')
  assert.equal(zs.fromCloud, true)
  assert.equal(zs.userId, null, '云端 userId 与本机 user.id 是两个 id 空间，撞上会把别人的角色当成自己的')
  assert.equal(zs.id, 'cloud-7', ':key 不能与本机 id 7 撞号')
})

test('accountId 不同就是两个人，哪怕 username 撞上 awd_ 前缀', () => {
  const local = [{ id: 7, userId: 7, username: 'hanzewei', accountId: 'acc-1', displayName: '韩泽伟' }]
  const cloud = [{ id: 31, username: 'awd_hanzewei', accountId: 'acc-2', displayName: '另一个韩泽伟' }]
  assert.equal(sameMember(local[0], cloud[0]), false)
  assert.equal(mergeMembers(local, cloud).length, 2)
})

test('一条云端条目只能认领一个本机条目（同名同姓不会把两个人吞成一个）', () => {
  const local = [
    { id: 1, userId: 1, username: 'a', displayName: '甲' },
    { id: 2, userId: 2, username: 'a2', displayName: '乙' },
  ]
  const cloud = [{ id: 9, username: 'awd_a', displayName: '甲', role: 'ADMIN' }]
  const out = mergeMembers(local, cloud)
  assert.equal(out.length, 2)
  assert.equal(out[0].role, 'ADMIN')
  assert.equal(out[1].role, undefined)
})

test('云端名单为空/读取失败时原样返回本机名单（不为此报错）', () => {
  const local = [{ id: 1, userId: 1, username: 'a' }]
  assert.deepEqual(mergeMembers(local, []), local)
  assert.deepEqual(mergeMembers(local, null), local)
  assert.deepEqual(mergeMembers(null, null), [])
})

test('没有 username 也没有 accountId 的脏行不会互相误配', () => {
  const local = [{ id: 1, userId: 1, displayName: '无名' }]
  const cloud = [{ id: 2, displayName: '无名' }]
  assert.equal(mergeMembers(local, cloud).length, 2)
})
