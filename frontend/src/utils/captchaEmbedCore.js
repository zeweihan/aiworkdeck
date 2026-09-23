// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 托管人机验证页（官网 `/captcha-embed`）的父页侧纯逻辑。零依赖，node 可直接导入测试
 * （`tests/captcha/captcha-embed.test.mjs`）；DOM 接线在 `utils/captcha.js`。
 *
 * ## 为什么 Turnstile 要走托管页
 * Cloudflare Turnstile 按域名放行 sitekey，而打包版桌面端主窗口是 `file://` 页面，
 * 直接 `turnstile.render` 必报 110200（2026-09-23 用真实 sitekey 实测）。官网提供一个
 * 只渲染控件、允许被任意源 iframe 嵌入的页面，控件在官网域名下跑，token 经 postMessage 交回来。
 *
 * ## postMessage 契约（所有消息都带 `source: 'awd-captcha'`）
 * 页面 → 父：`ready{provider}` / `token{token}`（空串 = 未通过）/ `error{code}` /
 *            `size{height}` / `disabled`（本站没启用人机验证）
 * 父 → 页面：`get-token`（页面取一枚 token 回 `token`）/ `reset`
 */

export const MESSAGE_SOURCE = 'awd-captcha'
export const EMBED_WIDTH = 300
export const EMBED_DEFAULT_HEIGHT = 65
const EMBED_MAX_HEIGHT = 600

/** 托管页地址。lang 只有 zh/en 两档，theme 只有 light/dark 两档，其余一律回落。 */
export function buildEmbedUrl(baseUrl, { lang, theme } = {}) {
  const base = String(baseUrl || '').replace(/\/+$/, '')
  const l = String(lang || '').toLowerCase().startsWith('en') ? 'en' : 'zh'
  const t = theme === 'dark' ? 'dark' : 'light'
  return `${base}/captcha-embed?lang=${l}&theme=${t}`
}

/** 取 URL 的 origin；解析不了回空串（空串不会等于任何真实 event.origin，等于全拒）。 */
export function originOf(url) {
  try {
    return new URL(String(url)).origin
  } catch (e) {
    return ''
  }
}

/**
 * 过滤一条 message 事件，合格时返回 data，否则 null。
 *
 * 两道判据**缺一不可**，互相补的是对方看不到的那一半：
 * - `event.source === expectedSource`（我们那个 iframe 的 contentWindow）：
 *   父页是 `file://`，它自己的 origin 是 `null`，同一窗口里别的 frame、打开者、
 *   扩展注入的脚本都能往这里 post；只认这一个窗口对象才知道消息来自我们挂的那个框。
 * - `event.origin === expectedOrigin`（官网站点的 origin）：
 *   窗口对象在导航后**不变**——框里的页面若被跳走（控件里的链接、重定向、被劫持），
 *   `source` 仍然相等，只有 origin 能说明此刻框里跑的还是官网。
 */
export function acceptMessage(event, { expectedSource, expectedOrigin }) {
  if (!event || !expectedSource || !expectedOrigin) return null
  if (event.source !== expectedSource) return null
  if (event.origin !== expectedOrigin) return null
  const data = event.data
  if (!data || typeof data !== 'object') return null
  if (data.source !== MESSAGE_SOURCE || typeof data.type !== 'string') return null
  return data
}

/** size 消息的高度：非有限数/非正数回 null（不改尺寸），过大截断。 */
export function normalizeHeight(h) {
  const n = Number(h)
  if (!Number.isFinite(n) || n <= 0) return null
  return Math.min(Math.round(n), EMBED_MAX_HEIGHT)
}

/**
 * 父页侧状态机。
 *
 * - `ready` 之前调 `getToken` 排队，ready 到了再发请求；等 ready 另有上限（`readyTimeoutMs`），
 *   托管页加载不出来时不让「获取验证码」按钮永远转圈。
 * - 每次取 token 先发 `reset` 再发 `get-token`：token 一次性，不 reset 的话重发会带上已核销的那枚。
 * - 请求发出后 `timeoutMs`（默认 8 秒）内没拿到非空 token 回空串。
 * - 等待期间收到的**空串 token 不结束等待**：reset 之后控件可能先回一枚空串（重置回调），
 *   把它当「未通过」会让每次取 token 都立刻失败；真失败由 `error` 消息或超时收口。
 * - 没人在等时自动产出的 token 不留存——下一次 getToken 反正先 reset，它已作废。
 * - 并发调用共用同一个在途请求。
 *
 * @param {object} opts
 * @param {(msg: object) => void} opts.post 发给托管页（调用方负责 targetOrigin）
 * @param {(height: number) => void} [opts.onSize]
 * @param {() => void} [opts.onDisabled]
 * @param {number} [opts.timeoutMs=8000]
 * @param {number} [opts.readyTimeoutMs=15000]
 * @param {Function} [opts.setTimer=setTimeout]
 * @param {Function} [opts.clearTimer=clearTimeout]
 */
export function createEmbedController(opts) {
  const {
    post,
    onSize = () => {},
    onDisabled = () => {},
    timeoutMs = 8000,
    readyTimeoutMs = 15000,
    setTimer = (fn, ms) => setTimeout(fn, ms),
    clearTimer = (id) => clearTimeout(id),
  } = opts || {}

  const state = { ready: false, disabled: false, destroyed: false, provider: '' }
  let inflight = null // { promise, resolve, timer, requested }

  function settle(token) {
    if (!inflight) return
    const cur = inflight
    inflight = null
    if (cur.timer != null) clearTimer(cur.timer)
    cur.resolve(token)
  }

  function sendRequest() {
    if (!inflight || inflight.requested) return
    inflight.requested = true
    if (inflight.timer != null) clearTimer(inflight.timer)
    inflight.timer = setTimer(() => settle(''), timeoutMs)
    post({ source: MESSAGE_SOURCE, type: 'reset' })
    post({ source: MESSAGE_SOURCE, type: 'get-token' })
  }

  function handle(data) {
    if (state.destroyed || !data) return
    switch (data.type) {
      case 'ready':
        state.ready = true
        state.provider = typeof data.provider === 'string' ? data.provider : ''
        sendRequest()
        break
      case 'token':
        if (inflight && inflight.requested && typeof data.token === 'string' && data.token) {
          settle(data.token)
        }
        break
      case 'error':
        if (inflight && inflight.requested) settle('')
        break
      case 'size': {
        const h = normalizeHeight(data.height)
        if (h != null) onSize(h)
        break
      }
      case 'disabled':
        state.disabled = true
        onDisabled()
        settle('')
        break
      default:
        break
    }
  }

  function getToken() {
    if (state.destroyed || state.disabled) return Promise.resolve('')
    if (inflight) return inflight.promise
    let resolve
    const promise = new Promise((r) => { resolve = r })
    inflight = { promise, resolve, timer: null, requested: false }
    if (state.ready) {
      sendRequest()
    } else {
      inflight.timer = setTimer(() => settle(''), readyTimeoutMs)
    }
    return promise
  }

  function destroy() {
    state.destroyed = true
    settle('')
  }

  return { handle, getToken, destroy, state }
}
