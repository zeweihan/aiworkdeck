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

// ────────────────────────────────────────────────────────────────────────────
// P3「底稿定位增强」：locator → 可直接渲染的形状（纯函数，
// tests/evidence/locatorGeometry.test.mjs 钉住）。
//
// locator 的坐标常来自 OCR 或外部核查服务，**缺字段是常态**：任何一步取不到可用的数，
// 一律退化成 null——调用方据此「只打开文件、什么都不画」。绝不猜、绝不补默认值：
// 补出来的框就是假高亮，比不画更坏。
// ────────────────────────────────────────────────────────────────────────────

function finiteNum(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

/** 0..1 归一化矩形：四个数都要在、w/h 必须为正；越界裁回 [0,1]，裁成零面积即判无效。 */
export function normalizeRect(r) {
  if (!r || typeof r !== 'object') return null
  const x = finiteNum(r.x)
  const y = finiteNum(r.y)
  const w = finiteNum(r.w)
  const h = finiteNum(r.h)
  if (x == null || y == null || w == null || h == null) return null
  if (!(w > 0) || !(h > 0)) return null
  // 已经在纸面内的原样返回：重算一遍 x1-x0 会引入浮点噪声（0.3 变成 0.30000000000000004）
  if (x >= 0 && y >= 0 && x + w <= 1 && y + h <= 1) return { x, y, w, h }
  const x0 = Math.min(Math.max(x, 0), 1)
  const y0 = Math.min(Math.max(y, 0), 1)
  const x1 = Math.min(Math.max(x + w, 0), 1)
  const y1 = Math.min(Math.max(y + h, 0), 1)
  if (!(x1 > x0) || !(y1 > y0)) return null
  return { x: x0, y: y0, w: x1 - x0, h: y1 - y0 }
}

/**
 * pdf 定位符 → `{page, quote, rects}`；page 1 基，非正数视为缺。
 * rects 自带 page 时只收本页的（spec §1.4 允许一条 locator 里跨页给框）。三样全缺 → null。
 */
export function parsePdfLocator(loc) {
  if (!loc || loc.type !== 'pdf') return null
  const p = finiteNum(loc.page)
  const page = p != null && p >= 1 ? Math.floor(p) : null
  const quote = typeof loc.quote === 'string' && loc.quote.trim() ? loc.quote.trim() : ''
  const rects = (Array.isArray(loc.rects) ? loc.rects : [])
    .filter((r) => {
      const rp = finiteNum(r && r.page)
      return page == null || rp == null || Math.floor(rp) === page
    })
    .map(normalizeRect)
    .filter(Boolean)
  if (page == null && !quote && rects.length === 0) return null
  return { page, quote, rects }
}

/** image 定位符 → 归一化矩形；缺 rect 或坐标非法 → null。 */
export function parseImageRect(loc) {
  if (!loc || loc.type !== 'image') return null
  return normalizeRect(loc.rect)
}

/** media 定位符 → 起播秒数；缺 startMs 或为负 → null。 */
export function parseMediaStartSec(loc) {
  if (!loc || loc.type !== 'media') return null
  const ms = finiteNum(loc.startMs)
  if (ms == null || ms < 0) return null
  return ms / 1000
}

/** 角度归一到 0/90/180/270（顺时针，与 CSS rotate 同向）。 */
export function normalizeRotation(deg) {
  const d = ((Math.round(finiteNum(deg) || 0) % 360) + 360) % 360
  return d === 90 || d === 180 || d === 270 ? d : 0
}

/** 旋转后图片外接框的显示尺寸（px）。 */
export function rotatedDisplaySize(natW, natH, scale, rotation) {
  const w = finiteNum(natW)
  const h = finiteNum(natH)
  const s = finiteNum(scale)
  if (!(w > 0) || !(h > 0) || !(s > 0)) return null
  const turned = normalizeRotation(rotation) % 180 !== 0
  return { w: (turned ? h : w) * s, h: (turned ? w : h) * s }
}

/** 归一化矩形随 90° 步进旋转，换算到「旋转后外接框」这套坐标里。 */
export function rotateNormRect(r, deg) {
  if (!r) return null
  switch (normalizeRotation(deg)) {
    case 90: return { x: 1 - r.y - r.h, y: r.x, w: r.h, h: r.w }
    case 180: return { x: 1 - r.x - r.w, y: 1 - r.y - r.h, w: r.w, h: r.h }
    case 270: return { x: r.y, y: 1 - r.x - r.w, w: r.h, h: r.w }
    default: return { x: r.x, y: r.y, w: r.w, h: r.h }
  }
}

/**
 * img 的 CSS transform（transform-origin: 0 0）。
 * **tx/ty 的口径是「旋转后外接框的左上角」**——旋转绕原点做，转完图片会跑到原点的
 * 左边/上边去，所以要按角度补一段平移把外接框推回 (tx,ty)。口径统一了，缩放锚点、
 * 居中摆放、画框换算三处才能共用同一套 tx/ty。
 */
export function imageTransform(view) {
  const size = view && rotatedDisplaySize(view.natW, view.natH, view.scale, view.rotation)
  if (!size) return ''
  const deg = normalizeRotation(view.rotation)
  const tx = finiteNum(view.tx) || 0
  const ty = finiteNum(view.ty) || 0
  const dx = deg === 90 || deg === 180 ? size.w : 0
  const dy = deg === 180 || deg === 270 ? size.h : 0
  return `translate(${tx + dx}px, ${ty + dy}px) rotate(${deg}deg) scale(${finiteNum(view.scale)})`
}

/**
 * 归一化矩形 → 视口里的像素框 `{left, top, width, height}`。
 * view = `{natW, natH, scale, tx, ty, rotation}`，与 imageTransform 同一套口径，
 * 因此缩放、平移、旋转之后画框仍然落在同一处图像内容上。
 */
export function imageRectBox(rect, view) {
  const r = normalizeRect(rect)
  const size = r && view && rotatedDisplaySize(view.natW, view.natH, view.scale, view.rotation)
  if (!size) return null
  const d = rotateNormRect(r, view.rotation)
  const tx = finiteNum(view.tx) || 0
  const ty = finiteNum(view.ty) || 0
  return {
    left: tx + d.x * size.w,
    top: ty + d.y * size.h,
    width: Math.max(2, d.w * size.w),
    height: Math.max(2, d.h * size.h),
  }
}
