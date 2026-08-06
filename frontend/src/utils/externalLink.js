import { host } from '@/services/host.js'

// 打开外部链接（官网、GitHub 等站外页面）的统一出口。
// - 桌面端（Electron）：走系统浏览器，host.shell.openExternal（preload 暴露，
//   主进程 checkba:shell-open-external 处理）。注意不能依赖 window.open 降级：
//   主进程 setWindowOpenHandler 会把 http(s) 转成 checkba:browser-open-new-tab 事件，
//   而该事件只有 project-overview 页消费，解锁页等场景会静默失效。
// - 浏览器端：host.shell 缺席，走 window.open 新标签页。
export function openExternalUrl(url) {
  if (!url || !/^https?:\/\//i.test(url)) return
  try {
    const shell = host.shell
    if (shell && typeof shell.openExternal === 'function') {
      shell.openExternal(url)
      return
    }
    window.open(url, '_blank')
  } catch (e) {
    console.warn('打开外部链接失败:', e)
  }
}
