// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// BUG-67：收藏夹卡片（ProjectFavoritesPanel.vue）与剪贴板卡片（ClipboardPanel.vue，见
// tests/clipboard/iconOrderAndEmptyState.test.mjs）此前三图标布局相同，但中间那个图标
// 语义不同，容易凭记忆点错。两处卡片统一为固定顺序「插入到文档 / 复制 / 删除」；
// 「新标签页打开」是收藏夹独有的功能，挪到统一三件套之后，不再占用前三个位置。
//
// BUG-68：收藏夹抽屉空态此前只有一行纯文字「暂无收藏」，与主区域
// （project-overview.scss 的 .empty-workspace）统一的图标+双行文案范式不一致。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/ProjectFavoritesPanel.vue', import.meta.url), 'utf8')
const TEMPLATE = SRC.match(/<template>([\s\S]*?)<\/template>/)[1]

function slice(src, startMarker, endMarker) {
  const start = src.indexOf(startMarker)
  assert.ok(start >= 0, `找不到起始标记 ${startMarker}`)
  const end = src.indexOf(endMarker, start)
  assert.ok(end > start, `找不到结束标记 ${endMarker}`)
  return src.slice(start, end)
}

test('BUG-67：收藏夹卡片三图标固定顺序——插入 / 复制 / 删除，「新标签页打开」排在其后', () => {
  const actionsBlock = slice(TEMPLATE, 'class="header-right"', 'Content Body')
  const insertAt = actionsBlock.indexOf('pfInsertTitle')
  const copyAt = actionsBlock.indexOf('pfCopyTitle')
  const deleteAt = actionsBlock.indexOf('pfDeleteTitle')
  const openAt = actionsBlock.indexOf('pfOpenNewTabTitle')
  assert.ok(insertAt > 0 && copyAt > 0 && deleteAt > 0 && openAt > 0, '四个按钮都应带 title')
  assert.ok(insertAt < copyAt, '插入应排在复制前面——与 ClipboardPanel 一致')
  assert.ok(copyAt < deleteAt, '复制应排在删除前面——与 ClipboardPanel 一致')
  assert.ok(deleteAt < openAt, '「新标签页打开」是收藏夹独有功能，不占用统一三件套的位置')
})

test('BUG-67：复制按钮真的存在对应方法，不是死绑定', () => {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  assert.match(script, /copyFav\s*\(/, '应有 copyFav 方法')
  assert.match(TEMPLATE, /@tap\.stop="copyFav\(fav\)"/, '复制按钮应绑定 copyFav(fav)')
})

test('BUG-68：收藏夹空态是图标 + 标题 + 说明的双行文案，不再是一行纯文字', () => {
  const block = slice(TEMPLATE, 'items.length === 0" class="empty"', 'class="list-grid"')
  assert.match(block, /empty-logo-tile/, '空态应带图标砖')
  assert.match(block, /iconmark_v2\.png/, '空态图标应复用主区域同一枚 App 图标')
  assert.match(block, /empty-title/, '空态应有标题行')
  assert.match(block, /pfEmpty['")]/, '标题应读 panels.pfEmpty')
  assert.match(block, /empty-sub/, '空态应有第二行说明文案')
  assert.match(block, /pfEmptyHint/, '说明文案应读新增的 panels.pfEmptyHint')
})

test('BUG-68：空态相关的 CSS 类在 scoped style 里真的有定义，不是死类名', () => {
  const style = SRC.match(/<style[^>]*>([\s\S]*?)<\/style>/)[1]
  for (const cls of ['.empty', '.empty-logo-tile', '.empty-state-img', '.empty-title', '.empty-sub']) {
    assert.ok(style.includes(cls), `缺少 ${cls} 的样式定义`)
  }
})
