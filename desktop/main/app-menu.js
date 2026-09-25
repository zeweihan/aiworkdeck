// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 应用菜单：主进程这一侧只做「把渲染层下发的 JSON 变成 NSMenu」，不决定菜单长什么样。
//
// 为什么数据源在渲染层：菜单的 enabled/checked 本来就必须由页面状态驱动（修订模式
// 开没开、有没有打开的标签、是不是客户视图），而主进程 require 不到前端的 ES 模块。
// 索性整张表都交给渲染层——命令表、加速键、命令面板因此共用同一份数据。
// 命令表在 frontend/src/config/commands/，下发方在 frontend/src/utils/appMenuBridge.js。
//
// **骨架恒定，下发只替换业务菜单。** 应用菜单、编辑 roles、视图里的重新加载与
// 开发者工具、窗口——这几段主进程永远自己持有，任何下发都覆盖不掉。渲染层白屏
// 或崩溃时如果菜单跟着没了，用户连「重新加载」都点不到，那就彻底没救了。
//
// 菜单动作经 'checkba:menu-action' 发回渲染层，载荷 { action: <命令 id> }。
// 动态条目的 id 带冒号后缀（file.openRecent:7 / view.open:files），由渲染层解析。
//
// 设计见 docs/superpowers/specs/2026-08-16-desktop-chrome-and-command-menu.md。

const { app, Menu, ipcMain, dialog } = require('electron')
const { t, onAppLanguageChange } = require('./app-language')
const { isReloadLocked } = require('./reload-guard')

// 菜单里的应用名写死，**不要用 app.name**：desktop/package.json 没有顶层 productName，
// Electron 于是拿 name 字段当 app.name，菜单会显示「关于 aiworkdeck-desktop」。
// 而补一个顶层 productName 会连带把 app.getPath('userData') 从
// ~/Library/Application Support/aiworkdeck-desktop 改名到「AI WorkDeck」，
// 存量用户的 Local Storage（登录态、uni 存储）当场全丢——不值得为一个显示名冒这个险。
// 注：macOS 菜单栏最左边那个粗体应用名不由这里决定，它取自运行中 .app 包的
// CFBundleName（打包版=AI WorkDeck；dev 跑 node_modules 里的 Electron.app，
// 见 scripts/brand-dev-electron.js）。
const APP_DISPLAY_NAME = 'AI WorkDeck'

let getWindow = () => null
// 渲染层最近一次下发的业务菜单。null = 渲染层还没就绪，先只挂骨架。
let pushed = null
// 重载前销毁所有内嵌浏览器面板 BrowserView 的钩子（dev-board BUG-01）。
// 整页 reload 不会带走已 addBrowserView 的视图，也不会触发 BrowserPane.vue 的
// beforeUnmount，两头都不清就会一直浮在重载后的新页面上方。main.js 通过
// initAppMenu 的第二个参数注入真实实现；测试/未注入时是安全的空操作。
let destroyAllBrowserViews = () => {}

function send(action) {
  const win = getWindow()
  if (!win || win.isDestroyed()) return
  win.webContents.send('checkba:menu-action', { action })
}

/** 下发的一条 JSON 菜单项 → Electron 模板项。 */
function toTemplate(item) {
  if (!item || item.type === 'separator') return { type: 'separator' }
  const node = { label: String(item.label || '') }
  if (item.enabled === false) node.enabled = false
  if (Array.isArray(item.submenu)) {
    node.submenu = item.submenu.map(toTemplate)
    return node
  }
  if (item.type === 'checkbox') {
    node.type = 'checkbox'
    node.checked = !!item.checked
  }
  if (item.accel) node.accelerator = item.accel
  node.click = () => send(item.id)
  return node
}

/** 取下发菜单里某一段的模板项；渲染层没就绪或没有这段就返回空数组。 */
function pushedItems(menuId) {
  if (!pushed || !Array.isArray(pushed.menus)) return []
  const m = pushed.menus.find((x) => x.id === menuId)
  return m && Array.isArray(m.items) ? m.items.map(toTemplate) : []
}

function pushedLabel(menuId, fallback) {
  if (!pushed || !Array.isArray(pushed.menus)) return fallback
  const m = pushed.menus.find((x) => x.id === menuId)
  return (m && m.label) || fallback
}

/**
 * BUG-30：当前是不是在一个文档标签上（渲染层的 `flags.isDocTab`，见
 * frontend/src/config/commands/index.js 的 buildMenuPayload）。LOWA 引擎是画布
 * 渲染，Electron 的 `role: 'undo'/'redo'` 只认浏览器原生编辑历史（textarea/
 * contentEditable），对它完全无效——菜单栏「编辑 > 撤销」在文档里因此没反应。
 */
function isDocTabActive() {
  return !!(pushed && pushed.flags && pushed.flags.isDocTab)
}

/**
 * 撤销/重做该落到哪儿。文档标签激活时菜单项不再用 role，但也**不能一律转成文档撤销**
 * （J1 复核：焦点在 AI 输入框/查找替换/批注/重命名时 ⌘Z 也会去撤销文档）：
 *  - 焦点在主窗口之外的 webContents（浏览器面板的 BrowserView 等）→ 原样对它做原生
 *    undo/redo，与 role 的行为一致；
 *  - 焦点在主窗口自己或它的 <webview> 客体里 → 交给渲染层，由它看 document.activeElement
 *    决定：输入框/可编辑元素走原生 execCommand，编辑器画布才发 .uno:Undo
 *    （frontend/src/utils/undoRouting.js）。
 */
function routeUndoRedo(kind) {
  const win = getWindow()
  if (!win || win.isDestroyed()) return
  const main = win.webContents
  let focused = null
  try { focused = require('electron').webContents.getFocusedWebContents() } catch (e) { focused = null }
  const ownedByRenderer = !focused || focused === main || focused.hostWebContents === main
  if (!ownedByRenderer) {
    try { if (typeof focused[kind] === 'function') focused[kind]() } catch (e) { /* 失焦/已销毁：什么都不做 */ }
    return
  }
  send('edit.' + kind)
}

/** 撤销/重做菜单项：文档标签激活时按焦点分流（见 routeUndoRedo），否则原样保留 Electron role。 */
function undoRedoMenuItem(kind) {
  const label = kind === 'undo' ? t({ zh: '撤销', en: 'Undo' }) : t({ zh: '重做', en: 'Redo' })
  if (isDocTabActive()) {
    const accelerator = kind === 'undo' ? 'CmdOrCtrl+Z' : 'Shift+CmdOrCtrl+Z'
    return { label, accelerator, click: () => routeUndoRedo(kind) }
  }
  return { role: kind, label }
}

/** 有内容才成为一个顶级菜单——渲染层没就绪时不该出现一堆空菜单。 */
function optionalMenu(menuId, fallbackLabel) {
  const items = pushedItems(menuId)
  if (!items.length) return null
  return { label: pushedLabel(menuId, fallbackLabel), submenu: items }
}

/**
 * 「重新加载」菜单项（dev-board#628）。
 *
 * 开发态照旧 `role: 'reload'`——⌘R 顺手，dev 页面重载也丢不了什么。
 * 打包态**不能用 role**：role 自带默认加速键 ⌘R（Electron 30.5.1 的 roles 表里写死），
 * 而 ⌘R 在 Writer 里是「右对齐」，用户在正文里按到它就会整页重载、工作台所有标签
 * 全关。普通 click 项没有默认加速键，只能从菜单点进来——这条自救入口因此还在，
 * 白屏时照样点得到，只是点了要先确认。
 */
function reloadMenuItem() {
  const label = t({ zh: '重新加载', en: 'Reload' })
  if (!isReloadLocked({ packaged: app.isPackaged })) return { role: 'reload', label }
  return { label, click: () => confirmAndReload() }
}

/**
 * 确认后才重载。主进程这一侧看不到工作台开着几个标签（菜单下发的载荷里只有菜单项），
 * 所以不分情况一律问一次——重载的代价是「所有标签 + 防抖窗口里未落盘的改动」，
 * 问一句比猜错便宜。用原生对话框而不是让渲染层弹：白屏时渲染层正是不可用的那一侧。
 */
function confirmAndReload() {
  const win = getWindow()
  if (!win || win.isDestroyed()) return
  let choice = 0
  try {
    choice = dialog.showMessageBoxSync(win, {
      type: 'warning',
      buttons: [t({ zh: '取消', en: 'Cancel' }), t({ zh: '重新加载', en: 'Reload' })],
      defaultId: 0,
      cancelId: 0,
      message: t({ zh: '要重新加载界面吗？', en: 'Reload the interface?' }),
      detail: t({
        zh: '重新加载会关闭所有已打开的标签页，最近几秒还没保存的改动可能丢失。界面卡住或白屏时再用它。',
        en: 'Reloading closes every open tab; edits from the last few seconds may not have been saved yet. Use it only when the interface is stuck or blank.',
      }),
    })
  } catch (e) {
    // 弹不出来就什么都不做——宁可不重载，也不能在没问过用户的情况下关掉他所有标签
    return
  }
  if (choice !== 1) return
  // 先销毁所有内嵌浏览器面板的 BrowserView，再重载——否则它们会浮在重载后的
  // 新页面上方，直到进程重启（dev-board BUG-01）。确认框文案承诺「关闭所有
  // 已打开的标签页」，这一步是兑现它的另一半（渲染层那一半靠整页 reload 本身
  // 重起 Vue 树自动做到）。
  try { destroyAllBrowserViews() } catch (e) { /* 清不掉也不能挡住重载 */ }
  win.webContents.reload()
}

function buildTemplate() {
  const template = []

  // ── 应用菜单：roles 恒定，中间夹渲染层下发的「检查更新/设置/账户/语言」
  template.push({
    label: APP_DISPLAY_NAME,
    submenu: [
      { role: 'about', label: t({ zh: '关于 ', en: 'About ' }) + APP_DISPLAY_NAME },
      ...(pushedItems('app').length ? [{ type: 'separator' }, ...pushedItems('app')] : []),
      { type: 'separator' },
      { role: 'services', label: t({ zh: '服务', en: 'Services' }) },
      { type: 'separator' },
      { role: 'hide', label: t({ zh: '隐藏 ', en: 'Hide ' }) + APP_DISPLAY_NAME },
      { role: 'hideOthers', label: t({ zh: '隐藏其他', en: 'Hide Others' }) },
      { role: 'unhide', label: t({ zh: '全部显示', en: 'Show All' }) },
      { type: 'separator' },
      { role: 'quit', label: t({ zh: '退出 ', en: 'Quit ' }) + APP_DISPLAY_NAME },
    ],
  })

  const file = optionalMenu('file', t({ zh: '文件', en: 'File' }))
  if (file) template.push(file)

  // ── 编辑：roles 恒定（mac 上没有它，所有输入框的 ⌘C/⌘V 全部失灵），
  //    后面追加渲染层的查找/替换。刻意不用 role:'editMenu' 整块——那样追加不进去。
  template.push({
    label: pushedLabel('edit', t({ zh: '编辑', en: 'Edit' })),
    submenu: [
      undoRedoMenuItem('undo'),
      undoRedoMenuItem('redo'),
      { type: 'separator' },
      { role: 'cut', label: t({ zh: '剪切', en: 'Cut' }) },
      { role: 'copy', label: t({ zh: '复制', en: 'Copy' }) },
      { role: 'paste', label: t({ zh: '粘贴', en: 'Paste' }) },
      { role: 'pasteAndMatchStyle', label: t({ zh: '粘贴并匹配样式', en: 'Paste and Match Style' }) },
      { role: 'delete', label: t({ zh: '删除', en: 'Delete' }) },
      { role: 'selectAll', label: t({ zh: '全选', en: 'Select All' }) },
      ...(pushedItems('edit').length ? [{ type: 'separator' }, ...pushedItems('edit')] : []),
    ],
  })

  const doc = optionalMenu('document', t({ zh: '文档', en: 'Document' }))
  if (doc) template.push(doc)
  const ai = optionalMenu('ai', 'AI')
  if (ai) template.push(ai)

  // ── 视图：业务项在前，系统项（重新加载/开发者工具/缩放/全屏）恒定在后。
  //    系统项是渲染层白屏时的唯一自救入口，永远不接受下发覆盖。
  template.push({
    label: pushedLabel('view', t({ zh: '视图', en: 'View' })),
    submenu: [
      ...pushedItems('view'),
      ...(pushedItems('view').length ? [{ type: 'separator' }] : []),
      reloadMenuItem(),
      { role: 'toggleDevTools', label: t({ zh: '开发者工具', en: 'Developer Tools' }) },
      { type: 'separator' },
      { role: 'resetZoom', label: t({ zh: '实际大小', en: 'Actual Size' }) },
      { role: 'zoomIn', label: t({ zh: '放大', en: 'Zoom In' }) },
      { role: 'zoomOut', label: t({ zh: '缩小', en: 'Zoom Out' }) },
      { type: 'separator' },
      { role: 'togglefullscreen', label: t({ zh: '全屏', en: 'Toggle Full Screen' }) },
    ],
  })

  const go = optionalMenu('go', t({ zh: '转到', en: 'Go' }))
  if (go) template.push(go)
  const tools = optionalMenu('tools', t({ zh: '工具', en: 'Tools' }))
  if (tools) template.push(tools)

  // ── 窗口：恒定。刻意不含 close 角色——⌘W 留给渲染层「关闭当前标签」（IDE 语义），
  //    不能让菜单加速器抢走它去关整个窗口。
  template.push({
    label: t({ zh: '窗口', en: 'Window' }),
    submenu: [
      { role: 'minimize', label: t({ zh: '最小化', en: 'Minimize' }) },
      { role: 'zoom', label: t({ zh: '缩放', en: 'Zoom' }) },
      { type: 'separator' },
      { role: 'front', label: t({ zh: '前置全部窗口', en: 'Bring All to Front' }) },
    ],
  })

  const help = optionalMenu('help', t({ zh: '帮助', en: 'Help' }))
  if (help) template.push(help)

  return template
}

function rebuild() {
  Menu.setApplicationMenu(Menu.buildFromTemplate(buildTemplate()))
}

/**
 * 原生「关于」面板的版权与许可行（AGPL §0 Appropriate Legal Notices）。
 *
 * macOS 的 { role: 'about' } 打开的是系统面板，内容取自 .app 的 Info.plist；
 * setAboutPanelOptions 可以在运行期覆盖，且 Windows/Linux 上 Electron 会用同一份
 * 数据自绘一个面板。credits 只在 macOS 生效，Windows/Linux 靠 applicationVersion
 * 那行带出许可信息，两边都不落空。
 *
 * 版本号取 app.getVersion()（打包态 = desktop/package.json 的 version，单一来源）。
 */
function applyAboutPanel() {
  const copyright = [
    '版权所有 2026 北京京微资易科技有限公司及 AI WorkDeck 贡献者',
    'Copyright 2026 Beijing Jingwei Ziyi Technology Co., Ltd. and AI WorkDeck contributors',
    '',
    '本软件依 GNU Affero General Public License v3.0 或更高版本发布，不提供任何担保。',
    'Released under the GNU AGPL v3.0 or later, with ABSOLUTELY NO WARRANTY.',
    '',
    '源代码 / Source code: https://github.com/zeweihan/aiworkdeck',
    '许可证全文 / Full license: https://github.com/zeweihan/aiworkdeck/blob/master/LICENSE',
    '商标说明 / Trademark notice: https://github.com/zeweihan/aiworkdeck/blob/master/legal/TRADEMARKS.md',
    '',
    '「AI WorkDeck」为北京京微资易科技有限公司的商标，再分发修改版时不得使用该名称作为产品名。',
  ].join('\n')
  try {
    app.setAboutPanelOptions({
      applicationName: APP_DISPLAY_NAME,
      applicationVersion: app.getVersion(),
      version: app.getVersion(),
      // dev-board BUG-49：copyright 与 credits 曾经被设成同一份文本，macOS
      // 原生「关于」面板会分别渲染标准版权行与 Credits 区块，两处内容一样
      // 就是视觉上重复了一遍。不设置 credits，AGPL 许可告示（源码/许可证/
      // 商标说明链接）仍完整保留在 copyright 里，只出现一次。
      copyright,
      website: 'https://github.com/zeweihan/aiworkdeck',
    })
  } catch (e) {
    // 面板文案不是功能，拿不到就算了，绝不让它挡住菜单初始化
    console.warn('[app-menu] setAboutPanelOptions 失败:', e && e.message)
  }
}

function initAppMenu(mainWindowGetter, opts) {
  getWindow = mainWindowGetter
  destroyAllBrowserViews = (opts && opts.destroyAllBrowserViews) || (() => {})
  applyAboutPanel()
  rebuild()
  // 语言切换只影响骨架文案；业务菜单的文案由渲染层重新下发（它自己也在换 i18n）。
  onAppLanguageChange(() => rebuild())
  ipcMain.on('checkba:menu-state', (event, payload) => {
    if (!payload || !Array.isArray(payload.menus)) return
    pushed = payload
    rebuild()
  })
}

module.exports = { initAppMenu, applyAboutPanel }
