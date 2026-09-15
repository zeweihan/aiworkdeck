// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 反馈浮钮的「让路」几何（dev-board#574）。
//
// 浮钮是 fixed 定位、盖在一切页面之上的常驻入口，停在任何固定坐标都会在某种布局下
// 压住别人的主操作：默认位先在右下角压过 AI 输入区的发送键（#213），挪到右缘 60% 高度
// 后又压住空会话时垂直居中的输入卡（英文占位折行把发送键推下来约 10px 就撞上了）。
// 再换一个固定坐标只是换一处撞，所以改成反过来：主操作区域自己声明「这里别盖」，
// 浮钮每次落位前避开所有声明区域。
//
// 契约：给主操作区域的容器加 data-awd-keep-clear 属性即可（整块输入卡而不是只标一个
// 按钮——模型选择器、模式选择器同样是主操作）。本模块只做纯几何，不碰 DOM，
// 由 FeedbackWidget.vue 采集矩形后调用，便于 node --test 直接覆盖。

export const KEEP_CLEAR_ATTR = 'data-awd-keep-clear'

/** 两个矩形（{left, top, width, height}）是否相交；gap 是额外留的间距。 */
export function intersects(a, b, gap = 0) {
  return a.left < b.left + b.width + gap
    && b.left < a.left + a.width + gap
    && a.top < b.top + b.height + gap
    && b.top < a.top + a.height + gap
}

/**
 * 只沿竖直方向给浮钮找一个不压任何障碍的 top，取离原位最近的那个。
 * 候选位只有三类：原位、每个障碍的正上方、正下方（各隔 gap）——最近的可行位必然落在
 * 这些边界上，没必要逐像素扫。找不到可行位（障碍铺满整条竖线）就留在原位，
 * 不把按钮推出视口：看不见的入口比压住一点内容更糟。
 */
export function resolveLauncherTop(rect, obstacles, viewportHeight, opts = {}) {
  const margin = opts.margin == null ? 8 : opts.margin
  const gap = opts.gap == null ? 8 : opts.gap
  const list = (obstacles || []).filter((o) => o && o.width > 0 && o.height > 0)
  const free = (top) => {
    if (top < margin || top + rect.height > viewportHeight - margin) return false
    const probe = { left: rect.left, top, width: rect.width, height: rect.height }
    return !list.some((o) => intersects(probe, o, gap))
  }
  if (free(rect.top)) return rect.top
  const candidates = []
  for (const o of list) {
    candidates.push(o.top - gap - rect.height, o.top + o.height + gap)
  }
  let best = null
  for (const c of candidates) {
    if (!free(c)) continue
    if (best === null || Math.abs(c - rect.top) < Math.abs(best - rect.top)) best = c
  }
  return best === null ? rect.top : best
}
