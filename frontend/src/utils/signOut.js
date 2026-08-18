// signOut.js — 「退出登录」的唯一实现。
//
// 桌面端此前根本没有登出入口：个人中心那个按钮写着 `v-if="!isDesktop"`，
// 只对浏览器端渲染；桌面端能找到的两个近亲各只做一半，而且都藏在设置页深处——
// 「系统设置 → 账户与用量 → 断开连接」只摘账户，「个人中心 → 设置 → 授权 →
// 解除授权」只清授权票据。想换个账号登的人（尤其是自测的人）两处都要跑一遍
// 才回得到解锁门。这里把两件事按正确的条件收成一个动作，入口摆到人找得到的地方。
//
// 桌面端的「登录态」是两层，必须分开看：
//   ① 账户连接——~/.aiworkdeck 下的 awdk_ Key 文件（权益/平台 AI/账户指纹挂它）
//   ② 授权票据——license 状态，mode 可能是 account（来自账户）也可能是 trial（试用码）
//
// **只有 mode=account 时才连授权一起清**。存量试用码解锁的机器清了就回不来了：
// 官方发布版把试用码这条解锁路关掉了（application-desktop.yml 的
// security.license.trial-code.enabled=false，PR#409），解锁门只认账户凭据——
// 一个还没注册账户的试用用户点一下「退出登录」就会被关在自己的数据外面。
// 那种机器上「退出登录」只摘账户；真想回到未解锁态是另一件事，
// 个人中心「设置 → 授权 → 解除授权」一直都在，语义也更准。
//
// 浏览器端（团队服务器）没有这两层，走原来的 clearSession + 回登录页。

import { disconnectAccount, deactivateLicense, getAccountStatus, getLicenseStatus } from '@/services/api.js'
import { clearSession } from '@/utils/auth.js'
import { isDesktopHost } from '@/services/host.js'
import { t } from '@/i18n'

const confirmModal = (title, content, confirmText) => new Promise((resolve) => uni.showModal({
  title,
  content,
  cancelText: t('common.cancel'),
  confirmText,
  success: (res) => resolve(!!res.confirm),
  fail: () => resolve(false),
}))

/**
 * 退出登录。自带确认弹窗，确认后落到解锁门 / 登录页。
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

  // 先摸清这台机器现在处在哪一档，弹窗才说得出接下来会发生什么。
  // 查询失败不当场报错：按「连着账户」处理，后面真调接口时才是可信的成败。
  let connected = true
  let accountMode = true
  try {
    const st = await getAccountStatus()
    connected = !!(st && st.connected)
  } catch (e) { /* 查不到就按连着处理 */ }
  try {
    const lic = await getLicenseStatus()
    accountMode = !lic || lic.mode !== 'trial'
  } catch (e) { /* 查不到就按 account 处理 */ }

  if (!connected && !accountMode) {
    // 试用码解锁、又没连过账户：没有任何「登录」可退。直说，并指路解除授权，
    // 而不是让按钮点下去什么都不发生。
    uni.showModal({
      title: t('account.logoutNothingTitle'),
      content: t('account.logoutNothingContent'),
      showCancel: false,
    })
    return false
  }

  const ok = await confirmModal(
    t('account.logoutConfirmTitle'),
    accountMode ? t('account.logoutConfirmDesktop') : t('account.logoutConfirmTrial'),
    t('account.logoutBtn'))
  if (!ok) return false

  try {
    if (connected) await disconnectAccount()
    // 授权本身来自试用码时不动它，理由见文件头
    if (accountMode) await deactivateLicense()
  } catch (e) {
    uni.showToast({ title: (e && e.message) || t('account.logoutFailed'), icon: 'none' })
    return false
  }

  try { clearSession() } catch (e) { /* ignore */ }
  // 回启动页重跑分流，而不是自己跳解锁门：解锁与否的判据只有 launch 一处，
  // 这里另跳一次等于把那套分流抄了第二份，早晚漂。
  uni.reLaunch({ url: '/pages/launch/launch' })
  return true
}
