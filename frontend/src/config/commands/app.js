// 应用菜单（mac 上是最左边那个以应用名命名的菜单）的**业务补充项**。
//
// 关于 / 隐藏 / 退出 那几条是 Electron roles，由主进程常驻持有，不经这里下发。

export const APP_COMMANDS = [
  {
    id: 'app.checkUpdate',
    label: { zh: '检查更新…', en: 'Check for Updates…' },
    menu: 'app', group: 1,
    run: 'app:checkUpdate',
  },
  {
    id: 'app.settings',
    label: { zh: '设置…', en: 'Settings…' },
    accel: 'CmdOrCtrl+,',
    menu: 'app', group: 2,
    run: 'app:openSettings',
  },
  {
    id: 'app.account',
    // 文案里别用 &：Electron 把它当助记符标记吃掉（真机实测菜单显示成
    // 「Account  License…」，中间空两格）。要么写 &&，要么换词——换词更省事。
    label: { zh: '账户与授权…', en: 'Account and License…' },
    menu: 'app', group: 2,
    run: 'app:openAccount',
  },
  {
    id: 'app.logout',
    label: { zh: '退出登录…', en: 'Log Out…' },
    menu: 'app', group: 2,
    run: 'app:logout',
  },
  {
    id: 'app.langZh',
    label: { zh: '语言：中文', en: 'Language: 中文' },
    menu: 'app', group: 3,
    type: 'checkbox', checked: 'langZh',
    run: 'app:setLang:zh-CN',
  },
  {
    id: 'app.langEn',
    label: { zh: '语言：English', en: 'Language: English' },
    menu: 'app', group: 3,
    type: 'checkbox', checked: 'langEn',
    run: 'app:setLang:en-US',
  },
]
