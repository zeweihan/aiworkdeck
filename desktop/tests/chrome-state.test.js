// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Windows 非最大化时应用边界与浅色资源管理器分不清（dev-board#722）。
//
// 决定：主进程随 chrome-state 一并上报 isMaximized，渲染层据此在非最大化/非全屏
// 时描边区分窗口边界。本文件只守主进程这一半——sendChromeState 载荷里带上
// isMaximized，并且 maximize/unmaximize 事件也会触发一次推送（此前只有
// enter/leave-full-screen 与 did-finish-load 会推，maximize/unmaximize 只驱动
// views.layoutAll，不通知渲染层，导致渲染层判断不了什么时候该摘掉描边）。
// 渲染层那一半（onState 把 isMaximized 翻成 is-maximized class）见
// frontend/tests/window-chrome/maximize-outline.test.mjs。
//
// 复用 main-window-lifecycle.test.js 的 harness 写法：vm 里真跑 createMainWindow，
// 只桩 Electron/OS 效果。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { EventEmitter } = require('node:events')

const source = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
function section(from, to) {
  const start = source.indexOf(from)
  const end = source.indexOf(to, start)
  assert.ok(start >= 0 && end > start)
  return source.slice(start, end)
}

function harness({ dark = false } = {}) {
  const windows = []
  class Window extends EventEmitter {
    constructor(options) {
      super()
      this.options = options
      this.destroyed = false
      this.sent = []
      this.fullScreen = false
      this.maximized = false
      this.webContents = new EventEmitter()
      this.webContents.send = (...args) => this.sent.push(args)
      this.webContents.setWindowOpenHandler = () => {}
      this.webContents.session = new EventEmitter()
      windows.push(this)
    }
    isDestroyed() { return this.destroyed }
    loadFile() {}
    isFullScreen() { return this.fullScreen }
    isMaximized() { return this.maximized }
    static getAllWindows() { return windows.filter(w => !w.destroyed) }
  }
  const app = new EventEmitter()
  app.isPackaged = false
  const context = vm.createContext({
    app, BrowserWindow: Window, path, console,
    // createMainWindow 用 nativeTheme 决定窗口的 backgroundColor（首屏白闪兜底，dev-board#731）。
    // 默认给浅色：桌面壳启动时 applyNativeTheme('light') 就是出厂值，与真实运行一致。
    nativeTheme: { shouldUseDarkColors: dark },
    process: { platform: 'win32', env: {} }, __dirname: path.join(__dirname, '../main'),
    screen: { getPrimaryDisplay: () => ({ workAreaSize: { width: 1400, height: 900 } }) },
    require: name => {
      const stubs = {
        'node:os': { totalmem: () => 8 * 1024 ** 3 },
        './services/win-arch': { isWinArmEmulated: () => false },
        './reload-guard': { attachReloadGuard: () => true },
      }
      assert.ok(name in stubs, 'createMainWindow 里多了一个没桩的 require: ' + name)
      return stubs[name]
    },
    setInterval: () => 0, clearInterval: () => {},
    views: { layoutAll() {} }, restoreViewsVisibility() {}, syncOcrSelectWinBounds() {},
    attachCopyListener() {}, attachDownloadListener() {}, attachAvatarCorpRelaxation() {},
    startClipboardWatcher() {},
  })
  vm.runInContext(`let mainWindow = null;
    let services = { ports: { backend: 9799 } }; const IS_DEV = false;
    ${section('function createMainWindow()', 'function syncOcrSelectWinBounds()')}
    globalThis.create = createMainWindow;
    globalThis.current = () => mainWindow;
  `, context)
  return { context, windows }
}

test('did-finish-load 推送的 chrome-state 带 isMaximized 字段', () => {
  const h = harness()
  h.context.create()
  const win = h.windows[0]
  win.webContents.emit('did-finish-load')
  const [channel, payload] = win.sent.at(-1)
  assert.equal(channel, 'checkba:chrome-state')
  assert.equal(payload.isMaximized, false)
})

test('maximize 事件会推送 isMaximized: true', () => {
  const h = harness()
  h.context.create()
  const win = h.windows[0]
  win.maximized = true
  win.emit('maximize')
  const [channel, payload] = win.sent.at(-1)
  assert.equal(channel, 'checkba:chrome-state')
  assert.equal(payload.isMaximized, true)
})

test('unmaximize 事件会推送 isMaximized: false', () => {
  const h = harness()
  h.context.create()
  const win = h.windows[0]
  win.maximized = true
  win.emit('maximize')
  win.maximized = false
  win.emit('unmaximize')
  const [channel, payload] = win.sent.at(-1)
  assert.equal(channel, 'checkba:chrome-state')
  assert.equal(payload.isMaximized, false)
})

test('全屏与最大化字段互不覆盖：同一次推送里两个字段各自反映真实状态', () => {
  const h = harness()
  h.context.create()
  const win = h.windows[0]
  win.fullScreen = true
  win.maximized = true
  win.emit('enter-full-screen')
  const [, payload] = win.sent.at(-1)
  assert.equal(payload.fullscreen, true)
  assert.equal(payload.isMaximized, true)
})

test('已销毁的窗口收到迟到的 maximize 不抛错（sendChromeState 的 isDestroyed 兜底仍然有效）', () => {
  const h = harness()
  h.context.create()
  const win = h.windows[0]
  win.destroyed = true
  assert.doesNotThrow(() => win.emit('maximize'))
})

// 首屏白闪兜底（dev-board#731）：主窗口必须自带 backgroundColor，否则 Electron 默认白底，
// 换成玉脂白外壳后每次启动都会闪一下纯白。取值必须来自色源 design/tokens/awd-palette.json，
// 不许在 main.js 里另写一套字面量。
test('主窗口带 backgroundColor，且取值与色源一致（浅色）', () => {
  const palette = JSON.parse(
    fs.readFileSync(path.join(__dirname, '../../design/tokens/awd-palette.json'), 'utf8')
  )
  const h = harness()
  h.context.create()
  assert.equal(
    String(h.windows[0].options.backgroundColor).toUpperCase(),
    palette.roles.light.bg.toUpperCase(),
    '主窗口浅色底应等于色源 roles.light.bg'
  )
})

test('系统处于深色时主窗口底色跟着走，不会先闪浅色', () => {
  const palette = JSON.parse(
    fs.readFileSync(path.join(__dirname, '../../design/tokens/awd-palette.json'), 'utf8')
  )
  const h = harness({ dark: true })
  h.context.create()
  assert.equal(
    String(h.windows[0].options.backgroundColor).toUpperCase(),
    palette.roles.dark.bg.toUpperCase(),
    '主窗口深色底应等于色源 roles.dark.bg'
  )
})
