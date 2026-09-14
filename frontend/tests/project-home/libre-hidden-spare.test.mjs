// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 三方合并借用的隐藏引擎实例必须挂在 .editors-container 直下、v-if/v-else 之外。
// 病灶（2026-09-14 真机走查）：备胎 v-for 在 `v-else .editors-grid` 里，左栏没开文档时
// 整块不渲染，隐藏实例永远不 mount，acquireLibreHiddenInstance 空转 180 秒后降级成整份三选一。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const VUE = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const TEMPLATE = VUE.slice(0, VUE.indexOf('<script>'))

test('隐藏实例的 v-for 在空态 v-if 之前（即 .editors-grid 之外），左栏没开文档也会 mount', () => {
  const hidden = TEMPLATE.indexOf('v-for="sp in libreHiddenSpares"')
  const emptyState = TEMPLATE.indexOf('v-if="leftFiles.length === 0 && !splitMode"')
  const container = TEMPLATE.indexOf('<view class="editors-container">')
  assert.ok(hidden > 0, '模板里没有 libreHiddenSpares 的 v-for')
  assert.ok(emptyState > 0, '找不到空态 v-if 锚点')
  assert.ok(container > 0 && hidden > container, '隐藏实例要在 .editors-container 里')
  assert.ok(hidden < emptyState, '隐藏实例的 v-for 落在了空态 v-if 之后——那在 v-else 的 .editors-grid 里，左栏空着时不渲染')
})

test('隐藏实例桶与可见备胎桶分开渲染，且都以 .libre-spare-standby 隐形占位（不能 display:none）', () => {
  assert.match(TEMPLATE, /v-for="sp in libreVisibleSpares"/, '左窗格里的备胎 v-for 要改用 libreVisibleSpares，否则隐藏实例会渲染两份')
  assert.ok(!/v-for="sp in libreSpares"/.test(TEMPLATE), '不许再直接遍历 libreSpares')
  const block = TEMPLATE.slice(TEMPLATE.indexOf('v-for="sp in libreHiddenSpares"'), TEMPLATE.indexOf('v-if="leftFiles.length === 0 && !splitMode"'))
  assert.match(block, /class="pane-content libre-spare-standby"/, '隐藏实例要用 .libre-spare-standby（绝对定位 + visibility:hidden）')
  assert.match(block, /@ready="onLibreSpareReady\(sp, \$event\)"/, 'ready 回调要接到 onLibreSpareReady，acquire 才拿得到 executor')
  assert.ok(!/v-show=/.test(block), '不能用 v-show 藏隐藏实例')
})

test('两个桶都是从 libreSpares 按 hidden 位切出来的 computed', () => {
  assert.match(VUE, /libreVisibleSpares\(\)\s*\{\s*return this\.libreSpares\.filter\(\(sp\) => !sp\.hidden\)/)
  assert.match(VUE, /libreHiddenSpares\(\)\s*\{\s*return this\.libreSpares\.filter\(\(sp\) => sp\.hidden\)/)
})

test('自动合并进行中的顶栏文案带已等秒数，时钟只在 running 期间走', () => {
  assert.match(VUE, /version\.mergeAutoRunningElapsed/, '顶栏要有带秒数的文案键')
  assert.match(VUE, /'documentMergeState\.running'\(running\)/, '要 watch running 来起停时钟')
  assert.match(VUE, /beforeUnmount\(\)\s*\{[\s\S]{0,200}clearInterval\(this\._mergeElapsedTimer\)/, '卸载时要清掉时钟')
})
