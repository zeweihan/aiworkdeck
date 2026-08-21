// EvidenceLink 定位符（spec §1.4）与 filelink 链接的纯函数：摘要文案、解包、封装。
// 链接形态：文档里写 `<base>?u=<encode(checkba://filelink?k=<linkKey>&projectId=<pid>[&t=<targetId>])>`
// （web 包装不变，t 为可选 targetId）。

export const FILELINK_SCHEME = 'checkba://filelink'

export const EVIDENCE_METHODS = ['written_review', 'written_statement', 'web_check', 'third_party', 'interview']

function fmtMs(ms) {
  const total = Math.max(0, Math.floor(Number(ms || 0) / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${m}:${pad(s)}`
}

function hostOf(url) {
  try { return new URL(String(url)).host || String(url) } catch (e) { return String(url) }
}

// t(key, params) 由调用方注入（组件里传 this.$t），便于纯函数测试。
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

// 解包：接受包装 https 链接、裸 checkba://filelink、以及 checkba:/filelink 这种单斜杠写法。
// 非 filelink 或缺 k → null。t 解析成 Number（非法/缺省 → null）。
export function parseFileLinkUrl(raw) {
  let s = raw == null ? '' : String(raw).trim()
  if (!s) return null
  try {
    if (/^https?:\/\//i.test(s)) {
      const q = s.includes('?') ? s.slice(s.indexOf('?') + 1) : ''
      const inner = new URLSearchParams(q).get('u')
      if (!inner) return null
      s = decodeURIComponent(String(inner))
    }
  } catch (e) { return null }
  if (!/^checkba:/i.test(s)) return null
  s = s.replace(/^checkba:\/*/i, 'checkba://')
  if (!s.startsWith(FILELINK_SCHEME)) return null
  const q = s.includes('?') ? s.slice(s.indexOf('?') + 1) : ''
  const p = new URLSearchParams(q)
  const linkKey = p.get('k') || ''
  if (!linkKey) return null
  const tRaw = p.get('t')
  const t = tRaw != null && tRaw !== '' && /^\d+$/.test(tRaw) ? Number(tRaw) : null
  return { linkKey, projectId: p.get('projectId') || '', targetId: t }
}

// 反向：base 为空时返回裸 checkba:// 链接（测试/非 web 包装场景）。
export function buildFileLinkUrl(base, linkKey, projectId, targetId) {
  let inner = `${FILELINK_SCHEME}?k=${encodeURIComponent(String(linkKey))}&projectId=${encodeURIComponent(String(projectId == null ? '' : projectId))}`
  if (targetId != null && targetId !== '') inner += `&t=${encodeURIComponent(String(targetId))}`
  if (!base) return inner
  return `${base}?u=${encodeURIComponent(inner)}`
}
