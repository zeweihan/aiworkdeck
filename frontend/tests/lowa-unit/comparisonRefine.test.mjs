// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { loadWorkerFunctions } from './_workerFns.mjs'
const { comparisonRefinePlan } = loadWorkerFunctions(['myersEdits', 'minimalEdits', 'comparisonRefinePlan'])

test('合成服务协议→合成采购协议 重写成仅替换中段「服务」为「采购」', () => {
  const plan = comparisonRefinePlan('合成服务协议', '合成采购协议')
  assert.deepEqual(plan, [{ start: 2, delLen: 2, insText: '采购' }])
})

test('长段落中的散点改动保留公共前缀、后缀与中间未动文字', () => {
  const plan = comparisonRefinePlan('甲方应在合同签订后三个工作日内完成首批交付。', '乙方应在合同签订后五个工作日内完成首批交付。')
  assert.ok(plan && plan.length)
  const deleted = plan.reduce((s, e) => s + e.delLen, 0)
  // 只动「甲→乙」「三→五」等极少数字符，不是整段重写
  // 只动「甲→乙」「三→五」两个字符（edits 从右往左产出，顺序无关断言）
  assert.equal(deleted, 2)
  assert.deepEqual(plan.map(e => e.insText).sort(), ['乙', '五'])
})

test('完全相同的文本不值得重写', () => {
  assert.equal(comparisonRefinePlan('完全一致', '完全一致'), null)
  assert.equal(comparisonRefinePlan('', ''), null)
})

test('纯删除（ newText 为空或旧文被全删）返回 null', () => {
  assert.equal(comparisonRefinePlan('删除整段', ''), null)
})

test('纯插入（oldText 为空）返回 null', () => {
  assert.equal(comparisonRefinePlan('', '新插入内容'), null)
})

test('没有公共前后缀的整块重写返回 null，保持引擎原生形态', () => {
  assert.equal(comparisonRefinePlan('ABCD', 'EFGH'), null)
})

test('null/undefined 输入安全返回 null', () => {
  assert.equal(comparisonRefinePlan(null, 'x'), null)
  assert.equal(comparisonRefinePlan('x', undefined), null)
})
