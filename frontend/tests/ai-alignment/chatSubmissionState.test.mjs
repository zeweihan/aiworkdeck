// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  beginChatSubmission,
  failChatSubmission,
  receiptChatSubmission,
  shouldClearChatDraft,
  submitChatAttempt,
  submissionFingerprint,
} from '../../src/composables/chatSubmissionState.mjs'

test('failed submit keeps its draft snapshot and reuses the request id on an exact retry', () => {
  const draft = { prompt: 'Continue', contentHtml: 'Continue', fileIds: ['9'], imageKeys: ['paste-1'] }
  const tracker = { failed: null }
  const first = beginChatSubmission(tracker, draft, () => 'request-1')
  failChatSubmission(tracker, first)
  const retry = beginChatSubmission(tracker, { ...draft }, () => 'request-2')

  assert.equal(retry.clientRequestId, 'request-1')
  assert.equal(tracker.failed.clientRequestId, 'request-1')
})

test('receipt is the only transition that permits clearing the matching draft', () => {
  const tracker = { failed: null }
  const attempt = beginChatSubmission(tracker, { prompt: 'Queue me', fileIds: [], imageKeys: [] }, () => 'request-1')
  failChatSubmission(tracker, attempt)
  assert.equal(receiptChatSubmission(tracker, attempt, null), false)
  assert.equal(receiptChatSubmission(tracker, attempt, { status: 'accepted', messageId: 'm-1' }), true)
  assert.equal(tracker.failed, null)
})

test('editing text or attachments creates a new id instead of replaying the old request', () => {
  const tracker = { failed: null }
  const attempt = beginChatSubmission(tracker, { prompt: 'First', fileIds: ['1'], imageKeys: [] }, () => 'request-1')
  failChatSubmission(tracker, attempt)

  const edited = beginChatSubmission(tracker, { prompt: 'Second', fileIds: ['1'], imageKeys: [] }, () => 'request-2')
  const reattached = beginChatSubmission(tracker, { prompt: 'First', fileIds: ['2'], imageKeys: [] }, () => 'request-3')

  assert.equal(edited.clientRequestId, 'request-2')
  assert.equal(reattached.clientRequestId, 'request-3')
  assert.notEqual(submissionFingerprint(edited), submissionFingerprint(reattached))
})

test('double submit while the receipt is pending reuses the in-flight request id', () => {
  const tracker = { failed: null, inflight: {} }
  const draft = { prompt: 'Steer', conversationId: 'c-1', projectId: 'p-1', modelId: 'm-1', mode: 'AGENT' }
  const first = beginChatSubmission(tracker, draft, () => 'request-1')
  const doubleClick = beginChatSubmission(tracker, draft, () => 'request-2')

  assert.equal(first.clientRequestId, 'request-1')
  assert.equal(doubleClick.clientRequestId, 'request-1')
})

test('double Enter while HTTP receipt is held performs one network submission', async () => {
  let release
  const heldReceipt = new Promise((resolve) => { release = resolve })
  let submits = 0
  const attempt = { clientRequestId: 'request-1' }
  const first = submitChatAttempt(attempt, () => {
    submits += 1
    return heldReceipt
  })
  const second = submitChatAttempt(attempt, () => {
    submits += 1
    return heldReceipt
  })

  await Promise.resolve()
  assert.equal(submits, 1)
  assert.equal(first, second)
  release({ status: 'accepted', messageId: 'm-1' })
  assert.deepEqual(await first, { status: 'accepted', messageId: 'm-1' })
})

test('model, conversation, active document, or selected skills changes never reuse a failed id', () => {
  const tracker = { failed: null, inflight: {} }
  const base = {
    prompt: 'Continue', conversationId: 'c-1', projectId: 'p-1', modelId: 'm-1', mode: 'AGENT',
    skillIds: ['legal'], activeContext: { id: 'doc-1', pane: 'left' },
  }
  const first = beginChatSubmission(tracker, base, () => 'request-1')
  failChatSubmission(tracker, first)

  const variants = [
    { conversationId: 'c-2' }, { modelId: 'm-2' }, { activeContext: { id: 'doc-2', pane: 'left' } },
    { skillIds: ['legal', 'research'] },
  ]
  variants.forEach((change, index) => {
    const attempt = beginChatSubmission(tracker, { ...base, ...change }, () => `request-${index + 2}`)
    assert.notEqual(attempt.clientRequestId, 'request-1')
  })
})

test('draft clear compares exact editor HTML and attachments across an in-flight receipt', () => {
  const sent = {
    editorHtml: 'Draft <span data-file-id="9">contract.docx</span>',
    fileIds: ['9'], imageKeys: ['paste-1'], conversationId: 'c-1', prompt: 'Draft',
  }
  assert.equal(shouldClearChatDraft(sent, { ...sent }), true)
  assert.equal(shouldClearChatDraft(sent, { ...sent, editorHtml: sent.editorHtml + ' more' }), false)
  assert.equal(shouldClearChatDraft(sent, { ...sent, imageKeys: ['paste-1', 'paste-2'] }), false)
})
