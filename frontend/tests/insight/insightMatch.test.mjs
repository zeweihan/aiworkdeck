// 「依据」窗格纯函数（dev-board#182）：光标命中实体 + 一键修改的替换串生成。
// 跑法：npm run test:insight
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  CURSOR_RADIUS, cursorWindow, matchEntityAt,
  buildFixedQuote, fixSuggestions, fixBlockReason, findingLocateQuote,
} from '../../src/utils/insightMatch.js'

const COMPANY = { id: 1, kind: 'COMPANY', name: '京微资易科技有限公司', normKey: '京微资易科技' }
const LAW = { id: 2, kind: 'LAW', name: '《公司法》第二十条', normKey: '公司法#第二十条' }

// —————————————————————————— cursorWindow ——————————————————————————

test('cursorWindow：切点落在 before 尾部与 after 头部之间', () => {
  const w = cursorWindow({ before: 'abcdef', after: 'ghij' }, 3)
  assert.equal(w.text, 'defghi')
  assert.equal(w.cut, 3)
})

test('cursorWindow：空上下文给空窗口，不炸', () => {
  const w = cursorWindow(null)
  assert.equal(w.text, '')
  assert.equal(w.cut, 0)
})

// —————————————————————————— matchEntityAt ——————————————————————————

test('命中：实体名跨光标切点', () => {
  // 光标停在「京微资易|科技有限公司」中间
  const hit = matchEntityAt({ before: '由京微资易', after: '科技有限公司持有 51% 股权' }, [COMPANY])
  assert.equal(hit, COMPANY)
})

test('命中：光标停在实体名的起点（端点算命中）', () => {
  const hit = matchEntityAt({ before: '由', after: '京微资易科技有限公司持有' }, [COMPANY])
  assert.equal(hit, COMPANY)
})

test('命中：光标停在实体名的终点（端点算命中）', () => {
  const hit = matchEntityAt({ before: '由京微资易科技有限公司', after: '持有' }, [COMPANY])
  assert.equal(hit, COMPANY)
})

test('命中：正文写的是简称时按 normKey 命中', () => {
  const hit = matchEntityAt({ before: '由京微资', after: '易科技持有' }, [COMPANY])
  assert.equal(hit, COMPANY)
})

test('最长优先：全称与简称都覆盖切点时取全称那个实体', () => {
  const short = { id: 9, kind: 'COMPANY', name: '京微资易科技', normKey: '京微资易科技' }
  const ctx = { before: '由京微资易', after: '科技有限公司持有' }
  // 清单顺序反过来也要稳定取到全称那条
  assert.equal(matchEntityAt(ctx, [short, COMPANY]), COMPANY)
  assert.equal(matchEntityAt(ctx, [COMPANY, short]), COMPANY)
})

test('不命中：实体名在窗口里但不覆盖切点', () => {
  // 「京微资易科技有限公司」整体落在光标之前，光标已经走到句末
  const hit = matchEntityAt({ before: '由京微资易科技有限公司持有股权，', after: '本次交易' }, [COMPANY])
  assert.equal(hit, null)
})

test('不命中：邻域里根本没有实体名', () => {
  assert.equal(matchEntityAt({ before: '本次交易的对价为', after: '人民币一亿元' }, [COMPANY, LAW]), null)
})

test('不命中：空清单 / 空上下文', () => {
  assert.equal(matchEntityAt({ before: 'x', after: 'y' }, []), null)
  assert.equal(matchEntityAt({ before: '', after: '' }, [COMPANY]), null)
  assert.equal(matchEntityAt(null, null), null)
})

test('半径截断：实体名被 radius 切掉后就不再命中', () => {
  const ctx = { before: '由京微资易', after: '科技有限公司持有' }
  assert.equal(matchEntityAt(ctx, [COMPANY], { radius: 2 }), null)
  assert.equal(matchEntityAt(ctx, [COMPANY], { radius: CURSOR_RADIUS }), COMPANY)
})

test('法规实体：带书名号的展示名照样能命中', () => {
  const hit = matchEntityAt({ before: '依据《公司法》', after: '第二十条的规定' }, [LAW])
  assert.equal(hit, LAW)
})

test('太短的名字不参与匹配（一个字全是噪声）', () => {
  const tiny = { id: 3, kind: 'COMPANY', name: '甲', normKey: '甲' }
  assert.equal(matchEntityAt({ before: '由甲', after: '方承担' }, [tiny]), null)
})

// —————————————————————————— buildFixedQuote ——————————————————————————

test('替换串：把 quote 里的数字换成目标值', () => {
  const r = buildFixedQuote('标的公司名下房产共 58 项', '58', '39')
  assert.equal(r.ok, true)
  assert.equal(r.text, '标的公司名下房产共 39 项')
})

test('替换串：带千分位的数字逐字替换', () => {
  const r = buildFixedQuote('注册资本 1,000 万元', '1,000', '2,000')
  assert.equal(r.ok, true)
  assert.equal(r.text, '注册资本 2,000 万元')
})

test('替换串：numberText 不在 quote 里 → missing', () => {
  const r = buildFixedQuote('房产共五十八项', '58', '39')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'missing')
})

test('替换串：numberText 在 quote 里出现多次 → ambiguous（宁可不改）', () => {
  const r = buildFixedQuote('58 项中的 58 项已抵押', '58', '39')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'ambiguous')
})

test('替换串：目标值与原值相同 → same（不产生空改动）', () => {
  const r = buildFixedQuote('房产共 58 项', '58', '58')
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'same')
})

test('替换串：任一入参为空 → empty', () => {
  assert.equal(buildFixedQuote('', '58', '39').reason, 'empty')
  assert.equal(buildFixedQuote('房产共 58 项', '', '39').reason, 'empty')
  assert.equal(buildFixedQuote('房产共 58 项', '58', '').reason, 'empty')
  assert.equal(buildFixedQuote(null, null, null).ok, false)
})

// —————————————————————————— fixSuggestions ——————————————————————————

const MISMATCH = {
  subject: '标的', metric: '房产', unit: '项',
  claims: [
    { quote: '标的公司名下房产共 58 项', value: 58, unit: '项', numberText: '58', fixable: true },
    { quote: '附表二：房产明细共 39 项', value: 39, unit: '项', numberText: '39', fixable: true },
  ],
}

test('建议：两个候选值各一条，各自只改「其余」那条', () => {
  const s = fixSuggestions(MISMATCH)
  assert.equal(s.length, 2)
  const to58 = s.find((x) => x.numberText === '58')
  assert.equal(to58.edits.length, 1)
  assert.equal(to58.edits[0].quote, '附表二：房产明细共 39 项')
  assert.equal(to58.edits[0].replacement, '附表二：房产明细共 58 项')
  const to39 = s.find((x) => x.numberText === '39')
  assert.equal(to39.edits[0].replacement, '标的公司名下房产共 39 项')
})

test('建议：单位跟着 claim 走，缺省回落 detail.unit', () => {
  const s = fixSuggestions({
    unit: '项',
    claims: [
      { quote: 'A 共 58 项', value: 58, numberText: '58', fixable: true },
      { quote: 'B 共 39 项', value: 39, numberText: '39', fixable: true },
    ],
  })
  assert.equal(s[0].unit, '项')
})

test('建议：不可修改的 claim 不进候选、也不被改', () => {
  const s = fixSuggestions({
    claims: [
      { quote: '房产共 58 项', value: 58, unit: '项', numberText: '58', fixable: true },
      { quote: '房产共三十九项', value: 39, unit: '项', fixable: false, fixableReason: '模型改写了原文' },
    ],
  })
  assert.deepEqual(s, [])
})

test('建议：三条 claim 两种取值时，候选值只出现一次且改另外两条里该改的', () => {
  const s = fixSuggestions({
    claims: [
      { quote: '一：58 项', value: 58, unit: '项', numberText: '58', fixable: true },
      { quote: '二：39 项', value: 39, unit: '项', numberText: '39', fixable: true },
      { quote: '三：58 项', value: 58, unit: '项', numberText: '58', fixable: true },
    ],
  })
  assert.equal(s.length, 2)
  const to58 = s.find((x) => x.numberText === '58')
  // 值已经是 58 的那条不动，只改「二」
  assert.equal(to58.edits.length, 1)
  assert.equal(to58.edits[0].replacement, '二：58 项')
  const to39 = s.find((x) => x.numberText === '39')
  assert.equal(to39.edits.length, 2)
})

test('建议：quote 里数字出现多次的那条不生成替换（ambiguous 被滤掉）', () => {
  const s = fixSuggestions({
    claims: [
      { quote: '58 项中的 58 项已抵押', value: 58, unit: '项', numberText: '58', fixable: true },
      { quote: '附表 39 项', value: 39, unit: '项', numberText: '39', fixable: true },
    ],
  })
  // 「统一为 39」要改第一条 → ambiguous 滤掉 → 该候选整体不出现
  assert.equal(s.length, 1)
  assert.equal(s[0].numberText, '58')
  assert.equal(s[0].edits[0].replacement, '附表 58 项')
})

test('建议：空 detail / USCC 形状 → 没有建议', () => {
  assert.deepEqual(fixSuggestions(null), [])
  assert.deepEqual(fixSuggestions({}), [])
  assert.deepEqual(fixSuggestions({ code: '91330100799655058C', quote: '…', fixable: false }), [])
})

// —————————————————————————— 其余小工具 ——————————————————————————

test('fixBlockReason：取第一条不可修改 claim 的理由', () => {
  assert.equal(fixBlockReason({
    claims: [
      { quote: 'A', fixable: true, numberText: '1' },
      { quote: 'B', fixable: false, fixableReason: '正文里对不上这句原话' },
    ],
  }), '正文里对不上这句原话')
  assert.equal(fixBlockReason({ claims: [{ fixable: true }] }), '')
  assert.equal(fixBlockReason(null), '')
})

test('findingLocateQuote：COUNT_MISMATCH 取首条 claim 的 quote，USCC 取 detail.quote', () => {
  assert.equal(findingLocateQuote(MISMATCH), '标的公司名下房产共 58 项')
  assert.equal(findingLocateQuote({ code: '91…', quote: '登记于 91… 的主体' }), '登记于 91… 的主体')
  assert.equal(findingLocateQuote({ claims: [] }), '')
  assert.equal(findingLocateQuote(null), '')
})
