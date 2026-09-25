// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「⌘R 把工作台所有标签关掉」（dev-board#628）。
//
// 现场：维护者在编辑器正文里按 ⌘R——Writer 里那是「右对齐」——整页重载，SPA 从
// 项目列表重起，打开的文档标签全丢，自动保存防抖窗口里没落盘的改动跟着没。
//
// 根因不在我们写的加速键，而在 `{ role: 'reload' }` **自带**的那一个：Electron
// 30.5.1 的 menu-item-roles 表里写着 `reload:{label:"Reload",accelerator:"CmdOrCtrl+R"}`，
// 模板里不写 accelerator 也照样绑上，NSMenu 的 key equivalent 又先于响应链。
//
// 本文件守两道闸：
//   ① 打包态的「重新加载」不是 role，而是带 click 的普通项（普通项没有默认加速键），
//      点击要先过一次确认；开发态照旧 role，⌘R 不动；
//   ② before-input-event 兜底：打包态 ⌘R/⇧⌘R/Ctrl+R/F5 一律 preventDefault，
//      ⌘C 一类不受影响；开发态一个都不拦。
//
// electron 在裸 node 里 require 出来是个路径字符串，所以照 share-file.test.js 的
// 先例先往 require.cache 里塞一个假模块，菜单模板与确认对话框才断言得到。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { EventEmitter } = require('node:events')

// ── 假 electron ───────────────────────────────────────────────────────────
const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-reload-test-'))
let builtTemplate = null
const dialogCalls = []
let dialogChoice = 0

const fakeApp = {
  isPackaged: false,
  getVersion: () => '0.0.0-test',
  getPath: () => userDataDir,
  getLocale: () => 'zh-CN',
  setAboutPanelOptions() {},
}
const electronId = require.resolve('electron')
require.cache[electronId] = {
  id: electronId,
  filename: electronId,
  loaded: true,
  exports: {
    app: fakeApp,
    Menu: {
      buildFromTemplate: (tpl) => { builtTemplate = tpl; return { __template: tpl } },
      setApplicationMenu() {},
    },
    ipcMain: { on() {} },
    dialog: {
      showMessageBoxSync: (win, opts) => { dialogCalls.push({ win, opts }); return dialogChoice },
    },
  },
}

const { initAppMenu } = require('../main/app-menu')
const { isReloadShortcut, isReloadLocked, attachReloadGuard } = require('../main/reload-guard')

// ── 工具 ──────────────────────────────────────────────────────────────────
function fakeWindow() {
  const reloads = []
  return {
    isDestroyed: () => false,
    webContents: { reload: () => reloads.push('reload'), send() {} },
    __reloads: reloads,
  }
}

/** 按打包态/开发态重建一次菜单，拿到主进程真正交给 Menu.buildFromTemplate 的模板。 */
function buildMenu(packaged, win) {
  fakeApp.isPackaged = packaged
  builtTemplate = null
  initAppMenu(() => win)
  assert.ok(Array.isArray(builtTemplate), '没拿到菜单模板')
  return builtTemplate
}

function flatten(items, out = []) {
  for (const it of items || []) {
    if (!it) continue
    out.push(it)
    if (Array.isArray(it.submenu)) flatten(it.submenu, out)
  }
  return out
}

const reloadItemOf = (tpl) => flatten(tpl).find((it) => it.label === '重新加载' || it.label === 'Reload')

// ── 菜单项 ────────────────────────────────────────────────────────────────
test('打包态：「重新加载」不是 role，也不带任何加速键', () => {
  const item = reloadItemOf(buildMenu(true, fakeWindow()))
  assert.ok(item, '自救入口不许消失——渲染层白屏时用户只剩它')
  assert.equal(item.role, undefined,
    "role:'reload' 自带 CmdOrCtrl+R，写不写 accelerator 都会绑上（Electron 30 的 roles 表）")
  assert.ok(!item.accelerator, '打包态不许给重新加载任何加速键')
  assert.equal(typeof item.click, 'function', '菜单点击仍要能重载')
})

test('打包态：整张菜单里没有任何一项绑着 ⌘R / ⇧⌘R / F5', () => {
  const accels = flatten(buildMenu(true, fakeWindow()))
    .map((it) => String(it.accelerator || ''))
    .filter(Boolean)
  for (const a of accels) {
    assert.ok(!/^(Shift\+)?(CmdOrCtrl|Cmd|Ctrl|Command|Control)\+R$/i.test(a), '菜单里仍有 ⌘R：' + a)
    assert.ok(!/^F5$/i.test(a), '菜单里仍有 F5：' + a)
  }
  // role 项的默认加速键不在模板里（是 Electron 自己补的），所以上面这条只能靠
  // 「打包态不出现 role:'reload'」来保证——单独再断言一次，防止有人把 role 加回来。
  assert.ok(!flatten(buildMenu(true, fakeWindow())).some((it) => it.role === 'reload' || it.role === 'forceReload'),
    "打包态不许出现 role:'reload'/'forceReload'")
})

test('开发态：⌘R 原样保留（白屏自救与调试都指着它）', () => {
  const item = reloadItemOf(buildMenu(false, fakeWindow()))
  assert.equal(item.role, 'reload', '开发态应当还是 role，加速键跟着 role 走')
})

test('打包态点菜单：先弹确认，取消就不重载', () => {
  const win = fakeWindow()
  const item = reloadItemOf(buildMenu(true, win))
  dialogCalls.length = 0
  dialogChoice = 0 // 取消
  item.click()
  assert.equal(dialogCalls.length, 1, '开着标签就直接重载 = 无声关掉用户所有标签')
  assert.equal(dialogCalls[0].opts.defaultId, 0, '默认按钮必须是取消')
  assert.match(String(dialogCalls[0].opts.detail), /标签|tab/i, '要说清代价是关掉所有标签')
  assert.equal(win.__reloads.length, 0, '用户点了取消却还是重载了')
})

test('打包态点菜单：确认后才真的重载', () => {
  const win = fakeWindow()
  const item = reloadItemOf(buildMenu(true, win))
  dialogCalls.length = 0
  dialogChoice = 1 // 重新加载
  item.click()
  assert.equal(win.__reloads.length, 1, '确认之后必须真能重载，否则自救入口是摆设')
})

// dev-board BUG-01：确认框文案承诺「关闭所有已打开的标签页」，但只 reload() 渲染层
// 不会带走已 addBrowserView 的内嵌浏览器面板——它们会一直浮在重载后的新页面上方
// 直到进程重启。main.js 通过 initAppMenu 第二个参数注入销毁钩子，点击确认必须
// 在 reload() 之前调用它。
test('打包态点菜单确认重载：先销毁所有 BrowserView，再 reload；顺序不能反', () => {
  fakeApp.isPackaged = true
  const win = fakeWindow()
  const order = []
  win.webContents.reload = () => order.push('reload')
  const destroyAllBrowserViews = () => order.push('destroyAllBrowserViews')
  initAppMenu(() => win, { destroyAllBrowserViews })
  const item = reloadItemOf(builtTemplate)
  dialogCalls.length = 0
  dialogChoice = 1 // 重新加载
  item.click()
  assert.deepStrictEqual(order, ['destroyAllBrowserViews', 'reload'],
    '标签页对应的 BrowserView 必须在 reload() 之前清空，且真的被调用了')
})

test('打包态点菜单取消重载：不销毁 BrowserView，也不重载', () => {
  fakeApp.isPackaged = true
  const win = fakeWindow()
  let destroyed = false
  initAppMenu(() => win, { destroyAllBrowserViews: () => { destroyed = true } })
  const item = reloadItemOf(builtTemplate)
  dialogCalls.length = 0
  dialogChoice = 0 // 取消
  item.click()
  assert.equal(destroyed, false, '取消了却把用户还开着的标签销毁了')
  assert.equal(win.__reloads.length, 0)
})

test('没有注入销毁钩子（未接线/旧调用点）：确认重载不报错，安全空操作', () => {
  fakeApp.isPackaged = true
  const win = fakeWindow()
  initAppMenu(() => win) // 不传 opts
  const item = reloadItemOf(builtTemplate)
  dialogCalls.length = 0
  dialogChoice = 1
  assert.doesNotThrow(() => item.click())
  assert.equal(win.__reloads.length, 1)
})

// ── before-input-event 兜底 ───────────────────────────────────────────────
test('reload 快捷键识别：⌘R / Ctrl+R / ⇧⌘R / F5 算，⌘C 与裸 r 不算', () => {
  assert.ok(isReloadShortcut({ type: 'keyDown', key: 'r', meta: true }))
  assert.ok(isReloadShortcut({ type: 'keyDown', key: 'R', control: true }))
  assert.ok(isReloadShortcut({ type: 'keyDown', key: 'r', meta: true, shift: true }))
  assert.ok(isReloadShortcut({ type: 'keyDown', key: 'F5' }))
  assert.ok(!isReloadShortcut({ type: 'keyDown', key: 'c', meta: true }))
  assert.ok(!isReloadShortcut({ type: 'keyDown', key: 'r', meta: true, alt: true }), '⌥⌘R 是「修订模式」的加速键，不是重载')
  assert.ok(!isReloadShortcut({ type: 'keyDown', key: 'r', control: true, alt: true }))
  assert.ok(!isReloadShortcut({ type: 'keyDown', key: 'r' }))
  assert.ok(!isReloadShortcut({ type: 'keyUp', key: 'r', meta: true }), 'keyUp 拦了也没用，只认 keyDown')
  assert.ok(!isReloadShortcut(null))
})

test('锁的判据：打包态锁、开发态不锁，环境变量可显式覆盖（仅测试用）', () => {
  assert.equal(isReloadLocked({ packaged: true, env: {} }), true)
  assert.equal(isReloadLocked({ packaged: false, env: {} }), false)
  assert.equal(isReloadLocked({ packaged: false, env: { AIWORKDECK_LOCK_RELOAD: '1' } }), true)
  assert.equal(isReloadLocked({ packaged: true, env: { AIWORKDECK_LOCK_RELOAD: '0' } }), false)
})

function pressOn(wc, input) {
  let prevented = false
  wc.emit('before-input-event', { preventDefault: () => { prevented = true } }, input)
  return prevented
}

test('打包态：⌘R / ⇧⌘R / F5 被拦下，⌘C / ⌘V 不受影响', () => {
  const wc = new EventEmitter()
  assert.equal(attachReloadGuard(wc, { packaged: true, env: {} }), true)
  assert.ok(pressOn(wc, { type: 'keyDown', key: 'r', meta: true }), '⌘R 没拦住')
  assert.ok(pressOn(wc, { type: 'keyDown', key: 'r', meta: true, shift: true }), '⇧⌘R 没拦住')
  assert.ok(pressOn(wc, { type: 'keyDown', key: 'r', control: true }), 'Ctrl+R 没拦住')
  assert.ok(pressOn(wc, { type: 'keyDown', key: 'F5' }), 'F5 没拦住')
  assert.ok(!pressOn(wc, { type: 'keyDown', key: 'c', meta: true }), '⌘C 被误伤 = 复制粘贴全灭')
  assert.ok(!pressOn(wc, { type: 'keyDown', key: 'v', meta: true }), '⌘V 被误伤')
  assert.ok(!pressOn(wc, { type: 'char', key: 'r', meta: true }), 'char 事件不该被拦')
})

test('开发态：一个都不拦', () => {
  const wc = new EventEmitter()
  attachReloadGuard(wc, { packaged: false, env: {} })
  assert.ok(!pressOn(wc, { type: 'keyDown', key: 'r', meta: true }))
  assert.ok(!pressOn(wc, { type: 'keyDown', key: 'F5' }))
})

test('重复挂载去重（mac 关窗不退应用，点 Dock 会反复建窗）', () => {
  const wc = new EventEmitter()
  assert.equal(attachReloadGuard(wc, { packaged: true, env: {} }), true)
  assert.equal(attachReloadGuard(wc, { packaged: true, env: {} }), false)
  assert.equal(wc.listenerCount('before-input-event'), 1)
})

// ── 接线 ──────────────────────────────────────────────────────────────────
// main.js 起手就 new BrowserWindow / 拉服务，node 直接 require 不进来，
// 与 main-window-bounds.test.js 同口径做源码级断言。
test('主窗口建出来就挂上兜底拦截，且按 app.isPackaged 判档', () => {
  const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
  const CODE = SRC.replace(/(^|\s)\/\*[\s\S]*?\*\//g, '$1').replace(/^\s*\/\/.*$/gm, '')
  const start = CODE.indexOf('function createMainWindow()')
  const end = CODE.indexOf('function syncOcrSelectWinBounds', start)
  assert.ok(start >= 0 && end > start, '截不到 createMainWindow')
  const body = CODE.slice(start, end)
  assert.match(body, /attachReloadGuard\(\s*mainWindow\.webContents\s*,\s*\{\s*packaged:\s*app\.isPackaged\s*\}\s*\)/,
    '主窗口没挂重载兜底')
  assert.ok(!/did-attach-webview/.test(CODE),
    'preventDefault 连页面自己的 keydown 一起挡，挂到编辑器 <webview> 上等于吞掉 Writer 的右对齐')
})

test('main.js 的 initAppMenu 接线里真的注入了 BrowserView 销毁钩子（dev-board BUG-01）', () => {
  const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
  assert.match(SRC, /initAppMenu\(\s*\(\)\s*=>\s*mainWindow\s*,\s*\{\s*destroyAllBrowserViews:\s*\(\)\s*=>\s*views\.destroyAll\(\)\s*\}\s*\)/,
    'confirmAndReload 之前若不清空 views 注册表，重载后网页面板会一直浮在新页面上方')
})
