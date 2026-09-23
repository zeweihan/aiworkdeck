// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 国际站人机验证托管页的 webview 消息桥（dev-board#863）。
//
// 为什么有这个桥：主窗口 webSecurity=false，嵌在里面的 Turnstile 挑战帧会被 Chromium
// 以「bad IPC message, reason 1」杀掉，控件永远不出 token；托管页因此改挂 <webview>。
// 托管页往 window.parent 发消息——在 webview 里它是顶层页，发给的是它自己——
// preload 在同一个 window 上截住转给宿主，宿主发来的 reset/get-token 再投回去。
//
// 本文件守三件事：只转页面投给自己的、协议内的、官网 origin 的消息（Cloudflare 子框架的
// 消息、别的 origin、别的类型都不转）；宿主发来的只放行 reset/get-token；
// 投回页面时 source 恰好满足托管页的 `e.source === window.parent`。
const test = require('node:test')
const assert = require('node:assert')

const sentToHost = []
let hostListener = null
const electronId = require.resolve('electron')
require.cache[electronId] = {
  id: electronId,
  filename: electronId,
  loaded: true,
  exports: {
    ipcRenderer: {
      sendToHost: (channel, payload) => sentToHost.push({ channel, payload }),
      on: (channel, fn) => { if (channel === 'awd-captcha') hostListener = fn },
    },
  },
}

// 假 window：postMessage 同步派发给所有 message 监听，source 是 window 本身
const listeners = []
const posted = []
const fakeWindow = {
  location: { origin: 'https://www.workdeck.ai' },
  addEventListener: (type, fn) => { if (type === 'message') listeners.push(fn) },
  postMessage: (data, targetOrigin) => {
    posted.push({ data, targetOrigin })
    for (const fn of listeners) fn({ source: fakeWindow, origin: fakeWindow.location.origin, data })
  },
}
global.window = fakeWindow
require('../preload/captcha-webview-preload.js')

const dispatch = (ev) => { for (const fn of listeners) fn(ev) }
const msg = (type, rest = {}) => ({ source: 'awd-captcha', type, ...rest })

test('页面投给自己的协议消息转给宿主，带上 guest 此刻的 origin', () => {
  sentToHost.length = 0
  for (const m of [msg('ready', { provider: 'turnstile' }), msg('token', { token: 'tok' }), msg('error', { code: '300030' }), msg('size', { height: 140 }), msg('disabled')]) {
    dispatch({ source: fakeWindow, origin: 'https://www.workdeck.ai', data: m })
  }
  assert.deepStrictEqual(sentToHost.map((s) => s.channel), Array(5).fill('awd-captcha'))
  assert.deepStrictEqual(sentToHost.map((s) => s.payload.data.type), ['ready', 'token', 'error', 'size', 'disabled'])
  assert.ok(sentToHost.every((s) => s.payload.origin === 'https://www.workdeck.ai'))
  assert.strictEqual(sentToHost[1].payload.data.token, 'tok')
})

test('不转：Cloudflare 子框架的消息、别的 origin、协议外字段、父→页方向的类型', () => {
  sentToHost.length = 0
  const childFrame = {}
  dispatch({ source: childFrame, origin: 'https://challenges.cloudflare.com', data: msg('token', { token: 'x' }) })
  dispatch({ source: fakeWindow, origin: 'https://evil.example', data: msg('token', { token: 'x' }) })
  dispatch({ source: fakeWindow, origin: 'https://www.workdeck.ai', data: { source: 'other', type: 'token', token: 'x' } })
  dispatch({ source: fakeWindow, origin: 'https://www.workdeck.ai', data: 'awd-captcha' })
  dispatch({ source: fakeWindow, origin: 'https://www.workdeck.ai', data: msg('get-token') })
  dispatch({ source: fakeWindow, origin: 'https://www.workdeck.ai', data: msg('reset') })
  assert.deepStrictEqual(sentToHost, [])
})

test('宿主 → 页面：只放行 reset / get-token，投回自己的 window、targetOrigin 钉本站，且不回环', () => {
  sentToHost.length = 0
  posted.length = 0
  assert.ok(hostListener, 'preload 必须监听宿主频道')
  hostListener({}, msg('reset'))
  hostListener({}, msg('get-token'))
  hostListener({}, msg('token', { token: 'forged' }))
  hostListener({}, { type: 'get-token' })
  hostListener({}, null)
  assert.deepStrictEqual(posted.map((p) => p.data.type), ['reset', 'get-token'])
  assert.ok(posted.every((p) => p.targetOrigin === 'https://www.workdeck.ai' && p.data.source === 'awd-captcha'))
  // 投回去的 reset/get-token 自己的监听也会看到，但不许再转回宿主
  assert.deepStrictEqual(sentToHost, [])
})
