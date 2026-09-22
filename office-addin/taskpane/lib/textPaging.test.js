// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * get_text 分页口径（dev-board#806，审计 B-05）：
 *   node --test office-addin/taskpane/lib/textPaging.test.js
 *
 * 两个家族的 get_text 都走这一份，所以这里钉的是**对模型的承诺**：
 * nextStart 有就是「接着读」、没有就是「读完了」，且续读不会漏字也不会重字。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { pageText, GET_TEXT_DEFAULT_CHARS, GET_TEXT_MAX_CHARS } from './textPaging.js'

test('短文档一次读完：不给 nextStart，truncated=false', () => {
  const r = pageText('第一条 合作范围')
  assert.equal(r.text, '第一条 合作范围')
  assert.equal(r.startChar, 0)
  assert.equal(r.returned, 8)
  assert.equal(r.totalChars, 8)
  assert.equal(r.truncated, false)
  assert.equal('nextStart' in r, false)
})

test('超出默认上限：只给前 5 万字，nextStart 指向下一段起点', () => {
  const doc = 'x'.repeat(GET_TEXT_DEFAULT_CHARS + 1234)
  const r = pageText(doc)
  assert.equal(r.returned, GET_TEXT_DEFAULT_CHARS)
  assert.equal(r.totalChars, doc.length)
  assert.equal(r.truncated, true)
  assert.equal(r.nextStart, GET_TEXT_DEFAULT_CHARS)
})

test('按 nextStart 续读能把整篇拼回来：不漏字、不重字', () => {
  const doc = Array.from({ length: 12345 }, (_, i) => String(i % 10)).join('')
  let start = 0
  let joined = ''
  let rounds = 0
  for (;;) {
    const r = pageText(doc, { startChar: start, maxChars: 1000 })
    joined += r.text
    rounds++
    assert.ok(rounds < 100, '续读没有收敛')
    if (r.nextStart == null) {
      assert.equal(r.truncated, false)
      break
    }
    assert.equal(r.truncated, true)
    start = r.nextStart
  }
  assert.equal(joined, doc)
  assert.equal(rounds, 13)
})

test('maxChars 超过硬上限时夹到 80k：这是单条工具结果交给模型的口径', () => {
  const doc = 'y'.repeat(300_000)
  const r = pageText(doc, { maxChars: 200_000 })
  assert.equal(r.returned, GET_TEXT_MAX_CHARS)
  assert.equal(r.nextStart, GET_TEXT_MAX_CHARS)
})

test('起点落在文末：给空串、不给 nextStart（模型据此停下而不是无限续读）', () => {
  const r = pageText('abcdef', { startChar: 6 })
  assert.equal(r.text, '')
  assert.equal(r.returned, 0)
  assert.equal(r.truncated, false)
  assert.equal('nextStart' in r, false)
})

test('起点越界 / 负数 / 非数字一律按合法值处理，不抛也不返回 NaN', () => {
  assert.equal(pageText('abcdef', { startChar: 999 }).startChar, 6)
  assert.equal(pageText('abcdef', { startChar: -5 }).startChar, 0)
  assert.equal(pageText('abcdef', { startChar: '2', maxChars: '2' }).text, 'cd')
  assert.equal(pageText('abcdef', { startChar: 'x', maxChars: 'y' }).text, 'abcdef')
  assert.equal(pageText('abcdef', { maxChars: 0 }).returned, 6, 'maxChars=0 按不传处理，不是「一个字都不给」')
  assert.equal(pageText('abcdef', { maxChars: -3 }).returned, 6)
})

test('空文档与 null：totalChars=0，不抛', () => {
  for (const empty of ['', null, undefined]) {
    const r = pageText(empty)
    assert.equal(r.text, '')
    assert.equal(r.totalChars, 0)
    assert.equal(r.truncated, false)
  }
})
