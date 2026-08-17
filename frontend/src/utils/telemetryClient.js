import { logTelemetryEvent } from '@/services/api.js'
import { getSessionId } from '@/utils/auth.js'
import { isDesktopHost } from '@/services/host.js'

/**
 * 产品埋点前端入口（设计见 docs/ANALYTICS_TELEMETRY_DESIGN.md）。
 *
 * 与 activityTracker（律师工时计费功能，用户手动开关）完全无关，不要混用。
 * 只发枚举/数值/布尔字段，服务端 TelemetryAttrWhitelist 二次强制；
 * 失败静默——埋点绝不影响业务，也绝不弹错。
 */
export function track(eventName, attrs) {
  try {
    // 浏览器态未登录时一条都不发。/api/telemetry/event 本来就要会话，未登录必回
    // 4010，而 request() 收到 4010 会 reLaunch 登录页，reLaunch 又被 App.vue 的
    // 导航拦截器再记一条 ui.nav——「跳转 → 埋点 → 4010 → 跳转」自激成死循环，
    // 浏览器端就是整页无限刷新（addin.aiworkdeck.com 实测）。桌面 local-mode
    // 免登录，恒可发。
    if (!isDesktopHost() && !getSessionId()) return
    logTelemetryEvent(eventName, attrs || {}).catch(() => {})
  } catch (e) {
    // 静默
  }
}
