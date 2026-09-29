// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 客户门户构建（dev-board#1050）的运行时收口。
 *
 * 门户是 `npm run build:client-portal` 打出的一份 H5（base `/client/`，由案件库 nginx 托管），
 * pages.json 用条件编译 CLIENT_PORTAL 只注册三页：门户 → 项目列表 → 工作台。代码里仍有几十处
 * 硬编码跳到登录页 / 启动页 / 日程 / 设置薄壳页的地方（4010 兜底、退出登录……），那些页面
 * 在门户包里根本不存在，跳过去就是空白页。这里在路由层统一改写：不在三页之内的目标一律回门户。
 * 这只是入口收口，**真闸在后端**：CLIENT 在案件库上文件树、git、尽调写端点一律被拒。
 *
 * 本文件不许 import：纯函数部分要能被 node --test 直接跑。
 */

export const PORTAL_ENTRY = '/pages/client-portal/client-portal'

const PORTAL_PAGES = new Set([
  'pages/client-portal/client-portal',
  'pages/project-list/project-list',
  'pages/project-overview/project-overview',
])

/** 当前是不是客户门户构建。构建脚本以 VITE_CLIENT_PORTAL=1 注入。 */
export function isClientPortalBuild() {
  try {
    return import.meta.env.VITE_CLIENT_PORTAL === '1'
  } catch (e) {
    return false
  }
}

/** 门户包里的跳转目标改写：三页之内原样放行，其余一律回门户页。 */
export function portalRoute(url) {
  const raw = String(url == null ? '' : url)
  const path = raw.split('?')[0].replace(/^\//, '')
  if (!path) return raw
  return PORTAL_PAGES.has(path) ? raw : PORTAL_ENTRY
}

/**
 * 在路由起来之前把 `#code=` 取走并从地址栏清掉（hash 路由会把它当成一个不存在的路由；
 * 留在地址栏里也会进浏览器历史）。取到的码放在 window.__AWD_PORTAL_CODE__ 给门户页预填。
 */
export function capturePortalCode(win, parseCode) {
  try {
    const w = win || (typeof window !== 'undefined' ? window : null)
    if (!w || !w.location) return ''
    const code = parseCode(w.location.hash)
    if (!code) return ''
    w.__AWD_PORTAL_CODE__ = code
    if (w.history && typeof w.history.replaceState === 'function') {
      w.history.replaceState(null, '', w.location.pathname + w.location.search)
    }
    return code
  } catch (e) {
    return ''
  }
}
