// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'

import { extractCompletionEntries, matchCompletionItems } from '../../src/utils/completionLexicon.js'

const byKind = (entries, kind) => entries.filter((entry) => entry.kind === kind).map((entry) => entry.text)

test('集团名称按机构收录并可与事务所同时补全', () => {
  const entries = extractCompletionEntries('北京当红齐天集团。北京当红晴天律师事务所。')
  assert.deepEqual(byKind(entries, 'COMPANY'), ['北京当红齐天集团', '北京当红晴天律师事务所'])
  assert.equal(matchCompletionItems('北京当红', entries).filter(item => item.kind === 'COMPANY').length, 2)
})

test('从连续中文法律文本提取机构、法规条款、明确标注的人名和案号', () => {
  const text = '原告：张三与北京示例科技有限公司发生争议。依据《中华人民共和国民法典》第五百零九条处理。案号：（2026）京0105民初1234号。'
  const entries = extractCompletionEntries(text, { segmenter: null })

  assert.ok(byKind(entries, 'COMPANY').includes('北京示例科技有限公司'))
  assert.ok(byKind(entries, 'PERSON').includes('张三'))
  assert.ok(byKind(entries, 'LAW').includes('《中华人民共和国民法典》'))
  assert.ok(byKind(entries, 'ARTICLE').includes('《中华人民共和国民法典》第五百零九条'))
  assert.ok(byKind(entries, 'CASE').includes('（2026）京0105民初1234号'))
})

test('只在明确的人名标签后提取人名，不把普通中文词组猜成人名', () => {
  const entries = extractCompletionEntries('项目负责人认真核对材料。法定代表人：李四；联系人 王五。被告为赵六。')

  assert.deepEqual(byKind(entries, 'PERSON'), ['李四', '王五', '赵六'])
})

test('Segmenter 提供常见词，缺失时仍用标点边界提取短语', () => {
  const segmenter = {
    segment() {
      return [
        { segment: '违约责任', isWordLike: true },
        { segment: '。', isWordLike: false },
        { segment: '损害赔偿', isWordLike: true },
      ]
    },
  }
  const segmented = extractCompletionEntries('双方应当依法承担违约责任。损害赔偿另行计算。', { segmenter })
  assert.deepEqual(byKind(segmented, 'WORD'), ['违约责任', '损害赔偿'])
  assert.deepEqual(byKind(segmented, 'PHRASE'), ['双方应当依法承担违约责任', '损害赔偿另行计算'])

  const fallback = extractCompletionEntries('双方应当依法承担违约责任，损害赔偿另行计算。', { segmenter: null })
  assert.deepEqual(byKind(fallback, 'PHRASE'), ['双方应当依法承担违约责任', '损害赔偿另行计算'])
})

test('机构名称保留华为、向阳等名称组成部分，只清理由明确角色带入的上下文', () => {
  const entries = extractCompletionEntries('甲方为华为技术有限公司。乙方：向阳有限公司。原告：张三与北京示例科技有限公司发生争议。', { segmenter: null })
  assert.deepEqual(byKind(entries, 'COMPANY'), ['华为技术有限公司', '向阳有限公司', '北京示例科技有限公司'])
})

test('同一句中的多个机构按后缀分别提取，不把连接上下文吞进名称', () => {
  const entries = extractCompletionEntries('本协议由北京当红晴天律师事务所与北京示例有限公司签署。', { segmenter: null })
  assert.deepEqual(byKind(entries, 'COMPANY'), ['北京当红晴天律师事务所', '北京示例有限公司'])
})

test('去重、过滤敏感数字与越界长度，并将结果限制为50条', () => {
  const phrases = Array.from({ length: 60 }, (_, index) => `这是第${index}个用于测试本地词库的合法表述`).join('。')
  const text = `北京示例有限公司。北京示例有限公司。身份证110101199001011234，电话13800138000，银行卡6222021234567890123。${phrases}`
  const entries = extractCompletionEntries(text, { segmenter: null })

  assert.equal(entries.filter((entry) => entry.text === '北京示例有限公司').length, 1)
  assert.ok(entries.length <= 50)
  assert.ok(entries.every((entry) => !/(110101199001011234|13800138000|6222021234567890123)/.test(entry.text)))
  assert.ok(entries.every((entry) => entry.text.length >= 2 && entry.text.length <= 160))
})

test('连续句尾取最长可补全前缀，并返回多个不同类别候选', () => {
  const items = [
    { text: '中华人民共和国民法典', kind: 'LAW', source: 'user', uses: 2, lastUsedAt: 1 },
    { text: '中华人民共和国民事诉讼法', kind: 'LAW', source: 'project', uses: 1, lastUsedAt: 2 },
    { text: '中华人民共和国最高人民法院', kind: 'COMPANY', scope: 'project', uses: 3, lastUsedAt: 3 },
  ]
  const matches = matchCompletionItems('本案应当依据中华人民共和', items)

  assert.equal(matches.length, 3)
  assert.ok(matches.every((item) => item.prefix === '中华人民共和'))
})

test('支持法律条号、明确人名与案号前缀', () => {
  const cases = [
    ['依据《民法典》第五百', { text: '《民法典》第五百零九条', kind: 'ARTICLE' }],
    ['法定代表人欧阳', { text: '欧阳娜娜', kind: 'PERSON' }],
    ['案号（2026）京0105民', { text: '（2026）京0105民初1234号', kind: 'CASE' }],
  ]
  for (const [before, item] of cases) {
    const [match] = matchCompletionItems(before, [item])
    assert.equal(match.text, item.text)
    assert.ok(item.text.startsWith(match.prefix))
  }
})

test('不补全单字、短英文、无关内容或已经完整输入的词条', () => {
  const items = [
    { text: '北京示例有限公司', kind: 'COMPANY' },
    { text: 'Contract', kind: 'WORD' },
  ]
  assert.deepEqual(matchCompletionItems('北', items), [])
  assert.deepEqual(matchCompletionItems('Co', items), [])
  assert.deepEqual(matchCompletionItems('上海', items), [])
  assert.deepEqual(matchCompletionItems('北京示例有限公司', items), [])
})

test('先按前缀长度，再按project来源、使用次数和最近使用时间排序，并按text去重', () => {
  const items = [
    { text: '北京示例科技有限公司', kind: 'COMPANY', scope: 'user', source: 'learned', uses: 99, lastUsedAt: 99 },
    { text: '北京示例科技有限公司', kind: 'COMPANY', scope: 'project', source: 'document', uses: 1, lastUsedAt: 1 },
    { text: '示例合作协议', kind: 'PHRASE', scope: 'user', source: 'learned', uses: 20, lastUsedAt: 30 },
    { text: '示例补充协议', kind: 'PHRASE', scope: 'project', source: 'document', uses: 1, lastUsedAt: 1 },
    { text: '示例框架协议', kind: 'PHRASE', scope: 'project', source: 'variable', uses: 5, lastUsedAt: 1 },
    { text: '示例保密协议', kind: 'PHRASE', scope: 'project', source: 'insight', uses: 5, lastUsedAt: 9 },
  ]
  const matches = matchCompletionItems('签约方为北京示例', items, { limit: 5 })

  assert.deepEqual(matches.map((item) => item.text), [
    '北京示例科技有限公司',
    '示例保密协议',
    '示例框架协议',
    '示例补充协议',
    '示例合作协议',
  ])
  assert.equal(matches[0].scope, 'project')
})

test('完整年括号加法院代码可作为案号前缀，即使只有一个汉字', () => {
  const [match] = matchCompletionItems('案号(2024)京', [{ text: '(2024)京0105民初88号', kind: 'CASE' }])
  assert.equal(match.prefix, '(2024)京')
})

test('拉丁匹配不区分大小写，但当前协议只返回可由原文 startsWith 校验的前缀', () => {
  assert.deepEqual(matchCompletionItems('con', [{ text: 'Contract', kind: 'WORD' }]), [])
  const [match] = matchCompletionItems('Con', [{ text: 'Contract', kind: 'WORD' }])
  assert.equal(match.prefix, 'Con')
})
