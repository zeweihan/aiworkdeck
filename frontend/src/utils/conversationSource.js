/**
 * 会话来源角标（dev-board#298）：sourceChannel（插件镜像会话的来源通道）→ 展示文案。
 * null/空 = 本地会话，不出角标；未知非空值兜底成「插件」。
 * 历史对话抽屉（project-overview.vue）与概览页 ConversationList 共用这一份映射，
 * 不许再各写一份。
 */
import { t } from '@/i18n'

const LABEL_KEYS = {
  'office-word': 'common.sourceChannelOfficeWord',
  'office-excel': 'common.sourceChannelOfficeExcel',
  'office-powerpoint': 'common.sourceChannelOfficePowerpoint',
  'wps-word': 'common.sourceChannelWpsWord',
  'wps-excel': 'common.sourceChannelWpsExcel',
  'wps-powerpoint': 'common.sourceChannelWpsPowerpoint',
}

export function sourceChannelLabel(sourceChannel) {
  if (!sourceChannel) return ''
  return t(LABEL_KEYS[sourceChannel] || 'common.sourceChannelPlugin')
}
