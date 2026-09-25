// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board BUG-26：顶栏「浏览器」按钮实为「新建标签」动作，之前的 tooltip
// 只写「浏览器」，与旁边分屏/录制两个真正的开关放在一排，容易被误认成开关。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const ZH = readFileSync(new URL('../../src/locales/zh-CN/workbench.js', import.meta.url), 'utf8')
const EN = readFileSync(new URL('../../src/locales/en-US/workbench.js', import.meta.url), 'utf8')

test('顶栏浏览器按钮 tooltip 明确说明是「新建标签」动作', () => {
  const idx = SRC.indexOf('@tap="openBrowserTab()"')
  assert.ok(idx >= 0, '应能找到打开浏览器标签的按钮渲染行')
  const block = SRC.slice(idx, idx + 200)
  assert.match(block, /:title="\$t\('workbench\.browserNewTabHint'\)"/, '按钮 tooltip 应引用 browserNewTabHint 而非笼统的 workbench.browser')
})

test('workbench.browserNewTabHint 中英文案齐备且不同于纯名词 browser', () => {
  assert.match(ZH, /browserNewTabHint:\s*'.+'/)
  assert.match(EN, /browserNewTabHint:\s*'.+'/)
})
