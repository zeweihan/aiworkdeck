// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「文件」菜单：案卷与文件的进出口。字段含义见同目录 README.md。

export const FILE_COMMANDS = [
  {
    id: 'file.newProject',
    label: { zh: '新建项目…', en: 'New Project…' },
    accel: 'Shift+CmdOrCtrl+N',
    menu: 'file', group: 1,
    run: 'app:newProject',
  },
  {
    id: 'file.openFolder',
    label: { zh: '打开文件夹…', en: 'Open Folder…' },
    accel: 'CmdOrCtrl+O',
    menu: 'file', group: 1,
    run: 'app:openFolder',
  },
  {
    id: 'file.openFile',
    label: { zh: '打开文件…', en: 'Open File…' },
    accel: 'Shift+CmdOrCtrl+O',
    menu: 'file', group: 1,
    run: 'app:openFile',
  },
  // 「打开最近」是动态子菜单，由 appMenuBridge 用 state.recent 现场生成，
  // 不在这张静态表里——它的条目数随用户走。占位靠 buildMenus 的 group 顺序。

  // 新建事项（dev-board#899）：开工作台唯一的 TaskDialog，项目锁定为当前项目
  {
    id: 'task.new',
    label: { zh: '新建事项…', en: 'New Task…' },
    menu: 'file', group: 3,
    when: ['workbench', 'project'],
    run: 'wb:newTask',
  },
  {
    id: 'file.importFiles',
    label: { zh: '导入文件到项目…', en: 'Import Files…' },
    menu: 'file', group: 3,
    when: ['workbench', 'project'],
    run: 'wb:importFiles',
  },
  {
    id: 'file.revealInFinder',
    label: { zh: '在访达中显示', en: 'Reveal in Finder' },
    menu: 'file', group: 3,
    when: ['workbench', 'project'],
    run: 'wb:revealInFinder',
  },
  {
    id: 'file.share',
    label: { zh: '发送…', en: 'Send…' },
    menu: 'file', group: 3,
    when: ['workbench', 'tab'],
    run: 'wb:shareFile',
  },

  // 导出为 PDF…（dev-board#886/BUG-34）：应用自己的「文件」菜单原来没有导出/
  // 保存项，只能靠工具栏 PDF 图标或引擎自己的原生菜单——薄转发到已经接通的
  // LibreOfficeEditor.exportPdf()，跟工具栏那颗图标走同一条代码路径。
  {
    id: 'file.exportPdf',
    label: { zh: '导出为 PDF…', en: 'Export as PDF…' },
    menu: 'file', group: 4,
    when: ['workbench', 'docTab'],
    run: 'wb:exportPdf',
  },
  {
    id: 'file.closeTab',
    label: { zh: '关闭标签', en: 'Close Tab' },
    accel: 'CmdOrCtrl+W',
    menu: 'file', group: 5,
    when: ['workbench', 'tab'],
    run: 'wb:closeTab',
  },
  {
    id: 'file.closeProject',
    label: { zh: '关闭项目', en: 'Close Project' },
    menu: 'file', group: 5,
    when: ['workbench', 'project'],
    run: 'app:closeProject',
  },
]
