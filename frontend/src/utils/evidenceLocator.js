// evidenceLocator.js — EvidenceLink target.locator 的展示摘要与 filelink URL 解包。
// locator 分型见 spec §1.4：页码 1 基、paragraphIndex 0 基、坐标 0..1、毫秒整数；
// 缺 type = 整个文件。t 是 i18n 翻译函数（组件传 this.$t，非组件传 i18n 的 t）。

function fmtMs(ms) {
  const total = Math.max(0, Math.floor(Number(ms || 0) / 1000))
  const m = Math.floor(total / 60)
  const s = total % 60
  return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
}

function hostOf(url) {
  try { return new URL(String(url)).host || String(url) } catch (e) { return String(url) }
}

export function locatorSummary(loc, t) {
  if (!loc || !loc.type) return t('evidence.loc.wholeFile')
  switch (loc.type) {
    case 'pdf': return loc.page ? t('evidence.loc.page', { page: loc.page }) : t('evidence.loc.wholeFile')
    case 'docx': return loc.quote ? t('evidence.loc.quote', { quote: String(loc.quote).slice(0, 20) }) : t('evidence.loc.wholeFile')
    case 'image': return loc.rect ? t('evidence.loc.region') : t('evidence.loc.wholeFile')
    case 'media': return t('evidence.loc.time', { time: fmtMs(loc.startMs) })
    case 'web': return loc.url ? t('evidence.loc.web', { host: hostOf(loc.url) }) : t('evidence.loc.wholeFile')
    case 'sheet': return t('evidence.loc.cell', { sheet: loc.sheet || '', cell: loc.cell || '' })
    default: return t('evidence.loc.wholeFile')
  }
}

/** 解包 https://checkba-internal.local/open?u=checkba://filelink?k=&projectId=&t= ；非法返回 null。 */
export function parseFileLinkUrl(raw) {
  const u = String(raw || '')
  if (!u) return null
  let inner = u
  if (/^https?:\/\//i.test(u)) {
    try {
      const q = u.includes('?') ? u.slice(u.indexOf('?') + 1) : ''
      inner = new URLSearchParams(q).get('u') || ''
    } catch (e) { return null }
  }
  if (!/^checkba:\/\/filelink/i.test(inner)) return null
  const qs = inner.includes('?') ? inner.slice(inner.indexOf('?') + 1) : ''
  const p = new URLSearchParams(qs)
  const linkKey = p.get('k') || ''
  if (!linkKey) return null
  const tRaw = p.get('t')
  const targetId = tRaw && /^\d+$/.test(tRaw) ? Number(tRaw) : null
  return { linkKey, projectId: p.get('projectId') || '', targetId }
}

export function buildFileLinkUrl(base, linkKey, projectId, targetId) {
  let inner = `checkba://filelink?k=${encodeURIComponent(linkKey)}&projectId=${encodeURIComponent(String(projectId || ''))}`
  if (targetId != null) inner += `&t=${encodeURIComponent(String(targetId))}`
  return base ? `${base}${base.includes('?') ? '&' : '?'}u=${encodeURIComponent(inner)}` : inner
}
