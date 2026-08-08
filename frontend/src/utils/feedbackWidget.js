// 反馈浮窗的全局挂载点。
//
// uni-app 的页面组件挂不出「跨页面常驻」的东西：想在每个页面都有这个入口，
// 要么每个页面各写一遍（11 个页面 × 一份状态），要么挂到页面树之外。这里选后者——
// 在 <body> 下单独 createApp 一个只有反馈浮窗的 Vue 应用，全应用有且只有一个实例，
// 顺带避开了 project-overview 那条「navigateTo 页面栈多实例」的地雷。
//
// 代价是这个 app 实例上没有 uni 的内置组件（view/text/...），所以 FeedbackWidget.vue
// 里一律用原生标签；uni 的全局 API（uni.getStorageSync 等）不受影响，它们挂在 window 上。
import { createApp } from 'vue'
import FeedbackWidget from '@/components/FeedbackWidget.vue'

const CONTAINER_ID = 'awd-feedback-widget'

let mounted = false

export function mountFeedbackWidget() {
  // #ifdef H5
  if (mounted || typeof document === 'undefined') return
  if (document.getElementById(CONTAINER_ID)) return
  try {
    const el = document.createElement('div')
    el.id = CONTAINER_ID
    document.body.appendChild(el)
    createApp(FeedbackWidget).mount(el)
    mounted = true
  } catch (e) {
    // 反馈入口挂不上不能影响应用本身
    console.warn('[feedback] 浮窗挂载失败:', e)
  }
  // #endif
}
