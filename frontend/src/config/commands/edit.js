// 「编辑」菜单的**业务补充项**。
//
// 撤销/重做/剪切/复制/粘贴/全选那一段是 Electron 的 editMenu roles，由主进程
// 常驻持有（见 desktop/main/app-menu.js 的骨架）——mac 上没有它，所有输入框的
// ⌘C/⌘V 会全部失灵，所以它不能经渲染层下发、也不能被下发的内容覆盖。
//
// 这里只放 roles 给不了的那条。它是 A 档语义同构：菜单项做的事就是编辑器里
// ⌘F 做的事，只是把它提到 app 层，焦点在 webview 里也能用。

export const EDIT_COMMANDS = [
  {
    // 编辑器的查找条本来就带替换（replaceCurrent / replaceAll），
    // 不再单开一条「查找和替换」——两条菜单项做同一件事只是噪音。
    id: 'edit.find',
    label: { zh: '查找和替换…', en: 'Find and Replace…' },
    accel: 'CmdOrCtrl+F',
    menu: 'edit', group: 1,
    when: ['workbench', 'docTab'],
    run: 'wb:find',
  },
]
