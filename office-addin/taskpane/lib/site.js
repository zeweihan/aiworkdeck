/**
 * 官网站点映射与外链打开（dev-board#198 充值入口）。
 *
 * 为什么充值不做进任务窗格：插件云后端是多租户，刻意不保存用户的 awdk_ Key
 * （明文不落库，PlatformAiKeyService 只存派生的 runtime key），而发起支付要
 * 以用户身份调官网收银台；`/api/account/recharge` 那条链是机器级（桌面单机版
 * 专用，云端普通租户被 MachineAccountGuard 拦下）。所以付费永远发生在官网
 * 账户页，插件端负责把入口放到点上。
 *
 * 站点映射走白名单而不是「去掉 addin. 前缀」的通用规则：私有部署
 * （addin.yourfirm.com）推导出来的主域上并没有充值页，错链接比没链接更糟。
 */
import { normalizeBaseUrl } from './settings.js'

const OFFICIAL_SITES = {
  'addin.aiworkdeck.com': 'https://aiworkdeck.com',
  'addin.workdeck.ai': 'https://workdeck.ai'
}

/** 官网账户页（钱包+充值在这一页；/account 自动按 locale 跳转）。非官方后端回空串。 */
export function rechargeUrl(serverUrl) {
  const base = normalizeBaseUrl(serverUrl)
  if (!base) return ''
  try {
    const host = new URL(base).hostname
    const site = OFFICIAL_SITES[host]
    return site ? site + '/account' : ''
  } catch (e) {
    return ''
  }
}

/**
 * 在系统浏览器打开外链。Office 任务窗格里 window.open 在部分宿主（Mac Word 的
 * WKWebView）会被吞，官方姿势是 Office.context.ui.openBrowserWindow；
 * 老宿主没有该 API 时回退 window.open。
 */
export function openExternal(url) {
  if (!url) return
  try {
    if (typeof Office !== 'undefined' && Office.context && Office.context.ui
        && typeof Office.context.ui.openBrowserWindow === 'function') {
      Office.context.ui.openBrowserWindow(url)
      return
    }
  } catch (e) {
    // 落到 window.open
  }
  try {
    window.open(url, '_blank', 'noopener')
  } catch (e) {
    // 打不开就算了：按钮场景没有更好的兜底
  }
}
