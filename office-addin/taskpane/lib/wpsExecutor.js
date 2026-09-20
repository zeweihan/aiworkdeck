// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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
import {
  WPS_WORD_HANDLERS, locateInWpsDocument as locateImpl, withForcedTracking, wpsTrackingSupported
} from './wpsWordHandlers.js'
import { WPS_ET_HANDLERS, captureEtState, readEtState, writeEtState, locateEtTarget } from './wpsEtHandlers.js'
import {
  WPS_WPP_HANDLERS, captureWppState, readWppState, writeWppState, locateWppTarget
} from './wpsWppHandlers.js'

export { wpsTrackingSupported }

const HANDLERS = {
  ...WPS_WORD_HANDLERS,
  ...WPS_ET_HANDLERS,
  ...WPS_WPP_HANDLERS
}

const HOST_HANDLERS = { word: WPS_WORD_HANDLERS, excel: WPS_ET_HANDLERS, powerpoint: WPS_WPP_HANDLERS }

/**
 * 三个宿主各有一份实现的命令（dev-board#717）：按当前宿主分发，不按前缀定宿主。
 * 上面的展开合并里同名键后者覆盖前者，所以这类命令**绝不能**从 HANDLERS 里取——
 * 否则文字宿主会去跑演示面的实现。
 */
const ANY_HOST_COMMANDS = new Set(['read_for_reference'])

/** 与 officeExecutor.COMMAND_HOSTS 同口径：前缀定宿主，其余归文字；三宿主通用的命令要求当前宿主 */
function requiredHostOf(command, currentHost) {
  if (ANY_HOST_COMMANDS.has(command)) return currentHost
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
  if (!HANDLERS[command]) {
    return { ok: false, error: `unsupported command: ${command}` }
  }
  const host = detectWpsHost()
  const requiredHost = requiredHostOf(command, host)
  if (ANY_HOST_COMMANDS.has(command) && !HOST_HANDLERS[host]) {
    return { ok: false, error: 'unsupported host: 无法识别当前 WPS 宿主（当前宿主：未知）' }
  }
  if (host !== requiredHost) {
    return {
      ok: false,
      error: `unsupported host: 该命令只在 ${HOST_LABELS[requiredHost]} 中可用（当前宿主：${HOST_LABELS[host] || '未知'}）`
    }
  }
  const handler = ANY_HOST_COMMANDS.has(command) ? HOST_HANDLERS[host][command] : HANDLERS[command]
  // __forceTracking 是跨文档写入的内部标记（crossDocWrite 加的，dev-board#717）：
  // 不许漏进 handler 的参数；带着它时文字宿主的写入必须在修订下执行，标不了就拒绝
  const { __forceTracking: forceTracking, ...cleanArgs } = args || {}
  try {
    const data = forceTracking
      ? await withForcedTracking(() => handler(cleanArgs))
      : await handler(cleanArgs)
    return { ok: true, data: data == null ? {} : data }
  } catch (e) {
    const message = (e && e.message) || String(e)
    console.warn('[Addin] office_command 执行失败（WPS）', command, e)
    return { ok: false, error: message }
  }
}

/**
 * 跨文档写入的改前值 / 当前值 / 写回（dev-board#717），与 officeExecutor 的同名三件套同契约。
 * 快照按 kind 分派：excel 归表格宿主，pptCell/pptFrames 归演示宿主；宿主对不上时
 * capture/read 回 null（不可撤销 / 按冲突处理），write 抛错（撤销失败必须让用户看见）。
 */
export async function captureWpsState(command, args, limits) {
  if (!wpsAvailable()) return null
  const host = detectWpsHost()
  if (host === 'excel') return captureEtState(command, args, limits)
  if (host === 'powerpoint') return captureWppState(command, args, limits)
  return null
}

export async function readWpsState(target) {
  if (!wpsAvailable() || !target) return null
  const host = detectWpsHost()
  if (host === 'excel' && target.kind === 'excel') return readEtState(target)
  if (host === 'powerpoint' && (target.kind === 'pptCell' || target.kind === 'pptFrames')) return readWppState(target)
  return null
}

export async function writeWpsState(state) {
  const host = wpsAvailable() ? detectWpsHost() : ''
  const kind = state && state.kind
  if (host === 'excel' && kind === 'excel') return writeEtState(state)
  if (host === 'powerpoint' && (kind === 'pptCell' || kind === 'pptFrames')) return writeWppState(state)
  throw new Error('无法识别的修订快照或宿主不符，撤销未执行')
}

/**
 * 修订记录的「定位」（dev-board#717）：与 officeExecutor.locateOfficeTarget 同契约。
 * 宿主与快照对不上（在表格里点一条演示的记录）只回 {found:false}——定位不到是轻提示，
 * 不是错误；Word 条目靠文字走 locateInWpsDocument，不到这里。
 */
export async function locateWpsTarget(target) {
  if (!wpsAvailable() || !target) return { found: false }
  const host = detectWpsHost()
  if (host === 'excel' && target.kind === 'excel') return locateEtTarget(target)
  if (host === 'powerpoint' && (target.kind === 'pptSlide' || target.kind === 'pptCell' || target.kind === 'pptFrames')) {
    return locateWppTarget(target)
  }
  return { found: false }
}
