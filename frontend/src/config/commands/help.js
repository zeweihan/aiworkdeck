// 「帮助」菜单。此前整条菜单不存在——新用户找不到任何入口，使用手册只在官网上。

export const HELP_COMMANDS = [
  {
    id: 'help.manual',
    label: { zh: '使用手册', en: 'User Guide' },
    menu: 'help', group: 1,
    run: 'app:openUrl:https://aiworkdeck.com/start',
  },
  {
    id: 'help.shortcuts',
    label: { zh: '快捷键速查', en: 'Keyboard Shortcuts' },
    menu: 'help', group: 1,
    run: 'app:showShortcuts',
  },
  {
    id: 'help.website',
    label: { zh: '官网', en: 'Website' },
    menu: 'help', group: 1,
    run: 'app:openUrl:https://aiworkdeck.com',
  },

  {
    id: 'help.viewLogs',
    label: { zh: '查看日志', en: 'View Logs' },
    menu: 'help', group: 2,
    run: 'app:viewLogs',
  },
  {
    id: 'help.reportIssue',
    label: { zh: '报告问题…', en: 'Report an Issue…' },
    menu: 'help', group: 2,
    run: 'app:openFeedback',
  },
]
