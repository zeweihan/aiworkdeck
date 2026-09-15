// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「放进团队案件库」选哪条连接（src/utils/cloudShare.js）。
//
// 这段判定由 CollabDialog 与 InviteMemberDialog 共用。选错的后果不是界面难看：
// 拿列表第一条会带着失效令牌去推一个早已不在的服务器，静默改推官方会把客户材料
// 推去用户没选的地方——所以多于一条时必须**一次 share 都不许调**。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { pickShareConnectionId, shareProjectToLibrary, TOO_MANY_LIBRARIES } from '../../src/utils/cloudShare.js'

const recorder = () => {
  const calls = []
  const share = async (projectId, connectionId) => { calls.push([projectId, connectionId]) }
  return { calls, share }
}

test('0 条连接：不传 connectionId（回 null），由后端连官方案件库再共享', async () => {
  assert.deepEqual(pickShareConnectionId([]), { ok: true, connectionId: null })
  const { calls, share } = recorder()
  const res = await shareProjectToLibrary({ projectId: 235, connections: [], share })
  assert.deepEqual(res, { ok: true, connectionId: null })
  assert.deepEqual(calls, [[235, null]])
})

test('恰好 1 条连接：指名用它，省掉一次重新桥接', async () => {
  assert.deepEqual(pickShareConnectionId([{ id: 7 }]), { ok: true, connectionId: 7 })
  const { calls, share } = recorder()
  const res = await shareProjectToLibrary({ projectId: 235, connections: [{ id: 7 }], share })
  assert.deepEqual(res, { ok: true, connectionId: 7 })
  assert.deepEqual(calls, [[235, 7]])
})

test('多于 1 条：拒绝，且一次 share 都不调', async () => {
  assert.deepEqual(pickShareConnectionId([{ id: 7 }, { id: 8 }]), { ok: false, reason: TOO_MANY_LIBRARIES })
  const { calls, share } = recorder()
  const res = await shareProjectToLibrary({ projectId: 235, connections: [{ id: 7 }, { id: 8 }], share })
  assert.equal(res.ok, false)
  assert.equal(res.reason, TOO_MANY_LIBRARIES)
  assert.deepEqual(calls, [], '被拒绝的这一次绝不许发出 share 请求')
})

test('connections 不是数组（读取失败退成 undefined/null）时按 0 条处理', async () => {
  assert.deepEqual(pickShareConnectionId(undefined), { ok: true, connectionId: null })
  assert.deepEqual(pickShareConnectionId(null), { ok: true, connectionId: null })
})

test('share 自己抛的错原样往外抛，由调用方就地显示', async () => {
  const boom = async () => { throw new Error('没能放进案件库') }
  await assert.rejects(
    () => shareProjectToLibrary({ projectId: 235, connections: [], share: boom }),
    /没能放进案件库/,
  )
})
