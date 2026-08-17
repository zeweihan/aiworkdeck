// globalBack.js — 全局返回键。
//
// 为什么要有这个东西：设置页（admin）历史上根本没有返回入口，唯一的出口
// 「返回个人中心」还是 navigateTo，往页面栈里再压一页；个人中心的「返回项目列表」
// 又是 navigateBack，退回的正是 admin。两页互相弹，从工作台点进设置的人再也回不到
// 项目里去。单页补一个按钮只能治一处，下一个新页还会漏，所以做成全局的。
//
// 挂载方式与 feedbackWidget / recordingIndicator / windowChrome 的拖拽条一致：
// <body> 下的单例，页面树之外，天然免疫 navigateTo 页面栈多实例那条地雷。
// 落点是各页顶部那条 38px 拖拽条所在的空白带——那里本来就没有可点的东西，
// 而且正好是 macOS/Finder 里返回键该在的位置。
//
// 可见性只有一条判据：**页面栈深度 > 1**。栈里只有一页时「返回」没有语义
// （启动分流链一律 reLaunch，深度恒为 1，所以启动页/解锁/向导上不会冒出来）。
// 自带左上角导航的两页走豁免名单，否则两个返回键叠在一起。

import { t } from '@/i18n'

const EL_ID = 'awd-global-back'

/** 自带左上角返回/导航的页面：再浮一个会跟人家叠在一起。 */
const SELF_NAV_ROUTES = new Set([
  // 工作台顶栏（z-index 200）本来就盖住这条带子，且它的出口是顶栏切换器
  'pages/project-overview/project-overview',
  // 项目概览顶栏第一个元素就是「返回列表」，位置完全重合
  'pages/project-home/project-home',
])

let el = null

function stack() {
  try {
    return typeof getCurrentPages === 'function' ? getCurrentPages() : []
  } catch (e) {
    return []
  }
}

function shouldShow() {
  const pages = stack()
  if (pages.length <= 1) return false
  const top = pages[pages.length - 1]
  const route = (top && top.route) || ''
  return !SELF_NAV_ROUTES.has(route)
}

function apply() {
  if (!el) return
  el.style.display = shouldShow() ? 'inline-flex' : 'none'
}

/**
 * 重算可见性。
 *
 * 导航是异步的，而且**页面栈的更新时机不止一个**：拦截器的 invoke 跑在跳转之前，
 * complete 之后 uni 还要再摘一次栈——navigateBack 尤其明显（实测：从设置页退回
 * 工作台时，只在下一帧读，读到的仍是包含设置页的旧栈，于是返回键留在了工作台上，
 * 而工作台恰恰在豁免名单里）。所以补算几次，不赌某一个时机。
 * 这是个纯展示控件，晚几百毫秒纠正一次没有副作用；漏纠正才是坏的。
 */
export function refreshGlobalBack() {
  if (!el) return
  requestAnimationFrame(apply)
  setTimeout(apply, 150)
  setTimeout(apply, 450)
}

function onTap() {
  // 深度已由 shouldShow 保证 > 1
  uni.navigateBack({ delta: 1 })
}

/** App.vue onLaunch 调一次。 */
export function mountGlobalBack() {
  // #ifdef H5
  if (el || typeof document === 'undefined') return
  if (document.getElementById(EL_ID)) return
  try {
    el = document.createElement('div')
    el.id = EL_ID
    el.className = 'awd-global-back'
    el.setAttribute('role', 'button')
    el.setAttribute('tabindex', '0')
    el.title = t('shell.back')
    // 原生 SVG + 文案：这个元素不在任何 Vue app 里，没有 uni 组件可用
    el.innerHTML =
      '<svg viewBox="0 0 24 24" width="14" height="14" aria-hidden="true">' +
      '<path d="M15 6l-6 6 6 6" fill="none" stroke="currentColor" stroke-width="1.8"' +
      ' stroke-linecap="round" stroke-linejoin="round" /></svg>' +
      '<span></span>'
    el.querySelector('span').textContent = t('shell.back')
    el.style.display = 'none'
    el.addEventListener('click', onTap)
    el.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' || e.key === ' ') onTap()
    })
    document.body.appendChild(el)

    // 浏览器/壳的前进后退不走 uni 的路由 API，单独接一条；
    // hashchange 兜住任何绕开 uni 路由 API 的地址栏跳转（e2e 与深链常这么走）
    window.addEventListener('popstate', refreshGlobalBack)
    window.addEventListener('hashchange', refreshGlobalBack)
    refreshGlobalBack()
  } catch (e) {
    // 返回键挂不上不能影响应用本身
    console.warn('[globalBack] 挂载失败:', e)
    el = null
  }
  // #endif
}
