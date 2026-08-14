// 工作台交互文案与内部协议集中维护（禁止在组件中硬编码）

import { t } from '@/i18n'

export const OCR_ACTION_LABELS = {
  refresh: t('config.ocr.refresh'),
  recognize: t('config.ocr.recognize'),
  download: t('config.ocr.download'),
  copy: t('config.ocr.copy'),
  insertDoc: t('config.ocr.insertDoc'),
  webLink: t('config.ocr.webLink'),
  favorite: t('config.ocr.favorite')
}

// WPS 内部超链接协议：用于拦截点击并在应用内打开
export const INTERNAL_LINK_SCHEMES = {
  fileLink: 'checkba://filelink'
}

// WPS 超链接拦截：官方 onHyperLinkOpen 更稳定地只对 http/https 生效，
// 因此文档内写入“包装后的 https 链接”，点击时由 onHyperLinkOpen 接管并打开内部链接。
export const WPS_INTERNAL_HTTP_LINK_BASE = 'https://checkba-internal.local/open'


