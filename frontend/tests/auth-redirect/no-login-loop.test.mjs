// 「未登录 → 跳登录页」这条链在浏览器部署里曾自激成无限刷新（addin.aiworkdeck.com 实测：
// 8 秒 100 次整页导航、196 次 API 请求）。环路是：
//   任一需要会话的请求回 4010 → request() reLaunch 登录页
//   → App.vue 的导航拦截器给每次跳转补一条 ui.nav 埋点
//   → /api/telemetry/event 同样需要会话 → 又是 4010 → 又跳一次 …
// 两道闸各堵一头，缺一头就还能转起来；services/api.js 与 utils/telemetryClient.js
// 引用了 uni.* 与 @/ 别名、node 无法 import，只能做源码级契约断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const API = readFileSync(new URL('../../src/services/api.js', import.meta.url), 'utf8')
const TELEMETRY = readFileSync(new URL('../../src/utils/telemetryClient.js', import.meta.url), 'utf8')
const APP = readFileSync(new URL('../../src/App.vue', import.meta.url), 'utf8')

test('闸一：浏览器态未登录不发埋点（埋点本身需要会话，会回 4010）', () => {
  assert.match(TELEMETRY, /isDesktopHost/, 'track 要能识别桌面 local-mode')
  assert.match(TELEMETRY, /getSessionId/, 'track 要能识别未登录')
  assert.match(TELEMETRY, /if\s*\(!isDesktopHost\(\)\s*&&\s*!getSessionId\(\)\)\s*return/,
    '未登录且非桌面时必须直接 return，不许发请求')
})

test('闸二：已经在登录页时 4010 不再 reLaunch 登录页', () => {
  assert.match(API, /function isOnLoginPage\(/, '需要「当前是否已在登录页」的判定')
  assert.match(API, /getCurrentPages/, '判定要读页面栈而不是猜')
  // 跳登录页只许出现在 isOnLoginPage() 为假的那条分支里
  assert.match(API,
    /else if \(isOnLoginPage\(\)\) \{[\s\S]{0,600}?\} else \{[\s\S]{0,300}?uni\.reLaunch\(\{[\s\S]{0,80}?'\/pages\/login\/login'/,
    'reLaunch 登录页必须落在 isOnLoginPage 为假的分支')
})

test('埋点仍挂在导航拦截器上（环路的另一半，改动时要一并回看本文件）', () => {
  assert.match(APP, /track\('ui\.nav'/, 'ui.nav 埋点仍由导航拦截器发出')
})
