// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  applyInboxReceipt,
  applyInboxSnapshot,
  applyInputApplied,
  createInboxState,
  pendingInboxItems,
  removeInboxItem,
  replaceInboxItem,
} from '../../src/composables/agentInboxState.mjs'

const item = (overrides = {}) => ({
  id: 'm-1', message: 'First', displayText: '', submissionMode: 'queue',
  state: 'pending', position: 0, revision: 1, clientRequestId: 'c-1',
  runId: 'r-1', sequence: null, createdAt: '2026-09-10T00:00:00Z',
  updatedAt: '2026-09-10T00:00:00Z', ...overrides,
})

test('a retried accepted receipt updates one pending item instead of duplicating it', () => {
  const state = createInboxState()
  const receipt = { status: 'accepted', messageId: 'm-1', runId: 'r-1', submissionMode: 'steer', state: 'pending' }

  applyInboxReceipt(state, receipt, { message: 'Keep going', displayText: 'Keep going', clientRequestId: 'c-1' })
  applyInboxReceipt(state, receipt, { message: 'Keep going', displayText: 'Keep going', clientRequestId: 'c-1' })

  assert.equal(state.items.length, 1)
  assert.deepEqual(state.items[0], {
    id: 'm-1', message: 'Keep going', displayText: 'Keep going', submissionMode: 'steer',
    state: 'pending', position: 0, revision: 0, clientRequestId: 'c-1', runId: 'r-1',
    sequence: null, createdAt: null, updatedAt: null,
  })
})

test('server snapshots replace optimistic state and pending controls follow server position order', () => {
  const state = createInboxState()
  applyInboxReceipt(state,
    { status: 'accepted', messageId: 'optimistic', runId: 'r-1', submissionMode: 'queue', state: 'pending' },
    { message: 'Old', clientRequestId: 'old' })

  applyInboxSnapshot(state, {
    runId: 'r-2', status: 'RUNNING',
    items: [item({ id: 'm-2', message: 'Second', position: 2 }), item({ id: 'm-1', position: 1 })],
  })

  assert.equal(state.runId, 'r-2')
  assert.equal(state.status, 'RUNNING')
  assert.deepEqual(pendingInboxItems(state).map((x) => x.id), ['m-1', 'm-2'])
  assert.equal(state.items.some((x) => x.id === 'optimistic'), false)
})

test('input_applied accepts one increasing sequence for the active run and rejects replay or stale runs', () => {
  const state = createInboxState()
  applyInboxSnapshot(state, { runId: 'r-2', status: 'RUNNING', items: [item({ runId: 'r-2' })] })

  const first = applyInputApplied(state, {
    messageId: 'm-1', runId: 'r-2', sequence: 7, message: 'First', displayText: 'Shown',
  })
  const replay = applyInputApplied(state, {
    messageId: 'm-1', runId: 'r-2', sequence: 7, message: 'First', displayText: 'Shown',
  })
  const staleRun = applyInputApplied(state, {
    messageId: 'm-old', runId: 'r-1', sequence: 99, message: 'Old', displayText: 'Old',
  })

  assert.equal(first.accepted, true)
  assert.equal(first.item.state, 'applied')
  assert.equal(replay.accepted, false)
  assert.equal(staleRun.accepted, false)
  assert.equal(state.items.length, 1)
  assert.equal(state.items[0].displayText, 'Shown')
})

test('an applied REST snapshot cannot suppress the later render event for the same message', () => {
  const state = createInboxState()
  applyInboxSnapshot(state, {
    runId: 'r-2', status: 'RUNNING', items: [item({ runId: 'r-2', state: 'applied', sequence: 8 })],
  })
  const event = applyInputApplied(state, {
    messageId: 'm-1', runId: 'r-2', sequence: 8, message: 'First', displayText: '',
  })

  assert.equal(event.accepted, true)
})

test('revision-checked edit and delete responses update local queue without reordering peers', () => {
  const state = createInboxState()
  applyInboxSnapshot(state, { runId: 'r-1', status: 'RUNNING', items: [
    item({ id: 'm-1', position: 1 }), item({ id: 'm-2', position: 2, revision: 3 }),
  ] })

  replaceInboxItem(state, item({ id: 'm-2', message: 'Edited', position: 1, revision: 4 }))
  removeInboxItem(state, 'm-1')

  assert.deepEqual(pendingInboxItems(state).map((x) => [x.id, x.message, x.revision]), [
    ['m-2', 'Edited', 4],
  ])
})
