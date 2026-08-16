// IDE 化应用菜单：File 全套（打开文件夹/打开文件/新建项目文件夹/最近打开）+ 标准编辑/视图/窗口。
// 菜单动作经 'checkba:menu-action' 发给渲染层处理（导航与系统对话框流程都在前端）；
// 「最近打开」子菜单由渲染层经 'checkba:recent-projects' 推送后整体重建。
// 注意：窗口子菜单刻意不含 close 角色——Cmd+W 留给渲染层「关闭当前标签」（IDE 语义），
// 不能让菜单加速器抢走它去关整个窗口。
// 菜单文案随应用语言（app-language.js）双语；语言切换经 onAppLanguageChange 整体重建。

const { Menu, ipcMain } = require('electron')
const { t, onAppLanguageChange } = require('./app-language')

// 菜单里的应用名写死，**不要用 app.name**：desktop/package.json 没有顶层 productName，
// Electron 于是拿 name 字段当 app.name，菜单会显示「关于 aiworkdeck-desktop」。
// 而补一个顶层 productName 会连带把 app.getPath('userData') 从
// ~/Library/Application Support/aiworkdeck-desktop 改名到「AI Workdeck」，
// 存量用户的 Local Storage（登录态、uni 存储）当场全丢——不值得为一个显示名冒这个险。
// 注：macOS 菜单栏最左边那个粗体应用名不由这里决定，它取自运行中 .app 包的
// CFBundleName（打包版=AI Workdeck；dev 跑 node_modules 里的 Electron.app，
// 见 scripts/brand-dev-electron.js）。
const APP_DISPLAY_NAME = 'AI Workdeck'

let getWindow = () => null
let recentProjects = []

function send(action, payload) {
  const win = getWindow()
  if (!win || win.isDestroyed()) return
  win.webContents.send('checkba:menu-action', Object.assign({ action }, payload || {}))
}

function buildTemplate() {
  const recentSubmenu = recentProjects.length
    ? recentProjects.map((r) => ({
        label: String(r.name || (t({ zh: '项目 ', en: 'Project ' }) + r.id)),
        click: () => send('open-recent', { projectId: r.id }),
      }))
    : [{ label: t({ zh: '暂无最近项目', en: 'No Recent Projects' }), enabled: false }]

  return [
    {
      label: APP_DISPLAY_NAME,
      submenu: [
        { role: 'about', label: t({ zh: '关于 ', en: 'About ' }) + APP_DISPLAY_NAME },
        { type: 'separator' },
        { role: 'hide', label: t({ zh: '隐藏 ', en: 'Hide ' }) + APP_DISPLAY_NAME },
        { role: 'hideOthers', label: t({ zh: '隐藏其他', en: 'Hide Others' }) },
        { role: 'unhide', label: t({ zh: '全部显示', en: 'Show All' }) },
        { type: 'separator' },
        { role: 'quit', label: t({ zh: '退出 ', en: 'Quit ' }) + APP_DISPLAY_NAME },
      ],
    },
    {
      label: t({ zh: '文件', en: 'File' }),
      submenu: [
        { label: t({ zh: '打开文件夹…', en: 'Open Folder…' }), accelerator: 'CmdOrCtrl+O', click: () => send('open-folder') },
        { label: t({ zh: '打开文件…', en: 'Open File…' }), accelerator: 'CmdOrCtrl+Shift+O', click: () => send('open-file') },
        { label: t({ zh: '新建项目文件夹…', en: 'New Project Folder…' }), accelerator: 'CmdOrCtrl+Shift+N', click: () => send('create-folder') },
        { type: 'separator' },
        { label: t({ zh: '最近打开', en: 'Open Recent' }), submenu: recentSubmenu },
      ],
    },
    // 标准编辑角色：mac 上没有它，所有输入框的 Cmd+C/V/X/Z 全部失灵
    { label: t({ zh: '编辑', en: 'Edit' }), role: 'editMenu' },
    {
      label: t({ zh: '视图', en: 'View' }),
      submenu: [
        { role: 'reload', label: t({ zh: '重新加载', en: 'Reload' }) },
        { role: 'toggleDevTools', label: t({ zh: '开发者工具', en: 'Developer Tools' }) },
        { type: 'separator' },
        { role: 'resetZoom', label: t({ zh: '实际大小', en: 'Actual Size' }) },
        { role: 'zoomIn', label: t({ zh: '放大', en: 'Zoom In' }) },
        { role: 'zoomOut', label: t({ zh: '缩小', en: 'Zoom Out' }) },
        { type: 'separator' },
        { role: 'togglefullscreen', label: t({ zh: '全屏', en: 'Toggle Full Screen' }) },
      ],
    },
    {
      label: t({ zh: '窗口', en: 'Window' }),
      submenu: [
        { role: 'minimize', label: t({ zh: '最小化', en: 'Minimize' }) },
        { role: 'zoom', label: t({ zh: '缩放', en: 'Zoom' }) },
        { type: 'separator' },
        { role: 'front', label: t({ zh: '前置全部窗口', en: 'Bring All to Front' }) },
      ],
    },
  ]
}

function rebuild() {
  Menu.setApplicationMenu(Menu.buildFromTemplate(buildTemplate()))
}

function initAppMenu(mainWindowGetter) {
  getWindow = mainWindowGetter
  rebuild()
  onAppLanguageChange(() => rebuild())
  ipcMain.on('checkba:recent-projects', (event, list) => {
    recentProjects = Array.isArray(list)
      ? list.filter((r) => r && r.id).slice(0, 8)
      : []
    rebuild()
  })
}

module.exports = { initAppMenu }
