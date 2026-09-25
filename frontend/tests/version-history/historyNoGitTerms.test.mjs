// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-56（v0.49.0 真机）：「完整历史」页把 Git 露了出来——标签叫「提交历史」、
// 行徽标「主线」、每行一串 7 位短哈希。版本面板零 Git 术语是产品红线（version-control.md 地雷 #6，
// 「主线」是 trunk 的直译，也算）。跑法：node --test tests/version-history/*.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/version.js'
import en from '../../src/locales/en-US/version.js'

const read = (rel) => readFileSync(new URL(rel, import.meta.url), 'utf8')

const ZH_BANNED = /主线|分支|提交|哈希|git|commit/i
const EN_BANNED = /mainline|main line|branch|commit|hash|\bgit\b/i

test('版本记录的中文文案一个 Git 词都不出现（含「主线」「提交」）', () => {
  const leaks = Object.entries(zh).filter(([, v]) => typeof v === 'string' && ZH_BANNED.test(v))
  assert.deepEqual(leaks, [])
})

test('版本记录的英文文案同样不出现 mainline / branch / commit / hash', () => {
  const leaks = Object.entries(en).filter(([, v]) => typeof v === 'string' && EN_BANNED.test(v))
  assert.deepEqual(leaks, [])
})

test('完整历史标签名与版本面板上的入口同名', () => {
  assert.equal(zh.historyTabName, zh.openFullHistory)
})

test('完整历史页不向律师展示短哈希', () => {
  const src = read('../../src/components/version/CommitHistoryTab.vue')
  const template = src.slice(src.indexOf('<template>'), src.indexOf('<script'))
  assert.doesNotMatch(template, /shortId|sha\.slice/)
  // 对比两版时的「A → B」与对比标签页两侧标题也不许退回哈希
  assert.doesNotMatch(src, /slice\(0,\s*7\)/)
})
