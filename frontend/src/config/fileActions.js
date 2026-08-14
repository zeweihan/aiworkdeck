// 文件相关前端展示配置（集中维护，避免组件内硬编码文案/选项）

import { t } from '@/i18n'

export const FILE_BATCH_ACTIONS = [
  { key: 'move', label: t('config.fileActions.move'), className: '' },
  { key: 'cut', label: t('config.fileActions.cut'), className: '' },
  { key: 'copy', label: t('config.fileActions.copy'), className: '' },
  { key: 'delete', label: t('config.fileActions.delete'), className: 'batch-btn-danger' },
]

export const FILE_BATCH_CANCEL_LABEL = t('config.fileActions.cancel')

// 左侧文件树：快捷操作（集中维护，避免组件内硬编码）
// 说明：icon 采用简洁符号，尽量避免 emoji
export const FILE_TREE_QUICK_ACTIONS = [
  { key: 'newFile', label: t('config.fileActions.newFile'), title: t('config.fileActions.newFile'), iconPath: '/static/new-document_unselected.png', activeIconPath: '/static/new-document.png' },
  { key: 'newFolder', label: t('config.fileActions.newFolder'), title: t('config.fileActions.newFolder'), iconPath: '/static/icon_new_folder_unselected.png', activeIconPath: '/static/icon_new_folder.png' },
  { key: 'upload', label: t('config.fileActions.uploadFile'), title: t('config.fileActions.uploadFile'), iconPath: '/static/upload_unselected.png', activeIconPath: '/static/upload.png' },
  { key: 'sort', label: t('config.fileActions.sort'), title: t('config.fileActions.sort'), iconPath: '/static/sort_unselected.png', activeIconPath: '/static/sort.png' },
]


