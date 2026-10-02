// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/config/leftSidebarPlugins.js', import.meta.url), 'utf8')

function load() {
  const body = SRC.replace(/^import .*$/gm, '').replace(/^export /gm, '')
    + '\nreturn { LEFT_SIDEBAR_PLUGINS, getLeftSidebarPlugin, filterPluginsByEnabledSkills, isPanelSkill }'
  return new Function('t', body)((k) => k)
}

const {
  LEFT_SIDEBAR_PLUGINS,
  getLeftSidebarPlugin,
  filterPluginsByEnabledSkills,
  isPanelSkill
} = load()

test('tmeet 插件在左侧边栏插件列表中正确声明', () => {
  const plugin = LEFT_SIDEBAR_PLUGINS.find(p => p.key === 'tmeet')
  assert.ok(plugin, 'LEFT_SIDEBAR_PLUGINS 应该包含 tmeet')
  assert.equal(plugin.requiresSkill, 'tencent-meeting')
  assert.ok(plugin.svgPaths && plugin.svgPaths.length > 0, '应该有图标路径')
})

test('getLeftSidebarPlugin 能按 key 查到 tmeet 插件', () => {
  const p = getLeftSidebarPlugin('tmeet')
  assert.ok(p)
  assert.equal(p.key, 'tmeet')
})

test('filterPluginsByEnabledSkills 在启用了 tencent-meeting 时保留入口', () => {
  const all = LEFT_SIDEBAR_PLUGINS
  const enabled = new Set(['tencent-meeting'])
  const filtered = filterPluginsByEnabledSkills(all, enabled)
  const found = filtered.find(p => p.key === 'tmeet')
  assert.ok(found, '启用 tencent-meeting skill 时应该能看到 tmeet 面板位')
})

test('filterPluginsByEnabledSkills 在未启用 tencent-meeting 时隐藏入口', () => {
  const all = LEFT_SIDEBAR_PLUGINS
  const enabled = new Set(['some-other-skill'])
  const filtered = filterPluginsByEnabledSkills(all, enabled)
  const found = filtered.find(p => p.key === 'tmeet')
  assert.equal(found, undefined, '未启用 tencent-meeting 时应隐藏 tmeet 面板位')
})

test('tencent-meeting 属于面板型 Skill (isPanelSkill)', () => {
  assert.equal(isPanelSkill('tencent-meeting'), true)
})
