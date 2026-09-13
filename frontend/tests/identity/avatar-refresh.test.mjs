// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 改完头像，界面要当场跟上（dev-board#603）。
//
// 这条链上有两个各自独立的断点，都钉在这里：
//   ① 广播/订阅：两个上传入口都要 emit `awd:identity-updated`，顶栏所在的工作台要订阅并退订；
//   ② 桌面外壳：官网头像响应带 `Cross-Origin-Resource-Policy: same-site`，
//      而主窗口是 file:// 页面，Chromium 直接在网络层拦掉（ERR_BLOCKED_BY_RESPONSE.NotSameSite），
//      于是 <image> 什么都不画、首字母分支又因为 avatarUrl 是真值而不渲染——
//      屏幕上只剩一颗纯色圆。外壳必须在第一次 load 之前把这个头放开。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

const personalSettings = read('../../src/components/userprofile/PersonalSettingsPanel.vue')
const adminPane = read('../../src/components/admin/AdminPane.vue')
const overview = read('../../src/pages/project-overview/project-overview.vue')
const desktopMain = read('../../../desktop/main/main.js')

test('两个头像入口都广播 awd:identity-updated（侧栏用户卡那条以前是哑的）', () => {
  // 个人设置：传头像 / 删头像 / 改昵称三处
  assert.ok(personalSettings.split("uni.$emit('awd:identity-updated')").length - 1 >= 3,
    '个人设置面板的三个写动作都要广播')
  // 侧栏用户卡也传头像，走的是同一份权威源，不广播就只有它自己跟上
  assert.match(adminPane, /uni\.\$emit\('awd:identity-updated'\)/,
    'AdminPane.triggerAvatarUpload 成功后必须广播，否则顶栏要等下次重开才跟上')
})

test('工作台订阅 awd:identity-updated，并且退订（页面栈多实例地雷）', () => {
  assert.match(overview, /uni\.\$on\('awd:identity-updated', this\._onIdentityUpdated\)/,
    '顶栏头像是工作台在画，它不订阅就没人刷新')
  assert.match(overview, /uni\.\$off\('awd:identity-updated', this\._onIdentityUpdated\)/,
    '本页经 navigateTo 反复进入，不按引用 $off 就每回来一次多一份订阅')
  // 处理函数要真去拉新的身份，光订阅不干活等于没订阅
  const handler = overview.match(/this\._onIdentityUpdated = \(\) => \{([\s\S]*?)\n    \}/)
  assert.ok(handler, '找不到 _onIdentityUpdated 的处理函数')
  assert.match(handler[1], /loadRealUserInfo\(\)/, '顶栏那份 currentUser 必须重拉')
})

test('桌面外壳放开官网头像的 CORP，且赶在第一次 load 之前挂上', () => {
  assert.match(desktopMain, /onHeadersReceived\(\{\s*urls:\s*\['\*:\/\/\*\/api\/avatar\/\*'\]\s*\}/,
    '拦截面收在 /api/avatar/ 这一条路径上，别把整个 session 的响应头都接管了')
  assert.match(desktopMain, /headers\['Cross-Origin-Resource-Policy'\] = \['cross-origin'\]/,
    '官网发的是 same-site，file:// 页面永远不可能同站，必须改写成 cross-origin')

  const hookAt = desktopMain.indexOf('attachAvatarCorpRelaxation(mainWindow.webContents.session)')
  const loadAt = desktopMain.search(/mainWindow\.loadURL\(DEV_SERVER_URL\)/)
  assert.ok(hookAt > 0 && loadAt > 0, '找不到挂载点或首次 load')
  assert.ok(hookAt < loadAt,
    '挂在 load 之后，首屏那次头像请求会漏在拦截器外面')
})
