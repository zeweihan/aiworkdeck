// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 用户认证工具函数
// 说明：管理用户登录状态和 sessionId

const SESSION_KEY = 'checkba_session_id'
const USER_KEY = 'checkba_user'

/**
 * 保存 sessionId
 */
export function saveSession(sessionId, user) {
  try {
    uni.setStorageSync(SESSION_KEY, sessionId)
    uni.setStorageSync(USER_KEY, user)
  } catch (e) {
    console.error('保存 session 失败:', e)
  }
}

/**
 * 获取 sessionId
 */
export function getSessionId() {
  try {
    return uni.getStorageSync(SESSION_KEY)
  } catch (e) {
    console.error('获取 session 失败:', e)
    return null
  }
}

/**
 * 获取当前用户信息
 */
export function getCurrentUser() {
  try {
    return uni.getStorageSync(USER_KEY)
  } catch (e) {
    console.error('获取用户信息失败:', e)
    return null
  }
}

/**
 * 更新当前用户信息
 */
export function setSessionUser(user) {
  try {
    uni.setStorageSync(USER_KEY, user)
  } catch (e) {
    console.error('更新用户信息失败:', e)
  }
}


/**
 * 清除 session
 */
export function clearSession() {
  try {
    uni.removeStorageSync(SESSION_KEY)
    uni.removeStorageSync(USER_KEY)
  } catch (e) {
    console.error('清除 session 失败:', e)
  }
}

/**
 * 检查是否已登录
 */
export function isLoggedIn() {
  return getSessionId() != null && getCurrentUser() != null
}

/**
 * 获取请求头（包含 sessionId）
 */
export function getAuthHeaders() {
  const sessionId = getSessionId()
  const headers = {
    'Content-Type': 'application/json',
  }
  if (sessionId) {
    headers['X-Session-Id'] = sessionId
  }
  return headers
}

