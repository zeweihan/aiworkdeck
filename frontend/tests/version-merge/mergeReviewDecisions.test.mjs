// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 合并比对稿「完成裁决」提交的 decisions 清单契约（dev-board#630，spec §4.4 / §5.4）。
// 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  collectDecisions, decisionToken, revisionDecisionKey,
} from '../../src/utils/mergeReviewDecisions.js'

const tokens = (list) => list.map(decisionToken)

test('三块合成一份 decisions：同段裁决 / 逐条修订 / 格式未合并', () => {
  const decisions = collectDecisions({
    conflictChoices: [
      { key: 'p12', choice: 'main' },
      { key: 'p9', choice: 'self' },
    ],
    revisionOutcomes: [
      { paraKey: 3, side: 'M', action: 'A' },
      { paraKey: 7, side: 'T', action: 'R' },
    ],
    formatOnly: [{ paraKey: 20 }],
  })
  assert.deepEqual(tokens(decisions), ['p12MA', 'p9X', 'p3MA', 'p7TR', 'p20F'])
})

test('「用律师乙的」记成另一侧被接受（p12TA），「用你的」记成主线侧被接受（p12MA）', () => {
  assert.deepEqual(
    tokens(collectDecisions({ conflictChoices: [{ key: 'p12', choice: 'other' }] })),
    ['p12TA'])
  assert.deepEqual(
    tokens(collectDecisions({ conflictChoices: [{ key: 'p12', choice: 'main' }] })),
    ['p12MA'])
})

test('「自己改」没有侧别，只记一个 X', () => {
  const [d] = collectDecisions({ conflictChoices: [{ key: 'p9', choice: 'self' }] })
  assert.deepEqual(d, { key: 'p9', side: '', action: 'X' })
  assert.equal(decisionToken(d), 'p9X')
})

test('格式未合并只记录，没有侧别也没有接受/拒绝', () => {
  const [d] = collectDecisions({ formatOnly: [{ paraKey: 20, preview: '第二十段前四十字' }] })
  assert.deepEqual(d, { key: 'p20', side: '', action: 'F' })
})

test('还没处理的同段冲突不进清单（挡住「完成裁决」的那一处不该被当成已裁决）', () => {
  const decisions = collectDecisions({
    conflictChoices: [
      { key: 'p12', choice: 'main' },
      { key: 'p14', choice: '' },
      { key: 'p15' },
      { key: 'p16', choice: 'whatever' },
    ],
  })
  assert.deepEqual(tokens(decisions), ['p12MA'])
})

test('块 2 每条修订各记一条，接受 A、拒绝 R，两侧分别记 M / T', () => {
  const decisions = collectDecisions({
    revisionOutcomes: [
      { paraKey: 3, side: 'M', action: 'A' },
      { paraKey: 4, side: 'M', action: 'R' },
      { paraKey: 5, side: 'T', action: 'A' },
      { paraKey: 6, side: 'T', action: 'R' },
    ],
  })
  assert.deepEqual(tokens(decisions), ['p3MA', 'p4MR', 'p5TA', 'p6TR'])
})

test('表格里的修订按 t 键记，不按段落序（inTable 的段落序在正文序里没有位置）', () => {
  const decisions = collectDecisions({
    revisionOutcomes: [
      { paraKey: 8, inTable: true, tableKey: 't1.2.3', side: 'M', action: 'A' },
    ],
  })
  assert.deepEqual(tokens(decisions), ['t1.2.3MA'])
  assert.equal(revisionDecisionKey({ paraKey: 8, inTable: true, tableKey: 't1.2.3' }), 't1.2.3')
  assert.equal(revisionDecisionKey({ paraKey: 8 }), 'p8')
})

test('表格修订缺 t 键时退回段落序，不丢这一条（宁可键粗一点也不能少记一处裁决）', () => {
  const decisions = collectDecisions({
    revisionOutcomes: [{ paraKey: 8, inTable: true, side: 'T', action: 'R' }],
  })
  assert.deepEqual(tokens(decisions), ['p8TR'])
})

test('同一段多条修订同样处置只记一次（尾注是逐处裁决的账，不是修订条数的账）', () => {
  const decisions = collectDecisions({
    revisionOutcomes: [
      { paraKey: 3, side: 'M', action: 'A' },
      { paraKey: 3, side: 'M', action: 'A' },
      { paraKey: 3, side: 'M', action: 'R' },
    ],
  })
  assert.deepEqual(tokens(decisions), ['p3MA', 'p3MR'])
})

test('块 1 已记的键与块 2 撞上时也只留一条', () => {
  const decisions = collectDecisions({
    conflictChoices: [{ key: 'p12', choice: 'main' }],
    revisionOutcomes: [{ paraKey: 12, side: 'M', action: 'A' }],
  })
  assert.deepEqual(tokens(decisions), ['p12MA'])
})

test('侧别或动作不合法的条目一律丢掉，不把脏值写进尾注', () => {
  const decisions = collectDecisions({
    revisionOutcomes: [
      { paraKey: 3, side: 'X', action: 'A' },
      { paraKey: 4, side: 'M', action: 'Z' },
      { paraKey: 5, side: 'M' },
      { side: 'M', action: 'A' },
    ],
  })
  assert.deepEqual(decisions, [])
})

test('没有任何输入时是空数组，不是 null（调用方直接 JSON.stringify）', () => {
  assert.deepEqual(collectDecisions(), [])
  assert.deepEqual(collectDecisions({}), [])
})

test('formatOnly 直接给 key 时照用（表格/非段落单元也能记格式未合并）', () => {
  assert.deepEqual(
    tokens(collectDecisions({ formatOnly: [{ key: 't1.2.3' }, { paraKey: 20 }] })),
    ['t1.2.3F', 'p20F'])
})
