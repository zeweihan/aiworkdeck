// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 窗格身份（dev-board#717）回归用例。
 *   node --test office-addin/taskpane/lib/chatSessionIdentity.test.js
 *
 * 跨窗格下发是按「目标窗格当前的 conversationId」推 SSE 的。窗格一换会话
 * （新对话 / 切历史 / 自愈重签 / 换项目），云端登记簿里的那条就过期了，
 * 要等最多 30 秒的下一次心跳才能更正——这段时间里别的窗格往它身上发的命令
 * 全部投进一条没人听的旧会话，白等 30 秒超时。所以会话身份一变就必须
 * 立刻通知心跳补发一次（onIdentityChange）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

const store = new Map()
globalThis.localStorage = {
  getItem: (k) => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => { store.set(k, String(v)) },
  removeItem: (k) => { store.delete(k) }
}
// 会话 ID 存储键按宿主分作用域，打 Word 桩让键落在 word 上（与 chatSession.test.js 同）
globalThis.Word = {}

const {
  activateSession, newConversation, switchConversation, paneId, paneIdentity, onIdentityChange
} = await import('./chatSession.js')

function sseOkResponse() {
  return {
    ok: true,
    status: 200,
    body: { getReader: () => ({ read: () => new Promise(() => {}) }) }
  }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

let issued = 0
const originalFetch = globalThis.fetch
globalThis.fetch = async (url) => {
  const u = String(url)
  if (u.includes('/api/ai/history')) return jsonReply([])
  if (u.endsWith('/api/agent/conversations')) { issued++; return jsonReply({ conversationId: `conv-${issued}` }) }
  if (u.includes('/api/agent/connect/')) return sseOkResponse()
  if (u.includes('/api/ai/models')) return jsonReply({ models: [], defaultModel: '' })
  if (u.includes('/api/skills/list')) return jsonReply([])
  throw new Error(`未预期的请求: ${u}`)
}

async function waitFor(pred) {
  for (let i = 0; i < 200; i++) {
    if (pred()) return
    await new Promise((r) => setTimeout(r, 5))
  }
  throw new Error('等待超时')
}

test('paneIdentity 带出窗格 id、当前会话与项目；会话身份一变就通知', async () => {
  const seen = []
  const off = onIdentityChange((id) => { seen.push(id) })
  // 一个抛错的监听者不能拖垮会话流程，也不能挡住其余监听者
  const offBad = onIdentityChange(() => { throw new Error('boom') })
  try {
    assert.equal(typeof paneId, 'string')
    assert.ok(paneId.length > 0)

    await activateSession({ settings: { serverUrl: 'https://cloud.example', token: 'awdt_t1' }, projectId: '7' })
    assert.deepEqual(paneIdentity(), { paneId, conversationId: 'conv-1', projectId: 7 })
    assert.deepEqual(seen.at(-1), { paneId, conversationId: 'conv-1', projectId: 7 })

    // 新对话：先清空（旧会话已作废）再签发新的，两步都要通知
    const before = seen.length
    newConversation()
    await waitFor(() => paneIdentity().conversationId === 'conv-2')
    const after = seen.slice(before).map((i) => i.conversationId)
    assert.deepEqual(after, [null, 'conv-2'])

    // 切到历史会话
    await switchConversation('conv-old')
    assert.equal(seen.at(-1).conversationId, 'conv-old')
    assert.equal(paneIdentity().conversationId, 'conv-old')

    // 换项目：项目 id 变化本身也是身份变化
    await activateSession({ settings: { serverUrl: 'https://cloud.example', token: 'awdt_t1' }, projectId: '8' })
    assert.deepEqual(seen.at(-1), { paneId, conversationId: 'conv-3', projectId: 8 })
    assert.ok(seen.some((i) => i.projectId === 8 && i.conversationId === null))

    // 身份没变就不重复通知
    const count = seen.length
    await activateSession({ settings: { serverUrl: 'https://cloud.example', token: 'awdt_t1' }, projectId: '8' })
    assert.equal(seen.length, count)

    // 退订后不再收到
    off()
    await switchConversation('conv-old-2')
    assert.equal(seen.length, count)
    assert.equal(paneIdentity().conversationId, 'conv-old-2')
  } finally {
    off()
    offBad()
    // 清掉会话：关连接，免得挂着的读流让进程不退出
    await activateSession({ settings: { serverUrl: '', token: '' }, projectId: '' })
    globalThis.fetch = originalFetch
  }
})

test('没有项目时 projectId 为 null（不把空串当 id 上送）', async () => {
  await activateSession({ settings: { serverUrl: '', token: '' }, projectId: '' })
  assert.equal(paneIdentity().projectId, null)
  assert.equal(paneIdentity().conversationId, null)
})
