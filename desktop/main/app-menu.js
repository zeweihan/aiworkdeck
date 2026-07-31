// IDE 化应用菜单：File 全套（打开文件夹/打开文件/新建项目文件夹/最近打开）+ 标准编辑/视图/窗口。
// 菜单动作经 'checkba:menu-action' 发给渲染层处理（导航与系统对话框流程都在前端）；
// 「最近打开」子菜单由渲染层经 'checkba:recent-projects' 推送后整体重建。
// 注意：窗口子菜单刻意不含 close 角色——Cmd+W 留给渲染层「关闭当前标签」（IDE 语义），
// 不能让菜单加速器抢走它去关整个窗口。

const { app, Menu, ipcMain } = require('electron')

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
        label: String(r.name || ('项目 ' + r.id)),
        click: () => send('open-recent', { projectId: r.id }),
      }))
    : [{ label: '暂无最近项目', enabled: false }]

  return [
    {
      label: app.name,
      submenu: [
        { role: 'about', label: '关于 ' + app.name },
        { type: 'separator' },
        { role: 'hide', label: '隐藏 ' + app.name },
        { role: 'hideOthers', label: '隐藏其他' },
        { role: 'unhide', label: '全部显示' },
        { type: 'separator' },
        { role: 'quit', label: '退出 ' + app.name },
      ],
    },
    {
      label: '文件',
      submenu: [
        { label: '打开文件夹…', accelerator: 'CmdOrCtrl+O', click: () => send('open-folder') },
        { label: '打开文件…', accelerator: 'CmdOrCtrl+Shift+O', click: () => send('open-file') },
        { label: '新建项目文件夹…', accelerator: 'CmdOrCtrl+Shift+N', click: () => send('create-folder') },
        { type: 'separator' },
        { label: '最近打开', submenu: recentSubmenu },
      ],
    },
    // 标准编辑角色：mac 上没有它，所有输入框的 Cmd+C/V/X/Z 全部失灵
    { label: '编辑', role: 'editMenu' },
    {
      label: '视图',
      submenu: [
        { role: 'reload', label: '重新加载' },
        { role: 'toggleDevTools', label: '开发者工具' },
        { type: 'separator' },
        { role: 'resetZoom', label: '实际大小' },
        { role: 'zoomIn', label: '放大' },
        { role: 'zoomOut', label: '缩小' },
        { type: 'separator' },
        { role: 'togglefullscreen', label: '全屏' },
      ],
    },
    {
      label: '窗口',
      submenu: [
        { role: 'minimize', label: '最小化' },
        { role: 'zoom', label: '缩放' },
        { type: 'separator' },
        { role: 'front', label: '前置全部窗口' },
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
  ipcMain.on('checkba:recent-projects', (event, list) => {
    recentProjects = Array.isArray(list)
      ? list.filter((r) => r && r.id).slice(0, 8)
      : []
    rebuild()
  })
}

module.exports = { initAppMenu }
