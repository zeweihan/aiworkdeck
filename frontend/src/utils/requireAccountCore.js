// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 「需要账户时就地登录」的纯逻辑（登录后置设计 2026-09-29 §5.1，dev-board#1046）。
// 零依赖：不 import uni / vue / api.js，node --test 直接跑（tests/account/require-account.test.mjs）。
// 接线（挂弹层、调接口、广播事件）在同目录 requireAccount.js。

/**
 * 后端 4011 回包里的 reason（AccountRequired.REASON_*）→ 前端弹层的说明键。
 * 后端多出来的 reason 按原样用（i18n 里没有对应键时弹层退回通用说明），不静默丢掉。
 */
const BACKEND_REASON_MAP = {
  platform_ai: 'ai',
  gateway: 'gateway',
  market: 'market',
  team: 'team',
  mobile: 'mobile',
  meeting: 'meeting',
  dictation: 'dictation',
}

/** 前端认得的 reason（决定弹层顶部那句说明）。与 locales 里 account.loginDialog.reason.* 对拍。 */
export const REASONS = ['ai', 'market', 'team', 'mobile', 'meeting', 'gateway', 'dictation', 'settings', 'account']

export function reasonFromBackend(raw) {
  const key = String(raw || '').trim()
  if (!key) return ''
  return BACKEND_REASON_MAP[key] || key
}

/**
 * @param {object} deps
 * @param {() => Promise<{connected:boolean}>} deps.getStatus  读账户连接状态（GET /api/account/status）
 * @param {(opts:{reason:string}) => Promise<{ok:boolean, result?:object}>} deps.openDialog  打开登录弹层
 * @param {(result:object) => void} deps.onLoggedIn  登录成功后的广播（awd:account-changed）
 * @param {() => number} [deps.now]
 * @param {number} [deps.ttlMs]  连接状态的缓存时长。/api/account/status 会顺手打一次官网 /me，
 *                               不能每点一次发送就查一次
 * @param {number} [deps.autoCooldownMs]  由 4011 自动触发的弹层，用户取消后多久内不再自动弹
 */
export function createAccountGate(deps) {
  const now = deps.now || (() => Date.now())
  const ttlMs = deps.ttlMs == null ? 30000 : deps.ttlMs
  const autoCooldownMs = deps.autoCooldownMs == null ? 60000 : deps.autoCooldownMs

  let cache = null // { connected, at }
  let inflight = null // 同一时刻只开一个弹层，并发的调用方共用同一个结果
  let autoSuppressedUntil = 0

  function noteConnected(connected) {
    cache = { connected: !!connected, at: now() }
  }

  function invalidate() {
    cache = null
  }

  async function isConnected() {
    if (cache && now() - cache.at < ttlMs) return cache.connected
    try {
      const st = await deps.getStatus()
      noteConnected(!!(st && st.connected))
    } catch (e) {
      // 查不到状态：不拿「未登录」吓人，按未知处理——交给弹层之外的原路径去报真错
      return null
    }
    return cache.connected
  }

  /**
   * @param {object} [opts]
   * @param {string} [opts.reason]  说明用途（REASONS 之一）
   * @param {boolean} [opts.force]  跳过「已连接」判定直接开弹层（后端刚回了 4011：本地缓存已经不可信）
   * @param {boolean} [opts.auto]   由 4011 自动触发（不是用户点的）：用户刚取消过就不再追着弹
   * @returns {Promise<boolean>} 已连接或登录成功 true；取消 false
   */
  async function requireAccount(opts = {}) {
    if (opts.force) {
      invalidate()
    } else {
      const connected = await isConnected()
      if (connected === true) return true
      // 状态未知（查询失败）：放行，由调用方的真实请求去报错，而不是凭空弹一个登录框
      if (connected === null) return true
    }
    if (opts.auto && now() < autoSuppressedUntil) return false
    if (inflight) return inflight
    inflight = (async () => {
      try {
        const res = await deps.openDialog({ reason: opts.reason || '' })
        if (res && res.ok) {
          noteConnected(true)
          autoSuppressedUntil = 0
          try { deps.onLoggedIn(res.result || {}) } catch (e) { /* 广播失败不影响结果 */ }
          return true
        }
        if (opts.auto) autoSuppressedUntil = now() + autoCooldownMs
        return false
      } finally {
        inflight = null
      }
    })()
    return inflight
  }

  return { requireAccount, invalidate, noteConnected, isConnected }
}

/**
 * api.js 4011 分支 reject 出去的错误（登录弹层关掉之后）。
 *
 * <p>**刻意是 reject 不是 resolve**：4011 意味着刚才那次请求**没有执行**。resolve 一个
 * 「成功」形状出去，不认识它的调用方会把它当成功——安装付费项的地方会弹「安装成功」、
 * 读 res.data.xxx 的地方会在 null 上炸。reject 的话不认识它的调用方照常走 catch、弹一句
 * message；认识它的调用方用 isAccountLoginRetry(err) 判断「用户刚登录成功了」，自行重试。
 *
 * @param {object} backend 后端 4011 回包 {code, kind, reason, message, gatewayKind?, canUseOwnKey?}
 * @param {boolean} loggedIn 弹层里是否登录成功
 * @param {string} retryMessage 登录成功时给不认识 loggedIn 的调用方看的那句（「已登录，请再试一次」）
 */
export function accountRequiredError(backend, loggedIn, retryMessage) {
  const b = backend || {}
  const err = new Error(loggedIn && retryMessage ? retryMessage : (b.message || ''))
  err.code = 4011
  err.accountRequired = true
  err.loggedIn = !!loggedIn
  err.reason = reasonFromBackend(b.reason)
  if (b.gatewayKind) {
    err.gatewayKind = b.gatewayKind
    err.canUseOwnKey = b.canUseOwnKey !== false
  }
  return err
}

/** 为真说明：刚才那次请求因为没账户没执行，而用户已在弹层里登录成功——可以重试。 */
export function isAccountLoginRetry(err) {
  return !!(err && err.accountRequired === true && err.loggedIn === true && err.code === 4011)
}
