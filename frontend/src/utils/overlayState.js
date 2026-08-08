// 页面之外的全屏浮层开关（当前只有反馈浮窗用）。
//
// 为什么要有这么一个模块级 ref：桌面端的 BrowserView 是原生层，永远盖在 DOM 上面，
// 所以任何全屏弹窗打开时都得让主进程把 view 藏起来。project-overview 已经有一套
// desktopOverlayActive → setViewsVisible 的 watcher，但反馈浮窗挂在页面之外
// （App.vue 里独立 mount，见 utils/feedbackWidget.js），它自己去调 setViewsVisible
// 会和那个 watcher 互相打架——一边藏一边显，谁最后跑谁说了算。
//
// 这里改成单一真相：浮窗只置这个 ref，project-overview 的 desktopOverlayActive
// 把它或进去，仍由那一处 watcher 统一控制 view 的显隐。
import { ref } from 'vue'

export const globalOverlayActive = ref(false)

export function setGlobalOverlay(active) {
  globalOverlayActive.value = !!active
}
