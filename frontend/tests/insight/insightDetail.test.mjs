// 检索详情整形（dev-band 无关，dev-board#182）：上游形状不受我们控制，
// 这组用例锁的是「认得的字段列出来、认不得的落原文兜底、少一个键不吞整块」。
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  unwrapResult, resultRecords, companyRows, companyShareholders,
  lawArticle, caseRecord, rawFallback,
  authoritative, caseRecognition, citationDetail,
} from '../../src/utils/insightDetail.js'

// —————————————————————————— unwrapResult ——————————————————————————

test('unwrapResult：对象原样返回', () => {
  assert.deepEqual(unwrapResult({ result: { a: 1 } }), { a: 1 })
})

test('unwrapResult：字符串化的 JSON 解开', () => {
  assert.deepEqual(unwrapResult({ result: '{"a":1}' }), { a: 1 })
})

test('unwrapResult：不是 JSON 的字符串原样返回', () => {
  assert.equal(unwrapResult({ result: '上游返回了一段纯文本' }), '上游返回了一段纯文本')
})

test('unwrapResult：MCP 的 content 信封剥掉并再解一层 JSON', () => {
  const d = { result: { content: [{ type: 'text', text: '[{"title":"公司法"}]' }] } }
  assert.deepEqual(unwrapResult(d), [{ title: '公司法' }])
})

test('unwrapResult：content 信封里是纯文本时给文本', () => {
  const d = { result: { content: [{ type: 'text', text: '未检索到' }] } }
  assert.equal(unwrapResult(d), '未检索到')
})

test('unwrapResult：没有 result 给 null', () => {
  assert.equal(unwrapResult({}), null)
  assert.equal(unwrapResult(null), null)
})

// —————————————————————————— resultRecords ——————————————————————————

test('resultRecords：数组即清单', () => {
  const r = resultRecords([{ a: 1 }, { b: 2 }])
  assert.equal(r.records.length, 2)
})

test('resultRecords：对象里挂着的数组被取出来', () => {
  const r = resultRecords({ total: 2, data: [{ a: 1 }, { a: 2 }] })
  assert.equal(r.records.length, 2)
  assert.equal(r.records[0].a, 1)
})

test('resultRecords：纯对象自成一条', () => {
  const r = resultRecords({ title: '公司法' })
  assert.equal(r.records.length, 1)
})

test('resultRecords：字符串落 text 不落 records', () => {
  const r = resultRecords('一段原文')
  assert.deepEqual(r.records, [])
  assert.equal(r.text, '一段原文')
})

// —————————————————————————— COMPANY ——————————————————————————

const COMPANY_DETAIL = {
  source: 'qichacha',
  basic: {
    企业名称: '京微资易科技有限公司',
    统一社会信用代码: '91110108MA01ABCD12',
    法定代表人: '张三',
    注册资本: '1000万元人民币',
    登记状态: '存续',
    经营范围: '',
  },
  shareholders: [
    { 股东: '张三', 持股比例: '51%', 认缴出资: '510万元' },
    { 股东: '李四', 持股比例: '49%' },
  ],
}

test('COMPANY：基本情况表保持后端键序，空值不出现', () => {
  const rows = companyRows(COMPANY_DETAIL)
  assert.deepEqual(rows.map((r) => r.label), ['企业名称', '统一社会信用代码', '法定代表人', '注册资本', '登记状态'])
  assert.equal(rows[0].value, '京微资易科技有限公司')
})

test('COMPANY：没有 basic 时给空表而不是抛', () => {
  assert.deepEqual(companyRows({}), [])
  assert.deepEqual(companyRows(null), [])
})

test('COMPANY：股东段缺字段也照列（少一个键不吞整行）', () => {
  const s = companyShareholders(COMPANY_DETAIL)
  assert.equal(s.length, 2)
  assert.equal(s[1].name, '李四')
  assert.equal(s[1].capital, '')
})

test('COMPANY：没有股东段返回空数组', () => {
  assert.deepEqual(companyShareholders({ basic: {} }), [])
})

// —————————————————————————— LAW ——————————————————————————

test('LAW：条文原文按认得的字段取出', () => {
  const a = lawArticle({
    result: { title: '中华人民共和国公司法', number: '第二十条', timeliness: '现行有效', content: '公司股东应当遵守…' },
  })
  assert.equal(a.title, '中华人民共和国公司法')
  assert.equal(a.article, '第二十条')
  assert.equal(a.timeliness, '现行有效')
  assert.match(a.content, /公司股东/)
  assert.deepEqual(a.more, [])
})

test('LAW：关键词库返回一串时首条展开、其余只留标题', () => {
  const a = lawArticle({ result: { data: [{ title: '公司法', content: '正文一' }, { title: '公司法司法解释三' }] } })
  assert.equal(a.title, '公司法')
  assert.deepEqual(a.more, ['公司法司法解释三'])
})

test('LAW：上游只给了一段纯文本时落 content', () => {
  const a = lawArticle({ result: '未检索到该条' })
  assert.equal(a.content, '未检索到该条')
  assert.equal(a.title, '')
})

// —————————————————————————— CASE ——————————————————————————

test('CASE：判决书首条展开成分段，其余只列标题', () => {
  const c = caseRecord({
    result: [
      {
        title: '甲与乙买卖合同纠纷一审民事判决书',
        case_number: '（2024）京0108民初1234号',
        courthouse_name: '北京市海淀区人民法院',
        decision_date: '2024-05-20',
        ascertain: '本院查明：…',
        reason: '本院认为：…',
        result: '判决如下：…',
      },
      { title: '丙与丁案' },
    ],
  })
  assert.equal(c.caseNumber, '（2024）京0108民初1234号')
  assert.equal(c.court, '北京市海淀区人民法院')
  assert.deepEqual(c.sections.map((s) => s.key), ['ascertain', 'reason', 'result'])
  assert.deepEqual(c.more, ['丙与丁案'])
})

test('CASE：全文段与某一段内容相同时不重复渲染', () => {
  const c = caseRecord({ result: [{ ascertain: '同一段', content: '同一段' }] })
  assert.equal(c.sections.length, 1)
})

test('CASE：完全认不出字段时至少把全文段留住', () => {
  const c = caseRecord({ result: [{ content: '整篇判决书正文' }] })
  assert.deepEqual(c.sections, [{ key: 'fullText', text: '整篇判决书正文' }])
})

test('CASE：纯文本结果落到 fullText 段', () => {
  const c = caseRecord({ result: '上游返回一段文字' })
  assert.deepEqual(c.sections, [{ key: 'fullText', text: '上游返回一段文字' }])
})

// —————————————————————————— 法宝升级件 ——————————————————————————

test('authoritative：权威条文原文按认得的字段取出', () => {
  const a = authoritative({
    result: 'Error',
    authoritative: {
      title: '中华人民共和国公司法（2023 修订）',
      original_text: '公司股东应当遵守法律…',
      url: 'https://www.pkulaw.com/chl/x',
      implement_date: '2024-07-01',
    },
  })
  assert.equal(a.title, '中华人民共和国公司法（2023 修订）')
  assert.match(a.text, /公司股东/)
  assert.equal(a.date, '2024-07-01')
  assert.equal(a.url, 'https://www.pkulaw.com/chl/x')
})

test('authoritative：没有这个字段返回 null（整段不渲染）', () => {
  assert.equal(authoritative({ result: {} }), null)
  assert.equal(authoritative(null), null)
  assert.equal(authoritative({ authoritative: {} }), null)
})

test('caseRecognition：案号识别行取标准化案号/法院/标题/链接', () => {
  const r = caseRecognition({
    recognition: {
      caseFlag: '（2021）京01民终1234号',
      court: '北京市第一中级人民法院',
      title: '甲与乙合同纠纷二审民事判决书',
      url: 'https://www.pkulaw.com/pfnl/abc',
    },
  })
  assert.equal(r.caseNumber, '（2021）京01民终1234号')
  assert.equal(r.court, '北京市第一中级人民法院')
  assert.equal(r.title, '甲与乙合同纠纷二审民事判决书')
})

test('caseRecognition：没有识别结果返回 null', () => {
  assert.equal(caseRecognition({ result: [] }), null)
  assert.equal(caseRecognition(null), null)
})

// —————————————————————————— 引用发现 ——————————————————————————

const NOT_FOUND = {
  id: 51, kind: 'CITATION_NOT_FOUND', severity: 'warn',
  detail: {
    lawTitle: '中华人民共和国民法典', citedArticle: '第九千九百九十九条', citedArabic: '9999',
    quote: '依据《中华人民共和国民法典》第九千九百九十九条', note: '可能条号有误或法规名不准，请人工核对',
    fixable: false,
  },
}

const MISMATCH = {
  id: 52, kind: 'CITATION_MISMATCH', severity: 'warn',
  detail: {
    lawTitle: '中华人民共和国公司法', citedArticle: '第十五条',
    citedText: '公司股东应当遵守…', quote: '依据《公司法》第十五条，公司向其他企业投资…',
    candidates: [
      { title: '中华人民共和国公司法（2018 修正）', articleNumber: '16', snippet: '公司向其他企业投资…', url: 'https://x' },
      null,
    ],
    note: '候选可能来自旧版法规（存在条文重编号），请人工核对现行版本',
    fixable: false,
  },
}

test('citationDetail：CITATION_NOT_FOUND 整形出可渲染字段', () => {
  const c = citationDetail(NOT_FOUND)
  assert.equal(c.kind, 'CITATION_NOT_FOUND')
  assert.equal(c.lawTitle, '中华人民共和国民法典')
  assert.equal(c.citedArticle, '第九千九百九十九条')
  assert.match(c.note, /人工核对/)
  assert.deepEqual(c.candidates, [])
})

test('citationDetail：CITATION_MISMATCH 带引用条文与候选（脏元素被滤掉）', () => {
  const c = citationDetail(MISMATCH)
  assert.equal(c.candidates.length, 1)
  assert.equal(c.candidates[0].articleNumber, '16')
  assert.match(c.candidates[0].title, /2018 修正/)
  assert.equal(c.candidates[0].url, 'https://x')
  assert.match(c.citedText, /公司股东/)
})

test('citationDetail：别的 finding 一律 null（各走各的渲染路径）', () => {
  assert.equal(citationDetail({ kind: 'COUNT_MISMATCH', detail: { claims: [] } }), null)
  assert.equal(citationDetail({ kind: 'USCC_INVALID', detail: { code: '91' } }), null)
  assert.equal(citationDetail(null), null)
  assert.equal(citationDetail({ kind: 'CITATION_MISMATCH' }), null)
})

// —————————————————————————— rawFallback ——————————————————————————

test('rawFallback：company 的 raw 优先', () => {
  assert.equal(rawFallback({ raw: '{"Name":"X"}' }), '{"Name":"X"}')
})

test('rawFallback：对象结果序列化，并受长度上限约束', () => {
  const big = { result: { text: 'x'.repeat(9000) } }
  assert.equal(rawFallback(big).length, 4000)
  assert.equal(rawFallback(big, 100).length, 100)
})

test('rawFallback：没有可兜底的内容给空串', () => {
  assert.equal(rawFallback(null), '')
  assert.equal(rawFallback({}), '')
})
