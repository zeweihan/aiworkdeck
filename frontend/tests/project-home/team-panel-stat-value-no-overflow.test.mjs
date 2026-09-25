// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board BUG-46：TeamPanel.vue 的 .stat-value 曾经固定 18px + white-space:
// nowrap，窄窗口下五列磁贴挤不下「0.0 小时」这类数字+单位组合时会可见溢出
// 磁贴边界（复核者否决了纯 nowrap 的做法，因为它只是把折行换成了溢出）。
// 改法：.stat-tile 开 container-type: inline-size 给 .stat-value 的 clamp()
// 提供按磁贴自身宽度缩放的容器查询上下文，.stat-value 的字号用 clamp() 连续
// 缩小，并且两处都收口 overflow:hidden（.stat-value 再加 text-overflow:
// ellipsis）兜底极端窄宽度，确保不再有可见溢出。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/admin/TeamPanel.vue', import.meta.url), 'utf8')

function rule(selector) {
  const style = SRC.slice(SRC.indexOf('<style'))
  const marker = `\n${selector} {`
  const start = style.indexOf(marker)
  assert.ok(start >= 0, `找不到 ${selector} 规则`)
  const end = style.indexOf('}', start)
  return style.slice(start, end)
}

test('.stat-tile 是 .stat-value clamp() 字号的容器查询上下文，并且自身裁切溢出', () => {
  const block = rule('.stat-tile')
  assert.match(block, /container-type:\s*inline-size/, '.stat-tile 应声明 container-type: inline-size')
  assert.match(block, /overflow:\s*hidden/, '.stat-tile 应 overflow:hidden 兜底裁切')
})

test('.stat-value 用 clamp() 按容器宽度连续缩字号，不再是固定 18px', () => {
  const block = rule('.stat-value')
  assert.match(block, /font-size:\s*clamp\([^)]*cq[iw][^)]*\)/, '.stat-value 的字号应是带容器查询单位的 clamp()')
  assert.doesNotMatch(block, /font-size:\s*18px/, '不应再固定 18px（会在窄容器下溢出）')
})

test('.stat-value 保留 nowrap 但配 overflow:hidden + text-overflow:ellipsis 兜底极端窄宽度', () => {
  const block = rule('.stat-value')
  assert.match(block, /white-space:\s*nowrap/)
  assert.match(block, /overflow:\s*hidden/)
  assert.match(block, /text-overflow:\s*ellipsis/)
})
