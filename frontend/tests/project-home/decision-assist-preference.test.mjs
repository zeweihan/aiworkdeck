// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { createDecisionAssistState, decisionAssistPreferenceKey, decisionAssistUser, readDecisionAssistPreference, writeDecisionAssistPreference } from '../../src/utils/decisionAssistPreference.js'
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

const memoryStorage = () => {
  const values = new Map()
  return { values, getStorageSync: k => values.get(k), setStorageSync: (k, v) => values.set(k, v) }
}

test('desktop local-mode: no checkba_user, the server-resolved user still yields a stable key (dev-board#877)', () => {
  const storage = memoryStorage()
  const resolved = { id: 7, displayName: '本机律师' }
  assert.equal(decisionAssistUser(null, resolved), resolved)
  assert.equal(decisionAssistUser('', resolved), resolved)
  assert.equal(decisionAssistUser({ id: 1 }, resolved).id, 1, 'browser login cache still wins')
  assert.equal(decisionAssistUser(null, { id: '  ' }), null)
  const key = decisionAssistPreferenceKey(decisionAssistUser(null, resolved), 'http://127.0.0.1:5269')
  assert.equal(key, 'awd_decision_assist:http%3A%2F%2F127.0.0.1%3A5269:7')
  const state = createDecisionAssistState({ storage, identity: () => key })
  assert.equal(state.enabled, false)
  assert.equal(state.toggle(), true)
  assert.equal(readDecisionAssistPreference(storage, key), true)
  const reopened = createDecisionAssistState({ storage, identity: () => key })
  assert.equal(reopened.enabled, true)
})

test('a click flips the switch even before the identity resolves, then the first identity adopts it', () => {
  const storage = memoryStorage()
  let id = null
  const seen = []
  const state = createDecisionAssistState({ storage, identity: () => id, onChange: v => seen.push(v) })
  assert.equal(state.enabled, false)
  assert.equal(state.toggle(), true, 'no silent return while identity is unknown')
  assert.deepEqual(seen, [true])
  assert.equal(state.sync(true), true, 'focus reload must not wipe the in-memory choice')
  assert.equal(state.ownerStillCurrent(null), true)
  id = 'awd_decision_assist:srv:7'
  assert.equal(state.sync(), true)
  assert.equal(state.owner, id)
  assert.equal(readDecisionAssistPreference(storage, id), true, 'persisted once the identity arrives')
  assert.equal(state.ownerStillCurrent(null), true, 'a pre-resolution snapshot belongs to the adopting identity')
  id = 'awd_decision_assist:srv:8'
  assert.equal(state.sync(), false, 'another account never inherits consent')
  assert.equal(state.ownerStillCurrent(null), false)
  assert.equal(state.ownerStillCurrent('awd_decision_assist:srv:7'), false)
})

test('each resolved identity keeps its own stored choice', () => {
  const storage = memoryStorage()
  let id = 'awd_decision_assist:srv:7'
  const state = createDecisionAssistState({ storage, identity: () => id })
  state.toggle()
  id = 'awd_decision_assist:srv:8'
  assert.equal(state.sync(), false)
  assert.equal(state.toggle(), true)
  assert.equal(readDecisionAssistPreference(storage, 'awd_decision_assist:srv:8'), true)
  id = 'awd_decision_assist:srv:7'
  assert.equal(state.sync(), true)
})

// BUG-25（v0.49.0 真机测试 C2-01）：常驻提示里写「无 Jev 请求或费用」，律师用户不认识 Jev。
// 服务名只在展开的「数据与费用说明」里作为数据去向点名一次（隐私告示要求），且要带解释。
test('智能决策辅助的常驻提示不暴露服务名 Jev，说明里点名时带上「工具预选」解释', async () => {
  for (const locale of ['zh-CN', 'en-US']) {
    const { default: chat } = await import(`../../src/locales/${locale}/chat.js`)
    for (const key of ['decisionAssistOnHint', 'decisionAssistOffHint', 'decisionAssistMaterials', 'decisionAssistLocal']) {
      assert.ok(!/Jev/.test(chat[key]), `${locale} ${key} 仍写着 Jev: ${chat[key]}`)
    }
    assert.match(chat.decisionAssistData, locale === 'zh-CN' ? /工具预选.*TypeSafe Jev/ : /tool-preselection.*TypeSafe Jev/)
  }
})
