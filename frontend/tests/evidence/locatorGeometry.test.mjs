// P3「底稿定位增强」：locator → 可渲染形状的纯函数（utils/evidenceLocator.js 后半段）。
// 三条不变式：
//   1. 缺字段（OCR 常态）一律退化成 null——调用方据此「只打开文件、什么都不画」，
//      绝不补默认值凑一个框出来（那就是假高亮）；
//   2. 图片画框在缩放/平移/旋转之后仍然罩住同一块图像内容；
//   3. pdf 的 rects 只收本页的，页码 1 基。
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  normalizeRect, parsePdfLocator, parseImageRect, parseMediaStartSec,
  normalizeRotation, rotatedDisplaySize, rotateNormRect, imageTransform, imageRectBox,
} from '../../src/utils/evidenceLocator.js'

// ── normalizeRect ───────────────────────────────────────────────────────────

test('normalizeRect：缺字段/非数/零面积一律 null，越界裁回 [0,1]', () => {
  assert.equal(normalizeRect(null), null)
  assert.equal(normalizeRect({}), null)
  assert.equal(normalizeRect({ x: 0.1, y: 0.1, w: 0.2 }), null, '缺 h')
  assert.equal(normalizeRect({ x: 'a', y: 0.1, w: 0.2, h: 0.2 }), null, 'x 不是数')
  assert.equal(normalizeRect({ x: 0.1, y: 0.1, w: 0, h: 0.2 }), null, 'w 为 0')
  assert.equal(normalizeRect({ x: 0.1, y: 0.1, w: -0.2, h: 0.2 }), null, 'w 为负')
  assert.equal(normalizeRect({ x: 2, y: 2, w: 0.5, h: 0.5 }), null, '整块在纸外')
  assert.deepEqual(normalizeRect({ x: 0.1, y: 0.2, w: 0.3, h: 0.4 }), { x: 0.1, y: 0.2, w: 0.3, h: 0.4 })
  const clipped = normalizeRect({ x: -0.2, y: 0.8, w: 0.5, h: 0.5 })
  assert.equal(clipped.x, 0)
  assert.equal(clipped.y, 0.8)
  assert.ok(Math.abs(clipped.w - 0.3) < 1e-9)
  assert.ok(Math.abs(clipped.h - 0.2) < 1e-9)
})

// ── pdf ─────────────────────────────────────────────────────────────────────

test('parsePdfLocator：非 pdf / 空 locator → null；三样全缺 → null', () => {
  assert.equal(parsePdfLocator(null), null)
  assert.equal(parsePdfLocator({ type: 'image', rect: { x: 0, y: 0, w: 1, h: 1 } }), null)
  assert.equal(parsePdfLocator({ type: 'pdf' }), null)
  assert.equal(parsePdfLocator({ type: 'pdf', page: 0 }), null, 'page 1 基，0 视为缺')
  assert.equal(parsePdfLocator({ type: 'pdf', quote: '   ' }), null, '空白引文视为缺')
})

test('parsePdfLocator：只有页码 / 页码 + 引文 / 页码 + rects 三档', () => {
  assert.deepEqual(parsePdfLocator({ type: 'pdf', page: 3 }), { page: 3, quote: '', rects: [] })
  assert.deepEqual(
    parsePdfLocator({ type: 'pdf', page: 3, quote: ' 统一社会信用代码 91… ' }),
    { page: 3, quote: '统一社会信用代码 91…', rects: [] },
  )
  const withRects = parsePdfLocator({
    type: 'pdf',
    page: 3,
    quote: '统一社会信用代码 91…',
    rects: [{ page: 3, x: 0.12, y: 0.3, w: 0.6, h: 0.04 }],
  })
  assert.equal(withRects.page, 3)
  assert.deepEqual(withRects.rects, [{ x: 0.12, y: 0.3, w: 0.6, h: 0.04 }])
})

test('parsePdfLocator：rects 自带 page 时只收本页的；坏 rect 被丢掉不影响其余', () => {
  const r = parsePdfLocator({
    type: 'pdf',
    page: 2,
    rects: [
      { page: 1, x: 0.1, y: 0.1, w: 0.2, h: 0.02 },   // 别页
      { page: 2, x: 0.1, y: 0.4, w: 0.5, h: 0.03 },   // 本页
      { page: 2, x: 0.1, y: 0.5 },                    // 缺 w/h
      { x: 0.2, y: 0.6, w: 0.3, h: 0.03 },            // 不带 page，视同本页
    ],
  })
  assert.deepEqual(r.rects, [
    { x: 0.1, y: 0.4, w: 0.5, h: 0.03 },
    { x: 0.2, y: 0.6, w: 0.3, h: 0.03 },
  ])
})

// ── image ───────────────────────────────────────────────────────────────────

test('parseImageRect：缺 rect / 坐标非法 → null', () => {
  assert.equal(parseImageRect({ type: 'image' }), null)
  assert.equal(parseImageRect({ type: 'image', rect: { x: 0.1, y: 0.2 } }), null)
  assert.equal(parseImageRect({ type: 'media', startMs: 1 }), null)
  assert.deepEqual(parseImageRect({ type: 'image', rect: { x: 0.1, y: 0.2, w: 0.3, h: 0.1 } }), { x: 0.1, y: 0.2, w: 0.3, h: 0.1 })
})

test('normalizeRotation：只认 0/90/180/270，其余归 0', () => {
  assert.equal(normalizeRotation(0), 0)
  assert.equal(normalizeRotation(90), 90)
  assert.equal(normalizeRotation(450), 90)
  assert.equal(normalizeRotation(-90), 270)
  assert.equal(normalizeRotation(45), 0)
  assert.equal(normalizeRotation(undefined), 0)
})

test('rotatedDisplaySize：90/270 宽高对调；尺寸或缩放无效 → null', () => {
  assert.deepEqual(rotatedDisplaySize(400, 300, 2, 0), { w: 800, h: 600 })
  assert.deepEqual(rotatedDisplaySize(400, 300, 2, 180), { w: 800, h: 600 })
  assert.deepEqual(rotatedDisplaySize(400, 300, 2, 90), { w: 600, h: 800 })
  assert.deepEqual(rotatedDisplaySize(400, 300, 2, 270), { w: 600, h: 800 })
  assert.equal(rotatedDisplaySize(0, 300, 2, 0), null)
  assert.equal(rotatedDisplaySize(400, 300, 0, 0), null)
})

test('rotateNormRect：四个角在旋转后落到对的象限', () => {
  const topLeft = { x: 0, y: 0, w: 0.2, h: 0.1 }
  assert.deepEqual(rotateNormRect(topLeft, 0), { x: 0, y: 0, w: 0.2, h: 0.1 })
  // 顺时针 90°：原来的左上角跑到右上角，宽高对调
  assert.deepEqual(rotateNormRect(topLeft, 90), { x: 0.9, y: 0, w: 0.1, h: 0.2 })
  assert.deepEqual(rotateNormRect(topLeft, 180), { x: 0.8, y: 0.9, w: 0.2, h: 0.1 })
  assert.deepEqual(rotateNormRect(topLeft, 270), { x: 0, y: 0.8, w: 0.1, h: 0.2 })
})

// imageTransform 与 imageRectBox 必须共用同一套 tx/ty 口径，这里用「把 CSS transform
// 真算一遍」的方式互校：整张图的框（0,0,1,1）必须正好等于 transform 后的外接框。
function applyCssTransform(transform, pt) {
  const m = transform.match(/translate\(([-\d.]+)px, ([-\d.]+)px\) rotate\((\d+)deg\) scale\(([-\d.]+)\)/)
  assert.ok(m, 'transform 形状变了，测试要跟着改：' + transform)
  const [tx, ty, deg, s] = [Number(m[1]), Number(m[2]), Number(m[3]), Number(m[4])]
  const rad = (deg * Math.PI) / 180
  const x = pt.x * s
  const y = pt.y * s
  return { x: tx + x * Math.cos(rad) - y * Math.sin(rad), y: ty + x * Math.sin(rad) + y * Math.cos(rad) }
}

for (const rotation of [0, 90, 180, 270]) {
  test(`imageTransform：旋转 ${rotation}° 后图片外接框仍然落在 (tx,ty)`, () => {
    const view = { natW: 400, natH: 300, scale: 1.5, tx: 40, ty: 20, rotation }
    const t = imageTransform(view)
    const corners = [{ x: 0, y: 0 }, { x: 400, y: 0 }, { x: 400, y: 300 }, { x: 0, y: 300 }]
      .map((p) => applyCssTransform(t, p))
    const size = rotatedDisplaySize(400, 300, 1.5, rotation)
    const near = (a, b) => assert.ok(Math.abs(a - b) < 1e-6, `${a} ≉ ${b}`)
    near(Math.min(...corners.map((c) => c.x)), 40)
    near(Math.min(...corners.map((c) => c.y)), 20)
    near(Math.max(...corners.map((c) => c.x)), 40 + size.w)
    near(Math.max(...corners.map((c) => c.y)), 20 + size.h)

    // 整图的框必须与外接框重合——画框与 transform 用的是同一套口径
    const box = imageRectBox({ x: 0, y: 0, w: 1, h: 1 }, view)
    near(box.left, 40)
    near(box.top, 20)
    near(box.width, size.w)
    near(box.height, size.h)
  })
}

function assertBox(box, want, label) {
  const near = (a, b, k) => assert.ok(Math.abs(a - b) < 1e-6, `${label} 的 ${k}：${a} ≉ ${b}`)
  for (const k of ['left', 'top', 'width', 'height']) near(box[k], want[k], k)
}

test('imageRectBox：同一块图像内容在缩放/平移/旋转后仍被同一个框罩住', () => {
  const rect = { x: 0.25, y: 0.5, w: 0.25, h: 0.1 }   // 图上 (100,150)-(200,180)（原图 400x300）
  const base = { natW: 400, natH: 300, scale: 1, tx: 0, ty: 0, rotation: 0 }
  assertBox(imageRectBox(rect, base), { left: 100, top: 150, width: 100, height: 30 }, '原始')
  // 放大 2 倍 + 平移：框跟着放大同样的倍数
  assertBox(imageRectBox(rect, { ...base, scale: 2, tx: 30, ty: -10 }), { left: 230, top: 290, width: 200, height: 60 }, '缩放平移')
  // 顺时针 90°：图像上「靠左中部」的那块，转过去落在「靠上中部」
  assertBox(imageRectBox(rect, { ...base, rotation: 90 }), { left: 120, top: 100, width: 30, height: 100 }, '旋转 90°')
  // 180°：对角翻过去
  assertBox(imageRectBox(rect, { ...base, rotation: 180 }), { left: 200, top: 120, width: 100, height: 30 }, '旋转 180°')
  // 270°
  assertBox(imageRectBox(rect, { ...base, rotation: 270 }), { left: 150, top: 200, width: 30, height: 100 }, '旋转 270°')
})

test('imageRectBox：locator 缺坐标或图片尺寸未知 → null（退化成只打开文件）', () => {
  const view = { natW: 400, natH: 300, scale: 1, tx: 0, ty: 0, rotation: 0 }
  assert.equal(imageRectBox(null, view), null)
  assert.equal(imageRectBox({ x: 0.1, y: 0.1 }, view), null)
  assert.equal(imageRectBox({ x: 0.1, y: 0.1, w: 0.2, h: 0.2 }, { ...view, natW: 0 }), null, '图还没解码')
  assert.equal(imageTransform({ ...view, natW: 0 }), '')
})

test('imageRectBox：极窄的框至少画 2px，否则用户看不见', () => {
  const box = imageRectBox({ x: 0.5, y: 0.5, w: 0.0001, h: 0.0001 }, { natW: 400, natH: 300, scale: 1, tx: 0, ty: 0, rotation: 0 })
  assert.equal(box.width, 2)
  assert.equal(box.height, 2)
})

// ── media ───────────────────────────────────────────────────────────────────

test('parseMediaStartSec：ms → 秒；缺 startMs / 负数 / 非 media → null', () => {
  assert.equal(parseMediaStartSec({ type: 'media', startMs: 125000 }), 125)
  assert.equal(parseMediaStartSec({ type: 'media', startMs: 0 }), 0, '0 是合法定位点，不是「缺」')
  assert.equal(parseMediaStartSec({ type: 'media' }), null)
  assert.equal(parseMediaStartSec({ type: 'media', startMs: -1 }), null)
  assert.equal(parseMediaStartSec({ type: 'media', startMs: 'abc' }), null)
  assert.equal(parseMediaStartSec({ type: 'pdf', page: 1 }), null)
})
