// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 计划审阅（dev-board#1022）Task 6：计划卡「打开修订」、SSE saved 事件、工作台接线。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolveSavedPath } from '../../src/utils/planReview.js'
const CARD = readFileSync(new URL('../../src/components/ArtifactCard.vue', import.meta.url), 'utf8')
const STREAM = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

test('从气泡正文取「已保存到项目文件」的路径（中英都认，取最后一次）', () => {
  assert.equal(resolveSavedPath('正文\n> 已保存到项目文件：AI 助手文件/conv-1/Plan.md\n'), 'AI 助手文件/conv-1/Plan.md')
  assert.equal(resolveSavedPath('> Saved to project file: AI Assistant Files/conv-1/Plan.md'), 'AI Assistant Files/conv-1/Plan.md')
  assert.equal(resolveSavedPath('没有'), null)
})
test('计划卡有「打开修订」按钮并发 open-review；useAgentStream 处理 saved', () => {
  assert.match(CARD, /chat\.openRevisionBtn/)
  assert.match(CARD, /\$emit\('open-review'/)
  assert.match(STREAM, /evt\.operation === 'saved'/)
})
