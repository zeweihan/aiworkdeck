// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { hoverViewportMetrics, hitTestRevision, revisionTypeLabel, revisionHoverCard } from '../../src/composables/zetaOfficeReviewHover.js'

// 与 zetaOfficeReviewBalloons.place() 相同口径的假视口：canvas 800x600 CSS 像素，
// frame 1600x1200 twips-pixels（nativeScale=0.5），可见区 x∈[2000,6000] y∈[1000,3000]。
const canvasRect = { left: 40, top: 20, width: 800, height: 600 }
const view = { frameWidth: 1600, frameHeight: 1200, left: 2000, right: 6000, top: 1000, bottom: 3000, viewport: { x: 0, y: 0, width: 800, height: 600 } }

test('hoverViewportMetrics 与 balloons 的 place() 同一套 twips→像素映射', () => {
  const m = hoverViewportMetrics(canvasRect, view)
  assert.equal(m.nativeScale, 0.5)
  assert.equal(m.scale, 0.1) // 800px / 4000twips
  assert.equal(m.originX, 40)
  assert.equal(m.originY, 20)
})

test('hitTest 命中：鼠标像素反解回 twips 后落在修订 anchor 矩形内', () => {
  const m = hoverViewportMetrics(canvasRect, view)
  // 修订 anchor：文档 (2400,1200) 起，宽 200 高 240 twips → 像素 x∈[80,100] y∈[40,64]
  const items = [{ kind: 'revision', x: 2400, y: 1200, w: 200, h: 240, data: {} }]
  assert.equal(hitTestRevision(90, 50, items, m), items[0])
  assert.notEqual(hitTestRevision(79, 50, items, m), items[0])
  // pad 容差（4 twips ≈ 0.4px）：矩形左侧外约 3 twips 仍命中
  assert.equal(hitTestRevision(79.7, 50, items, m), items[0])
})

test('hitTest 忽略非修订条目；缺失 w/h 时用默认矩形兜底', () => {
  const m = hoverViewportMetrics(canvasRect, view)
  assert.equal(hitTestRevision(90, 50, [{ kind: 'comment', x: 2400, y: 1200 }], m), null)
  // w=0 → 默认 60twips 宽：x∈[2400,2460] → 像素 [80,86]
  const fallback = [{ kind: 'revision', x: 2400, y: 1200, w: 0, h: 0, data: {} }]
  assert.equal(hitTestRevision(83, 50, fallback, m), fallback[0])
  assert.equal(hitTestRevision(90, 50, fallback, m), null)
})

test('metrics 无效（无 view/视口）时命中测试安全返回 null', () => {
  assert.equal(hoverViewportMetrics(null, view), null)
  assert.equal(hoverViewportMetrics(canvasRect, {}), null)
  assert.equal(hitTestRevision(0, 0, [], null), null)
})

test('revisionHoverCard：本人带「（我）」，AI WorkDeck 不带；文本超 80 字截断', () => {
  const mine = revisionHoverCard({ kind: 'revision', data: { author: '韩泽伟', date: '2026-10-02 10:00', type: 'Insert', text: '新增' } }, '韩泽伟')
  assert.equal(mine.author, '韩泽伟（我）')
  assert.equal(mine.type, '插入')
  const ai = revisionHoverCard({ kind: 'revision', data: { author: 'AI WorkDeck', type: 'Delete', text: '旧文' } }, 'AI WorkDeck')
  assert.equal(ai.author, 'AI WorkDeck')
  assert.equal(ai.type, '删除')
  const other = revisionHoverCard({ kind: 'revision', data: { author: '张三', type: 'Format' } }, '韩泽伟')
  assert.equal(other.author, '张三')
  assert.equal(other.type, '格式')
  const long = revisionHoverCard({ kind: 'revision', data: { author: '张三', type: 'Insert', text: '长'.repeat(90) } }, '')
  assert.equal(long.text.length, 81)
  assert.ok(long.text.endsWith('…'))
})

test('revisionTypeLabel 中英文与未知类型兜底', () => {
  assert.equal(revisionTypeLabel('Insert'), '插入')
  assert.equal(revisionTypeLabel('Delete', 'en'), 'Deleted')
  assert.equal(revisionTypeLabel('ParagraphFormat'), '段落格式')
  assert.equal(revisionTypeLabel('SomethingElse'), 'SomethingElse')
  assert.equal(revisionTypeLabel(''), '更改')
  assert.equal(revisionTypeLabel('', 'en-US'), 'Change')
})
