/**
 * 连接配置持久化（localStorage）。
 * MVP 形态：后端地址 + awdt_ 设备令牌手工粘贴；Phase D 会替换为 awdk_ 桥的体面流程。
 */
const KEY_SERVER = 'awd_addin_server_url'
const KEY_TOKEN = 'awd_addin_token'
const KEY_PROJECT = 'awd_addin_project_id'

export function normalizeBaseUrl(url) {
  return (url || '').trim().replace(/\/+$/, '')
}

export function loadSettings() {
  return {
    serverUrl: localStorage.getItem(KEY_SERVER) || '',
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
