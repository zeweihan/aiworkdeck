// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 提交历史里「这一版合并了什么」的说法（dev-board#630 / #632）。
 *
 * 病灶（设计稿 §0 最后一条）：既有裁决文案把 MAIN 一律说成「你这边」，而 MAIN 的
 * 物理侧在三个语境里不是同一件事——结束工作撞车时 MAIN 是**同事**的。方向说反，
 * 律师读到的是一句与事实相反的历史记录。翻译因此按 mergeContext 走，且全在纯函数里。
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { historyMergeLines, resolutionLine, mergeSideLabels } from '../../src/utils/historyMerges.js'

// 嵌套代入（一句话里套着另一句话）在这里很常见，所以桩函数不用 JSON——
// JSON.stringify 会把内层的引号连着反斜杠一起转义掉，断言就得对着转义后的形态写。
const t = (key, params) => `${key}(${Object.keys(params || {}).map((k) => `${k}=${params[k]}`).join(',')})`
const dec = (key, side, action) => ({ key, side, action })

// ---------------- 三语境的方向 ----------------

test('三语境各自的 MAIN / DRAFT 说法（方向表见 version-control.md）', () => {
  assert.deepEqual(mergeSideLabels(t, 'adopt'), {
    main: 'version.mergeSideMainline()', other: 'version.mergeSideDraft()',
  })
  assert.deepEqual(mergeSideLabels(t, 'cloud'), {
    main: 'version.mergeSideYours()', other: 'version.mergeSideLibrary()',
  })
  // 这一条就是病灶本身：session-end 的 MAIN 是同事的，不是「你这边」
  assert.deepEqual(mergeSideLabels(t, 'session-end'), {
    main: 'version.mergeSideColleague()', other: 'version.mergeSideYou()',
  })
})

test('拿得到那一侧尖端提交的作者名时用名字，拿不到才用语境默认词', () => {
  const l = mergeSideLabels(t, 'cloud', { other: '张律师' })
  assert.equal(l.other, '张律师')
  assert.equal(l.main, 'version.mergeSideYours()')
  assert.equal(mergeSideLabels(t, 'cloud', { other: '  ' }).other, 'version.mergeSideLibrary()')
})

test('语境缺席（老提交）不编方向：两侧都用中性词', () => {
  const l = mergeSideLabels(t, '')
  assert.equal(l.main, 'version.mergeSideMainNeutral()')
  assert.equal(l.other, 'version.mergeSideOtherNeutral()')
})

// ---------------- 自动合并那一句 ----------------

test('auto 项：说清合并了谁的改动、两边各几处', () => {
  const entry = {
    mergeContext: 'cloud',
    merges: [{ path: '合同.docx', mode: 'auto', decisions: [], mainCount: 3, otherCount: 4 }],
  }
  const [line] = historyMergeLines(t, entry)
  assert.ok(line.text.startsWith('version.mergeAutoLine('), line.text)
  assert.ok(line.text.includes('合同.docx'), line.text)
  assert.ok(line.text.includes('mainCount=3'), line.text)
  assert.ok(line.text.includes('otherCount=4'), line.text)
  assert.ok(line.text.includes('version.mergeSideLibrary'), line.text)
  assert.equal(line.more, 0)
})

test('auto 句里的「你」跟着语境走：结束工作撞车时合并进来的是同事的改动', () => {
  const entry = {
    mergeContext: 'session-end',
    merges: [{ path: '合同.docx', mode: 'auto', decisions: [], mainCount: 3, otherCount: 4 }],
  }
  const [line] = historyMergeLines(t, entry)
  assert.ok(line.text.includes('version.mergeSideColleague'), line.text)
  assert.ok(!line.text.includes('version.mergeSideYours'), line.text)
})

// ---------------- 逐处裁决那一句 ----------------

test('manual 项：逐处说清哪一段留了谁的', () => {
  const entry = {
    mergeContext: 'adopt',
    merges: [{
      path: '合同.docx', mode: 'manual', mainCount: 0, otherCount: 0,
      decisions: [dec('p3', 'M', 'A'), dec('p7', 'T', 'A'), dec('p15', 'T', 'R')],
    }],
  }
  const [line] = historyMergeLines(t, entry)
  assert.ok(line.text.startsWith('version.mergeManualLine('), line.text)
  assert.ok(line.text.includes('version.mergeUnitParagraph'), line.text)
  assert.ok(line.text.includes('n=3'), line.text)
  assert.ok(line.text.includes('version.mergeDecisionKept'), line.text)
  assert.ok(line.text.includes('version.mergeDecisionRejected'), line.text)
  assert.equal(line.more, 0)
})

test('同一段两边都接受了 = 「两边都留」，不是两句话', () => {
  const entry = {
    mergeContext: 'adopt',
    merges: [{ path: 'a.docx', mode: 'manual', decisions: [dec('p12', 'M', 'A'), dec('p12', 'T', 'A')] }],
  }
  const [line] = historyMergeLines(t, entry)
  assert.ok(line.text.includes('version.mergeDecisionBoth'), line.text)
  // 「第 12 段」只出现一次
  assert.equal(line.text.split('n=12').length - 1, 1, line.text)
})

test('接受这边 + 拒绝那边 = 留了这边的一句话', () => {
  const entry = {
    mergeContext: 'adopt',
    merges: [{ path: 'a.docx', mode: 'manual', decisions: [dec('p12', 'M', 'A'), dec('p12', 'T', 'R')] }],
  }
  const [line] = historyMergeLines(t, entry)
  assert.ok(line.text.includes('version.mergeDecisionKept'), line.text)
  assert.ok(!line.text.includes('version.mergeDecisionBoth'), line.text)
  assert.ok(!line.text.includes('version.mergeDecisionRejected'), line.text)
})

test('「自己改的」与「格式没合」各有各的说法，不混进接受/拒绝', () => {
  const entry = {
    mergeContext: 'adopt',
    merges: [{ path: 'a.docx', mode: 'manual', decisions: [dec('p9', '', 'X'), dec('p20', '', 'F')] }],
  }
  const [line] = historyMergeLines(t, entry)
  assert.ok(line.text.includes('version.mergeDecisionSelf'), line.text)
  assert.ok(line.text.includes('version.mergeDecisionFormat'), line.text)
})

test('单元键翻成律师的话：段落 / 表格单元 / 演示页；表格类键不说成「第 N 段」', () => {
  const entry = {
    mergeContext: 'adopt',
    merges: [{
      path: 'a.docx', mode: 'manual',
      decisions: [dec('t1.2.3', 'M', 'A'), dec('s3', 'T', 'A'), dec('Sheet1!B7', 'M', 'A')],
    }],
  }
  const [line] = historyMergeLines(t, entry)
  assert.ok(line.text.includes('version.mergeUnitCell'), line.text)
  assert.ok(line.text.includes('version.mergeUnitSlide'), line.text)
  assert.ok(line.text.includes('Sheet1!B7'), line.text)
  assert.ok(!line.text.includes('version.mergeUnitParagraph'), line.text)
})

test('超过 6 处折叠成「等 N 处」，详情区那份仍是全量', () => {
  const decisions = []
  for (let i = 1; i <= 9; i++) decisions.push(dec('p' + i, 'M', 'A'))
  const entry = { mergeContext: 'adopt', merges: [{ path: 'a.docx', mode: 'manual', decisions }] }
  const [line] = historyMergeLines(t, entry)
  assert.equal(line.more, 3)
  assert.ok(line.text.includes('version.mergeManualMore'), line.text)
  assert.ok(line.text.includes('count=3'), line.text)
  // 折叠的那一句只列前 6 处，展开的那一句列满 9 处
  assert.equal(line.text.split('version.mergeUnitParagraph').length - 1, 6, line.text)
  assert.equal(line.full.split('version.mergeUnitParagraph').length - 1, 9, line.full)
  assert.ok(!line.full.includes('version.mergeManualMore'), line.full)
})

test('没有 merges 的版本一行都不出（绝大多数版本就没合并过）', () => {
  assert.deepEqual(historyMergeLines(t, { mergeContext: 'adopt' }), [])
  assert.deepEqual(historyMergeLines(t, null), [])
  assert.deepEqual(historyMergeLines(t, { merges: [{}, null] }), [])
})

test('多份文件各占一行', () => {
  const entry = {
    mergeContext: 'cloud',
    merges: [
      { path: 'a.docx', mode: 'auto', mainCount: 1, otherCount: 1 },
      { path: 'b.xlsx', mode: 'manual', decisions: [dec('Sheet1!B7', 'T', 'A')] },
    ],
  }
  assert.equal(historyMergeLines(t, entry).length, 2)
})

// ---------------- 整份三选一那一句（既有文案的方向修正） ----------------

test('有语境时按语境说方向——session-end 的 MAIN 是同事的', () => {
  const out = resolutionLine(t, { path: 'a.docx', kept: 'MAIN' }, 'session-end')
  assert.ok(out.startsWith('version.resolutionKeptSide('), out)
  assert.ok(out.includes('version.mergeSideColleague'), out)
})

test('语境缺席（老提交）保持旧文案，不改写历史的说法', () => {
  assert.ok(resolutionLine(t, { path: 'a.docx', kept: 'MAIN' }, '').startsWith('version.resolutionKeptMain('))
  assert.ok(resolutionLine(t, { path: 'a.docx', kept: 'DRAFT' }, '').startsWith('version.resolutionKeptDraft('))
  assert.ok(resolutionLine(t, { path: 'a.docx', kept: 'BOTH' }, '').startsWith('version.resolutionKeptBoth('))
})

test('两边都留与逐处合并各说各的；认不出的值退回「两边都留」（老/新客户端互看）', () => {
  assert.ok(resolutionLine(t, { path: 'a.docx', kept: 'BOTH' }, 'cloud').startsWith('version.resolutionKeptBoth('))
  assert.ok(resolutionLine(t, { path: 'a.docx', kept: 'MERGED' }, 'cloud').startsWith('version.resolutionMerged('))
  assert.ok(resolutionLine(t, { path: 'a.docx', kept: 'WAT' }, 'cloud').startsWith('version.resolutionKeptBoth('))
})
