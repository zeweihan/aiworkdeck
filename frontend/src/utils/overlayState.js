// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 页面之外的全屏浮层开关（反馈浮窗 / MemoryBrowser 弹窗态等共用）。
//
// 为什么要有这么一个模块级状态：桌面端的 BrowserView 是原生层，永远盖在 DOM 上面，
// 所以任何全屏弹窗打开时都得让主进程把 view 藏起来。project-overview 已经有一套
// desktopOverlayActive → setViewsVisible 的 watcher，但反馈浮窗挂在页面之外
// （App.vue 里独立 mount，见 utils/feedbackWidget.js），它自己去调 setViewsVisible
// 会和那个 watcher 互相打架——一边藏一边显，谁最后跑谁说了算。
//
// 这里改成单一真相：各浮层只置这个状态，project-overview 的 desktopOverlayActive
// 把它或进去，仍由那一处 watcher 统一控制 view 的显隐。
//
// 按持有者记账而不是单个布尔或计数（dev-board#879）：可能同时存在两个各自开关它的
// 浮层（反馈浮窗 + ChatInterface 的记忆弹窗）。单布尔时后关的那个会把还开着的另一个
// 也置成 false，BrowserView 提前露出来挡住先开的浮层；裸计数时同一浮层重复 open
// （rail 图标连点两次）会多记一份、永远释放不掉。按 holder 键记 Set，同一持有者
// 重复置 true/false 天然幂等，只有所有持有者都释放后才归零。
import { ref, computed } from 'vue'

const holders = ref(new Set())

export const globalOverlayActive = computed(() => holders.value.size > 0)

/**
 * @param {boolean} active 是否持有全屏浮层
 * @param {string} holder 持有者键；不传时为 'default'（反馈浮窗这类全局单例）。
 *   同一组件可能多实例的（MemoryBrowser）必须传每实例唯一的键。
 */
export function setGlobalOverlay(active, holder = 'default') {
  const next = new Set(holders.value)
  if (active) next.add(holder)
  else next.delete(holder)
  holders.value = next
}
