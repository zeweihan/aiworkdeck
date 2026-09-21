// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 登录页按站点给 tab + 人机验证控件的外观（dev-board#766）：
 *   node --test office-addin/taskpane/lib/loginTabs.test.js
 *
 * 分两段。前半段是纯逻辑（lib/loginTabs.js）：站点能力怎么解析、哪些 tab 该出现、
 * 当前形态不成立时怎么纠正。后半段是**接线**——纯函数全绿而组件没用上它们的话，
 * 用户看到的还是老样子，而且不报错：
 *   1. SettingsView 真的按 codeTabs 渲染、真的在配置到达时调 applyIdentity；
 *   2. 口令入口常驻，不随站点一起被隐掉（否则大陆站的存量口令用户没有任何入口）；
 *   3. **人机验证的两个节点仍在所有 v-if 分支之外**——领域文档记过这颗雷：控件在
 *      onMounted 时按 id 认下节点并一直持有，放进分支里用户切一次 tab 节点就被拆掉，
 *      滑块弹不出来也取不到 token，而且一声不响。这次改的正是 tab 结构，必须钉住；
 *   4. Turnstile 锁 theme:'light'（外壳恒浅色，缺省的 'auto' 在深色系统下是黑方块）
 *      且用 compact 尺寸（窗格窄）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import {
  accountIdentityOf,
  defaultMode,
  isModeAvailable,
  visibleCodeTabs
} from './loginTabs.js'
import { getAccountLoginCaptchaConfig } from './api.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (rel) => fs.readFileSync(path.join(here, rel), 'utf8')

// ==================== 纯逻辑 ====================

test('accountIdentityOf：认得 phone / email，大小写与空白都容忍', () => {
  assert.equal(accountIdentityOf({ accountIdentity: 'phone' }), 'phone')
  assert.equal(accountIdentityOf({ accountIdentity: 'email' }), 'email')
  assert.equal(accountIdentityOf({ accountIdentity: ' Email ' }), 'email')
})

test('accountIdentityOf：认不出的一律当未知（空串），不许猜', () => {
  // 旧云后端没有这个字段；配置拿不到时 api.js 给的是 { provider: null }
  assert.equal(accountIdentityOf({ provider: null }), '')
  assert.equal(accountIdentityOf(null), '')
  assert.equal(accountIdentityOf(undefined), '')
  assert.equal(accountIdentityOf({ accountIdentity: '' }), '')
  assert.equal(accountIdentityOf({ accountIdentity: 'username' }), '')
  assert.equal(accountIdentityOf({ accountIdentity: 123 }), '')
})

test('未知站点：两个验证码 tab 都给，默认停手机号——与改造前逐字一致', () => {
  assert.deepEqual(visibleCodeTabs(''), ['phone', 'email'])
  assert.equal(defaultMode(''), 'phone')
})

test('国际站：只给邮箱 tab，默认就落在邮箱上', () => {
  assert.deepEqual(visibleCodeTabs('email'), ['email'])
  assert.equal(defaultMode('email'), 'email')
})

test('大陆站：只给手机号 tab', () => {
  assert.deepEqual(visibleCodeTabs('phone'), ['phone'])
  assert.equal(defaultMode('phone'), 'phone')
})

test('isModeAvailable：不支持的验证码路要被纠正，口令永远成立', () => {
  assert.equal(isModeAvailable('phone', 'email'), false, '国际站不认手机号')
  assert.equal(isModeAvailable('email', 'phone'), false, '大陆站不认邮箱验证码')
  assert.equal(isModeAvailable('email', 'email'), true)
  // 口令是两站存量账号共用的第三条路：站点只决定验证码走哪条，不决定它
  assert.equal(isModeAvailable('password', 'phone'), true)
  assert.equal(isModeAvailable('password', 'email'), true)
  // 未知站点时什么都不纠正
  assert.equal(isModeAvailable('phone', ''), true)
  assert.equal(isModeAvailable('email', ''), true)
})

// ==================== 客户端取配置：字段要原样透出来 ====================

function stubFetch(handler) {
  const original = globalThis.fetch
  globalThis.fetch = handler
  return () => { globalThis.fetch = original }
}

test('getAccountLoginCaptchaConfig：accountIdentity 原样透出（信封剥掉即可）', async () => {
  const restore = stubFetch(async () => ({
    ok: true,
    json: async () => ({ code: 0, data: { provider: null, accountIdentity: 'email' } })
  }))
  try {
    const config = await getAccountLoginCaptchaConfig({ serverUrl: 'https://addin.example.com' })
    assert.equal(accountIdentityOf(config), 'email')
  } finally {
    restore()
  }
})

test('getAccountLoginCaptchaConfig：端点不可用时不猜站点（未知 = 两个 tab 都给）', async () => {
  const restore = stubFetch(async () => { throw new Error('offline') })
  try {
    const config = await getAccountLoginCaptchaConfig({ serverUrl: 'https://addin.example.com' })
    assert.equal(accountIdentityOf(config), '')
    assert.deepEqual(visibleCodeTabs(accountIdentityOf(config)), ['phone', 'email'])
  } finally {
    restore()
  }
})

// ==================== 接线：SettingsView.vue ====================

const SETTINGS = '../components/SettingsView.vue'

test('SettingsView：tab 行按 codeTabs 渲染，只剩一个时整行不出现', () => {
  const src = read(SETTINGS)
  assert.ok(src.includes("import { accountIdentityOf, defaultMode, isModeAvailable, visibleCodeTabs } from '../lib/loginTabs.js'"),
    '组件必须用 lib/loginTabs.js 那份判定，不许在组件里再抄一遍')
  assert.ok(src.includes('visibleCodeTabs(identity.value)'), 'codeTabs 要从 identity 派生')
  assert.ok(src.includes('v-if="codeTabs.length > 1"'), '只剩一个 tab 时不该渲染 tab 行')
  assert.ok(src.includes('v-for="tab in codeTabs"'), 'tab 按钮要按 codeTabs 循环，不许写死两个')
  assert.ok(!src.includes('emailMode'), '旧的邮箱 tab 内二级切换应已被 mode=password 取代')
})

test('SettingsView：配置到达时落地站点能力（不调 applyIdentity = 这张卡等于没做）', () => {
  const src = read(SETTINGS)
  assert.ok(src.includes('applyIdentity(accountIdentityOf(config))'),
    '取 captcha-config 的那一处要顺带把 accountIdentity 落到界面上')
  assert.ok(src.includes('if (!isModeAvailable(mode.value, next)) mode.value = defaultMode(next)'),
    '当前形态在新站点下不成立时必须纠正——那个 tab 马上就要从界面上消失')
})

test('SettingsView：口令入口常驻，不随站点隐藏', () => {
  const src = read(SETTINGS)
  const template = src.slice(src.indexOf('<template>'), src.lastIndexOf('</template>'))
  const link = template.indexOf('togglePasswordMode')
  assert.ok(link > 0, '没找到「改用口令登录」的入口')
  assert.equal(branchDepthAt(template, link), 1,
    '口令入口必须在所有 v-if 分支之外：挂在邮箱 tab 里的话，大陆站隐掉邮箱 tab '
    + '会把存量口令用户的入口一起埋掉')
})

/**
 * 统计 index 处还有几层未闭合的 `<template>`：SFC 根是 1 层，落进某个 v-if 分支就是 2 层。
 * 注释先抹掉（保留换行），免得注释里提到的标签把计数带偏。
 */
function branchDepthAt(template, index) {
  const head = template.slice(0, index).replace(/<!--[\s\S]*?-->/g, '')
  const opens = (head.match(/<template[\s>]/g) || []).length
  const closes = (head.match(/<\/template>/g) || []).length
  return opens - closes
}

test('SettingsView：人机验证的两个节点仍在所有 v-if 分支之外（老地雷，这次改的正是 tab 结构）', () => {
  const src = read(SETTINGS)
  const template = src.slice(src.indexOf('<template>'), src.lastIndexOf('</template>'))
  for (const id of ['id="login-captcha"', 'id="login-captcha-trigger"']) {
    const at = template.indexOf(id)
    assert.ok(at > 0, `没找到 ${id}`)
    assert.equal(branchDepthAt(template, at), 1,
      `${id} 落进了 v-if 分支：用户切一次 tab 节点就被拆掉，SDK 攥着一个已经不在文档里的`
      + '元素——弹不出滑块也取不到 token，而且一声不响')
    const line = template.slice(template.lastIndexOf('\n', at) + 1, template.indexOf('\n', at))
    assert.ok(!/v-if|v-else|v-show/.test(line), `${id} 这一行自己也不许带条件渲染`)
  }
})

test('SettingsView：控件居中并与表单同一档间距（改前是贴着左边的一块外来方块）', () => {
  const src = read(SETTINGS)
  const at = src.indexOf('.captcha-holder:not(:empty)')
  const rule = src.slice(at, src.indexOf('}', at) + 1)
  assert.ok(rule.includes('justify-content: center'), '控件要在窗格里居中')
  assert.ok(rule.includes('margin-top') && rule.includes('margin-bottom'),
    '上下都要留与表单一致的间距，只留上边距会与下面的按钮粘在一起')
  // 刻意不画边框：appearance:'interaction-only' 下要不要显示由 Cloudflare 决定，
  // 框一画上去，它不显示的时候就成了表单里凭空多出的一个空框。
  assert.ok(!rule.includes('border:'), '不给第三方控件画固定边框（不显示时会变成空框）')
  assert.ok(!src.includes('.captcha-holder { display: none'), '容器不许 display:none（turnstile 会拿不到尺寸）')
})

// ==================== 接线：captcha.js ====================

test('Turnstile：锁浅色主题 + compact 尺寸', () => {
  const src = read('./captcha.js')
  const render = src.slice(src.indexOf('window.turnstile.render'))
  const options = render.slice(0, render.indexOf('})'))
  assert.ok(options.includes("theme: 'light'"),
    "缺 theme:'light' 就是跟着系统走：深色系统下控件是一块黑方框贴在浅色表单上")
  assert.ok(options.includes("size: 'compact'"),
    "窗格窄（约 320px），normal/flexible 都按 300px 起算，套进表单卡片会横向溢出")
  assert.ok(options.includes("appearance: 'interaction-only'"), '免打扰模式不该被改掉')
})
