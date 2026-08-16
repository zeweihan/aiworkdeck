// 「工具」菜单：动作类小工具。与「视图」的分工是——视图管面板显隐，工具管做事。
//
// 截图取词用 Alt+CmdOrCtrl+4 而不是 Shift+Cmd+4：后者是 macOS 系统截图，
// 系统快捷键优先级高于应用菜单，我们根本收不到（spec §4.2）。

export const TOOLS_COMMANDS = [
  {
    id: 'tools.ocrCapture',
    label: { zh: '截图取词', en: 'Screen Capture OCR' },
    accel: 'Alt+CmdOrCtrl+4',
    menu: 'tools', group: 1,
    when: ['workbench', 'notClient'],
    run: 'wb:ocrCapture',
  },
  {
    id: 'tools.newBrowserTab',
    label: { zh: '新建浏览器标签', en: 'New Browser Tab' },
    menu: 'tools', group: 1,
    when: ['workbench', 'notClient'],
    run: 'wb:openBrowserTab',
  },
  {
    id: 'tools.activityRecord',
    label: { zh: '活动记录', en: 'Activity Recording' },
    menu: 'tools', group: 1,
    type: 'checkbox', checked: 'recording',
    when: ['workbench', 'notClient'],
    run: 'wb:toggleRecording',
  },

  {
    id: 'tools.feedback',
    label: { zh: '反馈…', en: 'Send Feedback…' },
    menu: 'tools', group: 3,
    run: 'app:openFeedback',
  },
]
