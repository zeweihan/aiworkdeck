// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「纵向滚轮 → 横向滚动」的唯一实现（编辑器工具栏主命令区、编辑器标签栏两处在用）。
//
// 为什么不写成模板上的 `@wheel`（dev-board#543 复发的那一步）：uni-h5 对内置元素
// （`<view>` / `<scroll-view>` …）上的原生事件走 `$nne` → `createNativeEvent`，把事件
// **重建成一个普通对象**——`currentTarget` 被换成 `{ id, dataset, offsetTop, offsetLeft }`，
// 既不是 DOM 元素（没有 querySelectorAll / scrollWidth），`wheel` 又不在它补字段的四个
// 分支（click / mouse 系 / touch 系 / 键盘）里，连 deltaX/deltaY 都没有。于是「从
// currentTarget 找滚动容器」这一步第一道守卫就 return，横滚整条是死的。
// 真渲染对照实验：同一套算法用原生 addEventListener 挂到真实 DOM 上，同一次滚轮
// scrollLeft 0 → 600；走模板 @wheel 时 0 → 0。
//
// 所以位置固定：组件 mounted（以及会重建 scroller 的状态变化后的 nextTick）里，
// 拿到 uni 渲染出来的真实元素，用原生 addEventListener 挂，beforeUnmount 摘掉。
import { wheelDeltaOf } from './wheelDelta.js'

// 同一个元素只挂一次：分屏开关、标签增删都会让调用方重新跑一遍绑定，
// 重复挂的后果是一次滚轮滚两倍。
const BOUND = new WeakMap()

/**
 * 找到真正 overflow 的那个元素。uni `<scroll-view>` 真正滚的是内层
 * `div.uni-scroll-view`，不是我们挂 class 的根元素本身。
 * @param {any} root 绑定的根元素
 * @returns {any} 可横向滚动的元素；没有就 null（此时不该拦截滚轮）
 */
export function findHorizontalScroller(root) {
  if (!root) return null
  if (root.scrollWidth > root.clientWidth + 1) return root
  if (typeof root.querySelectorAll !== 'function') return null
  for (const el of root.querySelectorAll('*')) {
    if (el.scrollWidth > el.clientWidth + 1) return el
  }
  return null
}

/**
 * 给一个元素挂上「滚轮转横滚」，返回摘除函数（幂等：重复绑定返回同一个）。
 * @param {any} root uni 渲染出的真实元素（如 <uni-scroll-view class="tabs-scroll">）
 * @returns {() => void} unbind
 */
export function bindHorizontalWheel(root) {
  if (!root || typeof root.addEventListener !== 'function') return () => {}
  const already = BOUND.get(root)
  if (already) return already.unbind

  const handler = (evt) => {
    const delta = wheelDeltaOf(evt)
    if (!delta) return
    const scroller = findHorizontalScroller(root)
    // 滚不动就别拦：这一条既让宽窗口下的滚轮照常滚页面，也让 passive:false
    // 只在真的要横滚时才付出代价。
    if (!scroller) return
    if (typeof evt.preventDefault === 'function') evt.preventDefault()
    scroller.scrollLeft += delta
  }
  const unbind = () => {
    if (BOUND.get(root) !== rec) return
    BOUND.delete(root)
    root.removeEventListener('wheel', handler)
  }
  const rec = { handler, unbind }
  BOUND.set(root, rec)
  // passive:false：不声明的话 Chromium 对 wheel 默认按 passive 处理，
  // preventDefault 会被忽略，横滚的同时页面还会跟着纵向滚一下。
  root.addEventListener('wheel', handler, { passive: false })
  return unbind
}

/**
 * 按选择器给 root 下的每个匹配元素挂上（数量随分屏开关变化，所以要能反复跑）。
 * @param {any} root 组件根元素
 * @param {string} selector 例 '.tabs-scroll'
 * @param {Set<Function>} [sink] 收集 unbind 的集合（同一元素返回同一个函数，天然去重）
 * @returns {Set<Function>} sink
 */
export function bindHorizontalWheelAll(root, selector, sink = new Set()) {
  if (!root || typeof root.querySelectorAll !== 'function') return sink
  for (const el of root.querySelectorAll(selector)) sink.add(bindHorizontalWheel(el))
  return sink
}
