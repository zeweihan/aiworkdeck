// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * read_for_reference 的纯函数部分（dev-board#717）：
 *   node --test office-addin/taskpane/lib/referenceRead.test.js
 *
 * locator 是模型从别的窗格发过来的「按哪一块读」——解析错了不会报错，只会读错地方：
 * 模型要第 3 页却拿到全文，或者要「第三条」却拿到「第十三条」。所以这里把
 * 解析、标题切段、截断标注三件事单独钉死。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  parseLocator, sliceByHeading, findHeadingSpan, capReferenceText,
  unsupportedLocatorError, MAX_REFERENCE_CHARS, TRUNCATION_MARK
} from './referenceRead.js'

test('parseLocator', () => {
  assert.deepEqual(parseLocator(''), { kind: 'none' })
  assert.deepEqual(parseLocator('page:3'), { kind: 'page', n: 3 })
  assert.deepEqual(parseLocator('slide:2'), { kind: 'slide', n: 2 })
  assert.deepEqual(parseLocator('sheet:报价!A1:D20'), { kind: 'sheet', sheet: '报价', range: 'A1:D20' })
  assert.deepEqual(parseLocator('sheet:报价'), { kind: 'sheet', sheet: '报价', range: '' })
  assert.deepEqual(parseLocator('heading:第三条 违约责任'), { kind: 'heading', text: '第三条 违约责任' })
  assert.throws(() => parseLocator('page:0'))
  assert.throws(() => parseLocator('foo:1'))
})

test('parseLocator：空值、空白与大小写前缀', () => {
  assert.deepEqual(parseLocator(null), { kind: 'none' })
  assert.deepEqual(parseLocator(undefined), { kind: 'none' })
  assert.deepEqual(parseLocator('   '), { kind: 'none' })
  assert.deepEqual(parseLocator(' Page: 12 '), { kind: 'page', n: 12 })
  assert.deepEqual(parseLocator('SLIDE:1'), { kind: 'slide', n: 1 })
})

test('parseLocator：页码必须是正整数，否则报可读错误', () => {
  for (const bad of ['page:', 'page:-1', 'page:1.5', 'page:三', 'slide:0', 'slide:abc']) {
    assert.throws(() => parseLocator(bad), /无法识别的定位/, bad)
  }
})

test('parseLocator：工作表名带引号或含感叹号时按最后一个 ! 切', () => {
  assert.deepEqual(parseLocator("sheet:'2024 报价'!B2:C9"), { kind: 'sheet', sheet: '2024 报价', range: 'B2:C9' })
  assert.deepEqual(parseLocator("sheet:'O''Brien'"), { kind: 'sheet', sheet: "O'Brien", range: '' })
  assert.deepEqual(parseLocator('sheet:注意!事项!A1'), { kind: 'sheet', sheet: '注意!事项', range: 'A1' })
  assert.throws(() => parseLocator('sheet:'), /无法识别的定位/)
  assert.throws(() => parseLocator('sheet:!A1'), /无法识别的定位/)
})

test('parseLocator：heading 为空报错；未知前缀的报错里列出可用写法', () => {
  assert.throws(() => parseLocator('heading:   '), /无法识别的定位/)
  assert.throws(() => parseLocator('foo:1'), (e) => /page:/.test(e.message) && /heading:/.test(e.message))
  assert.throws(() => parseLocator('第三页'), /无法识别的定位/)
})

test('sliceByHeading stops at same-or-higher level', () => {
  const paras = [
    { text: '第一条', isHeading: true, level: 1 }, { text: 'a', isHeading: false },
    { text: '1.1', isHeading: true, level: 2 }, { text: 'b', isHeading: false },
    { text: '第二条', isHeading: true, level: 1 }, { text: 'c', isHeading: false }
  ]
  assert.equal(sliceByHeading(paras, '第一条'), '第一条\na\n1.1\nb')
  assert.equal(sliceByHeading(paras, '1.1'), '1.1\nb')
  assert.throws(() => sliceByHeading(paras, '第九条'), /没有找到标题/)
})

test('sliceByHeading：最后一个标题一直读到文末；去空白后包含即可命中', () => {
  const paras = [
    { text: '第一条  定义', isHeading: true, level: 1 }, { text: 'a', isHeading: false },
    { text: '第三条　违约责任', isHeading: true, level: 1 }, { text: 'x', isHeading: false },
    { text: '3.1', isHeading: true, level: 2 }, { text: 'y', isHeading: false }
  ]
  assert.equal(sliceByHeading(paras, '第三条 违约责任'), '第三条　违约责任\nx\n3.1\ny')
  assert.equal(sliceByHeading(paras, '违约责任'), '第三条　违约责任\nx\n3.1\ny')
})

test('sliceByHeading：完全相同的标题优先于「包含」命中', () => {
  // 只按「包含」取第一个的话，要「第一条」会先撞上「第一条之一」
  const paras = [
    { text: '第一条之一', isHeading: true, level: 1 }, { text: 'p', isHeading: false },
    { text: '第一条', isHeading: true, level: 1 }, { text: 'q', isHeading: false }
  ]
  assert.equal(sliceByHeading(paras, '第一条'), '第一条\nq')
})

test('sliceByHeading：正文段落里出现同样的字不算标题', () => {
  const paras = [
    { text: '依照第三条约定', isHeading: false }, { text: 'z', isHeading: false },
    { text: '第三条', isHeading: true, level: 1 }, { text: 'w', isHeading: false }
  ]
  assert.equal(sliceByHeading(paras, '第三条'), '第三条\nw')
})

test('findHeadingSpan：返回 [start, end) 段落下标，WPS 面据此切原文区间', () => {
  const paras = [
    { text: '', isHeading: false },
    { text: '第一条\r', isHeading: true, level: 1 }, { text: '', isHeading: false },
    { text: '第二条\r', isHeading: true, level: 1 }
  ]
  assert.deepEqual(findHeadingSpan(paras, '第一条'), { start: 1, end: 3 })
  assert.deepEqual(findHeadingSpan(paras, '第二条'), { start: 3, end: 4 })
})

test('没有找到标题时的报错：列出现有标题，或说明文档没有标题样式', () => {
  const withHeadings = [
    { text: '第一条', isHeading: true, level: 1 }, { text: '第二条', isHeading: true, level: 1 }
  ]
  assert.throws(() => sliceByHeading(withHeadings, '第九条'),
    (e) => /没有找到标题：第九条/.test(e.message) && /第一条/.test(e.message) && /第二条/.test(e.message))
  const noHeadings = [{ text: '第一条', isHeading: false }, { text: 'a', isHeading: false }]
  assert.throws(() => sliceByHeading(noHeadings, '第一条'),
    (e) => /没有找到标题/.test(e.message) && /标题样式/.test(e.message))
})

test('capReferenceText：超长截断并标注，未超长原样返回', () => {
  const short = capReferenceText('abc')
  assert.deepEqual(short, { text: 'abc', truncated: false, totalChars: 3 })
  const long = 'x'.repeat(MAX_REFERENCE_CHARS + 5)
  const capped = capReferenceText(long)
  assert.equal(capped.truncated, true)
  assert.equal(capped.totalChars, MAX_REFERENCE_CHARS + 5)
  assert.ok(capped.text.endsWith(TRUNCATION_MARK))
  assert.equal(TRUNCATION_MARK, '\n...(截断)')
  assert.equal(capped.text.length, MAX_REFERENCE_CHARS + TRUNCATION_MARK.length)
  assert.deepEqual(capReferenceText(null), { text: '', truncated: false, totalChars: 0 })
})

test('unsupportedLocatorError：按宿主给出可用写法', () => {
  assert.match(unsupportedLocatorError('word', 'slide').message, /Word 文档不支持该定位/)
  assert.match(unsupportedLocatorError('excel', 'page').message, /表格不支持该定位/)
  assert.match(unsupportedLocatorError('powerpoint', 'heading').message, /演示稿不支持该定位/)
  assert.match(unsupportedLocatorError('excel', 'page').message, /sheet:/)
})
