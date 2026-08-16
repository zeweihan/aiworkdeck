// 「AI」菜单。整条菜单对客户视图不可见（notClient）——这是安全边界不是排版偏好，
// 见 spec §6.3：客户不该看到 AI、系统设置、插件广场这些入口的存在。
//
// 「停止当前任务」刻意不给加速键：Esc 一旦成为菜单加速键就会吞掉编辑器和所有
// 输入框的 Esc（spec §4.2 硬规则）。

export const AI_COMMANDS = [
  {
    id: 'ai.newChat',
    label: { zh: '新建对话', en: 'New Conversation' },
    accel: 'Alt+CmdOrCtrl+N',
    menu: 'ai', group: 1,
    when: ['workbench', 'notClient'],
    run: 'wb:newChat',
  },
  {
    id: 'ai.stop',
    label: { zh: '停止当前任务', en: 'Stop Current Task' },
    menu: 'ai', group: 1,
    when: ['workbench', 'notClient', 'aiRunning'],
    run: 'wb:stopAi',
  },

  {
    id: 'ai.modeAsk',
    label: { zh: '模式：问答', en: 'Mode: Ask' },
    menu: 'ai', group: 2,
    type: 'checkbox', checked: 'aiModeAsk',
    when: ['workbench', 'notClient'],
    run: 'wb:setAiMode:ASK',
  },
  {
    id: 'ai.modePlan',
    label: { zh: '模式：计划', en: 'Mode: Plan' },
    menu: 'ai', group: 2,
    type: 'checkbox', checked: 'aiModePlan',
    when: ['workbench', 'notClient'],
    run: 'wb:setAiMode:PLAN',
  },
  {
    id: 'ai.modeAgent',
    label: { zh: '模式：智能体', en: 'Mode: Agent' },
    menu: 'ai', group: 2,
    type: 'checkbox', checked: 'aiModeAgent',
    when: ['workbench', 'notClient'],
    run: 'wb:setAiMode:AGENT',
  },

  {
    id: 'ai.history',
    label: { zh: '对话历史', en: 'Conversation History' },
    menu: 'ai', group: 3,
    when: ['workbench', 'notClient'],
    run: 'wb:openChatHistory',
  },

  {
    id: 'ai.pluginMarket',
    label: { zh: '插件广场', en: 'Plugin Marketplace' },
    menu: 'ai', group: 4,
    when: ['workbench', 'notClient'],
    run: 'wb:openPluginMarket',
  },
]
