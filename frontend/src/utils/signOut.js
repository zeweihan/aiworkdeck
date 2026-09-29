// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// signOut.js — 「退出登录」的唯一实现。
//
// 登录后置之后（设计 2026-09-29 §5.4，dev-board#1046）桌面端的「退出登录」只剩一件事：
// 断开本机与 AI WorkDeck 账户的连接（~/.aiworkdeck 下的 awdk_ Key 文件，权益/平台 AI/
// 账户指纹都挂它），然后**停在当前页面**，广播 awd:account-changed 让界面各处刷新。
//
// 与之前的三处不同：
//   - 不再 reLaunch 回启动页：启动不设门了，回启动页只会原样回到这里，还把标签页全销毁；
//   - mode=account 也不再调 deactivateLicense：授权票据留着无害，而启动分流已经不读它；
//     08-18 那条「mode=trial 绝不 deactivate」的红线随之自然消失（一律不 deactivate）；
//   - 没连账户时如实说「没有可退出的登录」，而不是让按钮点下去什么都不发生。
// 「解除授权」（只清本机解锁票据）仍是个人中心「账户与安全」里的高级动作，行为不变。
//
// 浏览器端（团队服务器）没有这层，走原来的 clearSession + 回登录页。

import { disconnectAccount, getAccountStatus } from '@/services/api.js'
import { clearSession } from '@/utils/auth.js'
import { isDesktopHost } from '@/services/host.js'
import { t } from '@/i18n'
// 广播 awd:account-changed（及广场/权益刷新）的唯一出口
import { broadcastAccountChanged } from '@/utils/requireAccount.js'

const confirmModal = (title, content, confirmText) => new Promise((resolve) => uni.showModal({
  title,
  content,
  cancelText: t('common.cancel'),
  confirmText,
  success: (res) => resolve(!!res.confirm),
  fail: () => resolve(false),
}))

/**
 * 退出登录。自带确认弹窗。
 *
 * @returns {Promise<boolean>} 是否真的退出了（用户取消、无可退、失败都返回 false）
 */
export async function signOut() {
  if (!isDesktopHost()) {
    const ok = await confirmModal(
      t('account.logoutConfirmTitle'), t('account.logoutConfirmContent'), t('account.logoutBtn'))
    if (!ok) return false
    try { clearSession() } catch (e) { /* 会话本来就没了也算退成功 */ }
    uni.reLaunch({ url: '/pages/login/login' })
    return true
  }

  // 查询失败不当场报错：按「连着账户」处理，后面真调接口时才是可信的成败
  let connected = true
  try {
    const st = await getAccountStatus()
    connected = !!(st && st.connected)
  } catch (e) { /* 查不到就按连着处理 */ }

  if (!connected) {
    uni.showModal({
      title: t('account.logoutNothingTitle'),
      content: t('account.logoutNothingContent'),
      showCancel: false,
    })
    return false
  }

  const ok = await confirmModal(
    t('account.logoutConfirmTitle'), t('account.logoutConfirmDesktop'), t('account.logoutBtn'))
  if (!ok) return false

  let result = null
  try {
    result = await disconnectAccount()
  } catch (e) {
    uni.showToast({ title: (e && e.message) || t('account.logoutFailed'), icon: 'none' })
    return false
  }

  // 与登录弹层成功同一个广播出口：账户态、广场付费项按钮、权益一起刷新
  broadcastAccountChanged({
    connected: false,
    aiProviderFallback: (result && result.aiProviderFallback) || '',
  })
  uni.showToast({ title: t('account.loginDialog.signedOutToast'), icon: 'none' })
  return true
}
