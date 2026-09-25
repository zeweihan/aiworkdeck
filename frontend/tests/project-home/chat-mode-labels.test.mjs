// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-38（v0.49.0 真机测试 C5-06）：模式下拉主标题写死英文 Agent / Ask / Plan，
// 中文界面只有副标题是中文。主标题要走 i18n，且与菜单栏「AI > 模式：…」同名。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zhChat from '../../src/locales/zh-CN/chat.js'
import enChat from '../../src/locales/en-US/chat.js'
import { AI_COMMANDS } from '../../src/config/commands/ai.js'

const CI = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')

const MODES = [['AGENT', 'modeAgent', 'ai.modeAgent'], ['ASK', 'modeAsk', 'ai.modeAsk'], ['PLAN', 'modePlan', 'ai.modePlan']]

test('模式主标题不写死英文，走 chat.mode*Name', () => {
  const block = CI.slice(CI.indexOf('const ALL_MODES = ['), CI.indexOf(']', CI.indexOf('const ALL_MODES = [')))
  for (const [id, key] of MODES) {
    assert.ok(new RegExp(`id: '${id}', name: t\\('chat\\.${key}Name'\\)`).test(block), `${id} 的主标题没有走 i18n`)
  }
})

test('模式主标题与菜单栏「AI > 模式：…」同名（zh / en）', () => {
  const list = AI_COMMANDS
  for (const [, key, cmdId] of MODES) {
    const cmd = list.find((c) => c.id === cmdId)
    assert.ok(cmd, cmdId)
    assert.equal(`模式：${zhChat[`${key}Name`]}`, cmd.label.zh)
    assert.equal(`Mode: ${enChat[`${key}Name`]}`, cmd.label.en)
  }
})
