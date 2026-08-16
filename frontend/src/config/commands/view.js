// 「视图」菜单的**业务部分**：面板显隐与底部工具。
//
// 「打开视图」子菜单（资源管理器/搜索/版本记录/诉讼可视化…）是动态的——rail 上
// 有哪些入口取决于角色与已启用的 skill，还会被动态插件追加，所以由
// appMenuBridge 用 state.views 现场生成，不写死在这里。
//
// 重新加载 / 开发者工具 / 缩放 / 全屏那一段由主进程常驻持有，**不经这里下发**：
// 渲染层白屏时如果菜单也跟着没了，用户连「重新加载」都点不到（spec §6.1）。

export const VIEW_COMMANDS = [
  {
    id: 'view.sidebar',
    label: { zh: '左栏', en: 'Sidebar' },
    accel: 'Alt+CmdOrCtrl+B',
    menu: 'view', group: 1,
    type: 'checkbox', checked: 'sidebarOpen',
    when: ['workbench'],
    run: 'wb:toggleSidebar',
  },
  {
    id: 'view.toolsPanel',
    label: { zh: '底部工具', en: 'Bottom Panel' },
    accel: 'Alt+CmdOrCtrl+J',
    menu: 'view', group: 1,
    type: 'checkbox', checked: 'toolsPanelOpen',
    when: ['workbench', 'notClient'],
    run: 'wb:toggleToolsPanel',
  },
  {
    id: 'view.aiPanel',
    label: { zh: 'AI 面板', en: 'AI Panel' },
    accel: 'Alt+CmdOrCtrl+I',
    menu: 'view', group: 1,
    type: 'checkbox', checked: 'aiPanelOpen',
    when: ['workbench', 'notClient'],
    run: 'wb:toggleAiPanel',
  },
  {
    id: 'view.split',
    label: { zh: '分屏', en: 'Split Editor' },
    accel: 'Alt+CmdOrCtrl+\\',
    menu: 'view', group: 1,
    type: 'checkbox', checked: 'splitMode',
    when: ['workbench', 'notClient'],
    run: 'wb:toggleSplit',
  },

  // group 2 留给动态生成的「打开视图」子菜单

  {
    id: 'view.toolVariables',
    label: { zh: '变量库', en: 'Variables' },
    menu: 'view', group: 3,
    type: 'checkbox', checked: 'toolVariables',
    when: ['workbench', 'notClient'],
    run: 'wb:openTool:variables',
  },
  {
    id: 'view.toolFavorites',
    label: { zh: '收藏夹', en: 'Favorites' },
    menu: 'view', group: 3,
    type: 'checkbox', checked: 'toolFavorites',
    when: ['workbench', 'notClient'],
    run: 'wb:openTool:favorites',
  },
  {
    id: 'view.toolClipboard',
    label: { zh: '剪贴板', en: 'Clipboard' },
    menu: 'view', group: 3,
    type: 'checkbox', checked: 'toolClipboard',
    when: ['workbench', 'notClient'],
    run: 'wb:openTool:clipboard',
  },
]
