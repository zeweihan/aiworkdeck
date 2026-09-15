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

test('法律叙述中的公司名称不包含名册、记载或出资角色前缀', () => {
  const text = '股东名册中青岛致衡贸易有限公司持股40%。股东名册记载青岛华为贸易有限公司。出资人为青岛向阳贸易有限公司。'
  const entries = extractCompletionEntries(text, { segmenter: null })
  assert.deepEqual(byKind(entries, 'COMPANY'), ['青岛致衡贸易有限公司', '青岛华为贸易有限公司', '青岛向阳贸易有限公司'])
  assert.equal(matchCompletionItems('青岛致', entries)[0]?.text, '青岛致衡贸易有限公司')
})

test('持股出资叙述收录原文人名，普通主语和机构不能猜成人名', () => {
  const text = '《公司章程》记载韩明远持股60%。股东张三实际出资600万元。出资人欧阳明认缴100万元。章程记载公司持股60%。股东均已出资。股东依法出资。记载企业出资。青岛致衡贸易有限公司持股40%。'
  const entries = extractCompletionEntries(text, { segmenter: null })
  assert.deepEqual(byKind(entries, 'PERSON'), ['韩明远', '张三', '欧阳明'])
  assert.equal(matchCompletionItems('韩明', entries)[0]?.text, '韩明远')
  assert.ok(entries.filter(x => x.kind === 'PERSON').every(x => text.includes(x.text)))
})

test('较大文档预算保留多类别候选，而非前50个公司占满全部位置', () => {
  const text = Array.from({ length: 510 }, (_, i) => `北京示例${i}有限公司。`).join('') + '《民法典》第五百零九条。姓名：韩明远。'
  const entries = extractCompletionEntries(text, { segmenter: null, limit: 500 })
  assert.equal(entries.length, 500)
  assert.ok(byKind(entries, 'LAW').includes('《民法典》'))
  assert.ok(byKind(entries, 'PERSON').includes('韩明远'))
  assert.ok(byKind(entries, 'ARTICLE').includes('《民法典》第五百零九条'))
})

test('裸法规名补全使用配对去括号的插入文本并保留原文与资料元数据', () => {
  const item = { text: '《民法典》', kind: 'LAW', entityId: 17, hasDetail: true, id: 'insight:17' }
  const bare = matchCompletionItems('依据民法', [item])[0]
  assert.equal(bare?.text, '民法典')
  assert.equal(bare.prefix, '民法')
  assert.equal(bare.displayText, '《民法典》')
  assert.equal(bare.entityId, 17)
  assert.equal(bare.hasDetail, true)
  assert.equal(bare.id, 'insight:17')
  const bracketed = matchCompletionItems('依据《民法', [item])[0]
  assert.equal(bracketed.text, '《民法典》')
  assert.equal(bracketed.prefix, '《民法')
  assert.deepEqual(matchCompletionItems('依据民法典', [item]), [])
})

test('条款前缀可独立补全，显示完整法源且保留同条号的不同法规', () => {
  const items = [
    { text: '《公司法》第二十条', kind: 'ARTICLE', entityId: 1 },
    { text: '《民法典》第二十条', kind: 'ARTICLE', entityId: 2 },
  ]
  const matches = matchCompletionItems('依据第二十', items)
  assert.deepEqual(matches.map(x => x.text), ['第二十条', '第二十条'])
  assert.deepEqual(matches.map(x => x.displayText), ['《公司法》第二十条', '《民法典》第二十条'])
  assert.deepEqual(matches.map(x => x.entityId), [1, 2])
  assert.equal(matchCompletionItems('依据公司法第二十', items)[0]?.text, '公司法第二十条')
})

test('出资叙述中的短机构名和普通主语不成为人名', () => {
  const text = '股东甲公司持股60%。股东北京银行出资600万元。记载全体股东出资。股东韩明远出资600万元。'
  assert.deepEqual(byKind(extractCompletionEntries(text, { segmenter: null }), 'PERSON'), ['韩明远'])
})
