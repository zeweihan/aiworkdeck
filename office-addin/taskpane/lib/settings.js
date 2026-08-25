/**
 * 连接配置持久化（localStorage）。
 * 常规形态：用户只填官网账户 Key（awdk_），换回的 awdt_ 设备令牌存本机；
 * 后端地址有构建期默认值，仅自建服务器场景需要在高级设置里改。
 */
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

export function loadSettings() {
  return {
    // 用户显式改过（localStorage 有非空值）则以用户值为准，否则用默认地址
    serverUrl: safeGetItem(KEY_SERVER) || normalizeBaseUrl(DEFAULT_SERVER_URL),
    token: safeGetItem(KEY_TOKEN) || '',
    projectId: safeGetItem(KEY_PROJECT) || ''
  }
}

export function saveSettings({ serverUrl, token }) {
  safeSetItem(KEY_SERVER, normalizeBaseUrl(serverUrl))
  safeSetItem(KEY_TOKEN, (token || '').trim())
}

export function saveProjectId(projectId) {
  safeSetItem(KEY_PROJECT, projectId == null ? '' : String(projectId))
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
