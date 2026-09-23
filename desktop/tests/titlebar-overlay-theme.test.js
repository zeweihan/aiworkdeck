// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Windows 右上角原生窗控跟随应用主题（dev-board#865）。
//
// 病灶：main.js 建窗时把 titleBarOverlay 写死成 { color:'#ffffff', symbolColor:'#3c4043' }，
// 主题切换链路（渲染层 appTheme.setThemeMode → checkba:set-theme → applyNativeTheme）
// 只改 nativeTheme.themeSource，从来没有调过 setTitleBarOverlay——于是应用切到深色后
// 顶栏变深，最小化/最大化/关闭那一块仍是白底黑字。
//
// 本文件守三件事：
//   1. applyNativeTheme 每次落主题都按 nativeTheme 的深浅刷新覆盖层，深色取值来自色源；
//   2. 只在 win32 调（mac 是交通灯，没有覆盖层）；
//   3. 「跟随系统」时系统自己切深浅（nativeTheme 'updated'）也会刷新。
// 复用 chrome-state.test.js 的 harness 写法：vm 里真跑 main.js 的相关片段，只桩 Electron。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { EventEmitter } = require('node:events')

const source = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const palette = JSON.parse(fs.readFileSync(path.join(__dirname, '../../design/tokens/awd-palette.json'), 'utf8'))

function section(from, to) {
  const start = source.indexOf(from)
  const end = source.indexOf(to, start)
  assert.ok(start >= 0 && end > start, '截不到 ' + from)
  return source.slice(start, end)
}

// 浅色保持改造前的取值（维护者要求浅色观感一丝不动）
const LIGHT = { color: '#ffffff', symbolColor: '#3c4043', height: 42 }

function harness({ platform = 'win32', systemDark = false } = {}) {
  const windows = []
  class Window extends EventEmitter {
    constructor(options) {
      super()
      this.options = options
      this.destroyed = false
      this.overlayCalls = []
      this.webContents = new EventEmitter()
      this.webContents.send = () => {}
      this.webContents.setWindowOpenHandler = () => {}
      this.webContents.session = new EventEmitter()
      windows.push(this)
    }
    isDestroyed() { return this.destroyed }
    loadFile() {}
    isFullScreen() { return false }
    isMaximized() { return false }
    setTitleBarOverlay(o) { this.overlayCalls.push(o) }
    setMenuBarVisibility() {}
    setAutoHideMenuBar() {}
  }
  // 与真实 nativeTheme 同语义：themeSource 决定 shouldUseDarkColors，'system' 看系统
  const nativeTheme = {
    _src: 'system',
    systemDark,
    set themeSource(v) { this._src = v },
    get themeSource() { return this._src },
    get shouldUseDarkColors() { return this._src === 'dark' || (this._src === 'system' && this.systemDark) },
  }
  const app = new EventEmitter()
  app.isPackaged = false
  const context = vm.createContext({
    app, BrowserWindow: Window, path, console, nativeTheme,
    process: { platform, env: {} }, __dirname: path.join(__dirname, '../main'),
    screen: { getPrimaryDisplay: () => ({ workAreaSize: { width: 1400, height: 900 } }) },
    require: (name) => {
      const stubs = {
        'node:os': { totalmem: () => 8 * 1024 ** 3 },
        './services/win-arch': { isWinArmEmulated: () => false },
        './reload-guard': { attachReloadGuard: () => true },
      }
      assert.ok(name in stubs, '多了一个没桩的 require: ' + name)
      return stubs[name]
    },
    setInterval: () => 0, clearInterval: () => {},
    views: { layoutAll() {} }, restoreViewsVisibility() {}, syncOcrSelectWinBounds() {},
    attachCopyListener() {}, attachDownloadListener() {}, attachAvatarCorpRelaxation() {},
    startClipboardWatcher() {},
  })
  vm.runInContext(`let mainWindow = null;
    let services = { ports: { backend: 9799 } }; const IS_DEV = false;
    ${section('function applyNativeTheme(', 'function syncOcrSelectWinBounds()')}
    globalThis.applyNativeTheme = applyNativeTheme;
    globalThis.create = createMainWindow;
    globalThis.sync = syncTitleBarOverlay;
  `, context)
  return { context, windows, nativeTheme }
}

test('深色取值与色源一致：底色 = roles.dark.surface（顶栏底），符号 = roles.dark.text-2（顶栏图标）', () => {
  const h = harness()
  h.context.applyNativeTheme('light')
  h.context.create()
  h.context.applyNativeTheme('dark')
  const last = h.windows[0].overlayCalls.at(-1)
  assert.equal(last.color.toUpperCase(), palette.roles.dark.surface.toUpperCase())
  assert.equal(last.symbolColor.toUpperCase(), palette.roles.dark['text-2'].toUpperCase())
  assert.equal(last.height, 42, '高度必须仍对齐 42px 顶栏')
})

test('浅色建窗取值与改造前一字不差', () => {
  const h = harness()
  h.context.applyNativeTheme('light')
  h.context.create()
  assert.deepEqual({ ...h.windows[0].options.titleBarOverlay }, LIGHT)
})

test('运行中切深色再切回浅色：每次都调 setTitleBarOverlay，且落在对应取值', () => {
  const h = harness()
  h.context.applyNativeTheme('light')
  h.context.create()
  const win = h.windows[0]
  h.context.applyNativeTheme('dark')
  assert.equal(win.overlayCalls.length, 1)
  assert.notEqual(win.overlayCalls[0].color.toLowerCase(), LIGHT.color, '切深色后窗控不能还是白底')
  h.context.applyNativeTheme('light')
  assert.equal(win.overlayCalls.length, 2)
  assert.deepEqual({ ...win.overlayCalls[1] }, LIGHT)
})

test("'system' 态按系统真实深浅落色（nativeTheme 是应用主题的镜像）", () => {
  const h = harness({ systemDark: true })
  h.context.applyNativeTheme('light')
  h.context.create()
  h.context.applyNativeTheme('system')
  assert.equal(h.windows[0].overlayCalls.at(-1).color.toUpperCase(), palette.roles.dark.surface.toUpperCase())
  // 系统切回浅色：主进程收到 nativeTheme 'updated' 后调同一个 sync
  h.nativeTheme.systemDark = false
  h.context.sync()
  assert.deepEqual({ ...h.windows[0].overlayCalls.at(-1) }, LIGHT)
})

test('深色启动（窗口建在深色 nativeTheme 下）：建窗参数直接就是深色，不先闪白', () => {
  const h = harness()
  h.context.applyNativeTheme('dark')
  h.context.create()
  assert.equal(h.windows[0].options.titleBarOverlay.color.toUpperCase(), palette.roles.dark.surface.toUpperCase())
})

test('非 win32 一律不碰覆盖层（mac 是交通灯）', () => {
  const h = harness({ platform: 'darwin' })
  h.context.applyNativeTheme('light')
  h.context.create()
  h.context.applyNativeTheme('dark')
  assert.equal(h.windows[0].options.titleBarOverlay, undefined)
  assert.equal(h.windows[0].overlayCalls.length, 0)
})

test('窗口还没建 / 已销毁时切主题不抛错', () => {
  const h = harness()
  assert.doesNotThrow(() => h.context.applyNativeTheme('dark'))
  h.context.create()
  h.windows[0].destroyed = true
  assert.doesNotThrow(() => h.context.applyNativeTheme('light'))
  assert.equal(h.windows[0].overlayCalls.length, 0)
})

test("系统外观变化（nativeTheme 'updated'）接到 syncTitleBarOverlay", () => {
  const code = source.replace(/(^|\s)\/\*[\s\S]*?\*\//g, '$1').replace(/^\s*\/\/.*$/gm, '')
  assert.match(code, /nativeTheme\.on\(\s*'updated'\s*,[^\n]*syncTitleBarOverlay\(\)/,
    "「跟随系统」下用户在系统设置里切深浅，没有这条监听窗控就停在旧颜色")
})
