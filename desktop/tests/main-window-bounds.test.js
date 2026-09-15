// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 主窗口出生尺寸必须夹在显示器工作区内（dev-board#459）。
//
// 病灶：createMainWindow 写死 width:1400 / height:900，既没有 x/y 也没有按
// screen.getPrimaryDisplay().workAreaSize 夹一次。工作区比 1400×900 窄的显示器上，
// 窗口一出生就比屏幕大，只能靠「窗口 → 缩放」（app-menu.js 的 role:'zoom'）救回来。
// 全仓找不到第二条能让主窗口变大的代码路径——没有任何 setSize/setBounds 打在
// mainWindow 上，所以这是「窗口超出屏幕」唯一的代码成因。
//
// main.js 起手就 new BrowserWindow / 拉服务，node 直接 require 不进来，
// 因此与 native-theme-light.test.js 同口径做源码级断言。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const CODE = SRC.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

function createMainWindowBody() {
  const start = CODE.indexOf('function createMainWindow()')
  assert.ok(start >= 0, '找不到 createMainWindow')
  const end = CODE.indexOf('webPreferences', start)
  assert.ok(end > start, '截不到 new BrowserWindow 的尺寸段')
  return CODE.slice(start, end)
}

test('从 electron 引入 screen（夹取工作区尺寸靠它）', () => {
  const line = CODE.split('\n').find((l) => l.includes("require('electron')"))
  assert.ok(line, "找不到顶层 require('electron')")
  assert.match(line, /\bscreen\b/, '不引入 screen 就读不到工作区尺寸')
})

test('建窗尺寸取自 workAreaSize，而不是裸常量', () => {
  const body = createMainWindowBody()
  assert.match(body, /getPrimaryDisplay\(\)\s*\.\s*workAreaSize/,
    '要按主显示器工作区夹（workAreaSize 已扣掉菜单栏/Dock，不要自己再减）')
  assert.match(body, /width:\s*Math\.min\(/, 'width 必须夹一次')
  assert.match(body, /height:\s*Math\.min\(/, 'height 必须夹一次')
  assert.ok(!/width:\s*1400\s*,/.test(body), 'width 不能再是裸的 1400')
  assert.ok(!/height:\s*900\s*,/.test(body), 'height 不能再是裸的 900')
})

test('工作区尺寸在 new BrowserWindow 之前就取好', () => {
  const body = createMainWindowBody()
  const wa = body.indexOf('workAreaSize')
  const win = body.indexOf('new BrowserWindow')
  assert.ok(wa >= 0 && win >= 0)
  assert.ok(wa < win, '要先量工作区再建窗')
})

// ── 主窗口尺寸「只读」护栏（B4 / 0907 清单 B10）。
//
// 那两条报告都说「窗口自己撑成整块工作区」。查下来运行期一条回写路径都没有：
// 渲染层唯一能改几何的通道是 checkba:browser-set-bounds，它进的是 BrowserView
// 的 bounds（browser-views.js 的 setBounds → layoutAll → view.setBounds），
// 窗口本身一次都没被动过；BrowserView 比窗口大只会被裁，不会把窗口顶开。
// 撑窗口的是 macOS 自己（双击标题栏 = 缩放），根因在渲染层的拖拽区，修在
// frontend/src/App.vue，见 frontend/tests/window-chrome/titlebar-drag-region.test.mjs。
//
// 这两条把「不存在回写通道」钉住：以后要给窗口加尺寸回写，先过这个测试，
// 顺便被迫想清楚「谁来触发」——弹出层引起的瞬时视口变化不算用户显式操作。

// 收得住的接收者：这些都不是主窗口，给它们设几何是正常的。
// 主窗口的别名（mainWindow / win / …）一律不在名单里——加了新别名就会红，
// 这是刻意的：请先想清楚是谁触发这次回写。
const GEOMETRY_RECEIVERS_OK = new Set(['view', 'views', 'ocrSelectWin', 'confirmWin', 'verifyWin'])
const GEOMETRY_SETTERS = 'setBounds|setSize|setContentBounds|setContentSize'
  + '|setMinimumSize|setMaximumSize|maximize|unmaximize|setFullScreen'

function mainProcessSources() {
  const dir = path.join(__dirname, '../main')
  const out = []
  const walk = (d) => {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const p = path.join(d, e.name)
      if (e.isDirectory()) walk(p)
      else if (e.name.endsWith('.js')) out.push(p)
    }
  }
  walk(dir)
  out.push(path.join(__dirname, '../preload/preload.js'))
  return out
}

test('主进程里没有任何打在主窗口上的几何 setter', () => {
  const re = new RegExp('([A-Za-z_$][\\w$]*)\\s*\\.\\s*(' + GEOMETRY_SETTERS + ')\\s*\\(', 'g')
  const offenders = []
  for (const file of mainProcessSources()) {
    const code = fs.readFileSync(file, 'utf8')
      .replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
    let m
    while ((m = re.exec(code))) {
      if (GEOMETRY_RECEIVERS_OK.has(m[1])) continue
      offenders.push(path.basename(file) + ': ' + m[1] + '.' + m[2] + '()')
    }
  }
  assert.deepStrictEqual(offenders, [],
    '主窗口尺寸是只读的：' + offenders.join('、')
    + '。要新增回写就得同时回答「谁触发」——弹出层引起的瞬时视口变化不算用户显式操作。')
})

test('preload 不向渲染层暴露任何窗口尺寸通道', () => {
  const code = fs.readFileSync(path.join(__dirname, '../preload/preload.js'), 'utf8')
  const channels = (code.match(/'checkba:[^']+'/g) || []).map((s) => s.slice(1, -1))
  const leaked = channels.filter((c) => /window/i.test(c)
    && /(size|bounds|resize|maximi[sz]e|fullscreen)/i.test(c))
  assert.deepStrictEqual(leaked, [],
    '渲染层不该有改窗口尺寸的通道：' + leaked.join('、')
    + '（checkba:browser-set-bounds 是 BrowserView 的 bounds，不是窗口，别混在一起）')
})
