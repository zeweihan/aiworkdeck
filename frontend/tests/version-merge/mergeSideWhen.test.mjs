// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 合并比对稿说明条（MergeReviewTab.vue::whenLine，dev-board#630）的时间格式化。
 *
 * 缺陷 4：sidesLine 曾经把后端给的 ISO 时间戳（'2026-09-14T09:31:36Z'）原样
 * 显示给律师。修法是复用左栏时间线（VersionTimeline.vue::timeOf）与提交历史
 * 标签页同一个格式化函数——src/utils/projectHomeFormat.js 的 formatDateTime，
 * 而不是在 MergeReviewTab 里再拼一份新的日期措辞。这里只断言这个契约：
 * 输出不再是 ISO 的 T…Z 形态。
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { formatDateTime } from '../../src/utils/projectHomeFormat.js'

test('whenLine 用的 formatDateTime：ISO 时间戳不再原样透出（无 T…Z 形态）', () => {
  const out = formatDateTime('2026-09-14T09:31:36Z')
  assert.ok(out, 'formatDateTime 应该能解析后端的 Instant 时间戳')
  assert.doesNotMatch(out, /T\d{2}:\d{2}:\d{2}Z?$/, `不许再是 ISO 原串：${out}`)
  assert.doesNotMatch(out, /\d{4}-\d{2}-\d{2}/, `不许再带 yyyy-MM-dd：${out}`)
})

test('LocalDateTime 串（不带 Z）同样格式化，不炸', () => {
  const out = formatDateTime('2026-09-13T21:58:00')
  assert.ok(out, out)
  assert.doesNotMatch(out, /T\d{2}:\d{2}:\d{2}/, `不许再是 ISO 原串：${out}`)
})

test('坏值/空值不显示 ISO 残渣', () => {
  assert.equal(formatDateTime(''), '')
  assert.equal(formatDateTime(null), '')
  assert.equal(formatDateTime('not-a-date'), '')
})
