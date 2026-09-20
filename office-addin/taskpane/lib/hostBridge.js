// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 宿主桥：同一套任务窗格 Vue 层跑在两个宿主家族里——Microsoft Office（Office.js）
 * 与 WPS（WPS 加载项 JSAPI）。Vue 层（chatSession/ChatView）只 import 本模块，
 * 由这里按运行环境分发到 wordDoc/officeExecutor（Office 面）或 wpsDoc/wpsExecutor
 * （WPS 面）。两个家族的实现文件互不感知，各自忠于各自宿主的原生 API。
 *
 * 环境判定：Office 家族看 Office.context（office.js 由 taskpane.html 以 script
 * 标签引入，WPS 构建的入口页不含它）；WPS 家族看 window.wps（WPS 任务窗格 webview
 * 注入）。两者不会共存——一份构建产物只服务一个家族，这里的判定是运行时兜底，
 * 也让普通浏览器直开调试（两者皆无）时优雅降级，行为与改造前一致。
 *
 * 对后端而言两个家族无差别：officeHost 仍是 word/excel/powerpoint 三值
 * （WPS 文字/表格/演示分别映射），clientCapability 仍是 'office'，
 * office_command 契约与回传路径完全一致——后端零改动是本设计的硬约束。
 */
import {
  officeAvailable,
  detectHost as officeDetectHost,
  readActiveDocument as officeReadActiveDocument,
  readDocumentMeta as officeReadDocumentMeta,
  officeDocumentPath
} from './wordDoc.js'
import {
  executeOfficeCommand,
  commandDisplayName as sharedCommandDisplayName,
  locateInDocument as officeLocateInDocument,
  trackingSupported as officeTrackingSupported,
  captureOfficeState,
  readOfficeState,
  writeOfficeState,
  locateOfficeTarget
} from './officeExecutor.js'
import {
  wpsAvailable,
  detectWpsHost,
  readWpsActiveDocument,
  readWpsDocumentMeta,
  hideWpsTaskPane,
  wpsDocumentPath
} from './wpsDoc.js'
import {
  executeWpsCommand,
  locateInWpsDocument,
  wpsTrackingSupported,
  captureWpsState,
  readWpsState,
  writeWpsState,
  locateWpsTarget
} from './wpsExecutor.js'
import { docKeyOf } from './revisionLog.js'

export { hashContent } from './wordDoc.js'

/** 'office' | 'wps' | ''（普通浏览器调试） */
export function hostFamily() {
  if (officeAvailable()) return 'office'
  if (wpsAvailable()) return 'wps'
  return ''
}

export function hostAvailable() {
  return hostFamily() !== ''
}

/**
 * 当前宿主：'word' | 'excel' | 'powerpoint' | ''。
 * WPS 文字/表格/演示归一到同一套三值——后端按 officeHost 细分工具可见性，
 * 不感知家族差异。
 *
 * 注意判定顺序不是走 hostFamily：officeDetectHost 自带「Office.context 取不到时
 * 按 Word/Excel 全局对象兜底」的降级路（office.js 未初始化的窗口期 + 既有测试
 * 依赖它），必须先问它、拿不到再问 WPS，语义才与改造前逐点一致。
 */
export function detectHost() {
  const officeHost = officeDetectHost()
  if (officeHost) return officeHost
  if (wpsAvailable()) return detectWpsHost()
  return ''
}

export async function readActiveDocument() {
  const family = hostFamily()
  if (family === 'office') return officeReadActiveDocument()
  if (family === 'wps') return readWpsActiveDocument()
  return null
}

/**
 * 与 readActiveDocument 不同，这里跟 detectHost 同款「不要求 officeAvailable」——
 * 壳（id/name/fileType）在 office.js 半初始化状态下也要能出（旧实现如此，
 * 发送契约测试钉着这条）。
 */
export function readDocumentMeta() {
  if (officeDetectHost()) return officeReadDocumentMeta()
  if (wpsAvailable() && detectWpsHost()) return readWpsDocumentMeta()
  return null
}

/**
 * 执行一条 office_command。与两个家族的执行器同契约：永不 throw，
 * 一律返回 {ok:true, data} 或 {ok:false, error}。
 */
export async function executeCommand(command, args) {
  const family = hostFamily()
  if (family === 'office') return executeOfficeCommand(command, args)
  if (family === 'wps') return executeWpsCommand(command, args)
  return { ok: false, error: '宿主环境不可用：请在 Office 或 WPS 任务窗格中使用本插件' }
}

/** 命令中文名表两个家族共用（officeExecutor 里的纯数据表，不碰宿主 API） */
export function commandDisplayName(command) {
  return sharedCommandDisplayName(command)
}

/**
 * 收起任务窗格（仅 WPS 家族有意义）：WPS 平台 bug 会在窗格停靠期间冻住 ribbon
 * （bbs 93291），窗格内的收起按钮是用户唯一的解锁通路。Office 家族无此问题也
 * 没有对应 API，返回 false（界面按 hostFamily 隐藏按钮，正常不会调到）。
 */
export function hidePanel() {
  if (hostFamily() === 'wps') return hideWpsTaskPane()
  return false
}

/** 引用定位（正文引文 chip 点击选中），仅 Word/文字宿主 */
export async function locateInDocument(text) {
  const family = hostFamily()
  if (family === 'office') return officeLocateInDocument(text)
  if (family === 'wps') return locateInWpsDocument(text)
  return { found: false }
}

// ==================== 跨文档写入（dev-board#717） ====================

/**
 * 本窗格的文字宿主能否标记修订：别的窗格发来的写入要靠它判定能不能执行
 * （标不了就拒绝，无痕迹的跨文档写入不允许发生）。Office = WordApi 1.4；
 * WPS = TrackRevisions 可读写。非文字宿主与普通浏览器一律 false。
 */
export function crossDocTrackingOk() {
  const family = hostFamily()
  if (family === 'office') return officeTrackingSupported()
  if (family === 'wps') return wpsTrackingSupported()
  return false
}

/** 执行前取受影响区域的原值：{target, before} 或 null（不可撤销） */
export async function captureCrossDocState(command, args, limits) {
  const family = hostFamily()
  if (family === 'office') return captureOfficeState(command, args, limits)
  if (family === 'wps') return captureWpsState(command, args, limits)
  return null
}

/** 按 target 读当前值；目标已不存在时回 null */
export async function readCrossDocState(target) {
  const family = hostFamily()
  if (family === 'office') return readOfficeState(target)
  if (family === 'wps') return readWpsState(target)
  return null
}

/** 把快照写回宿主（撤销）；失败抛错 */
export async function writeCrossDocState(state) {
  const family = hostFamily()
  if (family === 'office') return writeOfficeState(state)
  if (family === 'wps') return writeWpsState(state)
  throw new Error('宿主环境不可用：请在 Office 或 WPS 任务窗格中使用本插件')
}

/**
 * 修订记录面板的「定位」：按快照 target 跳到被改的位置（Excel 激活表并选中区域、
 * PPT 跳到那一页）。Word 条目不走这里（靠 locateInDocument 按文字选中）。
 * 永不 throw，一律回 {found}——定位不到只是轻提示，不是错误。
 */
export async function locateCrossDocTarget(target) {
  const family = hostFamily()
  if (family === 'office') return locateOfficeTarget(target)
  if (family === 'wps') return locateWpsTarget(target)
  return { found: false }
}

/**
 * 本窗格实例的随机后缀：模块加载时生成一次，不持久化（与 chatSession.paneId 同寿命）。
 * 只用在「没有文件路径」的那条退路上，见 documentKey。
 */
const UNSAVED_DOC_SCOPE = 'u' + Date.now().toString(36) + Math.random().toString(36).slice(2, 10)

/**
 * 修订记录与会话 ID 按哪一份文档分开存：有文件路径（Office 的文档 URL、WPS 的 FullName）
 * 就用路径；连宿主都判不出（普通浏览器调试）回空串 = 不绑定，条目只在内存里。
 *
 * **没有路径时不能只用「宿主:文档名」**（dev-board#717）：Office 面的文档名正是从
 * Office.context.document.url 推出来的，url 为空（未保存的新文档）时它是宿主通称
 * （「当前 Word 文档」），于是两份新建的 Word 算出同一个键 → 同一条 conversationId →
 * 跨窗格下发按会话走，命令落到另一份文档上，沿途无人报错。这时再补一维「本窗格实例」：
 * 未保存的新文档本来就没有稳定身份（窗格重载即换键，修订记录只在本次会话内存活），
 * 但两份新文档绝不会撞在一起。存过盘之后有了路径，键自然回到按路径分，跨重载稳定。
 */
export function documentKey() {
  const host = detectHost()
  if (!host) return ''
  const family = hostFamily()
  const path = family === 'office' ? officeDocumentPath()
    : family === 'wps' ? wpsDocumentPath() : ''
  const meta = readDocumentMeta()
  const key = docKeyOf({ path, host, docName: meta ? meta.name : '' })
  if (!key || path) return key
  return `${key}#${UNSAVED_DOC_SCOPE}`
}
