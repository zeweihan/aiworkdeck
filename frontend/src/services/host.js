// host.js — 宿主能力层：前端与「壳」之间的唯一接缝。
//
// 前端此前直接读 window.checkbaDesktop（Electron preload 经 contextBridge 暴露），
// 93 处散落在 18 个文件里。那等于把 Electron 焊进了业务代码：换壳（鸿蒙 Electron /
// Tauri / WebView2）或跑纯浏览器（Web 服务器版，docs/HARMONYOS_PLAN.md Phase A）
// 都得再挨个找一遍。本模块把这层收口——业务代码一律 import { host }，壳的差异
// 只在这里表达。
//
// 语义保持不变（这是刻意的，迁移必须是机械的）：
// - 桌面态 host 就是 window.checkbaDesktop 的门面，字段与行为逐一透传；
// - Web 态只提供「浏览器里真能实现」的能力，其余字段缺席（undefined）。
//   调用点原有的 `if (host.browser && ...)` 守卫因此原样成立，不需要改判断。
//
// 唯一被规范化的是 zetaoffice.getEditor：它现在返回带 kind 的描述符，
// 宿主据此挂 <webview>（桌面）或 <iframe>（Web）。见 LibreOfficeEditor.vue。

/** 是否运行在桌面壳里。用于「仅桌面版可用」类的能力分支。 */
export function isDesktopHost() {
  return !!nativeHost()
}

function nativeHost() {
  return (typeof window !== 'undefined' && window.checkbaDesktop) || null
}

// Web 态编辑器资源路径。与 deploy/web/nginx.conf.example 的部署布局一一对应
// （站点 root 下 zetaoffice/ 放 build:zetaoffice 产物，其中 lowa/ 是引擎）。
// 全站 COOP/COEP 由 nginx 下发——没有跨源隔离，LOWA 的 SharedArrayBuffer 用不了，
// 引擎会在 bootZetaOffice 的校验里明确报错，不会静默降级。
const WEB_EDITOR_BASE = '/zetaoffice/'

function webEditorDescriptor() {
  const q = new URLSearchParams()
  q.set('lowa', WEB_EDITOR_BASE + 'lowa/')
  return { kind: 'iframe', url: WEB_EDITOR_BASE + 'editor.html?' + q.toString() }
}

// Web 态编辑器是否真的部署了。桌面版引擎随包走、必然存在，Web 版却取决于运维
// 有没有把 frontend/dist/zetaoffice 放到站点下（dev server 上就没有）。
// 不探测的话，宿主会照常挂 iframe，然后停在「正在启动文档引擎」直到超时——
// 用户看到的是卡死而不是「这里没有编辑器」。探一次、缓存结果。
let webEditorProbe = null
function probeWebEditor() {
  if (!webEditorProbe) {
    webEditorProbe = fetch(webEditorDescriptor().url, { method: 'HEAD' })
      .then((r) => r.ok)
      .catch(() => false)
  }
  return webEditorProbe
}

// Web 态的 draw.io：与 zetaoffice 同一套「探一次、缓存结果」的思路。资源是否部署
// 取决于运维有没有把 frontend/dist/drawio 放到站点下（dev server 上就没有）。
// 探不到就返回 available:false，调用点退回「下载后用其他程序打开」——挂一个永远
// 转圈的 iframe 比说清楚"这里没有编辑器"糟糕得多。
const WEB_DRAWIO_BASE = '/drawio/'
const WEB_DRAWIO_QUERY = 'embed=1&proto=json&stealth=1&spin=1&lang=zh&ui=min&noSaveBtn=0&saveAndExit=0'

let webDrawioProbe = null
function probeWebDrawio() {
  if (!webDrawioProbe) {
    webDrawioProbe = fetch(WEB_DRAWIO_BASE + 'index.html', { method: 'HEAD' })
      .then((r) => r.ok)
      .catch(() => false)
  }
  return webDrawioProbe
}

// Web 态能力表：只有编辑器两项。浏览器面板/截图/剪贴板/组件下载/自动更新/
// 本地文件对话框等都依赖 Electron 主进程，浏览器里没有对等实现——缺席比假实现
// 诚实，调用点的守卫会把对应入口收起来。
const WEB_CAPABILITIES = {
  zetaoffice: {
    isAvailable: probeWebEditor,
    getEditor: async () => webEditorDescriptor(),
  },
  drawio: {
    getEditor: async () => {
      if (!(await probeWebDrawio())) return { available: false }
      return {
        available: true,
        kind: 'iframe',
        origin: window.location.origin,
        url: WEB_DRAWIO_BASE + 'index.html?' + WEB_DRAWIO_QUERY,
      }
    },
  },
}

// 桌面态的 zetaoffice：把 getEditor 的返回值补上 kind，其余透传。
// 按 native 对象缓存，避免每次属性访问都新建。
let zetaCache = null
function desktopZetaoffice(native) {
  if (!zetaCache || zetaCache.native !== native) {
    zetaCache = {
      native,
      api: {
        isAvailable: async () => true, // 引擎随包分发，必然在
        getEditor: async () => {
          const info = await native.zetaoffice.getEditor() // { url, preload, partition }
          return { kind: 'webview', ...info }
        },
      },
    }
  }
  return zetaCache.api
}

function resolve(prop) {
  const native = nativeHost()
  if (!native) return WEB_CAPABILITIES[prop]
  // zetaoffice 要条件包装：宿主未必带这一项（app-e2e 给浏览器目标注入的最小桩
  // 只有 shell.openExternal）。无条件包一层的话，调用点的 `typeof getEditor ===
  // 'function'` 守卫会通过，然后在 native.zetaoffice.getEditor() 上抛 TypeError——
  // 把原本优雅的「当前环境不支持文档编辑」变成崩溃。
  if (prop === 'zetaoffice') return native.zetaoffice ? desktopZetaoffice(native) : undefined
  return native[prop]
}

// 惰性解析（而非模块加载时取一次快照）：与被替换掉的 `window.checkbaDesktop.X`
// 逐次读取语义完全一致。preload 注入、测试桩注入的时机差异因此不影响任何调用点。
export const host = new Proxy({}, {
  get: (_t, prop) => resolve(prop),
  has: (_t, prop) => resolve(prop) !== undefined,
})
