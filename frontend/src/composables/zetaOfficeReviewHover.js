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
// inline（默认）模式只有位置，此时补一次 list_revisions（locate:false）按 index 合并，
// 每个 documentSeq 只补一次。

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
  let disposed = false
  let layout = null, metaByIndex = null, metaDocSeq = null
  let hoverTimer = 0, refreshTimer = 0, inFlight = false, again = false
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
  async function refresh() {
    if (disposed || inFlight) { again = true; return }
    inFlight = true
    try {
      const data = await execute('get_review_layout', { fresh: false })
      if (disposed) return
      if (!data?.success || data.available === false) { layout = null; hide(); return }
      layout = data
      // inline 模式 items 只有位置：按 index 合并一次 list_revisions 元数据。
      const needMeta = data.items.some(i => i.kind === 'revision' && !i.data?.author)
      if (needMeta && metaDocSeq !== data.documentSeq) {
        const revs = await execute('list_revisions', { limit: 500, locate: false })
        if (revs?.success) {
          metaByIndex = new Map(revs.revisions.map(r => [r.index, r]))
          metaDocSeq = data.documentSeq
        }
      }
      if (needMeta && metaByIndex) {
        for (const item of data.items) {
          if (item.kind === 'revision' && !item.data?.author) {
            const meta = metaByIndex.get(item.data.index)
            if (meta) item.data = { ...meta, index: item.data.index }
          }
        }
      }
    } catch { /* 只读查询失败：保留上一份快照 */ }
    finally { inFlight = false; if (again && !disposed) { again = false; refresh() } }
  }
  function scheduleRefresh(delay = 300) {
    if (disposed || refreshTimer) return
    refreshTimer = setTimeout(() => { refreshTimer = 0; refresh() }, delay)
  }
  function onMove(e) {
    if (!layout?.items?.length) { hide(); return }
    clearTimeout(hoverTimer)
    // 150-200ms 防抖：连续移动不打点，停下来才做命中测试。
    hoverTimer = setTimeout(() => {
      hoverTimer = 0
      const metrics = hoverViewportMetrics(canvas.getBoundingClientRect(), layout.view)
      const item = hitTestRevision(e.clientX, e.clientY, layout.items, metrics)
      if (!item) { root.hidden = true; return }
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
      const x = e.clientX + 14 + root.offsetWidth > win.innerWidth ? e.clientX - 14 - root.offsetWidth : e.clientX + 14
      const y = e.clientY + 16 + root.offsetHeight > win.innerHeight ? e.clientY - 16 - root.offsetHeight : e.clientY + 16
      root.style.left = x + 'px'; root.style.top = y + 'px'
    }, 160)
  }
  const onWheel = () => { hide(); scheduleRefresh() }
  const onLeave = hide
  const onResize = () => { hide(); scheduleRefresh() }
  canvas.addEventListener('mousemove', onMove)
  canvas.addEventListener('mouseleave', onLeave)
  canvas.addEventListener('wheel', onWheel, { passive: true })
  win.addEventListener('resize', onResize)
  refresh()
  return {
    documentChanged() { hide(); scheduleRefresh(150) },
    destroy() {
      disposed = true; hide(); clearTimeout(refreshTimer)
      canvas.removeEventListener('mousemove', onMove); canvas.removeEventListener('mouseleave', onLeave)
      canvas.removeEventListener('wheel', onWheel); win.removeEventListener('resize', onResize)
      root.remove(); style.remove()
    },
  }
}
