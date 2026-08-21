// 编辑器工具栏「插入链接」的地址整形：用户习惯只敲 www.example.com，worker set_selection_hyperlink
// 现在校验 scheme（https?:// / checkba:// / mailto:），所以无 scheme 时在这里补 https://。
// 返回 '' 表示整形后仍不合法（含不放行的 scheme，如 file: / javascript:），由调用方用本地化文案报错。
const ALLOWED = /^(https?:\/\/|checkba:\/\/|mailto:)/i
const HAS_SCHEME = /^[a-z][a-z0-9+.-]*:/i

export function normalizeLinkUrl(raw) {
  const url = String(raw || '').trim()
  if (!url) return ''
  if (ALLOWED.test(url)) return url
  if (HAS_SCHEME.test(url)) return ''
  // 裸邮箱视作 mailto:
  if (/^[^\s@/]+@[^\s@/]+\.[^\s@/]+$/.test(url)) return 'mailto:' + url
  return 'https://' + url
}
