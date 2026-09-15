// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 展示名/头像写去哪儿、姓名引导弹不弹（src/utils/identityProfile.js）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  PROFILE_SOURCE, resolveProfileSource,
  shouldPromptNameNudge, withNudgeDismissed, NAME_NUDGE_STORAGE_KEY,
} from '../../src/utils/identityProfile.js'

test('local-mode 且已连接账户 → 写官网', () => {
  assert.equal(resolveProfileSource({ localMode: true, connected: true }), PROFILE_SOURCE.ACCOUNT)
})

test('缺任一条件一律回落本机', () => {
  assert.equal(resolveProfileSource({ localMode: true, connected: false }), PROFILE_SOURCE.LOCAL)
  assert.equal(resolveProfileSource({ localMode: false, connected: true }), PROFILE_SOURCE.LOCAL)
  assert.equal(resolveProfileSource({ localMode: false, connected: false }), PROFILE_SOURCE.LOCAL)
  // 状态还没读回来（undefined / 整个参数缺席）也算不成立：宁可显示成自建服务器那套只读形态，
  // 也不要先画出一个会失败的输入框
  assert.equal(resolveProfileSource({}), PROFILE_SOURCE.LOCAL)
  assert.equal(resolveProfileSource(), PROFILE_SOURCE.LOCAL)
})

test('默认名 + 没打发过 → 弹一次', () => {
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: true, accountId: 'acc_1', dismissedIds: [] }), true)
})

test('已经打发过就不再弹（这条就是「别每次开机都弹」）', () => {
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: true, accountId: 'acc_1', dismissedIds: ['acc_1'] }), false)
  // 换个账户仍然要弹：已读是按 accountId 记的
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: true, accountId: 'acc_2', dismissedIds: ['acc_1'] }), true)
})

test('名字已经填过、或认不出是哪个账户 → 不弹', () => {
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: false, accountId: 'acc_1', dismissedIds: [] }), false)
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: true, accountId: '', dismissedIds: [] }), false)
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: true, dismissedIds: [] }), false)
  assert.equal(shouldPromptNameNudge(), false)
})

test('storage 里是垃圾数据时按「没打发过」算，不炸', () => {
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: true, accountId: 'acc_1', dismissedIds: null }), true)
  assert.equal(shouldPromptNameNudge({ displayNameIsDefault: true, accountId: 'acc_1', dismissedIds: 'acc_1' }), true)
  assert.deepEqual(withNudgeDismissed('nonsense', 'acc_1'), ['acc_1'])
  assert.deepEqual(withNudgeDismissed([null, 'acc_0'], 'acc_1'), ['acc_0', 'acc_1'])
})

test('已读记录追加且不重复、有上限', () => {
  assert.deepEqual(withNudgeDismissed([], 'acc_1'), ['acc_1'])
  assert.deepEqual(withNudgeDismissed(['acc_1'], 'acc_1'), ['acc_1'])
  assert.deepEqual(withNudgeDismissed(['acc_1'], ''), ['acc_1'])
  const many = Array.from({ length: 60 }, (_, i) => `acc_${i}`)
  const next = withNudgeDismissed(many, 'acc_new')
  assert.equal(next.length, 50)
  assert.equal(next[next.length - 1], 'acc_new')
})

test('storage key 是契约的一部分：改了等于把所有人的已读记录清空', () => {
  assert.equal(NAME_NUDGE_STORAGE_KEY, 'awd_name_nudge_dismissed')
})
