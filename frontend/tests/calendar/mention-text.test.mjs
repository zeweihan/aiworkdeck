// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// MentionInput 的纯文本逻辑（components/calendar/mentionText.js，dev-board#896）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { extractMentionQuery, applyMention, matchMembers, memberName } from '../../src/components/calendar/mentionText.js'

test('extractMentionQuery：行首或空白后的 @ 开始收集，光标前的查询词', () => {
  assert.deepEqual(extractMentionQuery('@', 1), { query: '', start: 0 })
  assert.deepEqual(extractMentionQuery('提交 @起诉', 6), { query: '起诉', start: 3 })
  assert.deepEqual(extractMentionQuery('a\n@韩', 4), { query: '韩', start: 2 })
  // 光标在中间：只看光标前
  assert.deepEqual(extractMentionQuery('见 @证据清单 附件', 5), { query: '证据', start: 2 })
})

test('extractMentionQuery：邮箱、@ 后有空白、光标不在查询里 → null', () => {
  assert.equal(extractMentionQuery('mail a@b.com', 12), null)
  assert.equal(extractMentionQuery('@韩 泽', 4), null)
  assert.equal(extractMentionQuery('没有 at', 5), null)
  assert.equal(extractMentionQuery('', 0), null)
  assert.equal(extractMentionQuery('@' + 'x'.repeat(41), 42), null, '超长不算')
})

test('applyMention：替换成 @名字 + 空格，光标落在空格后，不重复补空格', () => {
  assert.deepEqual(applyMention('提交 @起', 3, 5, '起诉状.docx'), { text: '提交 @起诉状.docx ', caret: 13 })
  assert.deepEqual(applyMention('@韩 明天', 0, 2, '韩泽伟'), { text: '@韩泽伟 明天', caret: 5 })
})

test('matchMembers：displayName / username 前缀，忽略大小写；空查询全给', () => {
  const ms = [
    { userId: 1, displayName: '韩泽伟', username: 'zewei' },
    { userId: 2, displayName: '张三', username: 'Zhang' },
    { userId: 3, displayName: '', username: 'lisi' },
  ]
  assert.deepEqual(matchMembers(ms, '').map((m) => m.userId), [1, 2, 3])
  assert.deepEqual(matchMembers(ms, '韩').map((m) => m.userId), [1])
  assert.deepEqual(matchMembers(ms, 'z').map((m) => m.userId), [1, 2])
  assert.deepEqual(matchMembers(ms, '泽').map((m) => m.userId), [], '只认前缀')
  assert.equal(memberName(ms[2]), 'lisi')
})
