// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 人机验证控件（桌面端）。
 *
 * 官网侧的实现在 `aiworkdeck_website/components/captcha/Captcha.tsx`，这里是同一套逻辑
 * 的无框架版——uni-app 页面用不了 React 组件，但两边的时序与坑必须一致，改一边要看另一边。
 *
 * ## 为什么桌面端非要有这个
 * 桌面端登录是 `桌面 → 本机 Java /api/account/login/send-code → AccountService → 官网`。
 * 官网启用人机验证后不带 token 就是 403，而官网**无法区分**「真桌面端的转发」与
 * 「攻击者直接 POST」——放过不带 token 的请求等于那条闸完全失效。所以桌面端必须真的带上。
 *
 * ## 两条与官网版一致的坑
 * - **阿里云的 `prefix`/`region` 要在脚本加载之前挂到全局 `AliyunCaptchaConfig`**，
 *   传进 `initAliyunCaptcha` 不生效，而且**不报错**（2026-08-18 浏览器实测）。
 * - **token 一次性**，每次取之前先 reset；不 reset 的话「重发验证码」会带上已核销的那枚。
 *
 * ## Turnstile 走官网托管页，不在本页 render
 * Turnstile 按域名放行 sitekey，打包版主窗口是 `file://`，直接 render 必报 110200。
 * 所以国际站嵌 `{官网}/captcha-embed`，控件在官网域名下跑、token 交回本页；
 * 消息过滤与状态机在 `captchaEmbedCore.js`（有 node 单测）。阿里云在 `file://` 下
 * 今天就是好的，那条分支不动。
 *
 * ## 桌面壳里托管页挂 <webview>，不挂 iframe（dev-board#863）
 * 主窗口 webPreferences.webSecurity=false。嵌在这种 WebContents 里的 Turnstile 挑战帧会被
 * Chromium 以「bad IPC message, reason 1」杀掉渲染进程，控件卡死（300030）、永远拿不到
 * token——表现就是「发送中」转 8 秒后报「请完成人机验证」，界面上什么都不出现
 * （2026-09-23 真桌面壳实测；同一页放进 web security 开着的 WebContents 6 秒出 token）。
 * webview 是独立的 guest WebContents，web security 默认开着；托管页往 window.parent 发的
 * 消息在 webview 里投给它自己，由 `desktop/preload/captcha-webview-preload.js` 截住转交。
 * 没有这条能力的宿主（Web 版、没带这条 IPC 的老壳）照旧挂 iframe——Web 版的父页是
 * 正常网页，iframe 本来就好。
 */

import { loadSiteLinks } from '@/utils/siteLinks.js'
import { getAppLanguage } from '@/utils/appLanguage.js'
import { host } from '@/services/host.js'
import {
  EMBED_WIDTH,
  EMBED_DEFAULT_HEIGHT,
  acceptBridgeMessage,
  acceptMessage,
  buildEmbedUrl,
  createEmbedController,
  originOf,
} from '@/utils/captchaEmbedCore.js'

const SCRIPTS = {
  aliyun: 'https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js',
}

const loading = new Map()
function loadScript(src) {
  if (loading.has(src)) return loading.get(src)
  const p = new Promise((resolve, reject) => {
    const el = document.createElement('script')
    el.src = src
    el.async = true
    el.onload = resolve
    el.onerror = () => reject(new Error('captcha script load failed: ' + src))
    document.head.appendChild(el)
  })
  loading.set(src, p)
  return p
}

// 当前挂着的托管页（iframe + message 监听）。一个页面同时只有一套：
// 解锁页切站会重新装配，旧的必须连同监听器一起拆掉。
let activeEmbed = null
// 装配代次：setupCaptcha 中途要 await 站点地址，两次装配交错时只让最后一次落地
let setupGen = 0

/** 拆掉当前托管页控件（iframe/webview 与它们的监听），在等的 getToken 回空串。可重复调用。 */
export function teardownCaptcha() {
  if (!activeEmbed) return
  const cur = activeEmbed
  activeEmbed = null
  cur.destroy()
}

function currentTheme() {
  try {
    return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light'
  } catch (e) {
    return 'light'
  }
}

// 与 desktop/preload/captcha-webview-preload.js 的 CHANNEL 必须一致
const BRIDGE_CHANNEL = 'awd-captcha'

/** 桌面壳给的 webview 消息桥配置 `{ preload }`；拿不到（Web 版 / 老壳 / IPC 出错）回 null。 */
async function webviewBridgeConfig() {
  try {
    const cap = host.captchaEmbed
    if (!cap || typeof cap.getConfig !== 'function') return null
    const cfg = await cap.getConfig()
    return cfg && typeof cfg.preload === 'string' && cfg.preload ? cfg : null
  } catch (e) {
    return null
  }
}

const EMBED_STYLE = `display:block;width:${EMBED_WIDTH}px;height:${EMBED_DEFAULT_HEIGHT}px;` +
  'max-width:100%;border:0;background:transparent;color-scheme:normal;'

function mountWebviewEmbed(el, src, expectedOrigin, preload) {
  const wv = document.createElement('webview')
  wv.setAttribute('preload', preload)
  // 页面拿不到 ipcRenderer（桥只在 preload 的隔离世界里）；不带 allowpopups，控件里的链接不弹窗
  wv.setAttribute('webpreferences', 'contextIsolation=yes,nodeIntegration=no,sandbox=yes')
  wv.className = 'awd-captcha-embed'
  // Electron 要求 webview 保持 flex 布局（内部 iframe 靠它撑满），行内摆放用 inline-flex
  wv.style.cssText = EMBED_STYLE.replace('display:block', 'display:inline-flex')
  let attached = false
  wv.addEventListener('dom-ready', () => { attached = true })

  const controller = createEmbedController({
    // guest 没起来之前 send 会抛；controller 只在收到 ready（guest 早已起来）之后才发
    post: (msg) => {
      if (!attached) return
      try { wv.send(BRIDGE_CHANNEL, msg) } catch (e) { /* 已拆 */ }
    },
    onSize: (h) => { wv.style.height = h + 'px' },
    onDisabled: () => { wv.style.display = 'none' },
  })
  wv.addEventListener('ipc-message', (e) => {
    if (!e || e.channel !== BRIDGE_CHANNEL) return
    const data = acceptBridgeMessage(e.args && e.args[0], { expectedOrigin })
    if (data) controller.handle(data)
  })
  // guest 渲染进程没了（被杀/崩溃）：在等的请求立刻回空串，不让按钮干等到超时
  wv.addEventListener('render-process-gone', () => controller.handle({ type: 'error', code: 'render-process-gone' }))
  wv.setAttribute('src', src)
  el.appendChild(wv)

  return {
    controller,
    destroy() {
      controller.destroy()
      if (wv.parentNode) wv.parentNode.removeChild(wv)
    },
  }
}

function mountIframeEmbed(el, src, expectedOrigin) {
  const iframe = document.createElement('iframe')
  iframe.src = src
  iframe.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-forms allow-popups')
  iframe.setAttribute('title', 'captcha')
  iframe.setAttribute('scrolling', 'no')
  iframe.className = 'awd-captcha-embed'
  iframe.style.cssText = EMBED_STYLE

  const controller = createEmbedController({
    // targetOrigin 钉死官网：框被跳走后，消息不会送到别人的页面里
    post: (msg) => {
      try { iframe.contentWindow && iframe.contentWindow.postMessage(msg, expectedOrigin) } catch (e) { /* 框已拆 */ }
    },
    onSize: (h) => { iframe.style.height = h + 'px' },
    onDisabled: () => { iframe.style.display = 'none' },
  })
  const onMessage = (event) => {
    const data = acceptMessage(event, { expectedSource: iframe.contentWindow, expectedOrigin })
    if (data) controller.handle(data)
  }
  window.addEventListener('message', onMessage)
  el.appendChild(iframe)

  return {
    controller,
    destroy() {
      window.removeEventListener('message', onMessage)
      controller.destroy()
      if (iframe.parentNode) iframe.parentNode.removeChild(iframe)
    },
  }
}

async function setupEmbedCaptcha(holderId, gen) {
  const [links, bridge] = await Promise.all([loadSiteLinks(), webviewBridgeConfig()])
  if (gen !== setupGen) return null
  const el = document.getElementById(holderId)
  if (!el) return null
  const baseUrl = (links && links.baseUrl) || ''
  const expectedOrigin = originOf(baseUrl)
  if (!expectedOrigin) return null

  const src = buildEmbedUrl(baseUrl, { lang: getAppLanguage(), theme: currentTheme() })
  const embed = bridge
    ? mountWebviewEmbed(el, src, expectedOrigin, bridge.preload)
    : mountIframeEmbed(el, src, expectedOrigin)
  activeEmbed = embed
  return { provider: 'turnstile', getToken: () => embed.controller.getToken() }
}

/**
 * 装配控件。重复调用会先拆掉上一次的托管页控件。
 *
 * @param {object} config 官网下发的公开配置（`GET /api/account/captcha-config`）
 * @param {string} holderId 页面上一个空 div 的 id，控件挂在里面
 * @returns {Promise<{getToken: () => Promise<string>, provider: string}>}
 *          未启用（`provider` 为空）时返回 null，调用方据此**跳过**验证码直接发码——
 *          与官网此刻确实不校验是同一个判断。
 */
export async function setupCaptcha(config, holderId) {
  const gen = ++setupGen
  teardownCaptcha()
  if (!config || !config.provider) return null

  if (config.provider === 'turnstile') return setupEmbedCaptcha(holderId, gen)

  // 阿里云：必须在 loadScript 之前设全局，脚本读的是加载那一刻的值
  window.AliyunCaptchaConfig = { region: 'cn', prefix: config.prefix }
  await loadScript(SCRIPTS.aliyun)
  if (!window.initAliyunCaptcha) return null

  let pending = null
  let instance = null
  window.initAliyunCaptcha({
    SceneId: config.sceneId,
    mode: 'popup',
    element: '#' + holderId,
    button: '#' + holderId + '-trigger',
    captchaVerifyCallback: async (captchaVerifyParam) => {
      if (pending) { pending(captchaVerifyParam); pending = null }
      // 这里返回 true 只是让弹窗关掉，**不等于放行**——真正的校验是官网服务端
      // 拿这个 param 再向阿里云核一次。
      return { captchaResult: true, bizResult: true }
    },
    onBizResultCallback: () => { /* 业务结果由发码流程处理 */ },
    // **必须存下实例**：校验参数是一次性的，下一次取之前要 refresh() 换一枚。
    // 丢掉实例就没法刷新，第二次滑完拿到的还是上一枚已用过的参数，
    // 服务端会判重复提交（2026-08-18 真机：第一次成功、之后每次都失败）。
    getInstance: (i) => { instance = i },
    slideStyle: { width: 320, height: 40 },
    language: 'cn',
    onError: (e) => console.warn('[captcha] 阿里云控件初始化失败:', e),
  })

  return {
    provider: 'aliyun',
    getToken: () => new Promise((resolve) => {
      pending = resolve
      // 一次性参数：每次取之前先刷新，否则拿到的是上一枚已核销的
      try { if (instance && instance.refresh) instance.refresh() } catch (e) { /* 未就绪时忽略 */ }
      const trigger = document.getElementById(holderId + '-trigger')
      if (!trigger) return resolve('')
      trigger.click()
      setTimeout(() => { if (pending === resolve) { pending = null; resolve('') } }, 120000)
    }),
  }
}
