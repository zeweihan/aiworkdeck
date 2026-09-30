// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { classifySelectionLookup } from '../../src/utils/selectionLookup.js'

test('完整企业名、合伙企业和分公司推荐工商查询', () => {
  for (const text of [
    '北京京微资易科技有限公司', '上海示例有限责任公司', '中国示例股份有限公司',
    '示例（北京）科技有限公司', '深圳华为技术有限公司',
    '北京示例投资合伙企业（有限合伙）', '北京示例合伙企业（特殊普通合伙）',
    '北京示例科技有限公司上海分公司', ' 北京 示例科技有限公司 ',
  ]) assert.equal(classifySelectionLookup(text), 'COMPANY', text)
})

test('案号要求年份、法院代字、案件类型和流水号', () => {
  for (const text of [
    '（2026）京0105民初1234号', '(2025)最高法民终123号',
    '（２０２６） 沪 ０１ 民终 １２３ 号', '(2024)粤0305执恢19号',
    '（2024）川01刑初123号', '(2025)浙0106行初12号',
  ]) assert.equal(classifySelectionLookup(text), 'CASE', text)
  for (const text of ['（2026）京0105项目123号', '（2026）京0105民初号', '(2026)京0105123号', '(2026)abc民初1号']) {
    assert.equal(classifySelectionLookup(text), null, text)
  }
})

test('法律名称、明确法规标题及条号推荐法规查询', () => {
  for (const text of [
    '公司法', '民法典', '《中华人民共和国公司法》', '中华人民共和国民法典',
    '公司法第二十三条', '《民法典》第五百零九条第一款', '第十二条之一第二款第三项',
    '第 123 条', '《劳动合同法实施条例》', '劳动合同法实施条例', '专利法实施细则', '上海市住宅物业管理规定',
    '《最高人民法院关于适用中华人民共和国民法典合同编通则若干问题的解释》',
    '《最高人民法院关于适用中华人民共和国公司法若干问题的规定（三）》第二条',
  ]) assert.equal(classifySelectionLookup(text), 'LAW', text)
})

test('普通文字、人名、机构、书名与混合选区不猜查询类型', () => {
  for (const text of [
    '', '张三', '违约责任', '公司', '中华人民共和国最高人民法院', '北京市人民政府',
    '《合同》', '《红楼梦》', '想个办法', '这是一个方法',
    '请查询北京示例有限公司', '甲方为北京示例有限公司', '我想了解公司法',
    '依据《公司法》第二条', '根据公司法', '《公司法》和《民法典》',
    '北京甲有限公司与上海乙有限公司', '北京甲有限公司、上海乙有限公司', '张三与北京示例有限公司',
    '北京甲有限公司适用公司法', '（2026）京0105民初123号和公司法',
    '张三\n北京示例有限公司', '民\n法典', '张三\u2028北京示例有限公司',
    '北京示例有限公司。', '公司法第二条规定公司应当依法设立',
    null, undefined, 123, '甲'.repeat(201),
  ]) assert.equal(classifySelectionLookup(text), null, String(text))
})
