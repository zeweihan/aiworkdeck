// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { groupRevisions } from '../utils/reviewGrouping.js'

// Keep collision layout in document space. Re-running it in viewport space
// would pin cards to the top when the document scrolls underneath them.
export function positionReviewCards(items, gap = 12) {
  const bottoms = new Map()
  return [...items].sort((a, b) => a.y - b.y || a.x - b.x).map(item => {
    const top = Math.max(item.y, (bottoms.get(item.page) ?? -Infinity) + gap)
    bottoms.set(item.page, top + item.height)
    return { ...item, top }
  })
}

export function attachReviewBalloons({ canvas, execute, transport, locale = 'zh' }) {
  const doc = canvas.ownerDocument, win = doc.defaultView, en = locale.startsWith('en')
  const labels = en
    ? { title: 'Comments & changes', overview: 'Review list', insert: 'Inserted', delete: 'Deleted', table: 'Table', comment: 'Comment', accept: 'Accept', reject: 'Reject', resolve: 'Resolve', reopen: 'Reopen', edit: 'Edit', remove: 'Delete', save: 'Save', cancel: 'Cancel', more: 'Some review items are not displayed.', failed: 'Could not update review. Try again.', empty: 'No comments or changes', other: 'Change', format: 'Formatting', paraFormat: 'Paragraph formatting', unknown: 'Unknown author' }
    : { title: '批注与修订', overview: '审阅列表', insert: '插入', delete: '删除', table: '表格', comment: '批注', accept: '接受', reject: '拒绝', resolve: '解决', reopen: '重新打开', edit: '编辑', remove: '删除', save: '保存', cancel: '取消', more: '部分审阅条目暂未显示。', failed: '审阅更新失败，请重试。', empty: '暂无批注或修订', other: '更改', format: '格式', paraFormat: '段落格式', unknown: '未知作者' }
  const root = doc.createElement('div'); root.className = 'awd-review-balloons'; root.hidden = true
  const style = doc.createElement('style'); style.textContent = `
    .awd-review-balloons { position:fixed;overflow:hidden;pointer-events:none;z-index:5;color:#26352f;font:12px/1.5 system-ui,sans-serif; }
    .awd-review-balloons[hidden] { display:none; }
    .awd-rb-actions button { cursor:pointer;font:inherit;color:inherit;background:transparent;border:1px solid #ced8d2;border-radius:5px;padding:2px 7px; }
    .awd-rb-list { position:absolute;inset:0;pointer-events:none; }
    .awd-rb-page { position:absolute;overflow:hidden;pointer-events:none; }
    .awd-rb-page.overflowing { pointer-events:auto; }
    .awd-rb-overflow { position:absolute;right:0;top:0;width:8px;height:100%;margin:0;writing-mode:vertical-lr;direction:ltr;accent-color:#6d9b85;pointer-events:auto;z-index:2; }
    .awd-rb-card { position:absolute;transform-origin:top left;padding:10px;box-sizing:border-box;background:#fff;border:1px solid #d8dfdc;border-radius:8px;box-shadow:0 2px 5px #16302608;pointer-events:auto;cursor:pointer; }
    .awd-rb-card:hover,.awd-rb-card.active { border-color:#437762;box-shadow:0 0 0 1px #43776233; }
    .awd-rb-card.resolved { opacity:.65; }
    .awd-rb-meta { display:flex;gap:6px;flex-wrap:wrap;align-items:center;color:#67756e;font-size:11px; }
    .awd-rb-meta strong { color:#315847;font-weight:600; }
    .awd-rb-content { white-space:pre-wrap;overflow-wrap:anywhere;max-height:280px;overflow:auto;margin:7px 0;font-size:13px;line-height:1.6;cursor:text; }
    .awd-rb-card.deletion .awd-rb-content { color:#a34640;text-decoration:line-through; }
    .awd-rb-quote { border-left:2px solid #d8dfdc;padding-left:7px;margin:6px 0;color:#67756e;white-space:pre-wrap;overflow-wrap:anywhere;max-height:48px;overflow:auto; }
    .awd-rb-editor { width:100%;height:140px;box-sizing:border-box;font:inherit;resize:none; }
    .awd-rb-actions { display:flex;gap:6px;flex-wrap:wrap; }.awd-rb-actions button:disabled { opacity:.45;cursor:wait; }
    .awd-rb-lines { position:absolute;inset:0;width:100%;height:100%;overflow:hidden;pointer-events:none; }
    .awd-rb-lines path { fill:none;stroke:#6d9b85;stroke-width:1;stroke-dasharray:4 4;opacity:.65; }
    .awd-rb-notice { position:absolute;bottom:8px;right:8px;max-width:260px;padding:5px 10px;background:#f1f3f5;color:#7d5346;z-index:3; }
    .theme-dark .awd-rb-notice { background:#101214;color:#c5d0ca;border-color:#343e38; }
    .theme-dark .awd-rb-card { background:#1b211e;color:#dae3dc;border-color:#3a4840; }
    .theme-dark .awd-rb-meta strong { color:#92bea5; }
    .theme-dark .awd-rb-card.deletion .awd-rb-content { color:#e69590; }
  `
  const svg = doc.createElementNS('http://www.w3.org/2000/svg', 'svg'); svg.classList.add('awd-rb-lines')
  const list = doc.createElement('aside'); list.className = 'awd-rb-list'; list.setAttribute('aria-label', labels.title)
  const notice = doc.createElement('div'); notice.className = 'awd-rb-notice'; notice.hidden = true
  root.append(svg, list, notice); doc.head.append(style); doc.body.append(root)
  let suspended = 0, generation = 0
  let disposed = false, timer = null, inFlight = false, again = false, snapshot = null
  let viewportTimer = null
  // Typing must never wait for review metadata (several UNO round trips per
  // item on the office thread). Edits refresh positions from cached metadata;
  // metadata is re-read once the document has been quiet for FRESH_DELAY.
  const EDIT_DELAY = 250, FRESH_DELAY = 900
  let wantFresh = true, freshTimer = null, lastAction = '', pendingPolls = 0
  let enabled = false, cards = [], focusKey = '', busy = false, editingKey = '', pointerDown = false
  const pageLayers = new Map()
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
    // Cards survive index shifts (see reconcile): handlers read the current data.
    const locate = async () => {
      const r = item.data
      if (busy) return
      try { const result = await execute(item.kind === 'comment' ? 'goto_comment' : 'goto_revision', { id: r.id, index: r.index ?? r.items[0].index, revision: item.revision, documentSeq: item.documentSeq, ...(item.kind === 'revision' ? { identifier: r.items[0].identifier } : {}) }); if (!result?.success) throw new Error(); schedule(0) }
      catch { showNotice(labels.failed) }
    }
    node.onclick = e => { if (!e.target.closest('button,textarea') && !win.getSelection()?.toString()) locate() }
    node.onkeydown = e => { if (e.target === node && (e.key === 'Enter' || e.key === ' ')) { e.preventDefault(); locate() } }
    const actions = doc.createElement('div'); actions.className = 'awd-rb-actions'
    for (const action of !item.writable ? [] : item.kind === 'comment' ? ['edit', 'remove', r.resolved ? 'reopen' : 'resolve'] : ['accept', 'reject']) {
      const b = doc.createElement('button'); b.textContent = labels[action]
      b.onclick = async e => {
        const r = item.data
        e.stopPropagation(); if (busy) return
        if (action === 'edit') {
          if (node.querySelector('textarea')) return
          const input = doc.createElement('textarea'); input.className = 'awd-rb-editor'; input.value = r.content || ''; input.setAttribute('aria-label', labels.edit)
          const editActions = doc.createElement('div'); editActions.className = 'awd-rb-actions'
          const save = doc.createElement('button'); save.textContent = labels.save
          const cancel = doc.createElement('button'); cancel.textContent = labels.cancel
          const close = () => { input.remove(); editActions.remove(); content.hidden = false; actions.hidden = false; if (snapshot) place(snapshot) }
          cancel.onclick = e => { e.stopPropagation(); busy = false; editingKey = ''; close(); schedule(0) }
          save.onclick = async e => {
            e.stopPropagation(); if (!input.value.trim()) { input.focus(); return }
            save.disabled = true; cancel.disabled = true
            let saved = false
            try {
              const result = await execute('update_comment', { id: r.id, index: r.index, content: input.value, expectedContent: r.content, revision: item.revision, documentSeq: item.documentSeq, expectedComment: r })
              if (!result?.success) throw new Error()
              saved = true; transport.send({ __lo: 'lo-relay', type: 'modified' }); close()
            } catch { showNotice(labels.failed) }
            finally {
              if (saved || !input.isConnected) { busy = false; editingKey = '' }
              save.disabled = false; cancel.disabled = false; refreshNow()
            }
          }
          editActions.append(save, cancel); content.hidden = true; actions.hidden = true; node.append(input, editActions); busy = true; editingKey = item.key
          if (snapshot) place(snapshot); input.focus(); return
        }
        busy = true; root.querySelectorAll('.awd-rb-actions button').forEach(b => { b.disabled = true })
        try {
          const result = await execute(item.kind === 'comment' ? (action === 'remove' ? 'delete_comment' : 'set_comment_resolved') : 'resolve_revisions', item.kind === 'comment'
            ? { id: r.id, index: r.index, resolved: action === 'resolve', revision: item.revision, documentSeq: item.documentSeq, expectedComment: r }
            : { indices: r.items.map(x => x.index).sort((a, b) => b - a), action, revision: item.revision, documentSeq: item.documentSeq, expectedRevisions: r.items })
          if (!result?.success || result.results?.some(x => !x.success)) throw new Error()
          transport.send({ __lo: 'lo-relay', type: 'modified' })
        } catch { showNotice(labels.failed) }
        finally { busy = false; root.querySelectorAll('.awd-rb-actions button').forEach(b => { b.disabled = false }); refreshNow() }
      }
      actions.append(b)
    }
    node.append(actions); list.append(node)
    return Object.assign(item, { node })
  }
  function reviewItems(data) {
    const revisions = data.items.filter(x => x.kind === 'revision')
    const byIndex = new Map(revisions.map(x => [x.data.index, x]))
    const groups = ['balloons', 'margin'].includes(data.mode) ? groupRevisions(revisions.map(x => x.data)).filter(group => group.type !== 'Insert') : []
    // A group keeps its card while edits elsewhere shift enumeration indices.
    return groups.map(g => ({ ...byIndex.get(g.items[0].index), key: g.items[0].identifier ? 'g:' + g.items[0].identifier : g.key, data: g }))
      .concat(data.items.filter(x => x.kind === 'comment'))
  }
  // Only what a card renders or sends as its fence; positions update in place.
  const cardSignature = (item, writable) => JSON.stringify(item.kind === 'comment'
    ? [item.kind, writable, ...['id', 'author', 'date', 'timestamp', 'content', 'anchorText', 'resolved'].map(k => item.data[k])]
    : [item.kind, writable, item.data.type, item.data.inTable, item.data.author, item.data.date, item.data.text, item.data.description,
      item.data.items.map(r => [r.identifier, r.type, r.text, r.author, r.timestamp])])
  function reconcile(data, items) {
    if (!data.writable && editingKey) { editingKey = ''; busy = false }
    const previous = new Map(cards.map(c => [c.key, c]))
    cards = items.map(item => {
      const next = { ...item, revision: data.revision, documentSeq: data.documentSeq, writable: data.writable }
      const signature = cardSignature(item, data.writable)
      let card = previous.get(item.key)
      previous.delete(item.key)
      if (card && (card.signature === signature || card.key === editingKey)) {
        // Keep the click target and any draft. Only identical content may inherit
        // a fresh mutation token; a real edit still fails the worker's stale guard.
        if (card.key === editingKey) Object.assign(card, { x: item.x, y: item.y, page: item.page })
        else Object.assign(card, next)
      } else {
        card?.node.remove()
        card = makeCard(next)
        card.signature = signature
      }
      return card
    })
    previous.forEach(card => {
      if (card.key === editingKey) { editingKey = ''; busy = false }
      card.node.remove()
    })
    showNotice(data.truncated ? labels.more : '')
  }
  function pageLayer(page) {
    let layer = pageLayers.get(page.number)
    if (layer) return layer
    const node = doc.createElement('div'); node.className = 'awd-rb-page'; node.dataset.page = page.number
    const slider = doc.createElement('input'); slider.type = 'range'; slider.className = 'awd-rb-overflow'
    slider.min = '0'; slider.step = 'any'; slider.hidden = true
    slider.setAttribute('aria-label', labels.title + ' · ' + page.number)
    layer = { node, slider, offset: 0, overflow: 0, scale: 1 }
    slider.oninput = () => { layer.offset = Number(slider.value); if (snapshot) place(snapshot) }
    node.addEventListener('wheel', e => {
      const body = e.target.closest('.awd-rb-content,.awd-rb-quote,textarea')
      const vertical = !e.ctrlKey && Math.abs(e.deltaY) >= Math.abs(e.deltaX)
      if (vertical && body && (e.deltaY < 0 ? body.scrollTop > 0 : body.scrollTop + body.clientHeight < body.scrollHeight - 1)) return
      const pixels = e.deltaY * (e.deltaMode === 1 ? 16 : e.deltaMode === 2 ? node.clientHeight : 1)
      const offset = Math.max(0, Math.min(layer.overflow, layer.offset + pixels / layer.scale))
      if (vertical && offset !== layer.offset) {
        e.preventDefault(); layer.offset = offset; if (snapshot) place(snapshot)
        return
      }
      // The overlay is a sibling of canvas, so bubbling cannot reach Qt's wheel
      // listener. At page-scroll limits (or without overflow) hand it to Qt.
      canvas.dispatchEvent(new win.WheelEvent('wheel', {
        bubbles: true, cancelable: true, deltaX: e.deltaX, deltaY: e.deltaY, deltaZ: e.deltaZ, deltaMode: e.deltaMode,
        clientX: e.clientX, clientY: e.clientY, screenX: e.screenX, screenY: e.screenY,
        ctrlKey: e.ctrlKey, shiftKey: e.shiftKey, altKey: e.altKey, metaKey: e.metaKey,
      }))
      e.preventDefault()
    }, { passive: false })
    node.append(slider); list.append(node); pageLayers.set(page.number, layer)
    return layer
  }
  function place(data) {
    const surface = canvas.getBoundingClientRect(), v = data.view
    if (!v?.frameWidth || !v.viewport || !data.pages?.length) { root.hidden = true; return }
    const nativeScale = surface.width / v.frameWidth
    const menu = Math.max(0, surface.height - v.frameHeight * nativeScale)
    const viewport = v.viewport
    const scale = viewport.width * nativeScale / (v.right - v.left)
    if (!(scale > 0)) { root.hidden = true; return }
    const zoom = scale * 15 // One native pixel at 100% is fifteen document twips.
    root.style.left = surface.left + viewport.x * nativeScale + 'px'
    root.style.top = surface.top + menu + viewport.y * nativeScale + 'px'
    root.style.width = viewport.width * nativeScale + 'px'
    root.style.height = viewport.height * nativeScale + 'px'
    root.dataset.viewport = [v.left, v.top, v.right, v.bottom].join(':')
    const pages = new Map(data.pages.map(page => [page.number, page]))
    const measured = [], usedPages = new Set()
    for (const card of cards) {
      const page = pages.get(card.page)
      if (!page || page.sidebar === 'none' || !(page.gutterWidth > 0)) { card.node.hidden = true; continue }
      const layer = pageLayer(page); usedPages.add(page.number)
      if (card.node.parentNode !== layer.node) layer.node.append(card.node)
      card.node.hidden = false
      card.node.style.width = Math.max(1, page.gutterWidth / 15 - 16) + 'px'
      card.node.style.transform = `scale(${zoom})`
      measured.push({ ...card, height: card.node.offsetHeight * 15 })
    }
    pageLayers.forEach((layer, number) => {
      if (!usedPages.has(number)) { layer.node.remove(); pageLayers.delete(number) }
    })
    const positioned = positionReviewCards(measured, 12 * 15)
    for (const number of usedPages) {
      const page = pages.get(number), layer = pageLayers.get(number), right = page.sidebar === 'right'
      const gutterX = right ? page.x + page.width : page.x - page.gutterWidth
      layer.node.style.left = (gutterX - v.left) * scale + 'px'
      layer.node.style.top = (page.y - v.top) * scale + 'px'
      layer.node.style.width = page.gutterWidth * scale + 'px'
      layer.node.style.height = page.height * scale + 'px'
      layer.scale = scale
      layer.overflow = Math.max(0, ...positioned.filter(item => item.page === number).map(item => item.top + item.height - page.y - page.height))
      layer.offset = Math.max(0, Math.min(layer.offset, layer.overflow))
      layer.node.classList.toggle('overflowing', layer.overflow > 0)
      layer.slider.hidden = !(layer.overflow > 0)
      layer.slider.max = String(layer.overflow); layer.slider.value = String(layer.offset)
    }
    svg.replaceChildren()
    for (const item of positioned) {
      const page = pages.get(item.page), layer = pageLayers.get(item.page), right = page.sidebar === 'right'
      const pageEdge = right ? page.x + page.width : page.x
      const cardX = right ? pageEdge + 8 * 15 : pageEdge - page.gutterWidth + 8 * 15
      const cardTop = item.top - layer.offset, left = (cardX - v.left) * scale, top = (cardTop - v.top) * scale
      item.node.style.left = 8 * zoom + 'px'; item.node.style.top = (cardTop - page.y) * scale + 'px'
      const inView = top + item.height * scale > 0 && top < viewport.height * nativeScale
        && left + item.node.offsetWidth * zoom > 0 && left < viewport.width * nativeScale
        && cardTop + item.height > page.y && cardTop < page.y + page.height
      item.node.style.visibility = inView ? 'visible' : 'hidden'
      item.node.classList.toggle('active', Math.abs(item.y - v.caretY) < 240)
      if (!inView) continue
      const anchorX = (item.x - v.left) * scale, anchorY = (item.y - v.top) * scale
      const edgeX = (pageEdge - v.left) * scale
      const joinX = right ? left : left + item.node.offsetWidth * zoom
      const joinY = Math.max((page.y - v.top) * scale, Math.min((page.y + page.height - v.top) * scale, top + 24 * zoom))
      const path = doc.createElementNS(svg.namespaceURI, 'path')
      path.setAttribute('d', `M ${anchorX} ${anchorY} L ${edgeX} ${anchorY} L ${joinX} ${joinY}`)
      svg.append(path)
    }
    const nearest = kind => data.items.filter(i => i.kind === kind && i.y >= v.top)
      .sort((a,b) => Math.abs(a.y - v.top - 80 / scale) - Math.abs(b.y - v.top - 80 / scale))[0]
    const focus = { revisionIndex: nearest('revision')?.data.index, commentIndex: nearest('comment')?.data.index }
    const nextFocus = JSON.stringify(focus)
    if (nextFocus !== focusKey) { focusKey = nextFocus; transport.send({ __lo: 'lo-relay', type: 'review-focus', payload: focus }) }
  }
  async function refresh() {
    if (disposed) return
    if (inFlight || suspended || pointerDown || (busy && !editingKey)) { again = true; return }
    inFlight = true
    const capturedGeneration = generation, fresh = wantFresh
    wantFresh = false
    try {
      const data = await execute('get_review_layout', { fresh })
      if (disposed || suspended || capturedGeneration !== generation) return
      if (pointerDown) { again = true; return }
      if (!data?.success) { showNotice(labels.failed); return }
      if (data.available === false) {
        root.hidden = true; snapshot = null
        // An unsupported engine retains its own notes. Do not keep probing or
        // reserve an empty gutter when the geometry contract is unavailable.
        if (enabled) { enabled = false; await execute('set_review_balloons', { enabled: false, width: 280 }) }
        return
      }
      if (data.stale || data.unread) armFresh()
      // Writer lays out far pages in idle time and sends no event when done: poll
      // the cheap position read a few times so their cards appear unprompted.
      if (!data.pending) pendingPolls = 0
      else if (pendingPolls < 6) { pendingPolls++; schedule(700) }
      // Cards on pages Writer has not laid out yet, or not read yet, keep the
      // gutter: releasing it for one read would shift the page back and forth.
      const items = reviewItems(data), hasItems = items.length > 0 || data.pending > 0 || (enabled && data.unread > 0)
      snapshot = data
      const desiredWidth = hasItems ? 280 : 0
      if (hasItems !== enabled || (data.sidebarWidth != null && Number(data.sidebarWidth) !== desiredWidth)) {
        const changed = await execute('set_review_balloons', { enabled: hasItems, width: 280 })
        if (disposed || suspended || capturedGeneration !== generation) return
        if (!changed?.success || changed.available === false) { root.hidden = true; return }
        enabled = hasItems
        // One fresh read after a native gutter transition; no periodic polling.
        again = true
      }
      if (pointerDown) { again = true; return }
      reconcile(data, items)
      root.hidden = !enabled
      if (enabled) place(data)
    } catch { if (!disposed) showNotice(labels.failed) }
    finally { inFlight = false; if (again && !suspended && !pointerDown && (!busy || editingKey)) { again = false; schedule(0) } }
  }
  function schedule(delay = 80) { if (disposed) return; clearTimeout(timer); timer = setTimeout(refresh, delay) }
  function armFresh() {
    if (disposed) return
    clearTimeout(freshTimer)
    freshTimer = setTimeout(() => { freshTimer = null; wantFresh = true; schedule(0) }, FRESH_DELAY)
  }
  // After a card action the content itself changed: read it back at once.
  function refreshNow() { wantFresh = true; schedule(0) }
  const edited = () => { schedule(EDIT_DELAY); armFresh() }
  const scheduleViewport = () => {
    // Nothing is shown: a scroll or resize has nothing to move.
    if (disposed || viewportTimer || !enabled) return
    viewportTimer = setTimeout(() => { viewportTimer = null; clearTimeout(timer); refresh() }, 40)
  }
  const onWheel = () => scheduleViewport(), onPointer = () => scheduleViewport()
  const onDrag = e => { if (e.buttons === 1) scheduleViewport() }
  const onResize = () => scheduleViewport()
  const onCardPointerDown = e => {
    if (!e.target.closest('.awd-rb-card')) return
    pointerDown = true
    // Preserve the native selection until the action dispatches. Textareas and
    // card text retain their normal focus/selection behavior.
    if (e.target.closest('button')) e.preventDefault()
  }
  const releasePointer = () => { if (pointerDown) { pointerDown = false; schedule(0) } }
  root.addEventListener('pointerdown', onCardPointerDown)
  win.addEventListener('pointerup', releasePointer); win.addEventListener('pointercancel', releasePointer); win.addEventListener('blur', releasePointer)
  canvas.addEventListener('wheel', onWheel, { passive: true }); canvas.addEventListener('pointerup', onPointer); canvas.addEventListener('pointermove', onDrag)
  win.addEventListener('resize', onResize)
  schedule(0)
  return {
    suspend(action) {
      suspended++; generation++; clearTimeout(timer); lastAction = action
      if (action === 'load_document') {
        busy = false; editingKey = ''; pointerDown = false; snapshot = null; cards = []; focusKey = ''; enabled = false
        list.replaceChildren(); pageLayers.clear(); svg.replaceChildren(); root.hidden = true
      }
    },
    resume() {
      suspended = Math.max(0, suspended - 1)
      if (suspended) return
      // Content edits also arrive as documentChanged; a command alone only moves things.
      if (lastAction === 'load_document') refreshNow(); else schedule(EDIT_DELAY)
    },
    documentChanged: edited,
    cursorMoved() { if (enabled) schedule(200) },
    destroy() {
      disposed = true; clearTimeout(timer); clearTimeout(viewportTimer); clearTimeout(freshTimer); root.remove(); style.remove()
      canvas.removeEventListener('wheel', onWheel); canvas.removeEventListener('pointerup', onPointer); canvas.removeEventListener('pointermove', onDrag)
      win.removeEventListener('resize', onResize); win.removeEventListener('pointerup', releasePointer); win.removeEventListener('pointercancel', releasePointer); win.removeEventListener('blur', releasePointer)
    },
  }
}
