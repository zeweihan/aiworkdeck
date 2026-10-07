// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// paragraphFormat.js — 「段落格式」快捷面板的纯函数（编辑器工具栏用）。
//
// 面板读 worker 的 get_formatting（单位 pt），写 set_paragraph_format（同样 pt）。
// 中文排版习惯用「字符」表达缩进（首行缩进 2 字符），这里按光标处字号把字符数折算成
// pt——与 worker set_paragraph_format 的 firstLineIndentChars 同一口径（一个汉字宽 =
// 一个字号）。折算放在宿主而不是用 firstLineIndentChars，是因为悬挂缩进要同时写首行
// （负值）和左缩进，两者必须用同一个字宽。
//
// 拆成纯函数是为了 node:test 能直接测，组件只管渲染和发命令。

// 工作台订阅的「往 AI 对话发一句话」事件（project-overview → resolveChatInterface →
// sendExternalPrompt）。payload {prompt, fileId?}。
export const AI_PROMPT_EVENT = 'awd:ai-prompt'

// 中文字号（Word「字号」下拉的中文档位）。pt 值按 GB/T 与 Word 中文版一致。
export const CN_FONT_SIZES = [
  { name: '初号', pt: 42 }, { name: '小初', pt: 36 }, { name: '一号', pt: 26 }, { name: '小一', pt: 24 },
  { name: '二号', pt: 22 }, { name: '小二', pt: 18 }, { name: '三号', pt: 16 }, { name: '小三', pt: 15 },
  { name: '四号', pt: 14 }, { name: '小四', pt: 12 }, { name: '五号', pt: 10.5 }, { name: '小五', pt: 9 },
  { name: '六号', pt: 7.5 },
]
export const PT_FONT_SIZES = [8, 9, 10, 10.5, 11, 12, 14, 16, 18, 20, 22, 24, 26, 28, 36, 48, 72]

export const LINE_MODES = ['single', '1.5', 'double', 'multiple', 'atLeast', 'exactly']
export const INDENT_MODES = ['none', 'firstLine', 'hanging']
export const ALIGNMENTS = ['left', 'center', 'right', 'justify']

const round1 = (n) => Math.round(Number(n) * 10) / 10
const num = (v, d = 0) => { const n = Number(v); return Number.isFinite(n) ? n : d }

export function emptyForm() {
  return {
    alignment: 'justify', indentMode: 'none', indentChars: 2,
    leftChars: 0, rightChars: 0, spaceBeforePt: 0, spaceAfterPt: 0,
    lineMode: 'single', lineValue: 1,
  }
}

// get_formatting → 表单。chPt = 光标处字号（读不到按 12pt），缩进按它折成字符。
export function formFromFormatting(fmt) {
  const f = emptyForm()
  const pa = (fmt && fmt.paragraph) || {}
  const ch = (fmt && fmt.character) || {}
  const chPt = num(ch.sizePt, 0) > 0 ? num(ch.sizePt) : 12
  if (ALIGNMENTS.includes(pa.alignment)) f.alignment = pa.alignment
  const first = num(pa.firstLineIndentPt)
  const left = num(pa.leftIndentPt)
  f.rightChars = round1(num(pa.rightIndentPt) / chPt)
  if (first > 0.05) { f.indentMode = 'firstLine'; f.indentChars = round1(first / chPt); f.leftChars = round1(left / chPt) }
  else if (first < -0.05) {
    // 悬挂缩进：Word 的「左缩进」显示的是去掉悬挂量之后的值
    const hang = -first
    f.indentMode = 'hanging'; f.indentChars = round1(hang / chPt); f.leftChars = round1((left - hang) / chPt)
  } else { f.indentMode = 'none'; f.indentChars = 2; f.leftChars = round1(left / chPt) }
  f.spaceBeforePt = round1(num(pa.spaceBeforePt))
  f.spaceAfterPt = round1(num(pa.spaceAfterPt))
  const ls = pa.lineSpacing || null
  if (ls && ls.mode === 'proportional') {
    const pct = num(ls.percent, 100)
    if (Math.abs(pct - 100) < 1) { f.lineMode = 'single'; f.lineValue = 1 }
    else if (Math.abs(pct - 150) < 1) { f.lineMode = '1.5'; f.lineValue = 1.5 }
    else if (Math.abs(pct - 200) < 1) { f.lineMode = 'double'; f.lineValue = 2 }
    else { f.lineMode = 'multiple'; f.lineValue = Math.round(pct) / 100 }
  } else if (ls && (ls.mode === 'atLeast' || ls.mode === 'exactly')) {
    f.lineMode = ls.mode; f.lineValue = round1(num(ls.valuePt, 12))
  }
  return { form: f, chPt }
}

// 表单 → set_paragraph_format 参数。只发合法值；出错返回 {error}（i18n 键尾）。
export function paramsFromForm(form, chPt) {
  const c = num(chPt, 0) > 0 ? num(chPt) : 12
  const f = Object.assign(emptyForm(), form || {})
  const out = {}
  if (ALIGNMENTS.includes(f.alignment)) out.alignment = f.alignment
  const indent = Math.max(0, num(f.indentChars))
  const left = Math.max(0, num(f.leftChars))
  const right = Math.max(0, num(f.rightChars))
  if (f.indentMode === 'firstLine') { out.firstLineIndentPt = round1(indent * c); out.leftIndentPt = round1(left * c) }
  else if (f.indentMode === 'hanging') { out.firstLineIndentPt = -round1(indent * c); out.leftIndentPt = round1((left + indent) * c) }
  else { out.firstLineIndentPt = 0; out.leftIndentPt = round1(left * c) }
  out.rightIndentPt = round1(right * c)
  const before = num(f.spaceBeforePt, -1), after = num(f.spaceAfterPt, -1)
  if (before < 0 || after < 0) return { error: 'badSpacing' }
  out.spaceBeforePt = before
  out.spaceAfterPt = after
  const v = num(f.lineValue, 0)
  if (f.lineMode === 'single' || f.lineMode === '1.5' || f.lineMode === 'double') out.lineSpacingMode = f.lineMode
  else if (f.lineMode === 'multiple') {
    if (!(v > 0 && v <= 10)) return { error: 'badLineValue' }
    out.lineSpacingMode = 'proportional'; out.lineSpacingValue = Math.round(v * 100)
  } else if (f.lineMode === 'atLeast' || f.lineMode === 'exactly') {
    if (!(v > 0 && v <= 500)) return { error: 'badLineValue' }
    out.lineSpacingMode = f.lineMode; out.lineSpacingValue = v
  }
  return { params: out }
}

// 一键预设：只改表单里列出的字段，其余保持当前段落的值。key 对应 i18n
// editor.format.presets.<key>。
export const PARAGRAPH_PRESETS = [
  { key: 'body', patch: { indentMode: 'firstLine', indentChars: 2, lineMode: '1.5', lineValue: 1.5, spaceBeforePt: 0, spaceAfterPt: 0, alignment: 'justify' } },
  { key: 'official', patch: { indentMode: 'firstLine', indentChars: 2, lineMode: 'exactly', lineValue: 28, spaceBeforePt: 0, spaceAfterPt: 0, alignment: 'justify' } },
  { key: 'contract', patch: { indentMode: 'firstLine', indentChars: 2, lineMode: 'multiple', lineValue: 1.25, spaceBeforePt: 0, spaceAfterPt: 6, alignment: 'justify' } },
  { key: 'heading', patch: { indentMode: 'none', leftChars: 0, alignment: 'center', spaceBeforePt: 12, spaceAfterPt: 12 } },
  { key: 'noIndent', patch: { indentMode: 'none', leftChars: 0, rightChars: 0 } },
  { key: 'hanging', patch: { indentMode: 'hanging', indentChars: 2 } },
]

export function applyPreset(form, key) {
  const p = PARAGRAPH_PRESETS.find((x) => x.key === key)
  return p ? Object.assign({}, form, p.patch) : Object.assign({}, form)
}

// 交给 AI 调整格式的那句话。文件名只用于让模型确认改的是哪份；具体改法交给模型
// 用 doc_set_paragraph_format / doc_format_selection / doc_apply_house_style 等工具。
export function buildAiFormatPrompt({ fileName, requirement, scope }, t) {
  const req = String(requirement || '').trim()
  const name = String(fileName || '').trim()
  return t('editor.format.aiPrompt', {
    name: name || t('editor.format.aiPromptDocFallback'),
    scope: t(scope === 'selection' ? 'editor.format.aiScopeSelection' : 'editor.format.aiScopeDocument'),
    requirement: req || t('editor.format.aiDefaultRequirement'),
  })
}
