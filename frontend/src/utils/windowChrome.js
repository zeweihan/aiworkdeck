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

let mounted = false

function classes() {
  return typeof document !== 'undefined' ? document.documentElement.classList : null
}

/**
 * 页面级拖拽条：给那些没有自己顶栏的页面（登录、项目列表、设置…）一条可以
 * 拖动窗口的区域。工作台的 .project-header（z-index 200）会盖在它上面，
 * 所以工作台不受影响，那边由顶栏自己承担拖拽。
 *
 * z-index 取 1：高于页面常规内容（能拖得动），低于所有弹窗/浮层。
 * 高度与 mac 交通灯的占位一致——这条带子覆盖的正是各页面顶部的安全区，
 * 安全区里本来就不该有可点的东西，所以它不会吃掉任何点击。
 */
function mountDragStrip() {
  if (typeof document === 'undefined') return
  if (document.getElementById(STRIP_ID)) return
  const strip = document.createElement('div')
  strip.id = STRIP_ID
  strip.className = 'awd-window-drag-strip'
  document.body.appendChild(strip)
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
}
