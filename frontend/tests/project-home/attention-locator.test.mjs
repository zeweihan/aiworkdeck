// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 待处理定位条（dev-board#663）的判定层：条目从哪来、什么时候消失。
// 可视区判断在 useChatReadingPosition，真 DOM 覆盖在 tests/chat-presentation-ui。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildChatTurns, pendingAttention } from '../../src/components/AgentMessage/chatTurns.mjs'

const user = (id, content) => ({ id, role: 'USER', content })
const assistant = (id, extra = {}) => ({ id, role: 'ASSISTANT', content: '', processes: [], artifacts: [], ...extra })
const locate = (list, options) => pendingAttention(buildChatTurns(list, options))

test('an unanswered question produces an entry that points at the card', () => {
  const found = locate([user('u', '审这份合同'), assistant('a', { question: { text: '按 30 日修订？', answered: false } })])
  assert.deepEqual(found, { index: 1, kind: 'question', count: 1 })
})

test('answering clears the entry, because the card is no longer actionable', () => {
  assert.equal(locate([user('u', '审这份合同'), assistant('a', { question: { text: '按 30 日修订？', answered: true } })]), null)
  // 用户改为直接追问：旧问题卡跟着上一轮留在历史里，不该继续催人回答。
  assert.equal(locate([user('u', 'a'), assistant('a', { question: { text: '?' } }), user('v', '先看付款条款')]), null)
})

test('a draft plan is an approval entry, and approving it clears the bar', () => {
  const withPlan = status => [user('u', '给个方案'), assistant('a', { artifacts: [{ type: 'implementation_plan', status }] })]
  assert.deepEqual(locate(withPlan('draft')), { index: 1, kind: 'approval', count: 1 })
  assert.equal(locate(withPlan('approved')), null)
})

test('nothing is waiting while the turn is still running', () => {
  const list = [user('u', 'a'), assistant('a', { question: { text: '?' }, isStreaming: true })]
  assert.equal(locate(list, { isStreaming: true, runStatus: 'RUNNING' }), null)
})

test('an empty or attention-free conversation yields no entry', () => {
  assert.equal(pendingAttention([]), null)
  assert.equal(locate([user('u', 'a'), assistant('a', { content: '好的' })]), null)
})

// buildChatTurns 只让最新一轮进入 attention，所以真实会话目前恒为 1 条。
// 这条用例钉的是聚合契约本身：一旦判定放宽到历史轮，定位条必须指向最早那条并计数，
// 而不是把用户丢到最后一条、留着前面的卡没人管。
test('with several waiting turns the bar counts them and points at the earliest', () => {
  const turns = [{ attentionIndex: 3, attentionKind: 'question' }, { attentionIndex: 9, attentionKind: 'approval' }]
  assert.deepEqual(pendingAttention(turns), { index: 3, kind: 'question', count: 2 })
})
