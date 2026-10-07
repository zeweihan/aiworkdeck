// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { attachReviewHover, hoverViewportMetrics, hitTestRevision, revisionTypeLabel, revisionHoverCard } from '../../src/composables/zetaOfficeReviewHover.js'

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


function hoverHarness(t, respond = async () => ({ success: true, available: true, items: [], view })) {
  const dom = new JSDOM('<canvas></canvas>', { pretendToBeVisual: true })
  const { window } = dom, canvas = window.document.querySelector('canvas'), calls = []
  canvas.getBoundingClientRect = () => canvasRect
  const hover = attachReviewHover({ canvas, execute: async (action, params) => { calls.push({ action, params }); return respond(action, params) } })
  t.after(() => { hover.destroy(); window.close() })
  t.mock.timers.enable({ apis: ['setTimeout'] })
  return { hover, canvas, window, calls, root: window.document.querySelector('.awd-review-hover'),
    move: (x = 90, y = 50) => canvas.dispatchEvent(new window.MouseEvent('mousemove', { clientX: x, clientY: y })),
    settle: async () => { for (let i = 0; i < 12; i++) await Promise.resolve() },
  }
}

test('opening and passive document/view changes never scan revision metadata', async t => {
  const h = hoverHarness(t)
  await h.settle()
  h.hover.documentChanged()
  h.window.dispatchEvent(new h.window.Event('resize'))
  h.canvas.dispatchEvent(new h.window.WheelEvent('wheel'))
  t.mock.timers.tick(1000); await h.settle()
  assert.deepEqual(h.calls, [])
})


const inlineLayout = () => ({ success: true, available: true, documentSeq: 8, revision: 12, selfAuthor: '测试人', view,
  items: [{ kind: 'revision', x: 2400, y: 1200, w: 200, h: 240, data: { index: 431 } }] })
const detailResult = () => ({ success: true, documentSeq: 8, revision: 12,
  revisions: [{ index: 431, author: '测试人', type: 'Delete', date: '2026-10-08', text: '原文字' }] })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

test('only a pointer dwell on a revision requests its single detail and reuses that detail', async t => {
  const h = hoverHarness(t, async action => action === 'get_review_layout' ? inlineLayout() : detailResult())
  h.move(); t.mock.timers.tick(159); await h.settle(); assert.equal(h.calls.length, 0)
  t.mock.timers.tick(1); await h.settle()
  assert.deepEqual(h.calls, [
    { action: 'get_review_layout', params: { fresh: false } },
    { action: 'list_revisions', params: { index: 431, documentSeq: 8, revision: 12, locate: false } },
  ])
  assert.equal(h.root.hidden, false)
  assert.match(h.root.textContent, /测试人（我）/)
  assert.match(h.root.textContent, /原文字/)
  h.move(91, 51); t.mock.timers.tick(160); await h.settle()
  assert.equal(h.calls.length, 2, 'unchanged document and view reuse metadata')
})

test('moving across the page or dwelling outside a revision never reads details', async t => {
  const h = hoverHarness(t, async () => inlineLayout())
  h.move(); t.mock.timers.tick(100); h.move(400, 400); t.mock.timers.tick(100)
  await h.settle(); assert.equal(h.calls.length, 0)
  t.mock.timers.tick(60); await h.settle()
  assert.deepEqual(h.calls.map(c => c.action), ['get_review_layout'])
  assert.equal(h.root.hidden, true)
})

for (const change of ['leave', 'wheel', 'resize', 'document', 'destroy']) {
  for (const stage of ['geometry', 'detail']) {
    test(`${change} invalidates an in-flight ${stage} without further reads or a stale card`, async t => {
      const wait = deferred()
      const h = hoverHarness(t, async action => (stage === 'geometry' || action === 'list_revisions') ? wait.promise : inlineLayout())
      h.move(); t.mock.timers.tick(160); await h.settle()
      const count = h.calls.length
      if (change === 'leave') h.canvas.dispatchEvent(new h.window.MouseEvent('mouseleave'))
      if (change === 'wheel') h.canvas.dispatchEvent(new h.window.WheelEvent('wheel'))
      if (change === 'resize') h.window.dispatchEvent(new h.window.Event('resize'))
      if (change === 'document') h.hover.documentChanged()
      if (change === 'destroy') h.hover.destroy()
      wait.resolve(stage === 'geometry' ? inlineLayout() : detailResult())
      await h.settle(); t.mock.timers.tick(1000); await h.settle()
      assert.equal(h.root.hidden, true)
      assert.equal(h.calls.length, count)
    })
  }
}

test('a new pointer dwell waits for an obsolete read and uses the new document geometry', async t => {
  const wait = deferred(); let reads = 0
  const h = hoverHarness(t, async action => action === 'get_review_layout'
    ? (++reads === 1 ? wait.promise : { ...inlineLayout(), items: [] }) : detailResult())
  h.move(); t.mock.timers.tick(160); await h.settle()
  h.hover.documentChanged(); h.move(); t.mock.timers.tick(160); await h.settle()
  assert.equal(reads, 1, 'do not queue concurrent geometry reads')
  wait.resolve(inlineLayout()); await h.settle()
  assert.equal(reads, 2)
  assert.deepEqual(h.calls.map(c => c.action), ['get_review_layout', 'get_review_layout'])
  assert.equal(h.root.hidden, true)
})

for (const fence of ['documentSeq', 'revision']) {
  test(`mismatched detail ${fence} is never shown or cached`, async t => {
    const h = hoverHarness(t, async action => action === 'get_review_layout' ? inlineLayout() : { ...detailResult(), [fence]: 99 })
    h.move(); t.mock.timers.tick(160); await h.settle()
    assert.equal(h.root.hidden, true)
    h.move(); t.mock.timers.tick(160); await h.settle()
    assert.equal(h.calls.filter(c => c.action === 'get_review_layout').length, 2)
  })
}

test('balloon geometry already containing metadata requires no detail request, even with an unnamed author', async t => {
  const data = inlineLayout(); data.items[0].data = { ...detailResult().revisions[0], author: '' }
  const h = hoverHarness(t, async () => data)
  h.move(); t.mock.timers.tick(160); await h.settle()
  assert.deepEqual(h.calls.map(c => c.action), ['get_review_layout'])
  assert.equal(h.root.hidden, false)
  assert.match(h.root.textContent, /未知作者/)
})


test('pointer relocation during a detail read cannot display the old target', async t => {
  const wait = deferred()
  const h = hoverHarness(t, async action => action === 'get_review_layout' ? inlineLayout() : wait.promise)
  h.move(); t.mock.timers.tick(160); await h.settle()
  h.move(400, 400); t.mock.timers.tick(160); await h.settle()
  wait.resolve(detailResult()); await h.settle()
  assert.equal(h.root.hidden, true)
  assert.equal(h.calls.filter(c => c.action === 'list_revisions').length, 1)
})

test('document edits clear a previously displayed detail before the next real hover', async t => {
  let text = '旧修订'
  const h = hoverHarness(t, async action => action === 'get_review_layout' ? inlineLayout()
    : { ...detailResult(), revisions: [{ ...detailResult().revisions[0], text }] })
  h.move(); t.mock.timers.tick(160); await h.settle()
  assert.match(h.root.textContent, /旧修订/)
  h.hover.documentChanged(); text = '新修订'
  t.mock.timers.tick(1000); await h.settle()
  assert.equal(h.calls.length, 2)
  assert.equal(h.root.hidden, true)
  h.move(); t.mock.timers.tick(160); await h.settle()
  assert.match(h.root.textContent, /新修订/)
  assert.equal(h.calls.length, 4)
})


test('nested command suspension prevents all reads until every command returns and a new pointer dwell occurs', async t => {
  const h = hoverHarness(t, async action => action === 'get_review_layout' ? inlineLayout() : detailResult())
  h.move(); t.mock.timers.tick(100)
  h.hover.suspend(); h.hover.suspend()
  t.mock.timers.tick(1000); h.move(); t.mock.timers.tick(1000); await h.settle()
  assert.equal(h.calls.length, 0)
  h.hover.resume(); h.move(); t.mock.timers.tick(1000); await h.settle()
  assert.equal(h.calls.length, 0)
  h.hover.resume(); t.mock.timers.tick(1000); await h.settle()
  assert.equal(h.calls.length, 0, 'resuming never starts background work')
  h.move(); t.mock.timers.tick(160); await h.settle()
  assert.equal(h.calls.length, 2)
  assert.equal(h.root.hidden, false)
})

test('suspending a command rejects its predecessor hover result even after resume', async t => {
  const wait = deferred()
  const h = hoverHarness(t, async action => action === 'get_review_layout' ? inlineLayout() : wait.promise)
  h.move(); t.mock.timers.tick(160); await h.settle()
  assert.equal(h.calls.length, 2)
  h.hover.suspend(); h.hover.resume()
  wait.resolve(detailResult()); await h.settle()
  assert.equal(h.root.hidden, true)
  assert.equal(h.calls.length, 2)
  h.move(); t.mock.timers.tick(160); await h.settle()
  assert.equal(h.calls.length, 4, 'old model geometry and metadata were discarded')
  assert.equal(h.root.hidden, false)
})
