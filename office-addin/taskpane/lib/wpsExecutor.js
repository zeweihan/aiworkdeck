/**
 * office_command 的 WPS 家族执行器（与 officeExecutor.js 同契约）：
 * 后端 OfficeBridgeService 经 SSE client_action 下发 {tool:'office_command',
 * requestId, command, args}，本模块按 command 分发到 WPS 加载项 JSAPI 实现并
 * 返回 {ok, data|error}，由调用方 POST /api/agent/office/result 回传。
 *
 * 硬规则与 Office 面完全一致：
 * - 后端注册的每个 office_* 工具都必须有对应实现，未知 command 立即回
 *   {ok:false, error:'unsupported command'}，绝不静默吞掉；
 * - 宿主守卫是最后一道防线（正常情况下后端已按 officeHost 过滤）；
 * - executeWpsCommand 永不 throw。
 *
 * 命令名与参数/返回值契约以 officeExecutor.js 为准绳——两个家族对后端和模型
 * 呈现同一张工具表，行为差异（如 WPS 有真的「最小值行距」而 Office.js 没有）
 * 只体现在返回值的说明字段里。
 *
 * 各宿主 HANDLERS 拆在三个文件里（文字/表格/演示），本文件只做合并与分发。
 */

import { wpsAvailable, detectWpsHost } from './wpsDoc.js'
import { WPS_WORD_HANDLERS, locateInWpsDocument as locateImpl } from './wpsWordHandlers.js'
import { WPS_ET_HANDLERS } from './wpsEtHandlers.js'
import { WPS_WPP_HANDLERS } from './wpsWppHandlers.js'

const HANDLERS = {
  ...WPS_WORD_HANDLERS,
  ...WPS_ET_HANDLERS,
  ...WPS_WPP_HANDLERS
}

/** 与 officeExecutor.COMMAND_HOSTS 同口径：前缀定宿主，其余归文字 */
function requiredHostOf(command) {
  if (command.startsWith('excel_')) return 'excel'
  if (command.startsWith('ppt_')) return 'powerpoint'
  return 'word'
}

const HOST_LABELS = { word: 'WPS 文字', excel: 'WPS 表格', powerpoint: 'WPS 演示' }

/** 引用定位（正文引文 chip 点击选中），仅文字宿主 */
export async function locateInWpsDocument(text) {
  if (!wpsAvailable() || detectWpsHost() !== 'word') return { found: false }
  try {
    return await locateImpl(text)
  } catch (e) {
    return { found: false }
  }
}

/**
 * 执行一条 office_command。永不 throw：一律返回 {ok:true, data} 或 {ok:false, error}。
 */
export async function executeWpsCommand(command, args) {
  if (!wpsAvailable()) {
    return { ok: false, error: 'WPS 环境不可用：请在 WPS 任务窗格中使用本插件' }
  }
  const handler = HANDLERS[command]
  if (!handler) {
    return { ok: false, error: `unsupported command: ${command}` }
  }
  const requiredHost = requiredHostOf(command)
  const host = detectWpsHost()
  if (host !== requiredHost) {
    return {
      ok: false,
      error: `unsupported host: 该命令只在 ${HOST_LABELS[requiredHost]} 中可用（当前宿主：${HOST_LABELS[host] || '未知'}）`
    }
  }
  try {
    const data = await handler(args || {})
    return { ok: true, data: data == null ? {} : data }
  } catch (e) {
    const message = (e && e.message) || String(e)
    console.warn('[Addin] office_command 执行失败（WPS）', command, e)
    return { ok: false, error: message }
  }
}
