// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board BUG-65：命令面板分类标签中英混排——'app' 菜单没有 label（macOS
// 用应用名渲染原生菜单栏），CommandPalette.vue 之前给它兜底成裸英文单词
// 'App'，跟同一个下拉里「文件」「视图」这些中文分类标签混排在一起。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/CommandPalette.vue', import.meta.url), 'utf8')

test("'app' 菜单的兜底分类标签不是裸英文单词 'App'", () => {
  const script = SRC.slice(SRC.indexOf('<script>'))
  assert.doesNotMatch(script, /:\s*'App'/, "命令面板的分类标签兜底不该是纯英文单词 'App'，会跟中文分类标签混排")
  assert.match(script, /m\.label \? labelOf\(m, lang\) : 'AI WorkDeck'/, '兜底应改用品牌名（中英文一致），而不是需要翻译的英文单词')
})
