// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 打包态不让「整页重新加载」被快捷键触发（dev-board#628）。
//
// 现场：维护者在编辑器里按 ⌘R（Writer 的「右对齐」），整页重载，SPA 从项目列表
// 重起，工作台所有标签全关，自动保存防抖窗口里还没落盘的改动跟着没。
//
// 根因：视图菜单里的 `{ role: 'reload' }`。**role 自带默认加速键**——Electron 30.5.1
// 的 menu-item-roles 表里 `reload:{label:"Reload",accelerator:"CmdOrCtrl+R",...}`，
// 模板里不写 accelerator 也照样绑上；NSMenu 的 key equivalent 又先于响应链，
// 于是这一下在到达编辑器之前就被菜单吃掉了。
//
// 两道闸：
//   ① 菜单项在打包态**不用 role**，改成带 click 的普通项（普通项没有默认加速键），
//      点击时先弹确认——它仍是渲染层白屏时的自救入口，只是不再一键触发（见 app-menu.js）；
//   ② 主窗口顶层 webContents 的 before-input-event 兜底再拦一次，防止别处又把
//      ⌘R/Ctrl+R/F5 绑回重载。
//
// **第二道闸只挂主窗口顶层，刻意不挂编辑器的 <webview>**：before-input-event 的
// preventDefault 连页面自己的 keydown 一起挡掉（Electron 文档原话：会阻止页面的
// keydown/keyup 与菜单快捷键），挂到 guest 上就等于把 Writer 的「右对齐」也吞了。
// guest 是独立的 webContents，宿主这个钩子覆盖不到它（要覆盖得靠 did-attach-webview
// 另外挂），所以什么都不做，⌘R 照常送进编辑器。顶层这一层没有任何 ⌘R/F5 的用法
// （全仓搜不到），吞掉它不损失功能。

const RELOAD_GUARD_ENV = 'AIWORKDECK_LOCK_RELOAD'

/** 这一次按键是不是在要求「整页重载」。 */
function isReloadShortcut(input) {
  if (!input || input.type !== 'keyDown') return false
  const key = String(input.key || '').toLowerCase()
  if (key === 'f5') return true
  // ⌘R / Ctrl+R / ⇧⌘R 都算；Alt 组合也一并收掉，顶层没有任何 ⌘R 系的用法。
  return key === 'r' && (!!input.control || !!input.meta)
}

/**
 * 这台机器上该不该锁住重载。
 * 打包态锁；开发态不锁（白屏自救与调试都还指着 ⌘R）。
 * `AIWORKDECK_LOCK_RELOAD=1/0` 是**仅供测试**的显式覆盖口：走查要在 dev 壳里
 * 模拟打包态，而 app.isPackaged 是只读的。
 */
function isReloadLocked(opts) {
  const env = (opts && opts.env) || process.env
  const flag = String(env[RELOAD_GUARD_ENV] || '')
  if (flag === '1') return true
  if (flag === '0') return false
  return !!(opts && opts.packaged)
}

/**
 * 给一个 webContents 挂上兜底拦截。重复挂会被去重标记挡住（同 attachCopyListener：
 * macOS 关窗不退应用，点 Dock 会反复走 createMainWindow）。
 * 返回值只为测试可断言：true = 这次真挂上了。
 */
function attachReloadGuard(webContents, opts) {
  if (!webContents || webContents.__awdReloadGuardBound) return false
  webContents.__awdReloadGuardBound = true
  webContents.on('before-input-event', (event, input) => {
    try {
      if (!isReloadLocked(opts)) return
      if (!isReloadShortcut(input)) return
      event.preventDefault()
    } catch (e) {
      // 拦不住就算了，绝不能让它把整条按键链带崩
    }
  })
  return true
}

module.exports = { isReloadShortcut, isReloadLocked, attachReloadGuard, RELOAD_GUARD_ENV }
