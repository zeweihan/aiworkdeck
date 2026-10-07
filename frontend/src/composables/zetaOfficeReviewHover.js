// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 正文悬停修订气泡（dev-board#1126 需求二）。鼠标悬停在正文修订标记上时，在光标
// 附近浮现轻量卡片：修订者（本人带「（我）」）、时间、类型与文本；移开或滚动即隐藏。
//
// 坐标系与 zetaOfficeReviewBalloons.place() 同一套映射：get_review_layout 的
// view（twips）+ canvas CSS 尺寸 → nativeScale/scale，鼠标 client 像素反解回文档
// twips 后与每条修订的 anchor 矩形（native.anchor x/y/width/height，twips）做命中
// 测试。纯函数拆开导出，node:test 直接覆盖（reviewHover.test.mjs）。
//
// 元数据按需补齐：balloons/margin 模式下 get_review_layout 的 items 自带作者/时间，
// inline（默认）模式只有位置，真实悬停命中后才读取该条元数据。启动、滚动及
// 文档变化只清缓存，不让后台全文扫描挡住正文的首次绘制。

export function hoverViewportMetrics(canvasRect, view) {
  if (!canvasRect || !view?.frameWidth || !view.viewport || !(view.right > view.left)) return null
  const nativeScale = canvasRect.width / view.frameWidth
  const menu = Math.max(0, canvasRect.height - view.frameHeight * nativeScale)
  const viewport = view.viewport
  const scale = viewport.width * nativeScale / (view.right - view.left)
  if (!(scale > 0)) return null
  return {
    nativeScale, scale, viewLeft: view.left, viewTop: view.top,
    originX: canvasRect.left + viewport.x * nativeScale,
    originY: canvasRect.top + menu + viewport.y * nativeScale,
  }
}

// client 像素 → 文档 twips；落在某条修订 anchor 矩形（±pad 容差）内则返回该条。
export function hitTestRevision(clientX, clientY, items, metrics, pad = 4) {
  if (!metrics || !Array.isArray(items)) return null
  const docX = (clientX - metrics.originX) / metrics.scale + metrics.viewLeft
  const docY = (clientY - metrics.originY) / metrics.scale + metrics.viewTop
  for (const item of items) {
    if (!item || item.kind !== 'revision') continue
    const w = Number(item.w) > 0 ? Number(item.w) : 60
    const h = Number(item.h) > 0 ? Number(item.h) : 240
    if (docX >= item.x - pad && docX <= item.x + w + pad && docY >= item.y - pad && docY <= item.y + h + pad) return item
  }
  return null
}

const TYPE_LABELS = {
  zh: { Insert: '插入', Delete: '删除', Format: '格式', ParagraphFormat: '段落格式', TextTable: '表格', other: '更改' },
  en: { Insert: 'Inserted', Delete: 'Deleted', Format: 'Formatting', ParagraphFormat: 'Paragraph formatting', TextTable: 'Table', other: 'Change' },
}
export function revisionTypeLabel(type, locale = 'zh') {
  const labels = TYPE_LABELS[String(locale).startsWith('en') ? 'en' : 'zh']
  const t = String(type || '')
  return labels[t] || t || labels.other
}

// 气片数据：作者与 selfAuthor（worker 的 humanAuthor）同名且非空 → 「（我）」。
export function revisionHoverCard(item, selfAuthor, locale = 'zh') {
  const d = item?.data || {}
  const author = String(d.author || '')
  const mine = !!author && author === selfAuthor && author !== 'AI WorkDeck'
  const text = String(d.text || d.description || '')
  return {
    type: revisionTypeLabel(d.type, locale),
    author: author ? (mine ? author + '（我）' : author) : (locale.startsWith('en') ? 'Unknown author' : '未知作者'),
    date: String(d.date || ''),
    text: text.length > 80 ? text.slice(0, 80) + '…' : text,
  }
}

export function attachReviewHover({ canvas, execute, locale = 'zh' }) {
  const doc = canvas.ownerDocument, win = doc.defaultView
  let disposed = false, suspended = 0
  let layout = null, metadata = new Map(), generation = 0, pointer = null
  let hoverTimer = 0, inFlight = false, pending = null
  const root = doc.createElement('div'); root.className = 'awd-review-hover'; root.hidden = true
  const style = doc.createElement('style'); style.textContent = `
    .awd-review-hover { position:fixed;z-index:6;pointer-events:none;max-width:280px;padding:6px 10px;
      background:var(--awd-surface);border:1px solid var(--awd-border);border-radius:8px;
      box-shadow:var(--awd-shadow-sm);color:var(--awd-text);font:12px/1.5 system-ui,sans-serif; }
    .awd-review-hover[hidden] { display:none; }
    .awd-review-hover .awd-rh-meta { display:flex;gap:6px;flex-wrap:wrap;align-items:center;color:var(--awd-text-2);font-size:11px; }
    .awd-review-hover .awd-rh-meta strong { color:var(--awd-accent-text);font-weight:600; }
    .awd-review-hover .awd-rh-text { margin-top:3px;white-space:pre-wrap;overflow-wrap:anywhere;max-height:96px;overflow:hidden; }
    .awd-review-hover .awd-rh-text.deletion { color:var(--awd-danger-text);text-decoration:line-through; }
  `
  doc.head.append(style); doc.body.append(root)
  function hide() { clearTimeout(hoverTimer); hoverTimer = 0; root.hidden = true }
  function invalidate() {
    generation++; pointer = null; pending = null; layout = null; metadata.clear(); hide()
  }
  async function show(point, gen) {
    const current = () => !disposed && !suspended && gen === generation && pointer === point
    if (!current()) return
    if (inFlight) { pending = { point, gen }; return }
    inFlight = true
    try {
      if (!layout) {
        const data = await execute('get_review_layout', { fresh: false })
        if (!current() || !data?.success || data.available === false) return
        layout = data
      }
      const metrics = hoverViewportMetrics(canvas.getBoundingClientRect(), layout.view)
      let item = hitTestRevision(point.x, point.y, layout.items, metrics)
      if (!item) return
      if (typeof item.data?.type !== 'string') {
        const index = item.data.index
        let detail = metadata.get(index)
        if (!detail) {
          const revs = await execute('list_revisions', { index, documentSeq: layout.documentSeq, revision: layout.revision, locate: false })
          if (!current()) return
          if (!revs?.success || revs.documentSeq !== layout.documentSeq || revs.revision !== layout.revision) {
            layout = null; metadata.clear(); return
          }
          detail = revs.revisions?.find(r => r.index === index)
          if (!detail) return
          metadata.set(index, detail)
        }
        item = { ...item, data: detail }
      }
      const card = revisionHoverCard(item, layout.selfAuthor, locale)
      const meta = doc.createElement('div'); meta.className = 'awd-rh-meta'
      const type = doc.createElement('strong'); type.textContent = card.type
      const author = doc.createElement('span'); author.textContent = card.author
      const date = doc.createElement('span'); date.textContent = card.date
      meta.append(type, author, date)
      root.replaceChildren(meta)
      if (card.text) {
        const text = doc.createElement('div'); text.className = 'awd-rh-text'
        text.classList.toggle('deletion', item.data?.type === 'Delete')
        text.textContent = card.text
        root.append(text)
      }
      root.hidden = false
      // 跟随鼠标，右/下缘出界时翻到左侧/上方。
      const x = point.x + 14 + root.offsetWidth > win.innerWidth ? point.x - 14 - root.offsetWidth : point.x + 14
      const y = point.y + 16 + root.offsetHeight > win.innerHeight ? point.y - 16 - root.offsetHeight : point.y + 16
      root.style.left = x + 'px'; root.style.top = y + 'px'
    } catch { /* Hover reads never interrupt editing. */ }
    finally {
      inFlight = false
      const next = pending; pending = null
      if (next) show(next.point, next.gen)
    }
  }
  function onMove(e) {
    hide()
    if (disposed || suspended) return
    if (e.buttons) { invalidate(); return }
    const point = pointer = { x: e.clientX, y: e.clientY }, gen = generation
    // Only a real pointer dwell starts work; moving across the page does not.
    hoverTimer = setTimeout(() => { hoverTimer = 0; show(point, gen) }, 160)
  }
  canvas.addEventListener('mousemove', onMove)
  canvas.addEventListener('mouseleave', invalidate)
  canvas.addEventListener('wheel', invalidate, { passive: true })
  win.addEventListener('resize', invalidate)
  return {
    suspend() { suspended++; invalidate() },
    resume() { suspended = Math.max(0, suspended - 1) },
    documentChanged: invalidate,
    destroy() {
      disposed = true; invalidate()
      canvas.removeEventListener('mousemove', onMove); canvas.removeEventListener('mouseleave', invalidate)
      canvas.removeEventListener('wheel', invalidate); win.removeEventListener('resize', invalidate)
      root.remove(); style.remove()
    },
  }
}
