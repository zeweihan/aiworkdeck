// 底部常用工具配置（集中维护，避免页面/组件内硬编码）

import { t } from '@/i18n'

export const WORKBENCH_TOOLS = [
  { key: 'variables', label: t('config.tools.variables'), icon: '⌘' },
  { key: 'favorites', label: t('config.tools.favorites'), icon: '★' },
  { key: 'clipboard', label: t('config.tools.clipboard'), icon: '⎘' },
]

export function getToolByKey(key) {
  return WORKBENCH_TOOLS.find(t => t.key === key) || WORKBENCH_TOOLS[0]
}


