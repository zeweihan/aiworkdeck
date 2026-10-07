// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 段落格式快捷面板的纯函数：get_formatting ↔ 表单 ↔ set_paragraph_format 的换算。
// 跑法：cd frontend && node --test tests/project-home/paragraph-format.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  formFromFormatting, paramsFromForm, applyPreset, buildAiFormatPrompt, CN_FONT_SIZES,
} from '../../src/utils/paragraphFormat.js'

const fmt = (pa, sizePt = 12) => ({ success: true, character: { sizePt }, paragraph: pa })

test('首行缩进按光标处字号折成字符，往返不丢', () => {
  const { form, chPt } = formFromFormatting(fmt({ alignment: 'justify', firstLineIndentPt: 32, leftIndentPt: 0, rightIndentPt: 0,
    spaceBeforePt: 0, spaceAfterPt: 6, lineSpacing: { mode: 'proportional', percent: 150 } }, 16))
  assert.equal(chPt, 16)
  assert.equal(form.indentMode, 'firstLine')
  assert.equal(form.indentChars, 2)
  assert.equal(form.lineMode, '1.5')
  const { params } = paramsFromForm(form, chPt)
  assert.equal(params.firstLineIndentPt, 32)
  assert.equal(params.leftIndentPt, 0)
  assert.equal(params.lineSpacingMode, '1.5')
  assert.equal(params.spaceAfterPt, 6)
})

test('悬挂缩进 = 首行负值 + 左缩进补回同样的量（Word 口径），读回时左缩进去掉悬挂量', () => {
  const { params } = paramsFromForm({ indentMode: 'hanging', indentChars: 2, leftChars: 1 }, 12)
  assert.equal(params.firstLineIndentPt, -24)
  assert.equal(params.leftIndentPt, 36)
  const { form } = formFromFormatting(fmt({ firstLineIndentPt: -24, leftIndentPt: 36 }))
  assert.equal(form.indentMode, 'hanging')
  assert.equal(form.indentChars, 2)
  assert.equal(form.leftChars, 1)
})

test('无特殊缩进时首行写 0（把原有首行缩进清掉，而不是不发）', () => {
  const { params } = paramsFromForm({ indentMode: 'none', leftChars: 0 }, 12)
  assert.equal(params.firstLineIndentPt, 0)
})

test('行距：多倍走 proportional 百分比，固定值/最小值走磅；非法值报错不发', () => {
  assert.deepEqual(
    [paramsFromForm({ lineMode: 'multiple', lineValue: 1.25 }, 12).params.lineSpacingMode, paramsFromForm({ lineMode: 'multiple', lineValue: 1.25 }, 12).params.lineSpacingValue],
    ['proportional', 125])
  const ex = paramsFromForm({ lineMode: 'exactly', lineValue: 28 }, 12).params
  assert.equal(ex.lineSpacingMode, 'exactly'); assert.equal(ex.lineSpacingValue, 28)
  assert.equal(paramsFromForm({ lineMode: 'exactly', lineValue: 0 }, 12).error, 'badLineValue')
  assert.equal(paramsFromForm({ spaceBeforePt: -1 }, 12).error, 'badSpacing')
  // 引擎读回的 proportional 120% 落到「多倍 1.2」
  assert.equal(formFromFormatting(fmt({ lineSpacing: { mode: 'proportional', percent: 120 } })).form.lineMode, 'multiple')
  assert.equal(formFromFormatting(fmt({ lineSpacing: { mode: 'exactly', valuePt: 28 } })).form.lineValue, 28)
})

test('读不到字号按 12pt 折算，不除以 0', () => {
  const { chPt, form } = formFromFormatting({ paragraph: { firstLineIndentPt: 24 } })
  assert.equal(chPt, 12)
  assert.equal(form.indentChars, 2)
})

test('预设只改它列出的字段，其余沿用当前段落', () => {
  const base = { ...formFromFormatting(fmt({ alignment: 'center', rightIndentPt: 24 })).form }
  const next = applyPreset(base, 'body')
  assert.equal(next.indentMode, 'firstLine')
  assert.equal(next.alignment, 'justify')
  assert.equal(next.rightChars, 2)        // body 预设不碰右缩进
  assert.deepEqual(applyPreset(base, 'nope'), base)
})

test('中文字号表：小四 = 12，五号 = 10.5，三号 = 16', () => {
  const by = Object.fromEntries(CN_FONT_SIZES.map((s) => [s.name, s.pt]))
  assert.equal(by['小四'], 12); assert.equal(by['五号'], 10.5); assert.equal(by['三号'], 16)
})

test('交给 AI 的那句话带文件名、范围与需求；需求空时给默认要求', () => {
  const t = (k, v) => k + (v ? JSON.stringify(v) : '')
  const p = buildAiFormatPrompt({ fileName: '合同.docx', requirement: '  仿宋三号 ', scope: 'document' }, t)
  assert.match(p, /editor\.format\.aiPrompt/)
  assert.match(p, /合同\.docx/)
  assert.match(p, /仿宋三号/)
  assert.match(p, /aiScopeDocument/)
  const q = buildAiFormatPrompt({ fileName: '', requirement: '', scope: 'selection' }, t)
  assert.match(q, /aiPromptDocFallback/)
  assert.match(q, /aiDefaultRequirement/)
  assert.match(q, /aiScopeSelection/)
})
