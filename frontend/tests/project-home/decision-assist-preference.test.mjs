// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { decisionAssistPreferenceKey, readDecisionAssistPreference, writeDecisionAssistPreference } from '../../src/utils/decisionAssistPreference.js'
import { beginChatSubmission, failChatSubmission } from '../../src/composables/chatSubmissionState.mjs'

test('Jev consent defaults off and is isolated by identity and server', () => {
  const values = new Map()
  const storage = { getStorageSync: k => values.get(k), setStorageSync: (k, v) => values.set(k, v) }
  const alice = decisionAssistPreferenceKey({ id: 1 }, 'https://one.test')
  const bob = decisionAssistPreferenceKey({ id: 2 }, 'https://one.test')
  const otherServer = decisionAssistPreferenceKey({ id: 1 }, 'https://two.test')
  assert.equal(readDecisionAssistPreference(storage, alice), false)
  writeDecisionAssistPreference(storage, alice, true)
  assert.equal(readDecisionAssistPreference(storage, alice), true)
  assert.equal(readDecisionAssistPreference(storage, bob), false)
  assert.equal(readDecisionAssistPreference(storage, otherServer), false)
  assert.equal(decisionAssistPreferenceKey(null), null)
  assert.equal(readDecisionAssistPreference(storage, null), false)
  for (const invalid of ['true', 'false', 1, {}, null]) {
    values.set(alice, invalid)
    assert.equal(readDecisionAssistPreference(storage, alice), false)
  }
  writeDecisionAssistPreference(storage, alice, 'true')
  assert.equal(readDecisionAssistPreference(storage, alice), false)
})

test('unavailable storage never enables remote decisions', () => {
  const broken = { getStorageSync() { throw new Error('unavailable') }, setStorageSync() { throw new Error('unavailable') } }
  assert.equal(readDecisionAssistPreference(broken, 'user'), false)
  assert.doesNotThrow(() => writeDecisionAssistPreference(broken, 'user', true))
})

test('unchanged retries keep their snapshot; a switch or identity change gets a new request', () => {
  let n = 0
  const createId = () => String(++n)
  const tracker = {}
  const draft = { prompt: '合成问题', decisionAssistEnabled: true, decisionAssistOwner: 'alice' }
  const original = beginChatSubmission(tracker, draft, createId)
  failChatSubmission(tracker, original)
  const retry = beginChatSubmission(tracker, { ...draft }, createId)
  assert.equal(retry.clientRequestId, original.clientRequestId)
  assert.equal(retry.decisionAssistEnabled, true)
  const switchedOff = beginChatSubmission(tracker, { ...draft, decisionAssistEnabled: false }, createId)
  assert.notEqual(switchedOff.clientRequestId, original.clientRequestId)
  assert.equal(switchedOff.decisionAssistEnabled, false)
  const changedUser = beginChatSubmission(tracker, { ...draft, decisionAssistOwner: 'bob' }, createId)
  assert.notEqual(changedUser.clientRequestId, original.clientRequestId)
})
