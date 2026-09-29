// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 图片预览（FilePreview.vue .preview-image）的滚轮缩放与双击锚点的取值口径（dev-board#1014）。
//
// 为什么要单独取值：uni-h5 对内置元素（<view> 等）上的模板事件走 $nne → createNativeEvent，
// 把事件重建成普通对象，只给 click / mouse* / touch / 键盘四类补字段。`wheel` 与 `dblclick`
// 都不在其中——前者丢了 deltaY 与 clientX/Y，后者丢了 clientX/Y。旧代码
// `e.deltaY < 0 ? 放大 : 缩小` 因此恒走缩小分支（「只能缩小、不能放大」）。
// 组件那边已改挂原生 addEventListener（拿到的就是真事件），这里仍按
// 「回调对象 → 正在派发的原生事件 window.event → 放弃」的顺序取，取不到就不动，绝不瞎猜方向。

export const IMAGE_WHEEL_ZOOM_STEP = 1.15

/**
 * 一次滚轮对应的缩放倍率。只看纵向 deltaY：横向为主的触控板手势不该让图片缩放。
 * @param {any} evt 事件对象（可能是 uni 重建过、没有 delta 的）
 * @param {number} [step]
 * @returns {number} >1 放大、<1 缩小、1 不动
 */
export function imageWheelZoomFactor(evt, step = IMAGE_WHEEL_ZOOM_STEP) {
  const src = pick(evt, 'wheel', (e) => typeof e.deltaY === 'number')
  const dy = src && Number.isFinite(src.deltaY) ? src.deltaY : 0
  if (!dy) return 1
  return dy < 0 ? step : 1 / step
}

/**
 * 事件在视口里的锚点（容器坐标）。取不到光标坐标时退到视口中心，而不是算出 NaN。
 * @param {any} evt
 * @param {{left:number, top:number, width:number, height:number}} rect 视口 getBoundingClientRect()
 * @returns {{x:number, y:number}}
 */
export function imageEventAnchor(evt, rect) {
  const src = pick(evt, evt && evt.type, (e) => Number.isFinite(e.clientX) && Number.isFinite(e.clientY))
  if (src) return { x: src.clientX - rect.left, y: src.clientY - rect.top }
  return { x: rect.width / 2, y: rect.height / 2 }
}

function pick(evt, type, ok) {
  if (evt && ok(evt)) return evt
  const nat = typeof window !== 'undefined' ? window.event : null
  if (nat && (!type || nat.type === type) && ok(nat)) return nat
  return null
}
