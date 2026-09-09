// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'

import { completionDetails } from '../../src/utils/completionDetails.js'

test('公司详情只展示 basic 中真实存在的字段，并接受字符串化 detail', () => {
  const view = completionDetails({
    name: '北京示例有限公司',
    kind: 'COMPANY',
    retrievalStatus: 'OK',
    retrievalSource: '企业信息服务',
    fetchedAt: '2026-09-09T08:30:00Z',
    detail: JSON.stringify({ basic: { 企业名称: '北京示例有限公司', 统一社会信用代码: '91110000TEST', 空字段: '' }, raw: '{不可插入}' }),
  })

  assert.equal(view.title, '北京示例有限公司')
  assert.equal(view.source, '企业信息服务')
  assert.equal(view.date, '2026-09-09T08:30:00Z')
  assert.deepEqual(view.variants, [{
    title: '北京示例有限公司',
    rows: [
      ['企业名称', '北京示例有限公司'],
      ['统一社会信用代码', '91110000TEST'],
    ],
  }])
})

test('每条法规检索记录形成独立候选，保留条号、时效性、URL、真实来源和查询日期', () => {
  const view = completionDetails({
    name: '《示例法》第十条',
    kind: 'LAW',
    retrievalStatus: 'OK',
    retrievalSource: '法规检索服务',
    fetchedAt: '2026-09-09',
    detail: {
      result: { records: [
        { title: '示例法（2025修订）', article: '第十条', timeliness: '现行有效', content: '当事人应当诚信履行。', url: 'https://law.example/2025/10' },
        { title: '示例法（2020修订）', article: '第九条', timeliness: '已失效', content: '旧版条文。', url: 'https://law.example/2020/9' },
      ] },
    },
  })

  assert.equal(view.variants.length, 2)
  assert.equal(view.variants[0].rows, undefined)
  assert.match(view.variants[0].text, /^示例法（2025修订）\n第十条\n时效性：现行有效\nURL：https:\/\/law\.example\/2025\/10\n\n当事人应当诚信履行。/)
  assert.match(view.variants[0].text, /来源 \/ Source：法规检索服务/)
  assert.match(view.variants[0].text, /查询日期：2026-09-09/)
  assert.match(view.variants[1].text, /\n\n旧版条文。\n\n/)
})

test('每条案例记录形成独立候选并保留案号、法院、日期和实际裁判内容', () => {
  const view = completionDetails({
    name: '（2026）京01民终1号',
    kind: 'CASE',
    retrievalStatus: 'OK',
    retrievalSource: '案例库',
    fetchedAt: '2026-09-09',
    detail: { result: { cases: [
      { title: '甲公司诉乙公司案', case_number: '（2026）京01民终1号', court: '北京市第一中级人民法院', decision_date: '2026-06-01', reason: '本院认为……', result: '驳回上诉。' },
      { title: '同名关联案件', case_number: '（2025）京01民终2号', court: '北京市第一中级人民法院' },
    ] } },
  })

  assert.equal(view.variants.length, 1, '没有裁判正文的元数据不能成为可插入候选')
  assert.equal(view.variants[0].rows, undefined)
  assert.match(view.variants[0].text, /^甲公司诉乙公司案\n案号：（2026）京01民终1号\n法院：北京市第一中级人民法院\n裁判日期：2026-06-01/)
  assert.match(view.variants[0].text, /本院认为……/)
  assert.match(view.variants[0].text, /驳回上诉。/)
})

test('检索错误仅返回真实错误说明且不暴露 raw JSON 或候选正文', () => {
  const view = completionDetails({
    name: '未知公司',
    kind: 'COMPANY',
    retrievalStatus: 'UNAVAILABLE',
    retrievalSource: '',
    retrievalNote: '服务暂不可用',
    detail: '{"raw":"内部响应"}',
  })

  assert.deepEqual(view, { title: '未知公司', source: '', date: '', note: '服务暂不可用', variants: [] })
})

test('检索失败时仍可展示真实 authoritative，但文案不声称已核实', () => {
  const view = completionDetails({
    name: '《示例法》第十条',
    kind: 'LAW',
    retrievalStatus: 'NOT_FOUND',
    retrievalNote: '相关检索未命中',
    detail: { authoritative: { title: '示例法 第十条', original_text: '权威条文原文。', url: 'https://law.example/auth', implement_date: '2025-01-01' } },
  })

  assert.equal(view.note, '相关检索未命中')
  assert.equal(view.variants.length, 1)
  assert.equal(view.variants[0].title, '示例法 第十条')
  assert.equal(view.variants[0].rows, undefined)
  assert.equal(view.variants[0].text, '示例法 第十条\n实施日期：2025-01-01\nURL：https://law.example/auth\n\n权威条文原文。')
  assert.doesNotMatch(JSON.stringify(view), /已核实|verified/i)
})

test('案例识别结果写入说明但不形成可插入 variant', () => {
  const view = completionDetails({
    name: '京01民终1号',
    kind: 'CASE',
    retrievalStatus: 'ERROR',
    retrievalNote: '案例正文查询失败',
    detail: { recognition: { caseFlag: '（2026）京01民终1号', court: '北京市第一中级人民法院', title: '甲公司诉乙公司案', url: 'https://case.example/1' } },
  })

  assert.deepEqual(view.variants, [])
  assert.match(view.note, /案例正文查询失败/)
  assert.match(view.note, /甲公司诉乙公司案/)
  assert.match(view.note, /（2026）京01民终1号/)
})

test('ARTICLE 与 LAW 使用同一完整引用契约', () => {
  const view = completionDetails({
    name: '《示例法》第二条', kind: 'ARTICLE', retrievalStatus: 'OK',
    detail: { result: { title: '示例法', article: '第二条', content: '本条原文。' } },
  })
  assert.deepEqual(view.variants, [{ title: '示例法 第二条', text: '示例法\n第二条\n\n本条原文。' }])
})

test('LAW 与 CASE 保留普通字符串及 MCP 文本信封中的真实原文', () => {
  const law = completionDetails({
    name: '《示例法》', kind: 'LAW', retrievalStatus: 'OK', retrievalSource: '法规服务', fetchedAt: '2026-09-09',
    detail: { result: '这是法规原文。' },
  })
  assert.deepEqual(law.variants, [{
    title: '《示例法》',
    text: '《示例法》\n\n这是法规原文。\n\n来源 / Source：法规服务\n查询日期：2026-09-09',
  }])

  const caseView = completionDetails({
    name: '（2026）京01民终1号', kind: 'CASE', retrievalStatus: 'OK',
    detail: { result: { content: [{ type: 'text', text: '这是裁判原文。' }] } },
  })
  assert.deepEqual(caseView.variants, [{
    title: '（2026）京01民终1号',
    text: '（2026）京01民终1号\n\n这是裁判原文。',
  }])
})

test('缺少详情时保持空候选，不根据实体标题猜正文或来源', () => {
  assert.deepEqual(completionDetails({ name: '《没有正文的法规》', kind: 'LAW' }), {
    title: '《没有正文的法规》', source: '', date: '', note: '', variants: [],
  })
})
