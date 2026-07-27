// 左侧侧边栏（IDE 左栏）插件位配置：集中维护，避免页面内硬编码

export const LEFT_SIDEBAR_PLUGINS = [
  {
    key: 'files',
    label: '资源管理器',
    icon: '/static/documents_unselected.png',
    activeIcon: '/static/documents_selected.png'
  },
  {
    key: 'dd-files',
    label: '尽调文件',
    icon: '/static/checklist_unselected.png',
    activeIcon: '/static/checklist_selected.png'
  },
  {
    key: 'shareholder-meeting',
    label: '股东大会',
    icon: '/static/meeting_unselected.png',
    activeIcon: '/static/meeting_selected.png'
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
    icon: '/static/MPIS-TTS.png',
    activeIcon: '/static/MPIS-TTS_selected.png'
  },
  {
    key: 'desensitize',
    label: '文件脱敏',
    svgPaths: [
      { d: "M12 22C12 22 20 18 20 12V5L12 2L4 5V12C4 18 12 22 12 22Z" }
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

