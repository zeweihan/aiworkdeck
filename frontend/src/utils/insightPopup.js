// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// insightPopup.js — 「依据」实体浮窗的两组坐标纯函数（dev-board#541）。
//
//   ① guestPointToHost：客体页（webview / 同源 iframe）视口坐标 → 宿主页面坐标；
//   ② hoverCardPosition：贴着点击点摆一张固定尺寸的卡片，靠边自动翻转、始终不出屏。
//
// 放在 utils 里是为了能被 node --test 直接导入（tests/insight/），
// **不许 import Vue / uni / @/i18n**——照 insightMatch.js 的先例。

/** 卡片相对点击点的默认偏移（px）：错开一点，别把刚点的那个词盖住。 */
export const HOVER_OFFSET = 12
/** 卡片与视口边缘的最小留白（px）。 */
export const HOVER_MARGIN = 8

function finite(v) {
  // null / undefined / '' 一律算「没给」——Number(null) 是 0，放过去就会把
  // 「客体页没带坐标」当成「点在 (0, 0)」，浮窗弹到屏幕左上角。
  if (v === null || v === undefined || v === '') return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/**
 * 客体页里的 clientX/clientY 换算成宿主页面坐标。
 *
 * 编辑器画布在宿主里是一个 <webview>（桌面壳）或同源 <iframe>（Web 版），
 * 客体页视口的原点就是那个元素的左上角，所以加一次 rect 的左上角即可。
 * **rect 必须每次现取**（getBoundingClientRect），不能缓存：分屏拖动、左栏收放、
 * 底部抽屉开合都会挪动它。
 *
 * rect 缺失或坐标不是有限数时返回 null——调用方据此退回「不弹浮窗」而不是弹到 (0,0)。
 */
export function guestPointToHost(rect, clientX, clientY) {
  const x = finite(clientX)
  const y = finite(clientY)
  if (x === null || y === null) return null
  const left = rect ? finite(rect.left) : null
  const top = rect ? finite(rect.top) : null
  if (left === null || top === null) return null
  return { x: left + x, y: top + y }
}

/**
 * 浮窗的 position:fixed 落点。
 *
 * 默认摆在点击点的右下方；右边/下边放不下就翻到左边/上边；翻过去还是放不下
 * （视口比卡片还窄）就贴边并夹进视口——**任何情况下都不许出屏**，出屏等于点了没反应。
 *
 * @returns {{left:number, top:number, flipX:boolean, flipY:boolean}}
 */
export function hoverCardPosition(opts) {
  const o = opts || {}
  const x = finite(o.x) || 0
  const y = finite(o.y) || 0
  const w = Math.max(0, finite(o.width) || 0)
  const h = Math.max(0, finite(o.height) || 0)
  const vw = Math.max(0, finite(o.viewportWidth) || 0)
  const vh = Math.max(0, finite(o.viewportHeight) || 0)
  const offset = finite(o.offset) != null ? finite(o.offset) : HOVER_OFFSET
  const margin = finite(o.margin) != null ? finite(o.margin) : HOVER_MARGIN

  const place = (pos, size, viewport) => {
    let start = pos + offset
    let flipped = false
    if (start + size > viewport - margin) {
      const alt = pos - offset - size
      // 翻过去比原位放得下才翻（视口两边都不够时保持原方向再夹）
      if (alt >= margin) { start = alt; flipped = true }
    }
    const max = Math.max(margin, viewport - margin - size)
    if (start > max) start = max
    if (start < margin) start = margin
    return { start, flipped }
  }

  const hx = place(x, w, vw)
  const hy = place(y, h, vh)
  return { left: hx.start, top: hy.start, flipX: hx.flipped, flipY: hy.flipped }
}
