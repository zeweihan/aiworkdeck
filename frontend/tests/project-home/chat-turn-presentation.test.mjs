// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildChatTurns, recoverPlanTodos } from '../../src/components/AgentMessage/chatTurns.mjs'
const user = (id, content) => ({ id, role: 'USER', content })
const assistant = (id, extra = {}) => ({ id, role: 'ASSISTANT', content: '', processes: [], artifacts: [], ...extra })
test('one user turn owns multiple assistant messages, preserving global navigation indices', () => {
  const list = [user('u', 'Review'), assistant('a', { processes: [{ id: 'p' }] }), assistant('b', { content: 'Answer', question: { text: 'Confirm?' } }), user('u2', 'Next')]
  const turns = buildChatTurns(list, { isStreaming: true, runStatus: 'RUNNING' })
  assert.equal(turns.length, 2)
  assert.equal(turns[0].assistants.length, 2)
  assert.equal(turns[0].answerIndex, 2)
  assert.equal(turns[0].attentionIndex, -1, 'old question must not remain actionable')
  assert.equal(turns[1].status, 'running')
  assert.equal(turns[0].processes.length, 1)
})
test('tasks carry across continuation turns until an explicit snapshot replaces them', () => {
  const todos = [{ content: 'Review', status: 'in_progress' }]
  const list = [user('u', 'A'), assistant('a', { planTodos: todos }), user('v', 'Continue'), assistant('b')]
  assert.deepEqual(buildChatTurns(list)[1].todos, todos)
  list.push(assistant('c', { planTodos: [{ content: 'Write', status: 'pending' }] }))
  assert.equal(buildChatTurns(list)[1].todos[0].content, 'Write')
})
test('thinking includes nested segments, without creating a disclosure for empty waiting time', () => {
  const turns = buildChatTurns([assistant('a', { thinking: { content: '', status: 'thinking' }, processes: [{ items: [{ type: 'thinking', content: 'Summary', status: 'done' }] }] })])
  assert.equal(turns[0].thoughts.length, 1)
  assert.equal(turns[0].thoughts[0].content, 'Summary')
})
test('explicit stop/error/wait states outrank a partial answer; unknown history is not called successful', () => {
  for (const status of ['ERROR', 'CANCELLED', 'INTERRUPTED', 'AWAITING_INPUT', 'AWAITING_APPROVAL', 'PAUSED']) {
    const turns = buildChatTurns([user('u', 'a'), assistant('a', { content: 'Partial' })], { runStatus: status })
    assert.equal(turns[0].status, status.toLowerCase())
  }
  assert.equal(buildChatTurns([assistant('a')])[0].status, 'idle')
})
test('pending question and approval point to the actionable final bubble even without an answer', () => {
  for (const extra of [{ question: { text: '?' } }, { artifacts: [{ type: 'plan', status: 'draft' }] }]) {
    const turn = buildChatTurns([user('u', 'a'), assistant('a', extra)])[0]
    assert.equal(turn.attentionIndex, 1)
    assert.equal(turn.answerIndex, -1)
  }
})
test('recover only successful JSON todo_write calls, preserve explicit clear and reject malformed input', () => {
  const call = (todos, status = 'success') => ({ items: [{ type: 'tool', status, code: `todo_write(${JSON.stringify({ todos: JSON.stringify(todos) })})` }] })
  const todos = [{ content: 'Read', status: 'completed' }]
  assert.deepEqual(recoverPlanTodos([call(todos)]), todos)
  assert.deepEqual(recoverPlanTodos([call(todos), call([])]), [])
  assert.deepEqual(recoverPlanTodos([call(todos), call([], 'error')]), todos)
  assert.equal(recoverPlanTodos([{ items: [{ type: 'tool', status: 'success', code: 'todo_write(not JSON)' }] }]), null)
})

test('pending interjection stays waiting while the preceding assistant continues to run', () => {
  const turns = buildChatTurns([user('u', 'Review'), assistant('a', { isStreaming: true }), { ...user('v', 'Focus on payment'), receiptState: 'pending' }], { isStreaming: true, runStatus: 'RUNNING' })
  assert.equal(turns[0].status, 'running')
  assert.equal(turns[1].status, 'queued')
})

test('an error belongs to the active turn, not the still-pending interjection', () => {
  const turns = buildChatTurns([user('u', 'Review'), assistant('a', { content: 'Partial reply' }), { ...user('v', 'Focus on payment'), receiptState: 'pending' }], { runStatus: 'ERROR' })
  assert.equal(turns[0].status, 'error')
  assert.equal(turns[1].status, 'queued')
})
