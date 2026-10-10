// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { trialBalanceText } from '../../src/utils/trialBalanceText.js'
import zh from '../../src/locales/zh-CN/account.js'
import en from '../../src/locales/en-US/account.js'

const tr = (dict) => (key, vars = {}) => dict[key.replace('account.', '')].replace(/\{(\w+)\}/g, (_, k) => String(vars[k]))

test('active: shows remaining days and turns from the server (zh/en)', () => {
  const d = { connected: true, available: true, status: 'active', remainingDays: 9, remainingCalls: 61 }
  assert.equal(trialBalanceText(d, tr(zh)), '试用中 · 剩余 9 天 · 剩余 61 次 AI 调用')
  assert.equal(trialBalanceText(d, tr(en)), 'Trial · 9 days left · 61 AI turns left')
})

test('not started / exhausted / expired', () => {
  assert.equal(trialBalanceText({ connected: true, status: 'none', remainingDays: 14, remainingCalls: 80 }, tr(zh)), '试用未开始 · 剩余 14 天 / 80 次')
  assert.equal(trialBalanceText({ connected: true, status: 'exhausted_calls', remainingDays: 5, remainingCalls: 0 }, tr(zh)), '试用 AI 调用次数已用完')
  assert.equal(trialBalanceText({ connected: true, status: 'expired_days', remainingDays: 0, remainingCalls: 12 }, tr(en)), 'Trial ended')
})

test('hidden when disconnected, failed, converted or numbers missing; stale is marked', () => {
  assert.equal(trialBalanceText(null, tr(zh)), '')
  assert.equal(trialBalanceText({ connected: false }, tr(zh)), '')
  assert.equal(trialBalanceText({ connected: true, status: 'converted' }, tr(zh)), '')
  assert.equal(trialBalanceText({ connected: true, status: 'active' }, tr(zh)), '')
  assert.match(trialBalanceText({ connected: true, available: false, stale: true, status: 'active', remainingDays: 3, remainingCalls: 2 }, tr(zh)), /离线/)
})

test('terms copy is only 以协议为准', () => {
  assert.equal(zh.trialTerms, '以协议为准')
})
