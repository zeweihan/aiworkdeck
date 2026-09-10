// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { groupRevisions } from '../utils/reviewGrouping.js'

// Keep collision layout in document space. Re-running it in viewport space
// would pin cards to the top when the document scrolls underneath them.
export function positionReviewCards(items, gap = 12) {
  let bottom = -Infinity
  return [...items].sort((a, b) => a.y - b.y || a.x - b.x).map(item => {
    const top = Math.max(item.y, bottom + gap)
    bottom = top + item.height
    return { ...item, top }
  })
}

export function attachReviewBalloons({ canvas, execute, transport, locale = 'zh' }) {
  const doc = canvas.ownerDocument, win = doc.defaultView, en = locale.startsWith('en')
  const labels = en
    ? { title: 'Comments & changes', overview: 'Review list', insert: 'Inserted', delete: 'Deleted', table: 'Table', comment: 'Comment', accept: 'Accept', reject: 'Reject', resolve: 'Resolve', reopen: 'Reopen', edit: 'Edit', remove: 'Delete', save: 'Save', cancel: 'Cancel', more: 'More items are available in the review list.', failed: 'Could not update review. Try again.', empty: 'No comments or changes', other: 'Change', format: 'Formatting', paraFormat: 'Paragraph formatting', unknown: 'Unknown author' }
    : { title: '批注与修订', overview: '审阅列表', insert: '插入', delete: '删除', table: '表格', comment: '批注', accept: '接受', reject: '拒绝', resolve: '解决', reopen: '重新打开', edit: '编辑', remove: '删除', save: '保存', cancel: '取消', more: '其余条目请在审阅列表中查看。', failed: '审阅更新失败，请重试。', empty: '暂无批注或修订', other: '更改', format: '格式', paraFormat: '段落格式', unknown: '未知作者' }
  const root = doc.createElement('div'); root.className = 'awd-review-balloons'; root.hidden = true
  const style = doc.createElement('style'); style.textContent = `
    html.awd-review-margin #qtcanvas { width: calc(100% - 316px); }
    .awd-review-balloons { position:fixed;inset:0;pointer-events:none;z-index:5;color:#26352f;font:12px/1.5 system-ui,sans-serif; }
    .awd-review-balloons[hidden] { display:none; }
    .awd-rb-rail { position:absolute;right:0;width:296px;bottom:28px;overflow:hidden;pointer-events:auto;background:#f1f3f5;border-left:1px solid #d8dfdc; }
    .awd-rb-head { position:absolute;top:0;left:0;right:0;display:flex;justify-content:space-between;gap:8px;padding:10px 12px;background:#f1f3f5;z-index:2;border-bottom:1px solid #d8dfdc; }
    .awd-rb-head button,.awd-rb-actions button { cursor:pointer;font:inherit;color:inherit;background:transparent;border:1px solid #ced8d2;border-radius:5px;padding:2px 7px; }
    .awd-rb-list { position:absolute;inset:42px 0 0;overflow:hidden; }
    .awd-rb-card { position:absolute;left:10px;right:10px;padding:10px;box-sizing:border-box;background:#fff;border:1px solid #d8dfdc;border-radius:8px;box-shadow:0 2px 5px #16302608;pointer-events:auto;cursor:pointer; }
    .awd-rb-card:hover,.awd-rb-card.active { border-color:#437762;box-shadow:0 0 0 1px #43776233; }
    .awd-rb-card.resolved { opacity:.65; }
    .awd-rb-meta { display:flex;gap:6px;flex-wrap:wrap;align-items:center;color:#67756e;font-size:11px; }
    .awd-rb-meta strong { color:#315847;font-weight:600; }
    .awd-rb-content { white-space:pre-wrap;overflow-wrap:anywhere;max-height:240px;overflow:auto;margin:7px 0;font-size:13px;line-height:1.6;cursor:text; }
    .awd-rb-card.deletion .awd-rb-content { color:#a34640;text-decoration:line-through; }
    .awd-rb-quote { border-left:2px solid #d8dfdc;padding-left:7px;margin:6px 0;color:#67756e;white-space:pre-wrap;overflow-wrap:anywhere;max-height:48px;overflow:auto; }
    .awd-rb-editor { width:100%;min-height:140px;box-sizing:border-box;font:inherit;resize:vertical; }
    .awd-rb-actions { display:flex;gap:6px;flex-wrap:wrap; }.awd-rb-actions button:disabled { opacity:.45;cursor:wait; }
    .awd-rb-lines { position:absolute;inset:0;width:100%;height:100%;overflow:hidden;pointer-events:none; }
    .awd-rb-lines path { fill:none;stroke:#6d9b85;stroke-width:1;stroke-dasharray:4 4;opacity:.65; }
    .awd-rb-notice { position:absolute;bottom:0;left:0;right:0;padding:5px 10px;background:#f1f3f5;color:#7d5346;z-index:3; }
    .theme-dark .awd-rb-rail,.theme-dark .awd-rb-head,.theme-dark .awd-rb-notice { background:#101214;color:#c5d0ca;border-color:#343e38; }
    .theme-dark .awd-rb-card { background:#1b211e;color:#dae3dc;border-color:#3a4840; }
    .theme-dark .awd-rb-meta strong { color:#92bea5; }
    .theme-dark .awd-rb-card.deletion .awd-rb-content { color:#e69590; }
  `
  const svg = doc.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.classList.add('awd-rb-lines')
  const rail = doc.createElement('aside'); rail.className = 'awd-rb-rail'; rail.setAttribute('aria-label', labels.title)
  const head = doc.createElement('div'); head.className = 'awd-rb-head'
  const title = doc.createElement('strong'); title.textContent = labels.title
  const overview = doc.createElement('button'); overview.textContent = labels.overview
  overview.onclick = () => transport.send({ __lo: 'lo-relay', type: 'review-overview' })
  head.append(title, overview)
  const list = doc.createElement('div'); list.className = 'awd-rb-list'
  const notice = doc.createElement('div'); notice.className = 'awd-rb-notice'; notice.hidden = true
  rail.append(head, list, notice); root.append(svg, rail); doc.head.append(style); doc.body.append(root)
  let suspended = 0, generation = 0
  let disposed = false, timer = null, inFlight = false, again = false, dirty = true, snapshot = null
  let viewportTimer = null
  let enabled = false, cards = [], renderKey = '', manualScroll = 0, previousView = '', focusKey = '', busy = false
  function showNotice(text) { notice.textContent = text; notice.hidden = !text }
  function makeCard(item) {
    const node = doc.createElement('article'); node.className = 'awd-rb-card'
    node.dataset.key = item.key; node.tabIndex = 0
    node.classList.toggle('deletion', item.kind === 'revision' && item.data.type === 'Delete')
    node.classList.toggle('resolved', !!item.data.resolved)
    const r = item.data, meta = doc.createElement('div'); meta.className = 'awd-rb-meta'
    const type = doc.createElement('strong'); type.textContent = item.kind === 'comment' ? labels.comment : (r.inTable ? labels.table + ' · ' : '') + (r.type === 'Delete' ? labels.delete : r.type === 'Insert' ? labels.insert : r.type === 'Format' ? labels.format : r.type === 'ParagraphFormat' ? labels.paraFormat : r.type || labels.other)
    const author = doc.createElement('span'); author.textContent = r.author || labels.unknown
    const date = doc.createElement('span'); date.textContent = r.date || ''
    meta.append(type, author, date)
    const content = doc.createElement('div'); content.className = 'awd-rb-content'; content.textContent = r.content || r.text || r.description || ''
    node.append(meta, content)
    if (r.anchorText) { const quote = doc.createElement('div'); quote.className = 'awd-rb-quote'; quote.textContent = r.anchorText; node.append(quote) }
    const locate = async () => {
      if (busy) return
      try { const result = await execute(item.kind === 'comment' ? 'goto_comment' : 'goto_revision', { id: r.id, index: r.index ?? r.items[0].index, revision: item.revision }); if (!result?.success) throw new Error(); manualScroll = 0; schedule(0) }
      catch { showNotice(labels.failed) }
    }
    node.onclick = e => { if (!e.target.closest('button,textarea') && !win.getSelection()?.toString()) locate() }
    node.onkeydown = e => { if (e.target === node && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); locate() } }
    const actions = doc.createElement('div'); actions.className = 'awd-rb-actions'
    for (const action of !item.writable ? [] : item.kind === 'comment' ? ['edit', 'remove', r.resolved ? 'reopen' : 'resolve'] : ['accept', 'reject']) {
      const b = doc.createElement('button'); b.textContent = labels[action]
      b.onclick = async e => {
        e.stopPropagation(); if (busy) return
        if (action === 'edit') {
          if (node.querySelector('textarea')) return
          const input = doc.createElement('textarea'); input.className = 'awd-rb-editor'; input.value = r.content || ''; input.setAttribute('aria-label', labels.edit)
          const editActions = doc.createElement('div'); editActions.className = 'awd-rb-actions'
          const save = doc.createElement('button'); save.textContent = labels.save
          const cancel = doc.createElement('button'); cancel.textContent = labels.cancel
          const close = () => { input.remove(); editActions.remove(); content.hidden = false; actions.hidden = false; if (snapshot) place(snapshot) }
          cancel.onclick = e => { e.stopPropagation(); busy = false; close() }
          save.onclick = async e => {
            e.stopPropagation(); if (!input.value.trim()) { input.focus(); return }
            save.disabled = true; cancel.disabled = true
            try {
              const result = await execute('update_comment', { id: r.id, index: r.index, content: input.value, expectedContent: r.content, revision: item.revision })
              if (!result?.success) throw new Error()
              dirty = true; transport.send({ __lo: 'lo-relay', type: 'modified' }); close()
            } catch { showNotice(labels.failed) }
            finally { busy = false; save.disabled = false; cancel.disabled = false; schedule(0) }
          }
          editActions.append(save, cancel); content.hidden = true; actions.hidden = true; node.append(input, editActions); busy = true
          if (snapshot) place(snapshot); input.focus(); return
        }
        busy = true; root.querySelectorAll('.awd-rb-actions button').forEach(b => { b.disabled = true })
        try {
          const result = await execute(item.kind === 'comment' ? (action === 'remove' ? 'delete_comment' : 'set_comment_resolved') : 'resolve_revisions', item.kind === 'comment'
            ? { id: r.id, index: r.index, resolved: action === 'resolve', revision: item.revision }
            : { indices: r.items.map(x => x.index).sort((a, b) => b - a), action, revision: item.revision })
          if (!result?.success || result.results?.some(x => !x.success)) throw new Error()
          transport.send({ __lo: 'lo-relay', type: 'modified' })
        } catch { showNotice(labels.failed) }
        finally { busy = false; dirty = true; root.querySelectorAll('.awd-rb-actions button').forEach(b => { b.disabled = false }); schedule(0) }
      }
      actions.append(b)
    }
    node.append(actions); list.append(node)
    return { ...item, node }
  }
  function rebuild(data) {
    const revisionItems = data.items.filter(x => x.kind === 'revision')
    const byIndex = new Map(revisionItems.map(x => [x.data.index, x]))
    const groups = data.mode === 'final' ? [] : groupRevisions(revisionItems.map(x => x.data))
    const items = groups.map(g => ({ ...byIndex.get(g.items[0].index), key: g.key, data: g }))
      .concat(data.items.filter(x => x.kind === 'comment'))
    list.replaceChildren(); cards = items.map(item => makeCard({ ...item, revision: data.revision, writable: data.writable })); manualScroll = 0
    showNotice(data.truncated ? labels.more : '')
  }
  function place(data) {
    const surface = canvas.getBoundingClientRect(), v = data.view
    if (!v || !v.frameWidth || !v.viewport) return
    const s = surface.width / v.frameWidth, menu = Math.max(0, surface.height - v.frameHeight * s)
    const viewportTop = surface.top + menu + v.viewport.y * s
    const scale = v.viewport.width * s / (v.right - v.left)
    if (!(scale > 0)) return
    rail.style.top = viewportTop + 'px'
    const listTop = list.getBoundingClientRect().top, listHeight = list.clientHeight
    const positioned = positionReviewCards(cards.map(c => ({ ...c, height: c.node.offsetHeight / scale })), 12 / scale)
    const viewKey = [v.left, v.top, v.right, v.bottom].join(':')
    root.dataset.viewport = viewKey
    if (viewKey !== previousView) { manualScroll = 0; previousView = viewKey }
    const last = positioned.at(-1)
    const overflow = last ? Math.max(0, viewportTop + (last.top + last.height - v.top) * scale - listTop - listHeight + 12) : 0
    manualScroll = Math.max(0, Math.min(manualScroll, overflow))
    svg.replaceChildren()
    const visible = []
    for (const item of positioned) {
      const x = surface.left + (v.viewport.x + (item.x - v.left) * v.viewport.width / (v.right - v.left)) * s
      const anchorY = viewportTop + (item.y - v.top) * scale
      const cardY = viewportTop + (item.top - v.top) * scale - manualScroll
      item.node.style.top = (cardY - listTop) + 'px'
      const inView = cardY + item.node.offsetHeight > listTop && cardY < listTop + listHeight
      item.node.style.visibility = inView ? 'visible' : 'hidden'
      const active = Math.abs(item.y - v.caretY) < 240
      item.node.classList.toggle('active', active)
      if (inView) visible.push(item)
      if (!inView || anchorY < viewportTop || anchorY > listTop + listHeight || x < surface.left || x > surface.right) continue
      const edge = rail.getBoundingClientRect().left, y = Math.max(listTop, cardY + 24)
      const path = doc.createElementNS(svg.namespaceURI, 'path')
      path.setAttribute('d', `M ${x} ${anchorY + 7} L ${surface.right + 5} ${anchorY + 7} L ${edge + 10} ${y}`)
      svg.append(path)
    }
    const target = viewportTop + 80
    const nearest = kind => data.items.filter(i => i.kind === kind && viewportTop + (i.y - v.top) * scale >= viewportTop)
      .sort((a,b) => Math.abs(viewportTop+(a.y-v.top)*scale-target)-Math.abs(viewportTop+(b.y-v.top)*scale-target))[0]
    const focus = { revisionIndex: nearest('revision')?.data.index, commentIndex: nearest('comment')?.data.index }
    const nextFocus = JSON.stringify(focus)
    if (nextFocus !== focusKey) { focusKey = nextFocus; transport.send({ __lo: 'lo-relay', type: 'review-focus', payload: focus }) }
  }
  async function refresh() {
    if (disposed) return
    if (inFlight || busy || suspended) { again = true; return }
    inFlight = true
    const capturedGeneration = generation
    try {
      const data = await execute('get_review_layout', {})
      if (disposed || suspended || capturedGeneration !== generation) return
      if (!data?.success) { showNotice(labels.failed); return }
      snapshot = data
      const hasItems = data.items.some(x => x.kind === 'comment' || data.mode !== 'final')
      if (hasItems !== enabled || (hasItems && data.notesVisible)) {
        // Hide the native narrow note windows only after the replacement exists.
        const changed = await execute('set_review_balloons', { enabled: hasItems })
        if (disposed || suspended || capturedGeneration !== generation) return
        if (!changed?.success) { showNotice(labels.failed); return }
        const resized = enabled !== hasItems
        enabled = hasItems; root.hidden = !enabled
        doc.documentElement.classList.toggle('awd-review-margin', enabled)
        dirty = true; if (resized) win.dispatchEvent(new win.Event('resize')); again = true
        return
      }
      const key = [data.revision, data.mode, data.writable, enabled].join(':')
      if (dirty || key !== renderKey) { rebuild(data); dirty = false; renderKey = key }
      root.hidden = !enabled
      if (enabled) place(data)
    } catch { if (!disposed) showNotice(labels.failed) }
    finally { inFlight = false; if (again && !suspended) { again = false; schedule(80) } }
  }
  function schedule(delay = 120) { if (disposed) return; clearTimeout(timer); timer = setTimeout(refresh, delay) }
  const wheel = e => {
    if (e.target.closest('.awd-rb-content,.awd-rb-quote')) return
    e.preventDefault(); manualScroll += e.deltaY; if (snapshot) place(snapshot)
  }
  rail.addEventListener('wheel', wheel, { passive: false })
  const scheduleViewport = () => {
    if (disposed || viewportTimer) return
    viewportTimer = setTimeout(() => { viewportTimer = null; clearTimeout(timer); refresh() }, 60)
  }
  const onWheel = e => { if (e.ctrlKey) dirty = true; scheduleViewport() }, onPointer = () => scheduleViewport()
  const onDrag = e => { if (e.buttons === 1) scheduleViewport() }
  let size = ''
  const onResize = () => { const r = canvas.getBoundingClientRect(), next = [r.width,r.height].join(':'); if (next !== size) { size = next; dirty = true; schedule(100) } }
  canvas.addEventListener('wheel', onWheel, { passive: true }); canvas.addEventListener('pointerup', onPointer); canvas.addEventListener('pointermove', onDrag)
  win.addEventListener('resize', onResize)
  schedule(0)
  return {
    suspend(action) {
      suspended++; generation++; clearTimeout(timer)
      if (action === 'load_document') {
        // Imported documents can reuse comment IDs. Never carry a draft or its
        // handlers across a model replacement, even when the content matches.
        busy = false; dirty = true; snapshot = null; cards = []; renderKey = ''; focusKey = ''
        list.replaceChildren(); svg.replaceChildren(); root.hidden = true
      }
    },
    resume() { suspended = Math.max(0, suspended - 1); if (!suspended) schedule(180) },
    documentChanged() { dirty = true; schedule(450) }, cursorMoved() { scheduleViewport() },
    destroy() { disposed = true; clearTimeout(timer); clearTimeout(viewportTimer); root.remove(); style.remove(); doc.documentElement.classList.remove('awd-review-margin'); canvas.removeEventListener('wheel', onWheel); canvas.removeEventListener('pointerup', onPointer); canvas.removeEventListener('pointermove', onDrag); win.removeEventListener('resize', onResize) },
  }
}
