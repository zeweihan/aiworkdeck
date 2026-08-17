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

const { Menu, ipcMain } = require('electron')
const { t, onAppLanguageChange } = require('./app-language')

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

/** 有内容才成为一个顶级菜单——渲染层没就绪时不该出现一堆空菜单。 */
function optionalMenu(menuId, fallbackLabel) {
  const items = pushedItems(menuId)
  if (!items.length) return null
  return { label: pushedLabel(menuId, fallbackLabel), submenu: items }
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
      { role: 'undo', label: t({ zh: '撤销', en: 'Undo' }) },
      { role: 'redo', label: t({ zh: '重做', en: 'Redo' }) },
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
      { role: 'reload', label: t({ zh: '重新加载', en: 'Reload' }) },
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

function initAppMenu(mainWindowGetter) {
  getWindow = mainWindowGetter
  rebuild()
  // 语言切换只影响骨架文案；业务菜单的文案由渲染层重新下发（它自己也在换 i18n）。
  onAppLanguageChange(() => rebuild())
  ipcMain.on('checkba:menu-state', (event, payload) => {
    if (!payload || !Array.isArray(payload.menus)) return
    pushed = payload
    rebuild()
  })
}

module.exports = { initAppMenu }
