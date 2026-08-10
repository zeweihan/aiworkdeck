// 左侧侧边栏（IDE 左栏）插件位配置：集中维护，避免页面内硬编码

export const LEFT_SIDEBAR_PLUGINS = [
  {
    key: 'files',
    label: '资源管理器',
    svgPaths: [
      { d: 'M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7l-5-5Z' },
      { d: 'M15 2v5h5' },
      { d: 'M9 13h6' },
      { d: 'M9 17h6' }
    ]
  },
  {
    key: 'dd-files',
    label: '尽调文件',
    svgPaths: [
      { d: 'M3 5l1.5 1.5L7 4' },
      { d: 'M3 12l1.5 1.5L7 10.5' },
      { d: 'M3 19l1.5 1.5L7 17.5' },
      { d: 'M11 6h10' },
      { d: 'M11 12h10' },
      { d: 'M11 18h10' }
    ]
  },
  {
    key: 'shareholder-meeting',
    label: '股东大会',
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
    key: 'litigation-visual',
    label: '诉讼可视化',
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
    key: 'search',
    label: '搜索',
    svgPaths: [
      { d: "M11 19C15.4183 19 19 15.4183 19 11C19 6.58172 15.4183 3 11 3C6.58172 3 3 6.58172 3 11C3 15.4183 6.58172 19 11 19Z" },
      { d: "M21 21L16.65 16.65" }
    ]
  },
  {
    key: 'easyvoice',
    label: 'EasyVoice',
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
    label: '文件脱敏',
    svgPaths: [
      { d: "M12 22C12 22 20 18 20 12V5L12 2L4 5V12C4 18 12 22 12 22Z" }
    ]
  },
  {
    key: 'version',
    label: '版本',
    svgPaths: [
      { d: "M12 22C17.5228 22 22 17.5228 22 12C22 6.47715 17.5228 2 12 2C6.47715 2 2 6.47715 2 12C2 17.5228 6.47715 22 12 22Z" },
      { d: "M12 7V12L15.5 14" }
    ]
  }
]

export function getLeftSidebarPlugin(key) {
  return LEFT_SIDEBAR_PLUGINS.find(p => p.key === key) || LEFT_SIDEBAR_PLUGINS[0]
}

export function getPluginsForUser(role) {
  if (role === 'CLIENT') {
    return [getLeftSidebarPlugin('dd-files')]
  }
  return LEFT_SIDEBAR_PLUGINS
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

