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

