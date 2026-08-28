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
  readDocumentMeta as officeReadDocumentMeta
} from './wordDoc.js'
import {
  executeOfficeCommand,
  commandDisplayName as sharedCommandDisplayName,
  locateInDocument as officeLocateInDocument
} from './officeExecutor.js'
import {
  wpsAvailable,
  detectWpsHost,
  readWpsActiveDocument,
  readWpsDocumentMeta
} from './wpsDoc.js'
import { executeWpsCommand, locateInWpsDocument } from './wpsExecutor.js'

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

/** 引用定位（正文引文 chip 点击选中），仅 Word/文字宿主 */
export async function locateInDocument(text) {
  const family = hostFamily()
  if (family === 'office') return officeLocateInDocument(text)
  if (family === 'wps') return locateInWpsDocument(text)
  return { found: false }
}
