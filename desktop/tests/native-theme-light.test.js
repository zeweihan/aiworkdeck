// 原生外观锁定浅色（dev-board#218）。
//
// 病灶：外壳是浅色单主题，但原生层默认跟随系统。系统开深色时，主窗口一失焦
// macOS 就按深色规则绘制交通灯，落在我们的浅色顶栏上完全不可见——实测失活态
// 抓图里交通灯区域偏离背景的像素数为 0（整组按钮凭空消失），锁浅色后恢复成
// 标准的三个灰色圆点。
//
// main.js 起手就 new BrowserWindow / 拉服务，node 直接 require 不进来，
// 因此与 frontend 的契约用例同口径做源码级断言。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const CODE = SRC.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

test('从 electron 引入 nativeTheme', () => {
  const line = CODE.split('\n').find((l) => l.includes("require('electron')"))
  assert.ok(line, "找不到顶层 require('electron')")
  assert.match(line, /nativeTheme/, '不引入就没法锁外观')
})

test('themeSource 锁成 light', () => {
  assert.match(CODE, /nativeTheme\.themeSource\s*=\s*'light'/,
    "外壳是浅色单主题；跟随系统会让深色下的失焦窗口丢掉交通灯")
  assert.ok(!/themeSource\s*=\s*'(dark|system)'/.test(CODE),
    '不允许有把外观改回深色/跟随系统的分支')
})

test('锁外观发生在第一次建主窗口之前', () => {
  const lock = CODE.indexOf("nativeTheme.themeSource = 'light'")
  // 只找调用点，跳过函数定义（definition 在文件上半部分）
  const call = CODE.search(/(?<!function\s)createMainWindow\(\)/)
  assert.ok(lock >= 0, '找不到锁外观那一行')
  assert.ok(call >= 0, '找不到 createMainWindow() 调用点')
  assert.ok(lock < call,
    '窗口建完再改外观会让首个窗口以系统外观创建，交通灯仍然会丢')
})
