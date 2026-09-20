// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// utils/inlineReviewGrouping.js：客体页浮球与宿主「审校」面板共用的判据。
// 两边各写一份的话，浮球上的数字和面板里的条数会对不上——所以判定只有这一份。
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  REVIEW_BUCKETS, bucketOf, filterByBucket, countByBucket, visibleFindings,
  isFresh, isLocatable, isApplicable,
} from '../../src/utils/inlineReviewGrouping.js'

const LIST = [
  { id: 'p', kind: 'PLACEHOLDER' },
  { id: 'b', kind: 'BLANK' },
  { id: 'c', kind: 'COUNT_MISMATCH' },
  { id: 'n', kind: 'NUMBERING' },
  { id: 'u', kind: 'USCC_INVALID' },
  { id: 'a', kind: 'LOGIC_REVIEW' },
]

test('五个分类的口径与后端 kind 对齐，认不出的落一致性而不是丢掉', () => {
  assert.deepEqual(REVIEW_BUCKETS, ['all', 'supplement', 'consistency', 'format', 'ai'])
  assert.equal(bucketOf({ kind: 'PLACEHOLDER' }), 'supplement')
  assert.equal(bucketOf({ kind: 'DOCUMENT_NOT_FOUND' }), 'supplement')
  assert.equal(bucketOf({ kind: 'ARITHMETIC' }), 'consistency')
  assert.equal(bucketOf({ kind: 'DANGLING_REFERENCE' }), 'consistency')
  assert.equal(bucketOf({ kind: 'SCRIPT_OUTLIER' }), 'format')
  assert.equal(bucketOf({ kind: 'LOGIC_REVIEW' }), 'ai')
  assert.equal(bucketOf({ kind: 'AI_ANYTHING_NEW' }), 'ai', '将来新增的 AI_* 自动归 AI 审校')
  assert.equal(bucketOf({ kind: '未来的新检查' }), 'consistency')
  assert.equal(bucketOf(null), 'consistency')
})

test('计数按全量算、筛选不改计数；all 就是全部', () => {
  const counts = countByBucket(LIST)
  assert.deepEqual(counts, { all: 6, supplement: 2, consistency: 1, format: 2, ai: 1 })
  assert.equal(filterByBucket(LIST, 'all').length, 6)
  assert.equal(filterByBucket(LIST).length, 6)
  assert.deepEqual(filterByBucket(LIST, 'ai').map((f) => f.id), ['a'])
  assert.deepEqual(filterByBucket(LIST, 'format').map((f) => f.id), ['n', 'u'])
  // 筛完再数，各类的数字与未筛选时一致
  assert.equal(countByBucket(filterByBucket(LIST, 'format')).format, 2)
  assert.deepEqual(countByBucket(null), { all: 0, supplement: 0, consistency: 0, format: 0, ai: 0 })
})

test('忽略过的条目不进列表也不进计数', () => {
  const left = visibleFindings(LIST, ['p', 'a'])
  assert.deepEqual(left.map((f) => f.id), ['b', 'c', 'n', 'u'])
  assert.equal(countByBucket(left).all, 4)
  assert.equal(visibleFindings(LIST, new Set(['b'])).length, 5)
  assert.equal(visibleFindings(LIST, null).length, 6)
  assert.equal(visibleFindings(null, ['p']).length, 0)
})

test('新鲜度：只有「开着 + ready + 有 revision」才算对得上当前正文', () => {
  assert.equal(isFresh({ enabled: true, status: 'ready', revision: 3 }), true)
  assert.equal(isFresh({ enabled: true, status: 'checking', revision: 3 }), false)
  assert.equal(isFresh({ enabled: true, status: 'stale', revision: null }), false)
  assert.equal(isFresh({ enabled: true, status: 'ready', revision: null }), false, '没有版本号就无从围栏')
  assert.equal(isFresh({ enabled: false, status: 'ready', revision: 3 }), false)
  assert.equal(isFresh(null), false)
})

test('能不能定位/采用：坐标齐全才定位，有替换文本且可写才采用', () => {
  const full = { expectedParagraph: '应于30日付款', start: 2, end: 4, replacement: '15' }
  assert.equal(isLocatable(full), true)
  assert.equal(isLocatable({ ...full, start: undefined }), false)
  assert.equal(isLocatable({ ...full, expectedParagraph: undefined }), false)
  assert.equal(isLocatable(null), false)
  const writable = { writable: true }
  assert.equal(isApplicable(full, writable), true)
  assert.equal(isApplicable({ ...full, replacement: undefined }, writable), false, '重复引文被宿主摘掉 replacement 后不给采用')
  assert.equal(isApplicable(full, { writable: false }), false, '只读成员不给采用')
  assert.equal(isApplicable(full, null), false)
})
