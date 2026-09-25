// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board BUG-51：插件广场侧栏「已安装」分组的空搜索文案曾经永远是笼统的
// 「没有匹配的已安装项」，跟同一个面板里在线广场 Skill/插件两个分组按类型
// 具体命名的措辞（「没有匹配的 Skill」/「没有匹配的插件」）不一致。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/MarketSidebarPanel.vue', import.meta.url), 'utf8')
const ZH = readFileSync(new URL('../../src/locales/zh-CN/market.js', import.meta.url), 'utf8')
const EN = readFileSync(new URL('../../src/locales/en-US/market.js', import.meta.url), 'utf8')

test('已安装分组空搜索文案跟随当前子分组复用 noMatchingSkill/noMatchingPlugin', () => {
  assert.match(SRC, /activeInstalledEmptyText\(\)\s*{\s*return this\.installedTab === 'skill' \? this\.\$t\('market\.noMatchingSkill'\) : this\.\$t\('market\.noMatchingPlugin'\)/)
  assert.match(SRC, /searchText \? activeInstalledEmptyText : \$t\('market\.noInstalledYet'\)/)
})

test('废弃的 noMatchingInstalled 文案已随改动一起清理，两个 locale 保持同步', () => {
  assert.doesNotMatch(ZH, /noMatchingInstalled/)
  assert.doesNotMatch(EN, /noMatchingInstalled/)
})
