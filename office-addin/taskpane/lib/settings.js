/**
 * 连接配置持久化（localStorage）。
 * 常规形态：用户只填官网账户 Key（awdk_），换回的 awdt_ 设备令牌存本机；
 * 后端地址有构建期默认值，仅自建服务器场景需要在高级设置里改。
 */
const KEY_SERVER = 'awd_addin_server_url'
const KEY_TOKEN = 'awd_addin_token'
const KEY_PROJECT = 'awd_addin_project_id'

/**
 * 构建期注入的默认后端地址（见 vite.config.js 的 define）。
 * 非 vite 环境（单测等）下常量不存在，退回空串。
 */
export const DEFAULT_SERVER_URL =
  typeof __ADDIN_DEFAULT_SERVER__ === 'string' ? __ADDIN_DEFAULT_SERVER__ : ''

export function normalizeBaseUrl(url) {
  return (url || '').trim().replace(/\/+$/, '')
}

export function loadSettings() {
  return {
    // 用户显式改过（localStorage 有非空值）则以用户值为准，否则用默认地址
    serverUrl: localStorage.getItem(KEY_SERVER) || normalizeBaseUrl(DEFAULT_SERVER_URL),
    token: localStorage.getItem(KEY_TOKEN) || '',
    projectId: localStorage.getItem(KEY_PROJECT) || ''
  }
}

export function saveSettings({ serverUrl, token }) {
  localStorage.setItem(KEY_SERVER, normalizeBaseUrl(serverUrl))
  localStorage.setItem(KEY_TOKEN, (token || '').trim())
}

export function saveProjectId(projectId) {
  localStorage.setItem(KEY_PROJECT, projectId == null ? '' : String(projectId))
}

export function isConfigured(settings) {
  return Boolean(settings.serverUrl && settings.token)
}
