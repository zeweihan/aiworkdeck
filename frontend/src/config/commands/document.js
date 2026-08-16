// 「文档」菜单：这一条是本次菜单改造的正面回答。
//
// 律师每天的动词是修订、批注、定稿、回退——在此之前这些一个都不在菜单栏里，
// 全藏在编辑器工具条深处。菜单栏是发现性最强的地方，也是快捷键的声明处。
//
// 全部条目都要求 docTab：Calc/Impress 没有修订机制（LibreOfficeEditor 的
// showsReview 已有同样判断），标签不是 Writer 文档时整条菜单置灰。
//
// 插入表格/图片/脚注这轮不进菜单：它们要走工具栏自己的对话框流程，接线成本高
// 而工具栏上本来就有入口，价值不抵成本。菜单先覆盖「修订、批注、定稿、回退」
// 这条律师每天真正在走的链。

export const DOCUMENT_COMMANDS = [
  {
    id: 'doc.trackChanges',
    label: { zh: '修订模式', en: 'Track Changes' },
    accel: 'Alt+CmdOrCtrl+R',
    menu: 'document', group: 1,
    type: 'checkbox', checked: 'trackChanges',
    when: ['workbench', 'docTab'],
    run: 'wb:toggleTrackChanges',
  },
  {
    id: 'doc.reviewPanel',
    label: { zh: '审阅面板', en: 'Review Panel' },
    accel: 'Alt+CmdOrCtrl+E',
    menu: 'document', group: 1,
    type: 'checkbox', checked: 'reviewOpen',
    when: ['workbench', 'docTab'],
    run: 'wb:toggleReviewPanel',
  },

  {
    id: 'doc.acceptAll',
    label: { zh: '接受全部修订', en: 'Accept All Changes' },
    menu: 'document', group: 2,
    when: ['workbench', 'docTab'],
    run: 'wb:acceptAllRevisions',
  },
  {
    id: 'doc.rejectAll',
    label: { zh: '拒绝全部修订', en: 'Reject All Changes' },
    menu: 'document', group: 2,
    when: ['workbench', 'docTab'],
    run: 'wb:rejectAllRevisions',
  },

  {
    id: 'doc.insertComment',
    label: { zh: '插入批注', en: 'Insert Comment' },
    accel: 'Alt+CmdOrCtrl+M',
    menu: 'document', group: 3,
    when: ['workbench', 'docTab'],
    run: 'wb:insertComment',
  },

  {
    id: 'doc.clearFormatting',
    label: { zh: '清除格式', en: 'Clear Formatting' },
    menu: 'document', group: 4,
    when: ['workbench', 'docTab'],
    run: 'wb:clearFormatting',
  },

  {
    id: 'doc.versionPanel',
    label: { zh: '版本记录', en: 'Version History' },
    menu: 'document', group: 5,
    when: ['workbench', 'project', 'notClient'],
    run: 'wb:openVersionPanel',
  },
]
