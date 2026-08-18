// windowChrome.js — 无边框窗口的渲染层一侧。
//
// 桌面壳把系统标题栏去掉了（desktop/main/main.js 的 titleBarStyle: 'hidden'），
// 窗口控件直接浮在网页内容上：mac 是左上角三颗交通灯，Windows 是右上角
// 最小化/最大化/关闭。渲染层因此要做两件事——让出位置、提供可拖拽区域。
//
// 本模块只负责「告诉 CSS 现在是什么环境」和「补一条页面级拖拽条」，
// 具体让多少像素写在 App.vue 的全局样式里。设计见
// docs/superpowers/specs/2026-08-16-desktop-chrome-and-command-menu.md。
//
// App.vue onLaunch 调一次即可。挂载方式与 feedbackWidget / recordingIndicator
// 一致（body 级单例，页面树之外），天然避开 navigateTo 页面栈多实例的重复订阅。

import { host, isDesktopHost } from '@/services/host.js'

const STRIP_ID = 'awd-window-drag-strip'

/**
 * 自带顶栏的页面——也就是自己声明了 `-webkit-app-region: drag` 的那几页。
 * 在这些页面上，body 级拖拽条必须整条让开（display:none）。
 *
 * **这条名单不是排版偏好，是 Chromium 合成拖拽区的方式决定的**（2026-08-18 实测）：
 * 渲染层把所有带 app-region 的元素按布局树顺序交给壳，壳按顺序 union(drag) /
 * difference(no-drag) 叠出最终可拖区域——**后来的覆盖先前的**。拖拽条是
 * position:fixed，fixed 盒子在布局树里恒挂在 LayoutView 下、排在所有常规流内容
 * 之后，所以它那块 38px 全宽 drag **永远最后一个合成**，把它底下所有 no-drag
 * 抠洞整片盖回成可拖——顶栏里的按钮于是一个都点不动（v0.18.0 的现象：工作台
 * 顶栏「项目名切换器 + 右上角面板/截图/AI 键」整条死掉，鼠标事件根本进不了页面）。
 * 改 DOM 顺序没用（insertBefore 到 body 最前面实测同样死），只有让它不存在才行。
 *
 * 反过来说：**fixed 的 no-drag 元素排在拖拽条之后就能赢**——全局返回键
 * （.awd-global-back，同为 body 级 fixed、在拖拽条之后 append）实测一直是好的，
 * 别照着它推断顶栏里的按钮也没事。
 */
const OWN_TITLEBAR_ROUTES = new Set([
  // 工作台 .project-header（42px，自身就是标题栏）
  'pages/project-overview/project-overview',
  // 项目概览薄壳页 .home-topbar（第一个元素就是「返回列表」）
  'pages/project-home/project-home',
  // 登录页 .top-nav
  'pages/login/login',
])

/** 上面那三页各自的顶栏元素。判定以 DOM 为准，路由只是快路径——原因见 pageOwnsTitlebar。 */
const OWN_TITLEBAR_SELECTOR = '.project-header, .home-topbar, .top-nav'

let mounted = false

function classes() {
  return typeof document !== 'undefined' ? document.documentElement.classList : null
}

/**
 * 页面级拖拽条：给那些没有自己顶栏的页面（项目列表、设置、个人中心…）一条可以
 * 拖动窗口的区域。自带顶栏的几页由 OWN_TITLEBAR_ROUTES 让开，那边由顶栏自己承担拖拽。
 *
 * z-index 取 1：高于页面常规内容（能拖得动），低于所有弹窗/浮层。
 * 高度与 mac 交通灯的占位一致。
 *
 * **注意 z-index 不是这里的关键**：它只决定谁画在上面、谁接得到 DOM 事件，
 * 而窗口拖拽区是壳按 app-region 另算一套的——工作台顶栏 z-index 200 确实盖在
 * 这条带子上面（elementFromPoint 实测返回的是顶栏里的按钮），鼠标事件却仍然
 * 被 OS 当成拖窗口吃掉。判断「会不会挡住点击」只能看 app-region，别看 z-index。
 */
function mountDragStrip() {
  if (typeof document === 'undefined') return
  if (document.getElementById(STRIP_ID)) return
  const strip = document.createElement('div')
  strip.id = STRIP_ID
  strip.className = 'awd-window-drag-strip'
  document.body.appendChild(strip)
}

function currentRoute() {
  try {
    const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
    const top = pages[pages.length - 1]
    return (top && top.route) || ''
  } catch (e) {
    return ''
  }
}

/**
 * 这一屏上是不是有一条自带拖拽区的顶栏。
 *
 * **判定以 DOM 为准，路由名单只当快路径**：`getCurrentPages()` 会滞后——
 * hash 直跳（深链、刷新、e2e 里的 goto）时 uni 的路由还没切过来，此刻读到的是上一页，
 * 于是让位整整错开一次导航（实测：进工作台时拖拽条还开着、进设置页反倒关上了）。
 * DOM 是所见即所得的那一份真相：屏幕上真有那条顶栏就让开。
 *
 * 用 getClientRects 判可见：页面栈里被压住的那些 uni-page 是 display:none，
 * 元素还在 DOM 里但没有盒子——只查 querySelector 会把它们也算上，
 * 于是从工作台 navigateTo 进设置页之后，设置页的顶部再也拖不动窗口。
 */
function pageOwnsTitlebar() {
  if (OWN_TITLEBAR_ROUTES.has(currentRoute())) return true
  try {
    const nodes = document.querySelectorAll(OWN_TITLEBAR_SELECTOR)
    for (const el of nodes) if (el.getClientRects().length > 0) return true
  } catch (e) { /* 查不到就按「没有」处理，拖拽条照常在 */ }
  return false
}

function applyDragStrip() {
  if (typeof document === 'undefined') return
  const strip = document.getElementById(STRIP_ID)
  if (!strip) return
  // '' 而不是 'block'：让位取消后交还给 CSS（非桌面态那条规则是 display:none）
  strip.style.display = pageOwnsTitlebar() ? 'none' : ''
}

/**
 * 重算拖拽条的让位。补算几次而不赌某一个时机，理由同 globalBack.refreshGlobalBack：
 * 拦截器 complete 之后 uni 还要再摘一次页面栈，只读一帧会读到旧栈。
 * 这是纯展示/交互态切换，晚几百毫秒纠正一次没有副作用；漏纠正就是一整条顶栏点不动。
 */
export function refreshDragStrip() {
  if (typeof document === 'undefined') return
  requestAnimationFrame(applyDragStrip)
  // 重页面（工作台）从触发导航到真渲染出顶栏能拖到一秒开外，所以补算窗口拉到 1.6s。
  // 多算几次是纯粹的幂等赋值，代价可以忽略。
  for (const ms of [150, 450, 900, 1600]) setTimeout(applyDragStrip, ms)
}

/**
 * 初始化窗口外壳适配。非桌面态（浏览器版）整体空转——那边窗口边框是浏览器的事。
 */
export function initWindowChrome() {
  if (mounted) return
  mounted = true
  if (!isDesktopHost()) return

  const cl = classes()
  if (!cl) return
  cl.add('is-desktop')

  // 平台：preload 直读 process.platform。老版本壳没有 chrome 命名空间，
  // 此时只加 is-desktop，让出位置的规则不生效——无边框也是新壳才有的行为，
  // 两者恰好同步，不会出现「让了位置但边框还在」的错配。
  const platform = (host.chrome && host.chrome.platform) || ''
  if (platform === 'darwin') cl.add('is-mac')
  else if (platform === 'win32') cl.add('is-win')
  else if (platform) cl.add('is-linux')

  // 全屏态：mac 全屏时交通灯隐藏，顶栏左侧那 88px 留白必须归零，
  // 否则全屏下项目名会莫名其妙缩进。主进程在进出全屏与首次加载完成时各推一次。
  if (host.chrome && host.chrome.onState) {
    host.chrome.onState((data) => {
      if (!data) return
      cl.toggle('is-fullscreen', !!data.fullscreen)
    })
  }

  mountDragStrip()
  refreshDragStrip()
  // 地址栏/壳的前进后退不走 uni 路由 API（深链与 e2e 常这么走），单独接一条；
  // 走 uni 路由 API 的那条由 App.vue 的路由拦截器 complete 调 refreshDragStrip。
  try {
    window.addEventListener('popstate', refreshDragStrip)
    window.addEventListener('hashchange', refreshDragStrip)
  } catch (e) { /* ignore */ }
}
