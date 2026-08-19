// 左侧侧边栏（IDE 左栏）插件位配置：集中维护，避免页面内硬编码

import { t } from '@/i18n'

/**
 * 尽调文件：对普通用户隐藏（2026-08-19）。照股东大会下线的先例——入口移除即等于
 * 功能隐藏，DdFilesPanel.vue / /api/dd/* / 后端 controller 与实体一律保留不动。
 *
 * **但它不能从这个文件里删掉**：CLIENT（客户访问码进来的那一档）只看得见尽调文件，
 * getPluginsForUser('CLIENT') 必须仍然拿得到这一项。所以定义留在这里、只是不进
 * LEFT_SIDEBAR_PLUGINS 数组。想对律师也恢复的话，把它加回数组即可。
 */
export const DD_FILES_PLUGIN = {
  key: 'dd-files',
  label: t('config.sidebar.ddFiles'),
  svgPaths: [
    { d: 'M3 5l1.5 1.5L7 4' },
    { d: 'M3 12l1.5 1.5L7 10.5' },
    { d: 'M3 19l1.5 1.5L7 17.5' },
    { d: 'M11 6h10' },
    { d: 'M11 12h10' },
    { d: 'M11 18h10' }
  ]
}

/**
 * rail 从上到下的顺序就是这个数组的顺序（项目概览在最前，它是这个项目的门面）。
 * 底部那一组（暂存区、成员堆叠）不在这里，它们由模板在 spacer 之后单独渲染。
 */
export const LEFT_SIDEBAR_PLUGINS = [
  {
    // 项目概览：2026-08-19 起在**左栏**展示（此前是中栏标签，维护者认为交互混乱）。
    // 走的是和其它面板完全一样的 toggleLeftPane 语义，因此它就是数组里的普通一项。
    key: 'home',
    label: t('config.sidebar.projectHome'),
    svgPaths: [
      { d: 'M3 22h18' },
      { d: 'M6 18v-7' },
      { d: 'M10 18v-7' },
      { d: 'M14 18v-7' },
      { d: 'M18 18v-7' },
      { d: 'M11.1 2.2a2 2 0 0 1 1.8 0l7.9 3.85c.47.23.3.95-.23.95H3.43c-.53 0-.7-.72-.22-.95L11.1 2.2Z' }
    ]
  },
  {
    key: 'files',
    label: t('config.sidebar.files'),
    svgPaths: [
      { d: 'M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7l-5-5Z' },
      { d: 'M15 2v5h5' },
      { d: 'M9 13h6' },
      { d: 'M9 17h6' }
    ]
  },
  {
    key: 'search',
    label: t('config.sidebar.search'),
    svgPaths: [
      { d: "M11 19C15.4183 19 19 15.4183 19 11C19 6.58172 15.4183 3 11 3C6.58172 3 3 6.58172 3 11C3 15.4183 6.58172 19 11 19Z" },
      { d: "M21 21L16.65 16.65" }
    ]
  },
  {
    // 插件中心：原来单挂在 rail 底部（goToPluginMarket）。它和其它面板一样只是
    // toggleLeftPane('market')，没有任何特殊性，收进数组后 rail 顺序才有单一出处。
    key: 'market',
    label: t('config.sidebar.market'),
    svgPaths: [
      { d: 'M4 4h7v7H4z' },
      { d: 'M4 13h7v7H4z' },
      { d: 'M13 13h7v7h-7z' },
      { d: 'M14.5 2.5h7v7h-7z' }
    ]
  },
  {
    // 语音：语音合成 + 会议录音的合并入口（2026-08-19）。两者都是语音功能，
    // 各占一个 rail 位纯属浪费；面板内部用两个 tab 切换，组件本身一行没改。
    //
    // 入口常显（语音合成本来就没有门控）；「会议录音」那个 tab 仅在
    // meeting-recorder skill 启用时出现，门控判据仍是 PANEL_SKILL_IDS。
    //
    // 路由键是新的 'voice'——uni.storage 里 leftPaneKey 的存量值可能是 'easyvoice'
    // 或 'meeting-recorder'，由 migrateLeftPaneKey() 兜底映射过来。
    key: 'voice',
    label: t('config.sidebar.voice'),
    svgPaths: [
      { d: 'M2 10v4' },
      { d: 'M6 6v12' },
      { d: 'M10 3v18' },
      { d: 'M14 8v8' },
      { d: 'M18 5v14' },
      { d: 'M22 10v4' }
    ]
  },
  {
    key: 'desensitize',
    label: t('config.sidebar.desensitize'),
    svgPaths: [
      { d: "M12 22C12 22 20 18 20 12V5L12 2L4 5V12C4 18 12 22 12 22Z" }
    ]
  },
  // 股东大会核查已下线（2026-08-17，维护者决定不做了）。左栏入口移除即等于功能隐藏；
  // ShareholderMeetingPanel.vue / api.js 的 /api/shareholder-meeting/* / 后端 controller
  // 与实体一律保留不动（存量案卷的数据还在库里），skill 也只改成默认不启用。
  // 想恢复的话把这一项加回来即可，不需要重写任何东西。
  {
    key: 'litigation-visual',
    label: t('config.sidebar.litigationVisual'),
    // 对应 skill.yml 的 enabled_by_default:false——默认不装，装了才出现在左栏。
    // 见 filterPluginsByEnabledSkills。
    requiresSkill: 'litigation-visual',
    // 折线 + 节点：时间轴/关系图的共同意象
    svgPaths: [
      { d: 'M3 17h18' },
      { d: 'M7 17v-4' },
      { d: 'M12 17V8' },
      { d: 'M17 17v-7' },
      { d: 'M7 11a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z' },
      { d: 'M17 8a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z' }
    ]
  },
  {
    key: 'version',
    label: t('config.sidebar.version'),
    svgPaths: [
      { d: "M12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22Z" },
      { d: "M12 7V12L15.5 14" }
    ]
  }
]

/** 不在 rail 数组里、但仍要能按 key 查到 label 的面板（CLIENT 的尽调文件） */
const OFF_RAIL_PLUGINS = [DD_FILES_PLUGIN]

export function getLeftSidebarPlugin(key) {
  return LEFT_SIDEBAR_PLUGINS.find(p => p.key === key)
    || OFF_RAIL_PLUGINS.find(p => p.key === key)
    || LEFT_SIDEBAR_PLUGINS[0]
}

export function getPluginsForUser(role) {
  if (role === 'CLIENT') {
    return [DD_FILES_PLUGIN]
  }
  return LEFT_SIDEBAR_PLUGINS
}

/**
 * uni.storage 里 `project_<id>_leftPaneKey` 的存量值映射。
 *
 * 存量安装里躺着已经不存在的 key（语音两项合并前的 easyvoice / meeting-recorder、
 * 已下线的 shareholder-meeting、对律师隐藏后的 dd-files）。不映射的话，律师下次
 * 进项目会落在一个没有任何面板分支命中的 leftPaneKey 上——左栏渲染成
 * 「加载中…」占位符，而且 rail 上没有一个按钮是高亮的，看上去就是坏了。
 *
 * CLIENT 不走这里（它的默认值另有分支，dd-files 对客户仍然有效）。
 */
const LEFT_PANE_KEY_ALIASES = {
  easyvoice: 'voice',
  'meeting-recorder': 'voice',
  'shareholder-meeting': 'files',
  'dd-files': 'files',
  'project-home': 'home',
}

export function migrateLeftPaneKey(key) {
  if (!key) return key
  return LEFT_PANE_KEY_ALIASES[key] || key
}

/**
 * 按「已启用 skill 列表」过滤插件位：条目声明了 requiresSkill 的，只有对应 skill 已启用
 * 才保留；没声明 requiresSkill 的条目不受影响。用于"默认不安装"的插件位（如诉讼可视化，
 * 对应 skill.yml 的 enabled_by_default:false）——左栏出不出现跟着 SkillRegistry 的启停
 * 状态走，不再单独维护一套插件位可见性开关。
 *
 * @param {Array} plugins 待过滤的插件位列表（通常是 getPluginsForUser() 的结果）
 * @param {Iterable<string>} enabledSkillIds 当前已启用的 skill id（如 GET /api/skills 里 enabled===true 的 id）
 */
export function filterPluginsByEnabledSkills(plugins, enabledSkillIds) {
  const enabled = enabledSkillIds instanceof Set ? enabledSkillIds : new Set(enabledSkillIds || [])
  return plugins.filter(p => !p.requiresSkill || enabled.has(p.requiresSkill))
}

/**
 * 「面板型 skill」：技术上是 skills/<id>/ 那一套，但装完之后用户看到的是左栏
 * 多了一个图标、点开是一整个面板（会议录音、诉讼可视化）。按 PR#198 定下的概念
 * 模型，长在 Railway 上、只有启用/停用的那一档叫**插件**；「生效方式三档」是
 * 对话型 skill 的概念，对面板型讲不通——面板的「生成纪要」按钮拼的 kick-off
 * prompt 要靠触发词命中，设成 manual 等于按钮点了没反应。
 *
 * 所以广场里按插件呈现（启用/停用一个开关），判据就是这里的 requiresSkill——
 * 不另立一张表，rail 上有没有它跟广场里怎么呈现必须是同一个事实。
 *
 * meeting-recorder 是唯一的例外，得手工补上：语音两项合并后它不再占一个 rail 位，
 * 而是「语音」面板里的一个 tab，所以数组里扫不到它的 requiresSkill。它在广场里
 * 仍然是一个面板型插件（启用/停用一个开关，装了那个 tab 才出现），漏掉这一条会让
 * 广场把它当成对话型 skill 呈现「生效方式三档」——那对面板讲不通（见上）。
 */
export const PANEL_SKILL_IDS = [
  ...LEFT_SIDEBAR_PLUGINS.filter(p => p.requiresSkill).map(p => p.requiresSkill),
  'meeting-recorder',
]

export function isPanelSkill(skillId) {
  return PANEL_SKILL_IDS.includes(skillId)
}

