// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 需要账户的功能用到时就地登录（登录后置设计 2026-09-29 §5.1，dev-board#1046）。
//
//   const ok = await requireAccount({ reason: 'ai' })   // 已连接直接 true；否则弹登录层
//   if (!ok) return                                     // 用户取消：停在原地
//   ... 继续原动作（不 reLaunch、不刷新页面）
//
// 判定逻辑（缓存、并发去重、自动触发的冷却）在同目录 requireAccountCore.js，零依赖可单测；
// 这里只做接线：弹层挂 <body>、调账户状态接口、登录成功广播 awd:account-changed。
//
// ── 为什么弹层挂 <body> 而不是某个页面里 ──
// 调用方遍布工作台、设置页、广场、会议面板，还有 services/api.js 收到 4011 时的兜底。
// 照 utils/dialog.js / feedbackWidget.js 的先例在 <body> 下单独 createApp 一个实例
// （App.vue 的模板会被 uni 的 LayoutComponent 整个替换，挂不进去），用完即卸。
// 打开期间持有全局浮层（overlayState.js）：桌面端 BrowserView 是原生层、永远盖在 DOM 上，
// 不让开的话登录框被网页视图挡住、点不到，等它结果的调用方就永远挂着（同 dev-board#968）。

import { createApp } from 'vue'
import AccountLoginDialog from '@/components/account/AccountLoginDialog.vue'
import { i18n } from '@/i18n/index.js'
import { getAccountStatus } from '@/services/api.js'
import { isDesktopHost } from '@/services/host.js'
import { setGlobalOverlay } from '@/utils/overlayState.js'
import { refreshEntitlements } from '@/composables/useEntitlement.js'
import { createAccountGate, reasonFromBackend } from './requireAccountCore.js'

export { isAccountLoginRetry, REASONS } from './requireAccountCore.js'

/** 账户连接状态变了（登录成功 / 退出登录）。订阅方重拉自己依赖账户的数据。 */
export const ACCOUNT_CHANGED_EVENT = 'awd:account-changed'

const CONTAINER_ID = 'awd-account-login-host'
const OVERLAY_HOLDER = 'awd-account-login'

function openDialog({ reason }) {
  return new Promise((resolve) => {
    if (typeof document === 'undefined' || !document.body) {
      resolve({ ok: false })
      return
    }
    let el = document.getElementById(CONTAINER_ID)
    if (!el) {
      el = document.createElement('div')
      el.id = CONTAINER_ID
      document.body.appendChild(el)
    }
    let app = null
    let settled = false
    const finish = (result) => {
      if (settled) return
      settled = true
      try { app && app.unmount() } catch (e) { /* ignore */ }
      setGlobalOverlay(false, OVERLAY_HOLDER)
      resolve(result)
    }
    try {
      app = createApp(AccountLoginDialog, {
        variant: 'dialog',
        reason: reason || '',
        captchaId: 'awd-login-dialog-captcha',
        // 粘 Key 那条路（仅 trialCodeEnabled）只解锁不连账户时，不算「已登录」
        onSuccess: (res) => finish({ ok: !(res && res.connected === false), result: res || {} }),
        onCancel: () => finish({ ok: false }),
      })
      app.use(i18n)
      setGlobalOverlay(true, OVERLAY_HOLDER)
      app.mount(el)
    } catch (e) {
      console.warn('[requireAccount] 登录弹层挂载失败:', e)
      finish({ ok: false })
    }
  })
}

const gate = createAccountGate({
  getStatus: () => getAccountStatus(),
  openDialog,
  onLoggedIn: (result) => {
    broadcastAccountChanged({ connected: true, ...(result || {}) })
  },
})

/**
 * 账户连接变了之后的广播（登录弹层成功 / 退出登录共用）：
 * - awd:account-changed：本模块的缓存与各处账户态（设置页、团队、会议面板、C 卡的 rail 入口）；
 * - awd:market-changed(-from-sidebar)：广场付费项的按钮形态随账户变（同 AdminPane.notifyMarketAccountChanged）；
 * - 权益按新账户同步一次，完了发 awd:entitlements-changed，被额度挡着的面板重拉列表
 *   （契约见 licensing-billing.md「SKU 解锁的前端刷新契约」）。
 */
export function broadcastAccountChanged(payload) {
  try { uni.$emit(ACCOUNT_CHANGED_EVENT, payload || {}) } catch (e) { /* ignore */ }
  try {
    uni.$emit('awd:market-changed')
    uni.$emit('awd:market-changed-from-sidebar')
  } catch (e) { /* ignore */ }
  refreshEntitlements(true)
    .then(() => { try { uni.$emit('awd:entitlements-changed', { source: 'account' }) } catch (e) { /* ignore */ } })
    .catch(() => {})
}

// 别处改了连接状态（设置页粘 Key、退出登录）也走同一个事件：缓存跟着改，
// 否则退出登录之后 30 秒内点 AI 发送不会弹登录层，直接撞后端 4011
try {
  uni.$on(ACCOUNT_CHANGED_EVENT, (payload) => {
    if (payload && typeof payload.connected === 'boolean') gate.noteConnected(payload.connected)
    else gate.invalidate()
  })
} catch (e) { /* 非 uni 环境（单测）无事件总线 */ }

/**
 * @param {object} [opts]
 * @param {'ai'|'market'|'team'|'mobile'|'meeting'|'gateway'|'dictation'|'settings'} [opts.reason]
 * @param {boolean} [opts.force]  不看本地缓存直接开弹层（后端刚回了 4011）
 * @param {boolean} [opts.auto]   由 4011 自动触发（非用户点击）：刚取消过就不再追着弹
 * @returns {Promise<boolean>}
 */
export function requireAccount(opts = {}) {
  // 浏览器端（团队服务器）没有「本机连官网账户」这回事：账户在服务端按人桥接，这里不拦
  if (!isDesktopHost()) return Promise.resolve(true)
  return gate.requireAccount(opts)
}

/** services/api.js 收到 4011 时调用：reason 取后端回包里的那个。 */
export function requireAccountFor4011(backend) {
  return requireAccount({ reason: reasonFromBackend(backend && backend.reason), force: true, auto: true })
}

/** 退出登录 / 在别处连上账户之后，让下一次判定重新问后端。 */
export function invalidateAccountCache() {
  gate.invalidate()
}

/** 已知连接状态（如设置页刚读过 status）时顺手喂给缓存，省一次查询。 */
export function noteAccountConnected(connected) {
  gate.noteConnected(connected)
}
