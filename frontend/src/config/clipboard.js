// 剪贴板面板：类型展示配置（集中维护，避免组件内硬编码）

import { t } from '@/i18n'

export const CLIPBOARD_TYPE_META = {
  TEXT: { label: t('config.clipboardTypes.text'), tone: 'neutral' },
  IMAGE: { label: t('config.clipboardTypes.image'), tone: 'info' },
  FILE: { label: t('config.clipboardTypes.file'), tone: 'info' },
}

export function getClipboardTypeMeta(type) {
  if (!type) return { label: t('config.clipboardTypes.unknown'), tone: 'neutral' }
  return CLIPBOARD_TYPE_META[type] || { label: String(type), tone: 'neutral' }
}
