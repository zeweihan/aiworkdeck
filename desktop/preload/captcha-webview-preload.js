// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// captcha-webview-preload.js —— 国际站人机验证托管页（官网 /captcha-embed）跑在
// <webview> 里时的消息桥（dev-board#863）。
//
// 为什么不是 iframe：主窗口 webPreferences.webSecurity=false，这种 WebContents 里嵌的
// Cloudflare Turnstile 挑战帧会被 Chromium 以「bad IPC message, reason 1」杀掉渲染进程，
// 控件卡死（错误码 300030），永远拿不到 token（2026-09-23 真桌面壳实测；同一页面放进
// webSecurity 开着的 WebContents，6 秒出 token）。<webview> 是独立的 guest WebContents，
// web security 默认开着，所以托管页改挂在这里。
//
// 托管页的契约不变：它往 `window.parent` postMessage。在 webview 里它是顶层页，
// window.parent 就是它自己，所以消息投到它自己的 window 上——这里在同一个 window 上
// 截住，经 sendToHost 交给宿主；宿主发来的 reset / get-token 再 postMessage 回这个
// window（托管页只认 `e.source === window.parent`，在顶层页上恰好成立）。
//
// contextIsolation 开着，页面拿不到 ipcRenderer：只有这里能往宿主发消息，
// 被跳走的页面也伪造不了（宿主另按 origin 再判一次）。

const { ipcRenderer } = require('electron')

const CHANNEL = 'awd-captcha'
const SOURCE = 'awd-captcha'
// 页面 → 父：只转这几种；父 → 页面：只收这两种（见 frontend/src/utils/captchaEmbedCore.js）
const TO_HOST = new Set(['ready', 'token', 'error', 'size', 'disabled'])
const TO_PAGE = new Set(['reset', 'get-token'])

window.addEventListener('message', (e) => {
  // 只认页面投给自己的：Cloudflare 子框架也往这个 window 发消息，source 是它们自己
  if (e.source !== window) return
  if (e.origin !== window.location.origin) return
  const d = e.data
  if (!d || typeof d !== 'object' || d.source !== SOURCE || !TO_HOST.has(d.type)) return
  let data
  try { data = JSON.parse(JSON.stringify(d)) } catch (err) { return }
  ipcRenderer.sendToHost(CHANNEL, { origin: window.location.origin, data })
})

ipcRenderer.on(CHANNEL, (_evt, msg) => {
  if (!msg || typeof msg !== 'object' || msg.source !== SOURCE || !TO_PAGE.has(msg.type)) return
  window.postMessage({ source: SOURCE, type: msg.type }, window.location.origin)
})
