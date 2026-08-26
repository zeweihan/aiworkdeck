/**
 * 连接配置持久化（localStorage）。
 * 常规形态：用户只填官网账户 Key（awdk_），换回的 awdt_ 设备令牌存本机；
 * 后端地址有构建期默认值，仅自建服务器场景需要在高级设置里改。
 */
import { LANG_STORAGE_KEY } from './i18n.js'

const KEY_SERVER = 'awd_addin_server_url'
const KEY_TOKEN = 'awd_addin_token'
const KEY_PROJECT = 'awd_addin_project_id'
// 会话 ID 按项目分别存：切文档/重开任务窗格会整个重建 webview，
// 会话 ID 只活在内存里就等于每次都从空白开始
const KEY_CONVERSATION_PREFIX = 'awd_addin_conv_'

/**
 * 构建期注入的默认后端地址（见 vite.config.js 的 define）。
 * 非 vite 环境（单测等）下常量不存在，退回空串。
 */
export const DEFAULT_SERVER_URL =
  typeof __ADDIN_DEFAULT_SERVER__ === 'string' ? __ADDIN_DEFAULT_SERVER__ : ''

export function normalizeBaseUrl(url) {
  return (url || '').trim().replace(/\/+$/, '')
}

/**
 * localStorage 在 Office 任务窗格的 webview 里不保证可用：第三方存储被策略禁用时
 * getItem/setItem 会抛 SecurityError，未定义时引用标识符本身就是 ReferenceError。
 * App.vue 顶层同步调用 loadSettings()，异常会让 createApp().mount() 整个抛出、
 * 任务窗格永远白屏——所以这里统一兜底：读不到用默认值，写不进静默降级。
 */
function safeGetItem(key) {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

function safeSetItem(key, value) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // 存储不可用：静默降级，本次会话内功能仍可用，只是刷新后不记住
  }
}

function safeRemoveItem(key) {
  try {
    localStorage.removeItem(key)
  } catch {
    // 同上
  }
}

/**
 * OfficeRuntime.storage 镜像（dev-board#174「登录状态不保留」的根治）。
 *
 * localStorage 活在任务窗格的 webview 缓存里，Office（尤其 Mac 的 WKWebView）
 * 会不定期清掉它——令牌一丢用户就得重新登录。OfficeRuntime.storage 是微软给
 * 插件的原生持久 KV（官方文档明确建议存 auth token），不随 webview 缓存清理。
 * 策略：写入双写（localStorage + 镜像，异步 fire-and-forget），启动时若
 * localStorage 里没有令牌则从镜像回灌（hydrateSettings）。
 */
function officeStorage() {
  try {
    if (typeof OfficeRuntime !== 'undefined' && OfficeRuntime.storage) return OfficeRuntime.storage
  } catch {
    // OfficeRuntime 不存在（普通浏览器调试）
  }
  return null
}

function mirrorSet(key, value) {
  const s = officeStorage()
  if (!s) return
  try {
    const p = value ? s.setItem(key, String(value)) : s.removeItem(key)
    if (p && p.catch) p.catch(() => {})
  } catch {
    // 镜像失败不影响主路径
  }
}

async function mirrorGet(key) {
  const s = officeStorage()
  if (!s) return null
  try {
    return await s.getItem(key)
  } catch {
    return null
  }
}

/**
 * 从 OfficeRuntime.storage 回灌被 webview 清掉的设置。
 * 返回补全后的最新值（无镜像/无增量时返回 null，调用方无事可做）。
 * 语言覆盖（LANG_STORAGE_KEY）一并回灌，调用方拿到 lang 后自行 setLang。
 */
export async function hydrateSettings() {
  const s = officeStorage()
  if (!s) return null
  const [server, token, project, lang] = await Promise.all([
    mirrorGet(KEY_SERVER), mirrorGet(KEY_TOKEN), mirrorGet(KEY_PROJECT), mirrorGet(LANG_STORAGE_KEY)
  ])
  if (!server && !token && !project && !lang) return null
  let changed = false
  // localStorage 有值 = 没被清（或用户改过），以它为准；只补空缺的
  if (token && !safeGetItem(KEY_TOKEN)) { safeSetItem(KEY_TOKEN, token); changed = true }
  if (server && !safeGetItem(KEY_SERVER)) { safeSetItem(KEY_SERVER, server); changed = true }
  if (project && !safeGetItem(KEY_PROJECT)) { safeSetItem(KEY_PROJECT, project); changed = true }
  if (lang && !safeGetItem(LANG_STORAGE_KEY)) { safeSetItem(LANG_STORAGE_KEY, lang); changed = true }
  if (!changed) return null
  return { ...loadSettings(), lang: safeGetItem(LANG_STORAGE_KEY) || '' }
}

/** 语言覆盖写镜像（i18n.setLang 只写 localStorage，这里补一笔持久层）。 */
export function mirrorLang(lang) {
  mirrorSet(LANG_STORAGE_KEY, lang)
}

/** 退出登录：只清令牌（后端地址与项目选择保留，换账号重登时少填一遍）。 */
export function clearToken() {
  safeRemoveItem(KEY_TOKEN)
  mirrorSet(KEY_TOKEN, '')
}

export function loadSettings() {
  return {
    // 用户显式改过（localStorage 有非空值）则以用户值为准，否则用默认地址
    serverUrl: safeGetItem(KEY_SERVER) || normalizeBaseUrl(DEFAULT_SERVER_URL),
    token: safeGetItem(KEY_TOKEN) || '',
    projectId: safeGetItem(KEY_PROJECT) || ''
  }
}

export function saveSettings({ serverUrl, token }) {
  const server = normalizeBaseUrl(serverUrl)
  const trimmed = (token || '').trim()
  safeSetItem(KEY_SERVER, server)
  safeSetItem(KEY_TOKEN, trimmed)
  mirrorSet(KEY_SERVER, server)
  mirrorSet(KEY_TOKEN, trimmed)
}

export function saveProjectId(projectId) {
  const value = projectId == null ? '' : String(projectId)
  safeSetItem(KEY_PROJECT, value)
  mirrorSet(KEY_PROJECT, value)
}

/**
 * 取该项目上次的会话 ID（无则空串）。
 */
export function loadConversationId(projectId) {
  if (!projectId) return ''
  return safeGetItem(KEY_CONVERSATION_PREFIX + projectId) || ''
}

/**
 * 记住该项目的会话 ID；传空值即清除（「新对话」）。
 */
export function saveConversationId(projectId, conversationId) {
  if (!projectId) return
  const key = KEY_CONVERSATION_PREFIX + projectId
  if (conversationId) safeSetItem(key, String(conversationId))
  else safeRemoveItem(key)
}

const KEY_MODEL = 'awd_addin_model'

/** 模型选择持久化（空串=跟随后端默认）。 */
export function loadModelChoice() {
  return safeGetItem(KEY_MODEL) || ''
}

export function saveModelChoice(modelId) {
  if (modelId) safeSetItem(KEY_MODEL, String(modelId))
  else safeRemoveItem(KEY_MODEL)
}

export function isConfigured(settings) {
  return Boolean(settings.serverUrl && settings.token)
}
