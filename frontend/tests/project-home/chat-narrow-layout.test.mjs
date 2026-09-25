// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-41（v0.49.0 真机测试 C5-09）：右栏拖窄到约 300px 或整窗 1100×700 时——
// ① 模型名「Kimi K3」折成两行；② 空状态输入卡的发送钮掉到控件行下面；
// ③ 历史下拉里分支会话那一行的标题被「分支自 …」角标整个挤没。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const CI = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
const PO_SCSS = readFileSync(new URL('../../src/pages/project-overview/project-overview.scss', import.meta.url), 'utf8')

const rule = (css, selector) => {
  const at = css.indexOf(`\n${selector} {`)
  assert.ok(at >= 0, `找不到 ${selector} 规则`)
  return css.slice(at, css.indexOf('}', at))
}

test('模型名单行显示、放不下时省略号截断，选择器本身可收缩', () => {
  const name = rule(CI, '.model-name')
  assert.match(name, /white-space:\s*nowrap/)
  assert.match(name, /text-overflow:\s*ellipsis/)
  assert.match(name, /overflow:\s*hidden/)
  assert.match(rule(CI, '.model-selector'), /min-width:\s*0/, '选择器不许以内容宽撑住整行')
})

test('空状态输入卡的发送钮与控件同一行，不再被 flex-basis 强制换行', () => {
  assert.ok(!/\.centered-style \.composer-actions\s*\{[^}]*flex-basis:\s*calc\(100% - 56px\)/.test(CI),
    'composer-actions 的 flex-basis 接近 100%，无论多宽都会和控件行分成两行')
})

test('历史下拉的来源/分支角标可收缩、带省略号，给标题留出位置', () => {
  const chip = rule(PO_SCSS, '.conv-source-chip')
  assert.ok(!/flex-shrink:\s*0/.test(chip), '角标 flex-shrink:0 会把标题挤成 0 宽')
  assert.match(chip, /max-width:/)
  assert.match(chip, /text-overflow:\s*ellipsis/)
  assert.match(chip, /white-space:\s*nowrap/)
})
