// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 滚轮位移的取值口径：uni-app H5 会把 <view>/<scroll-view> 上的原生事件交给
// createNativeEvent 重建成一个普通对象，只有 { type, timeStamp, target,
// currentTarget, detail } 加两个转发方法；随后按类型补字段，而补的分支只有
// click / mouse 系 / touch 系 / 键盘四类，补的也只是坐标。`wheel` 不在这四类里，
// 于是回调拿到的事件**没有 deltaX/deltaY**，`scrollLeft += evt.deltaY` 得到 NaN，
// 整条横滚静默失效（dev-board#543，回归自 PR#759）。
// 同 fileOpenTabs.js 的 mouseButtonOf：键位/位移一律从**当前正在派发的原生事件**
// （window.event）上取，回调自带时优先用回调的（事件没被包装的平台走那条）。

/**
 * 取一次滚轮的横向位移量（纵向滚轮映射为横向滚动）。
 * @param {any} evt uni 回调里的事件对象（可能已被重建、丢掉 delta 字段）
 * @returns {number} 位移像素；取不到任何 delta 时返回 0（调用方据此不动）
 */
export function wheelDeltaOf(evt) {
  const src = hasDelta(evt) ? evt : nativeWheelEvent()
  if (!hasDelta(src)) return 0
  const dx = numberOr0(src.deltaX)
  const dy = numberOr0(src.deltaY)
  return Math.abs(dx) > Math.abs(dy) ? dx : dy
}

function hasDelta(e) {
  return !!e && (typeof e.deltaY === 'number' || typeof e.deltaX === 'number')
}

function nativeWheelEvent() {
  return typeof window !== 'undefined' ? window.event : null
}

function numberOr0(v) {
  return typeof v === 'number' && Number.isFinite(v) ? v : 0
}
