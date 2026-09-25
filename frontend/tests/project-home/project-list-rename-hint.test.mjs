// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board BUG-23：方块视图里点击项目名会静默进入重命名态，之前没有任何
// title/tooltip 提示这是可编辑的，用户容易误以为点了空白处。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/pages/project-list/project-list.vue', import.meta.url), 'utf8')
const ZH = readFileSync(new URL('../../src/locales/zh-CN/projects.js', import.meta.url), 'utf8')
const EN = readFileSync(new URL('../../src/locales/en-US/projects.js', import.meta.url), 'utf8')

test('project-title-new（方块视图点名重命名）带 title 提示', () => {
  const line = SRC.split('\n').find((l) => l.includes('class="project-title-new"'))
  assert.ok(line, '应能找到 project-title-new 渲染行')
  assert.match(line, /:title="\$t\('projects\.renameHint'\)"/, '点名重命名的入口缺少可发现性 tooltip')
})

test('projects.renameHint 中英文案齐备', () => {
  assert.match(ZH, /renameHint:\s*'.+'/)
  assert.match(EN, /renameHint:\s*'.+'/)
})
