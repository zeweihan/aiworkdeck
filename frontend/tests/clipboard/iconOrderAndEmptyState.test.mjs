// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// BUG-67：剪贴板卡片与收藏夹卡片（ProjectFavoritesPanel.vue，见
// tests/project-home/favoritesIconOrderAndEmptyState.test.mjs）此前三图标布局相同，
// 但中间那个图标语义不同（一边是「插入」一边是「复制」），容易凭记忆点错。
// 两处卡片统一为固定顺序「插入到文档 / 复制 / 删除」，并保留各自的 title 提示。
//
// BUG-68：收藏夹/剪贴板抽屉空态此前只有一行纯文字「暂无收藏」/「暂无记录」，
// 与主区域（project-overview.scss 的 .empty-workspace）统一的图标+双行文案范式不一致。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/ClipboardPanel.vue', import.meta.url), 'utf8')
const TEMPLATE = SRC.match(/<template>([\s\S]*?)<\/template>/)[1]

function slice(src, startMarker, endMarker) {
  const start = src.indexOf(startMarker)
  assert.ok(start >= 0, `找不到起始标记 ${startMarker}`)
  const end = src.indexOf(endMarker, start)
  assert.ok(end > start, `找不到结束标记 ${endMarker}`)
  return src.slice(start, end)
}

test('BUG-67：文本卡片三图标固定顺序——插入 / 复制 / 删除', () => {
  const actionsBlock = slice(TEMPLATE, 'cli-actions-top', 'Content: Horizontal Scroll')
  const insertAt = actionsBlock.indexOf('cpInsertTitle')
  const copyAt = actionsBlock.indexOf('cpCopyTitle')
  const deleteAt = actionsBlock.indexOf('cpDeleteTitle')
  assert.ok(insertAt > 0 && copyAt > 0 && deleteAt > 0, '三个按钮都应带 title')
  assert.ok(insertAt < copyAt, '插入应排在复制前面')
  assert.ok(copyAt < deleteAt, '复制应排在删除前面')
})

test('BUG-67：图片卡没有复制按钮时留占位，删除恒在第三位', () => {
  const actionsBlock = slice(TEMPLATE, 'cli-actions-top', 'Content: Horizontal Scroll')
  // 按 v-if 条件把每种卡片实际渲染出来的按钮序列算出来：插入 / 复制位 / 删除
  const buttons = [...actionsBlock.matchAll(/<view\s+(v-if="[^"]*"|v-else)?[^>]*class="(cli-btn[^"]*)"/g)]
    .map((m) => ({ cond: m[1] || '', cls: m[2] }))
  const render = (type) => {
    const out = []
    let lastIf = null
    for (const b of buttons) {
      if (b.cond.startsWith('v-if')) {
        lastIf = b.cond.includes(`'${type}'`)
        if (lastIf) out.push(b.cls)
      } else if (b.cond === 'v-else') {
        if (lastIf === false) out.push(b.cls)
      } else {
        lastIf = null
        out.push(b.cls)
      }
    }
    return out
  }
  for (const type of ['TEXT', 'IMAGE']) {
    const seq = render(type)
    assert.equal(seq.length, 3, `${type} 卡片应恰好三个按钮位：${JSON.stringify(seq)}`)
    assert.match(seq[2], /danger/, `${type} 卡片的删除应在第三位：${JSON.stringify(seq)}`)
  }
  assert.match(render('IMAGE')[1], /cli-btn-placeholder/, '图片卡第二位是复制占位')
  const style = SRC.match(/<style[^>]*>([\s\S]*?)<\/style>/)[1]
  assert.match(style, /\.cli-btn\.cli-btn-placeholder\s*\{[^}]*visibility:\s*hidden[^}]*pointer-events:\s*none/,
    '占位不可见、不可点')
})

test('BUG-68：剪贴板空态是图标 + 标题 + 说明的双行文案，不再是一行纯文字', () => {
  const block = slice(TEMPLATE, 'items.length === 0" class="empty"', 'class="list-grid"')
  assert.match(block, /empty-logo-tile/, '空态应带图标砖')
  assert.match(block, /iconmark_v2\.png/, '空态图标应复用主区域同一枚 App 图标')
  assert.match(block, /empty-title/, '空态应有标题行')
  assert.match(block, /cpEmpty['")]/, '标题应读 panels.cpEmpty')
  assert.match(block, /empty-sub/, '空态应有第二行说明文案')
  assert.match(block, /cpEmptyHint/, '说明文案应读新增的 panels.cpEmptyHint')
})

test('BUG-68：空态相关的 CSS 类在 scoped style 里真的有定义，不是死类名', () => {
  const style = SRC.match(/<style[^>]*>([\s\S]*?)<\/style>/)[1]
  for (const cls of ['.empty', '.empty-logo-tile', '.empty-state-img', '.empty-title', '.empty-sub']) {
    assert.ok(style.includes(cls), `缺少 ${cls} 的样式定义`)
  }
})
