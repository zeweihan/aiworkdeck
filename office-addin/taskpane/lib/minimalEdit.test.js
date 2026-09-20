// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 词级最小修订差分（dev-board#716）。
 *
 * 病灶：改造前按 UTF-16 码元做 LCS，AI 把 "kicking off" 改写成 "commencing"
 * 时修订面板读作 "commenc~~kick~~ing ~~off~~"——差异段在词中间切开，Word 把
 * 插入与删除交错渲染，一句整句改写变成二十多个删/插块（真机截图
 * office-addin/appsource/screenshots/01-word-mac-tracked-changes.png）。
 *
 * 本文件钉死三件事：
 *  1. 差异段边界**只落在词边界上**（`assertWordAligned`，把颗粒度换回字符级即转红）；
 *  2. 相邻的小改动按「中间未变部分少于 3 个词」合并，一句改写落成 1~3 段；
 *  3. **接受全部修订后的正文与改前完全一致**（`applyEdits` 恒等，含随机脚本）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { minimalEdits, substringEdits } from './minimalEdit.js'

/** 按差分段逐段替换，还原出新文（模拟「接受全部修订」） */
function applyEdits(oldStr, edits) {
  let out = ''
  let cursor = 0
  for (const e of edits) {
    out += oldStr.slice(cursor, e.start) + e.newText
    cursor = e.end
  }
  return out + oldStr.slice(cursor)
}

/** 结构不变式：升序、互不重叠、oldText 与原串逐字相符 */
function assertShape(oldStr, edits) {
  let prevEnd = -1
  for (const e of edits) {
    assert.ok(e.start >= prevEnd, `编辑段必须升序且不重叠：${JSON.stringify(e)}`)
    assert.ok(e.end >= e.start && e.end <= oldStr.length, `越界：${JSON.stringify(e)}`)
    assert.equal(e.oldText, oldStr.slice(e.start, e.end), 'oldText 必须是原串的切片')
    assert.ok(e.oldText !== '' || e.newText !== '', '不许产出空编辑段')
    prevEnd = e.end
  }
}

const WORD_CHAR = /[0-9A-Za-zÀ-ɏ]/

/** 边界落在英文单词中间即视为字符级颗粒度——本用例组的核心断言 */
function assertWordAligned(oldStr, edits) {
  for (const e of edits) {
    for (const at of [e.start, e.end]) {
      if (at <= 0 || at >= oldStr.length) continue
      const cut = WORD_CHAR.test(oldStr[at - 1]) && WORD_CHAR.test(oldStr[at])
      assert.equal(cut, false,
        `差异段边界切在单词中间（偏移 ${at}）：…${oldStr.slice(Math.max(0, at - 8), at)}|${oldStr.slice(at, at + 8)}…`)
    }
  }
}

/** 一次跑完：形状 + 词对齐 + 正文恒等，返回编辑段供逐例断言 */
function diff(oldStr, newStr) {
  const edits = minimalEdits(oldStr, newStr)
  assertShape(oldStr, edits)
  assertWordAligned(oldStr, edits)
  assert.equal(applyEdits(oldStr, edits), newStr, '接受全部修订后的正文必须等于新文')
  return edits
}

/* ==================== 四种典型改写 ==================== */

test('英文句：词组改写落成一段整块替换（截图病灶 kicking off → commencing）', () => {
  const before = 'Northwind and Contoso want to run a pilot programme together, kicking off on 1 March 2026.'
  const after = 'Northwind and Contoso want to run a pilot programme together, commencing on 1 March 2026.'
  const edits = diff(before, after)
  assert.equal(edits.length, 1)
  assert.equal(edits[0].oldText, 'kicking off')
  assert.equal(edits[0].newText, 'commencing')
})

test('英文句：一句里两处改动，中间保留 4 个未改词时分成两段', () => {
  const before = 'The Buyer shall pay the price within 30 days.'
  const after = 'The Purchaser must pay the price within 60 days.'
  const edits = diff(before, after)
  // Buyer→Purchaser 与 shall→must 之间只隔一个空格（0 个词）→ 并成一段；
  // 30→60 与它之间隔着 pay the price within（4 个词）→ 保持分开
  assert.equal(edits.length, 2)
  assert.equal(edits[0].oldText, 'Buyer shall')
  assert.equal(edits[0].newText, 'Purchaser must')
  assert.equal(edits[1].oldText, '30')
  assert.equal(edits[1].newText, '60')
})

test('中文句：逐字成词，三处改动各自独立（中间隔着 3 个字以上）', () => {
  const before = '甲方应当在收到发票后三十日内支付合同价款。'
  const after = '乙方应当在收到发票后六十日内支付全部合同价款。'
  const edits = diff(before, after)
  assert.equal(edits.length, 3)
  assert.deepEqual(edits.map((e) => [e.oldText, e.newText]), [
    ['甲', '乙'],
    ['三', '六'],
    ['', '全部']
  ])
})

test('中文句：相邻改动中间只隔 2 个字时并成一段（不再是两段夹一个「双方」）', () => {
  const before = '本协议自双方签字之日起生效。'
  const after = '本合同经双方盖章之日起生效。'
  const edits = diff(before, after)
  assert.equal(edits.length, 1)
  assert.equal(edits[0].oldText, '协议自双方签字')
  assert.equal(edits[0].newText, '合同经双方盖章')
})

test('只改一个词：只标那一个词', () => {
  const edits = diff('The Seller shall deliver the goods.', 'The Seller must deliver the goods.')
  assert.equal(edits.length, 1)
  assert.equal(edits[0].oldText, 'shall')
  assert.equal(edits[0].newText, 'must')
})

test('整句重写：落成一段连续的删+插，而不是二十多个交错块', () => {
  const before = 'We figure the terms below are pretty much what both sides are after, and the rest can get sorted out later on.'
  const after = 'The parties consider the following terms to reflect their mutual understanding, with the remaining details to be agreed at a later stage.'
  const edits = diff(before, after)
  assert.equal(edits.length, 1)
  // 句末的句号是公共后缀，不该被卷进替换段
  assert.equal(edits[0].end, before.length - 1)
})

/* ==================== 合并阈值 ==================== */

test('合并阈值：中间未变 2 个词 → 并成一段；3 个词 → 保持两段', () => {
  const twoWords = diff('alpha beta gamma delta', 'ALPHA beta gamma DELTA')
  assert.equal(twoWords.length, 1)
  assert.equal(twoWords[0].oldText, 'alpha beta gamma delta')

  const threeWords = diff('alpha beta gamma theta delta', 'ALPHA beta gamma theta DELTA')
  assert.equal(threeWords.length, 2)
  assert.deepEqual(threeWords.map((e) => e.oldText), ['alpha', 'delta'])
})

test('合并不越过 Word 查找串 255 字上限（越限就没法回文档里定位、只能整段替换）', () => {
  const run = (prefix, n) => Array.from({ length: n }, (_, i) => `${prefix}${i}`).join(' ')
  // 两大块各自整块改写，中间只隔一个 and（1 个词，本该合并），但并起来超过 255 字
  const before = `${run('alpha', 40)} and ${run('beta', 10)}`
  const after = `${run('gamma', 40)} and ${run('delta', 10)}`
  assert.ok(before.length > 255, '用例前提：整段超过 255 字')
  const edits = diff(before, after)
  assert.equal(edits.length, 2, '合并会超限，必须保持两段')
  for (const e of edits) assert.ok(e.oldText.length <= 255, `单段旧文超限：${e.oldText.length}`)
})

/* ==================== 形态与边界 ==================== */

test('纯插入与纯删除：start === end / newText 为空', () => {
  const inserted = diff('The party shall pay the price.', 'The party shall pay the full price.')
  assert.equal(inserted.length, 1)
  assert.equal(inserted[0].start, inserted[0].end)
  assert.equal(inserted[0].oldText, '')

  const deleted = diff('The party shall pay the full price.', 'The party shall pay the price.')
  assert.equal(deleted.length, 1)
  assert.equal(deleted[0].newText, '')
})

test('相同串 / 空串 / null 入参', () => {
  assert.deepEqual(minimalEdits('同一段文字', '同一段文字'), [])
  assert.deepEqual(minimalEdits('', ''), [])
  assert.deepEqual(minimalEdits(null, null), [])
  assert.deepEqual(minimalEdits('', 'abc'), [{ start: 0, end: 0, oldText: '', newText: 'abc' }])
  assert.deepEqual(minimalEdits('abc', ''), [{ start: 0, end: 3, oldText: 'abc', newText: '' }])
})

test('代理对（emoji）整体成词，不会被劈成孤立代理项', () => {
  const before = '交付节点 🚀 已确认'
  const after = '交付节点 🛳 已确认'
  const edits = diff(before, after)
  assert.equal(edits.length, 1)
  assert.equal(edits[0].oldText, '🚀')
  for (const e of edits) {
    for (const ch of e.oldText + e.newText) {
      const code = ch.charCodeAt(0)
      assert.equal(code >= 0xdc00 && code <= 0xdfff, false)
    }
  }
})

test('中段过大时退回一次性替换，正文仍然恒等', () => {
  const before = Array.from({ length: 600 }, (_, i) => `词${i}`).join('，')
  const after = Array.from({ length: 600 }, (_, i) => `句${i}`).join('；')
  const edits = diff(before, after)
  assert.equal(edits.length, 1)
})

/* ==================== 随机脚本：正文恒等 ==================== */

test('随机改写脚本：接受全部修订后的正文恒等于新文', () => {
  const zh = ['甲方', '乙方', '应当', '支付', '价款', '违约', '赔偿', '协议', '解除', '通知', '三十', '日内']
  const en = ['party', 'shall', 'pay', 'price', 'within', 'thirty', 'days', 'notice', 'breach', 'terminate']
  let seed = 20260916
  const rand = () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    return seed / 0x7fffffff
  }
  const pick = (arr) => arr[Math.floor(rand() * arr.length)]

  for (let round = 0; round < 300; round++) {
    const chinese = rand() < 0.5
    const len = 3 + Math.floor(rand() * 12)
    const words = Array.from({ length: len }, () => pick(chinese ? zh : en))
    const before = chinese ? words.join('') : words.join(' ')
    const mutated = words.slice()
    const mutations = 1 + Math.floor(rand() * 3)
    for (let k = 0; k < mutations; k++) {
      const at = Math.floor(rand() * mutated.length)
      const kind = rand()
      if (kind < 0.34) mutated[at] = pick(chinese ? zh : en)
      else if (kind < 0.67) mutated.splice(at, 1)
      else mutated.splice(at, 0, pick(chinese ? zh : en))
      if (!mutated.length) mutated.push(pick(chinese ? zh : en))
    }
    const after = chinese ? mutated.join('') : mutated.join(' ')
    const edits = minimalEdits(before, after)
    assertShape(before, edits)
    assert.equal(applyEdits(before, edits), after,
      `第 ${round} 轮不恒等：\n  before=${before}\n  after=${after}`)
  }
})

/* ==================== substringEdits：给「切不出零长度区间」的宿主用（dev-board#717） ==================== */
// 跨文档写入的撤销要把 PPT 文本框改回原文。整框回写会抹掉框内分段格式与超链接，
// 所以按差异段落笔——但 PowerPoint 的 getSubstring / WPS 的 Characters 切零长度区间
// 行为未经验证，纯插入要借一个相邻字符变成「替换 1 个字」。

test('substringEdits：纯插入借右邻字符变成替换，不产出零长度区间', () => {
  const edits = substringEdits('甲方盖章', '甲方签字盖章')
  for (const e of edits) assert.ok(e.end > e.start, JSON.stringify(e))
  assert.equal(applyEdits('甲方盖章', edits), '甲方签字盖章')
})

test('substringEdits：插在末尾时借左邻字符', () => {
  const edits = substringEdits('甲方', '甲方盖章')
  for (const e of edits) assert.ok(e.end > e.start, JSON.stringify(e))
  assert.equal(applyEdits('甲方', edits), '甲方盖章')
})

test('substringEdits：借字不劈开代理对', () => {
  const before = 'a\u{1F600}'
  const edits = substringEdits(before, before + 'b')
  for (const e of edits) {
    assert.ok(e.end > e.start)
    assert.equal(e.oldText, before.slice(e.start, e.end))
    assert.ok(!/^[\uDC00-\uDFFF]/.test(e.oldText), '不许从低代理项开始切')
  }
  assert.equal(applyEdits(before, edits), before + 'b')
})

test('substringEdits：原串为空时只能整体赋值（交给调用方处理），不越界', () => {
  const edits = substringEdits('', '甲方')
  assert.deepEqual(edits, [{ start: 0, end: 0, oldText: '', newText: '甲方' }])
  assert.deepEqual(substringEdits('同', '同'), [])
})

test('substringEdits 随机脚本：从右到左逐段落笔后恒等于目标文本', () => {
  const zh = ['甲方', '乙方', '应当', '支付', '价款', '违约', '赔偿']
  let seed = 717
  const rand = () => {
    seed = (seed * 1103515245 + 12345) & 0x7fffffff
    return seed / 0x7fffffff
  }
  const pick = (arr) => arr[Math.floor(rand() * arr.length)]
  for (let round = 0; round < 300; round++) {
    const words = Array.from({ length: 2 + Math.floor(rand() * 8) }, () => pick(zh))
    const before = words.join('')
    const mutated = words.slice()
    const at = Math.floor(rand() * (mutated.length + 1))
    if (rand() < 0.5) mutated.splice(at, 0, pick(zh))
    else mutated.splice(Math.min(at, mutated.length - 1), 1)
    const after = mutated.join('')
    const edits = substringEdits(before, after)
    let text = before
    for (let k = edits.length - 1; k >= 0; k--) {
      const e = edits[k]
      assert.ok(e.end > e.start, `第 ${round} 轮出现零长度区间`)
      text = text.slice(0, e.start) + e.newText + text.slice(e.end)
    }
    assert.equal(text, after, `第 ${round} 轮不恒等：${before} -> ${after}`)
  }
})
