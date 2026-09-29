// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 顶栏右上角账户入口（AccountEntry.vue）订阅 awd:account-changed（dev-board#1046 / #1047 / #1062）：
// 登录弹层成功与退出登录广播之后，入口「登录 ⇄ 头像」即时翻转，不等宿主重拉、不刷新页面。
// 组件用 vue 自带 compiler-sfc 编译（口径同 tests/calendar/task-row.test.mjs），
// uni 事件总线用一个最小实现替身；手动 $emit 事件后断言状态与真渲染出来的 HTML。
// 跑法：npm run test:account

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { parse, compileTemplate } from 'vue/compiler-sfc'
import * as VueRuntime from 'vue'
import * as SsrRuntime from 'vue/server-renderer'
import { renderToString } from 'vue/server-renderer'
import { ICONS } from '../../src/config/icons.js'

const ACCOUNT_CHANGED_EVENT = 'awd:account-changed'
const SFC = fs.readFileSync(new URL('../../src/components/account/AccountEntry.vue', import.meta.url), 'utf8')
const read = (rel) => fs.readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')

// 最小 uni 事件总线（组件在 created 里 uni.$on、beforeUnmount 里 uni.$off）
function installBus() {
  const handlers = new Map()
  globalThis.uni = {
    $on(ev, fn) { if (!handlers.has(ev)) handlers.set(ev, new Set()); handlers.get(ev).add(fn) },
    $off(ev, fn) { handlers.get(ev) && handlers.get(ev).delete(fn) },
    $emit(ev, payload) { for (const fn of [...(handlers.get(ev) || [])]) fn(payload) },
  }
  return { count: (ev) => (handlers.get(ev) ? handlers.get(ev).size : 0) }
}

function buildComponent() {
  const { descriptor, errors } = parse(SFC, { filename: 'AccountEntry.vue' })
  assert.equal(errors.length, 0, 'AccountEntry.vue 解析失败')
  const compiled = compileTemplate({
    source: descriptor.template.content, filename: 'AccountEntry.vue', id: 'account-entry', ssr: true, ssrCssVars: [],
  })
  assert.equal(compiled.errors.length, 0, '模板编译报错：' + compiled.errors.join('; '))
  const renderBody = compiled.code
    .replace(/^import \{([\s\S]*?)\} from "vue"$/m, 'const {$1} = __vue')
    .replace(/^import \{([\s\S]*?)\} from "vue\/server-renderer"$/m, 'const {$1} = __ssr')
    .replace(/\bas\b/g, ':')
    .replace(/^export function ssrRender/m, 'function ssrRender')
  // eslint-disable-next-line no-new-func
  const ssrRender = new Function('__vue', '__ssr', renderBody + '\nreturn ssrRender')(VueRuntime, SsrRuntime)
  const script = descriptor.script.content
    .replace(/^import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  // eslint-disable-next-line no-new-func
  const options = new Function('ICONS', 'ACCOUNT_CHANGED_EVENT', script)(ICONS, ACCOUNT_CHANGED_EVENT)
  return { ...options, ssrRender }
}

const Entry = buildComponent()

// 组件实例替身：props + data + computed（getter 每次现算）+ methods，跑组件自己的 created。
// 不用 SSR 实例拿代理：SSR 下 computed 不随 data 变化重算，测不出「翻转」。
// 初始 HTML 用真 SSR 渲染一遍，与事件之后的 renderHtml 对照。
async function mountEntry(props) {
  const raw = { ...props, ...Entry.data.call({}), $emit() {}, $t: (k) => k }
  for (const [k, get] of Object.entries(Entry.computed)) {
    Object.defineProperty(raw, k, { get() { return get.call(raw) }, enumerable: true })
  }
  for (const [k, fn] of Object.entries(Entry.methods)) raw[k] = fn.bind(raw)
  Entry.created.call(raw)
  const html = await renderHtml(props, null)
  return { vm: raw, html }
}

async function renderHtml(props, connectedOverride) {
  // 带覆盖值渲染：data() 返回的 connectedOverride 预置成事件之后的样子
  const Patched = { ...Entry, data() { return { ...Entry.data.call(this), connectedOverride } } }
  const app = VueRuntime.createSSRApp({ render: () => VueRuntime.h(Patched, props) })
  app.config.globalProperties.$t = (k) => k
  app.config.warnHandler = () => {}
  // SSR 实例也会跑 created 订阅、且永不卸载：渲染期间换一条空总线，别污染被测的那条
  const saved = globalThis.uni
  globalThis.uni = { $on() {}, $off() {}, $emit() {} }
  try {
    return (await renderToString(app)).replace(/<!--[\s\S]*?-->/g, '')
  } finally {
    globalThis.uni = saved
  }
}

test('未登录 → 收到 awd:account-changed {connected:true} → signedIn 翻成 true', async () => {
  const bus = installBus()
  const { vm, html } = await mountEntry({ loggedIn: false })
  assert.ok(vm, '拿到组件实例')
  assert.match(html, /welcome\.signIn/, '初始渲染的是「登录」')
  assert.match(html, /header-login-btn/, '未登录是顶栏「登录」按钮形态')
  assert.doesNotMatch(html, /avatar-btn/)
  assert.equal(bus.count(ACCOUNT_CHANGED_EVENT), 1, 'created 里订阅了一次')
  assert.equal(vm.signedIn, false)
  globalThis.uni.$emit(ACCOUNT_CHANGED_EVENT, { connected: true })
  assert.equal(vm.signedIn, true)
  // 同一状态的真渲染：头像，不再是「登录」
  const after = await renderHtml({ loggedIn: false }, true)
  assert.match(after, /account-avatar/)
  assert.match(after, /avatar-btn/, '已登录是顶栏头像 .avatar-btn')
  assert.doesNotMatch(after, /welcome\.signIn/)
  assert.doesNotMatch(after, /header-login-btn/)
})

test('已登录且下拉开着 → 收到 {connected:false}（退出登录）→ 翻回「登录」并收起下拉', async () => {
  installBus()
  const { vm } = await mountEntry({ loggedIn: true })
  vm.menuOpen = true
  globalThis.uni.$emit(ACCOUNT_CHANGED_EVENT, { connected: false })
  assert.equal(vm.signedIn, false)
  assert.equal(vm.menuOpen, false)
  const after = await renderHtml({ loggedIn: true }, false)
  assert.match(after, /welcome\.signIn/)
})

test('不带 connected 的负载不改显示；宿主 prop 跟上后以 prop 为准（覆盖作废）', async () => {
  installBus()
  const { vm } = await mountEntry({ loggedIn: false })
  globalThis.uni.$emit(ACCOUNT_CHANGED_EVENT, {})
  assert.equal(vm.connectedOverride, null)
  assert.equal(vm.signedIn, false)
  globalThis.uni.$emit(ACCOUNT_CHANGED_EVENT, { connected: true })
  assert.equal(vm.connectedOverride, true)
  // 宿主重拉授权状态后 loggedIn 变了：watch 清掉本地覆盖
  Entry.watch.loggedIn.call(vm)
  assert.equal(vm.connectedOverride, null)
})

test('未登录点击发 login（宿主就地弹层），已登录点击开下拉', async () => {
  installBus()
  const emitted = []
  const vm = { signedIn: false, menuOpen: false, $emit: (e) => emitted.push(e) }
  Entry.methods.onTap.call(vm)
  assert.deepEqual(emitted, ['login'])
  vm.signedIn = true
  Entry.methods.onTap.call(vm)
  assert.equal(vm.menuOpen, true)
})

test('卸载时按引用退订（页面栈多实例不留幽灵订阅）', () => {
  const bus = installBus()
  const vm = { onAccountChanged() {} }
  Entry.created.call(vm)
  assert.equal(bus.count(ACCOUNT_CHANGED_EVENT), 1)
  Entry.beforeUnmount.call(vm)
  assert.equal(bus.count(ACCOUNT_CHANGED_EVENT), 0)
})

test('工作台接线：onAccountLogin 就地 requireAccount({ reason: \'account\' })，不再跳 unlock；账户变化后重拉三样', () => {
  const src = read('pages/project-overview/project-overview.vue')
  const i = src.indexOf('async onAccountLogin() {')
  assert.ok(i > 0)
  const body = src.slice(i, src.indexOf('\n    },', i))
  assert.match(body, /await requireAccount\(\{ reason: 'account' \}\)/)
  assert.doesNotMatch(body, /unlock|navigateTo|reLaunch/)
  assert.match(src, /uni\.\$on\(ACCOUNT_CHANGED_EVENT, this\._onAccountChanged\)/)
  assert.match(src, /uni\.\$off\(ACCOUNT_CHANGED_EVENT, this\._onAccountChanged\)/)
  const r = src.indexOf('refreshAccountState() {')
  const rb = src.slice(r, src.indexOf('\n    },', r))
  for (const m of ['loadLicenseMode()', 'loadWalletBalance()', 'loadRealUserInfo()']) assert.ok(rb.includes(m), '缺 ' + m)
})

test('客户视角：下拉里不出「我的日程」（客户看不到事项），宿主以 isClientView 传入', async () => {
  // 下拉只在 menuOpen 时渲染：用覆盖 data 的方式把它打开再真渲染
  async function renderMenu(props) {
    const Patched = { ...Entry, data() { return { ...Entry.data.call(this), menuOpen: true } } }
    const app = VueRuntime.createSSRApp({ render: () => VueRuntime.h(Patched, props) })
    app.config.globalProperties.$t = (k) => k
    app.config.warnHandler = () => {}
    const saved = globalThis.uni
    globalThis.uni = { $on() {}, $off() {}, $emit() {} }
    try {
      return (await renderToString(app)).replace(/<!--[\s\S]*?-->/g, '')
    } finally {
      globalThis.uni = saved
    }
  }
  const lawyer = await renderMenu({ loggedIn: true })
  assert.match(lawyer, /calendar\.mySchedule/, '律师视角有「我的日程」')
  assert.match(lawyer, /workbench\.settingsTabName/)
  const client = await renderMenu({ loggedIn: true, clientView: true })
  assert.doesNotMatch(client, /calendar\.mySchedule/, '客户视角不出「我的日程」')
  assert.match(client, /workbench\.settingsTabName/, '其余菜单项照常')
  assert.match(client, /account\.logoutBtn/)
  const host = read('pages/project-overview/project-overview.vue')
  const i = host.indexOf('<AccountEntry')
  const tag = host.slice(i, host.indexOf('/>', i))
  assert.match(tag, /:client-view="isClientView"/)
  // isClientView 必须是布尔值：无会话缓存时 getCurrentUser() 是空串，空串进 Boolean prop 会被 Vue 转成 true，
  // 律师的下拉就丢了「我的日程」（dev-board#1062 走查）。真渲染对照：空串 → 被当成客户视角
  const blank = await renderMenu({ loggedIn: true, clientView: '' })
  assert.doesNotMatch(blank, /calendar\.mySchedule/, 'Vue 的 Boolean 转换：空串 = true（这就是为什么宿主必须传布尔值）')
  const cv = host.slice(host.indexOf('isClientView() {'), host.indexOf('\n    },', host.indexOf('isClientView() {')))
  assert.match(cv, /return !!\(/, 'isClientView 没有收成布尔值')
})

test('宿主摆放：账户入口在顶栏 header-right、「活动记录」右侧；rail 里没有账户入口与设置齿轮（dev-board#1062）', () => {
  const host = read('pages/project-overview/project-overview.vue')
  const header = host.slice(host.indexOf('<view class="header-right">'), host.indexOf('<!-- 主体布局 -->'))
  const at = header.indexOf('<AccountEntry')
  assert.ok(at > 0, '顶栏里有 <AccountEntry>')
  assert.ok(at > header.indexOf('toggleRecording'), '在「活动记录」右侧')
  const rail = host.slice(host.indexOf('<view class="left-rail">'), host.indexOf('<FilePickerDialog'))
  assert.ok(rail.length > 0)
  assert.doesNotMatch(rail, /<AccountEntry|AccountRailEntry/)
  assert.doesNotMatch(rail, /GLYPHS\.settings|goToSystemSettings/)
})
