// 原生外观必须与应用主题一致（dev-board#218 → #223）。
//
// 病灶（#218）：外壳当时是浅色单主题，原生层却跟随系统。系统开深色时主窗口
// 一失焦，macOS 就按深色规则绘制交通灯，落在浅色顶栏上完全不可见——实测失活态
// 抓图里交通灯区域偏离背景的像素数为 0（整组按钮凭空消失）。
//
// #223 加入深色模式后不变式升级：不再是「恒为浅色」，而是「原生外观 = 应用
// 主题」。启动先按浅色（渲染层上报前的安全默认，也是主题出厂值），随后由
// checkba:set-theme 推来真实主题。
//
// 'system' 必须原样传给 themeSource：一旦设成非 'system'，Electron 会把所有
// 渲染进程的 prefers-color-scheme 钉死，渲染层的 matchMedia 再也读不到真实
// 系统设置（frontend/src/utils/appTheme.js 依赖它解析「跟随系统」）。
//
// main.js 起手就 new BrowserWindow / 拉服务，node 直接 require 不进来，
// 因此与 frontend 的契约用例同口径做源码级断言。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const CODE = SRC.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const PRELOAD = fs.readFileSync(path.join(__dirname, '../preload/preload.js'), 'utf8')

test('从 electron 引入 nativeTheme', () => {
  const line = CODE.split('\n').find((l) => l.includes("require('electron')"))
  assert.ok(line, "找不到顶层 require('electron')")
  assert.match(line, /nativeTheme/, '不引入就没法设外观')
})

test('themeSource 只由 applyNativeTheme 一处写', () => {
  const writes = CODE.match(/nativeTheme\.themeSource\s*=/g) || []
  assert.equal(writes.length, 1, '外观写入必须收口在 applyNativeTheme，散写会各说各话')
  assert.match(CODE, /function applyNativeTheme\s*\(/, '缺少 applyNativeTheme')
})

test("applyNativeTheme 原样透传 'system'，不自行解析成 light/dark", () => {
  const m = CODE.match(/function applyNativeTheme[\s\S]*?\n}/)
  assert.ok(m, '截不到 applyNativeTheme 函数体')
  assert.match(m[0], /'light',\s*'dark',\s*'system'/,
    "三种 mode 都要认；把 'system' 折叠掉会钉死所有渲染进程的 prefers-color-scheme")
  assert.match(m[0], /shouldUseDarkColors/, '要回报系统深浅，渲染层 system 态靠它')
})

test('启动时先落浅色，且发生在建主窗口之前', () => {
  const boot = CODE.indexOf("applyNativeTheme('light')")
  const call = CODE.search(/(?<!function\s)createMainWindow\(\)/)
  assert.ok(boot >= 0, '启动缺少浅色默认')
  assert.ok(call >= 0, '找不到 createMainWindow() 调用点')
  assert.ok(boot < call,
    '窗口建完再设外观会让首个窗口以系统外观创建，交通灯仍然会丢')
})

test('渲染层能推主题过来（IPC + preload 两端都在）', () => {
  assert.match(CODE, /ipcMain\.handle\(\s*'checkba:set-theme'/, '缺少主进程 handler')
  assert.match(PRELOAD, /checkba:set-theme/, 'preload 没暴露通道，渲染层推不过来')
})
