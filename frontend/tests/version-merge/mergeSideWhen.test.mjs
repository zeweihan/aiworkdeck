// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 合并比对稿说明条（MergeReviewTab.vue::whenLine，dev-board#630）的时间格式化。
 *
 * 缺陷 4：sidesLine 曾经把后端给的 ISO 时间戳（'2026-09-14T09:31:36Z'）原样
 * 显示给律师。修法是复用左栏时间线（VersionTimeline.vue::timeOf）与提交历史
 * 标签页同一个格式化函数——src/utils/projectHomeFormat.js 的 formatDateTime，
 * 而不是在 MergeReviewTab 里再拼一份新的日期措辞。
 *
 * BUG-64（2026-09 真机测试批次 P）：formatDateTime 的输出后来又改成了中文自然语言
 * 「8 月 8 日 10:11」，与项目概览头部的 ISO 字段「2026-09-25」在同一屏幕混用。
 * 这次统一改回 yyyy-MM-dd HH:mm 这种数字格式（与头部一致），所以这里改成断言
 * 「不再是带 T/Z 的原始 ISO 串，而是 yyyy-MM-dd HH:mm 这个形状」，不再禁止 yyyy-MM-dd。
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { formatDateTime } from '../../src/utils/projectHomeFormat.js'

test('whenLine 用的 formatDateTime：ISO 时间戳不再原样透出（无 T…Z 形态），统一成 yyyy-MM-dd HH:mm', () => {
  const out = formatDateTime('2026-09-14T09:31:36Z')
  assert.ok(out, 'formatDateTime 应该能解析后端的 Instant 时间戳')
  assert.doesNotMatch(out, /T\d{2}:\d{2}:\d{2}Z?$/, `不许再是 ISO 原串：${out}`)
  assert.match(out, /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$/, `应是 yyyy-MM-dd HH:mm：${out}`)
})

test('LocalDateTime 串（不带 Z）同样格式化，不炸', () => {
  const out = formatDateTime('2026-09-13T21:58:00')
  assert.equal(out, '2026-09-13 21:58')
})

test('坏值/空值不显示 ISO 残渣', () => {
  assert.equal(formatDateTime(''), '')
  assert.equal(formatDateTime(null), '')
  assert.equal(formatDateTime('not-a-date'), '')
})
