// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1014：图片预览里转滚轮只能缩小、不能放大。
//   cd frontend && node --test tests/project-home/image-preview-wheel-zoom.test.mjs
//
// 根因：FilePreview.vue 的 .preview-image 是 uni <view>，模板上的 @wheel / @dblclick 走
// uni-h5 的 $nne → createNativeEvent，事件被重建成 { type, timeStamp, target, detail,
// currentTarget, preventDefault, stopPropagation } 的普通对象；随后只给 click / mouse* /
// touch / 键盘四类补字段。`wheel` 与 `dblclick` 都不在其中，于是：
//   wheel    —— 没有 deltaY（`e.deltaY < 0` 恒 false → 恒按缩小算）也没有 clientX/Y；
//   dblclick —— 没有 clientX/Y（锚点算成 NaN，imageTransform 把 NaN 平移兜成 0，图跳到左上角）。
// 修法：两个处理器改挂原生 addEventListener（同 horizontalWheel.js 的位置），
// 缩放决策抽到零依赖纯函数 utils/imageWheelZoom.js，取不到 delta / 坐标时不瞎猜。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as zoom from '../../src/utils/imageWheelZoom.js'

const SRC = readFileSync(new URL('../../src/components/FilePreview.vue', import.meta.url), 'utf8')
const TEMPLATE = SRC.slice(0, SRC.indexOf('<script'))

function pickMethod(name) {
  const m = SRC.match(new RegExp('\\n    ' + name + '\\(([^)]*)\\) \\{\\n([\\s\\S]*?)\\n    \\},'))
  assert.ok(m, 'FilePreview.vue 里找不到方法 ' + name)
  return new Function(m[1], m[2])
}

// 组件方法体里用到的模块级导入，按同名挂到全局让 new Function 解析得到
Object.assign(globalThis, zoom, { IMAGE_MIN_SCALE: 0.1, IMAGE_MAX_SCALE: 8 })

// uni-h5 createNativeEvent 对 wheel / dblclick 的产物：没有 delta，没有坐标
function uniRebuiltEvent(type) {
  return {
    type,
    timeStamp: 1,
    target: { id: '', dataset: {}, offsetTop: 0, offsetLeft: 0 },
    detail: {},
    currentTarget: { id: '', dataset: {}, offsetTop: 0, offsetLeft: 0 },
    preventDefault() {},
    stopPropagation() {},
  }
}

function realWheel(deltaY, clientX = 300, clientY = 200) {
  return { type: 'wheel', deltaY, deltaX: 0, clientX, clientY, preventDefault() { this.prevented = true } }
}

function makeVm() {
  const viewport = {
    clientWidth: 800,
    clientHeight: 600,
    getBoundingClientRect: () => ({ left: 100, top: 50, width: 800, height: 600 }),
  }
  const vm = {
    imageNaturalWidth: 1000,
    imageNaturalHeight: 800,
    imageScale: 1,
    imageFitScale: 0.5,
    imageTx: 0,
    imageTy: 0,
    $refs: { imageViewport: viewport },
  }
  for (const name of ['getImageViewportEl', 'clampImageScale', 'zoomImageTo', 'handleImageWheel', 'handleImageDblClick']) {
    vm[name] = pickMethod(name).bind(vm)
  }
  return vm
}

function withWindowEvent(ev, fn) {
  const had = 'window' in globalThis
  const prev = globalThis.window
  globalThis.window = { event: ev }
  try { return fn() } finally { if (had) globalThis.window = prev; else delete globalThis.window }
}

test('病灶：uni 重建的 wheel 事件（无 deltaY）不许被当成「向下滚 = 缩小」', () => {
  const vm = makeVm()
  withWindowEvent(undefined, () => vm.handleImageWheel(uniRebuiltEvent('wheel')))
  assert.equal(vm.imageScale, 1, '拿不到滚动方向时不该动缩放')
  assert.ok(Number.isFinite(vm.imageTx) && Number.isFinite(vm.imageTy), '平移量不能被算成 NaN')
})

test('真 WheelEvent：deltaY<0 放大、deltaY>0 缩小，且以光标为锚点', () => {
  const vm = makeVm()
  const up = realWheel(-100)
  vm.handleImageWheel(up)
  assert.ok(up.prevented, '要 preventDefault，否则页面跟着滚')
  assert.ok(vm.imageScale > 1, `向上滚应放大，实际 ${vm.imageScale}`)
  // 锚点（容器坐标 200,150）在缩放前后屏幕位置不变：tx = ax - ax*ratio
  const ratio = vm.imageScale
  assert.ok(Math.abs(vm.imageTx - (200 - 200 * ratio)) < 1e-9)
  assert.ok(Math.abs(vm.imageTy - (150 - 150 * ratio)) < 1e-9)
  const before = vm.imageScale
  vm.handleImageWheel(realWheel(100))
  assert.ok(vm.imageScale < before, '向下滚应缩小')
})

test('dblclick：uni 重建的事件没有坐标时退到视口中心做锚点，不产生 NaN', () => {
  const vm = makeVm()
  withWindowEvent(undefined, () => vm.handleImageDblClick(uniRebuiltEvent('dblclick')))
  assert.equal(vm.imageScale, 0.5, '100% 时双击回到适应窗口')
  assert.ok(Number.isFinite(vm.imageTx) && Number.isFinite(vm.imageTy), '平移量不能被算成 NaN')
  // 视口中心 (400,300) 为锚点：tx = 400 - 400*0.5
  assert.equal(vm.imageTx, 200)
  assert.equal(vm.imageTy, 150)
})

test('纯函数：imageWheelZoomFactor 按 deltaY 定方向，缺 delta 返回 1；可从 window.event 取回', () => {
  assert.ok(zoom.imageWheelZoomFactor({ deltaY: -3 }) > 1)
  assert.ok(zoom.imageWheelZoomFactor({ deltaY: 3 }) < 1)
  assert.equal(zoom.imageWheelZoomFactor({ deltaY: 0 }), 1)
  assert.equal(withWindowEvent(undefined, () => zoom.imageWheelZoomFactor(uniRebuiltEvent('wheel'))), 1)
  const f = withWindowEvent({ type: 'wheel', deltaY: -50 }, () => zoom.imageWheelZoomFactor(uniRebuiltEvent('wheel')))
  assert.ok(f > 1, '回调对象被重建时应从正在派发的原生事件取 delta')
  // 横向为主的触控板手势不参与缩放方向判定（只看 deltaY）
  assert.equal(zoom.imageWheelZoomFactor({ deltaX: 40, deltaY: 0 }), 1)
})

test('纯函数：imageEventAnchor 有坐标取光标，没有就退视口中心', () => {
  const rect = { left: 100, top: 50, width: 800, height: 600 }
  assert.deepEqual(zoom.imageEventAnchor({ clientX: 300, clientY: 200 }, rect), { x: 200, y: 150 })
  assert.deepEqual(withWindowEvent(undefined, () => zoom.imageEventAnchor(uniRebuiltEvent('wheel'), rect)), { x: 400, y: 300 })
})

test('接线：.preview-image 不再用模板 @wheel / @dblclick，改为原生 addEventListener（wheel 带 passive:false）', () => {
  const tag = TEMPLATE.match(/<view\s[^>]*class="preview-image"[^>]*>/)
  assert.ok(tag, '找不到 .preview-image')
  assert.doesNotMatch(tag[0], /@wheel/)
  assert.doesNotMatch(tag[0], /@dblclick/)
  assert.match(SRC, /addEventListener\('wheel',[^)]*passive:\s*false/)
  assert.match(SRC, /addEventListener\('dblclick'/)
  assert.match(SRC, /removeEventListener\('wheel'/)
})
