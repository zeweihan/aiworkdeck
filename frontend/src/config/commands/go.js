// 「转到」菜单：导航。VS Code 的 Go 菜单同位。
//
// 快速打开这里挂的是 Alt+CmdOrCtrl+O 而不是 ⌘P——⌘P 在 Writer 里是打印，按
// 「编辑器优先」的口径不能被菜单抢走。但工作台内**原有的 ⌘P 键位保留不动**
// （project-overview 的 keydown，焦点在编辑器里时被 webview 吞掉交给打印，
// 在编辑器外时才是快速打开）。菜单这条是额外给出的、在编辑器内也能用的路径。
//
// 上/下一个标签用 Alt+CmdOrCtrl+方向键而不是 Ctrl+Tab：Tab 永不做加速键。

export const GO_COMMANDS = [
  {
    id: 'go.quickOpen',
    label: { zh: '快速打开…', en: 'Quick Open…' },
    accel: 'Alt+CmdOrCtrl+O',
    menu: 'go', group: 1,
    when: ['workbench', 'project'],
    run: 'wb:quickOpen',
  },
  {
    id: 'go.commandPalette',
    label: { zh: '命令面板…', en: 'Command Palette…' },
    accel: 'Alt+CmdOrCtrl+P',
    menu: 'go', group: 1,
    when: ['workbench'],
    run: 'wb:commandPalette',
  },

  {
    id: 'go.projectHome',
    label: { zh: '项目概览', en: 'Project Overview' },
    menu: 'go', group: 2,
    when: ['workbench', 'project'],
    run: 'wb:goProjectHome',
  },
  {
    id: 'go.allProjects',
    label: { zh: '全部项目', en: 'All Projects' },
    menu: 'go', group: 2,
    when: ['workbench'],
    run: 'wb:goAllProjects',
  },
  // 「切换项目」动态子菜单由 appMenuBridge 用 state.recent 生成（group 3）

  {
    id: 'go.nextTab',
    label: { zh: '下一个标签', en: 'Next Tab' },
    accel: 'Alt+CmdOrCtrl+Right',
    menu: 'go', group: 4,
    when: ['workbench', 'tab'],
    run: 'wb:nextTab',
  },
  {
    id: 'go.prevTab',
    label: { zh: '上一个标签', en: 'Previous Tab' },
    accel: 'Alt+CmdOrCtrl+Left',
    menu: 'go', group: 4,
    when: ['workbench', 'tab'],
    run: 'wb:prevTab',
  },
]
