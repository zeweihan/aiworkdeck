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
