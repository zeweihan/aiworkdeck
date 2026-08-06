import { logTelemetryEvent } from '@/services/api.js'

/**
 * 产品埋点前端入口（设计见 docs/ANALYTICS_TELEMETRY_DESIGN.md）。
 *
 * 与 activityTracker（律师工时计费功能，用户手动开关）完全无关，不要混用。
 * 只发枚举/数值/布尔字段，服务端 TelemetryAttrWhitelist 二次强制；
 * 失败静默——埋点绝不影响业务，也绝不弹错。
 */
export function track(eventName, attrs) {
  try {
    logTelemetryEvent(eventName, attrs || {}).catch(() => {})
  } catch (e) {
    // 静默
  }
}
