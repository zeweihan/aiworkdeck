// 「录音中」全局浮动指示器的挂载点。
//
// 与反馈浮窗（utils/feedbackWidget.js）同一模式：录音横跨页面跳转持续进行，
// 指示器必须在页面树之外常驻——body 下独立 createApp，全应用一个实例。
// 组件内部按 recorderState 决定显隐，空闲时什么都不渲染。
import { createApp } from 'vue'
import MeetingRecordingIndicator from '@/components/MeetingRecordingIndicator.vue'

const CONTAINER_ID = 'awd-recording-indicator'

let mounted = false

export function mountRecordingIndicator() {
  // #ifdef H5
  if (mounted || typeof document === 'undefined') return
  if (document.getElementById(CONTAINER_ID)) return
  try {
    const el = document.createElement('div')
    el.id = CONTAINER_ID
    document.body.appendChild(el)
    createApp(MeetingRecordingIndicator).mount(el)
    mounted = true
  } catch (e) {
    // 指示器挂不上不影响录音本身
    console.warn('[meeting] 录音指示器挂载失败:', e)
  }
  // #endif
}
