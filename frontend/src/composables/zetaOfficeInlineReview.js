// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { cursorRectToPixels, nativeCursorRectToPixels } from './zetaOfficeImeOverlay.js'

const LABELS = {
  zh: { title: '即时审校', disabled: '即时审校已关闭', enable: '开启即时检查', disable: '关闭即时检查', checking: '正在检查', stale: '正文已变化，等待重新检查', error: '检查暂不可用', empty: '此分类暂无问题', list: '查看全部问题', close: '关闭', collapse: '收起', expand: '展开', locate: '定位原文', apply: '采用建议', ignore: '忽略本条', refresh: '重新检查', deep: '深入审校（AI）', deepBusy: '深入审校中…', insight: '打开依据与在线核验', scope: '当前检查范围：正文段落；表格、页眉页脚等请使用完整核验。', truncated: '本次检查有截断，不能视为全文检查完成。', local: '即时规则检查', saved: '已采用建议，可撤销。', failed: '正文或版本已变化，请重新检查。', count: n => `${n} 条提示`, tabs: { all: '全部', supplement: '待补充', consistency: '一致性', format: '格式与号码', ai: 'AI 审校' } },
  en: { title: 'Inline review', disabled: 'Inline review is off', enable: 'Enable live checks', disable: 'Disable live checks', checking: 'Checking…', stale: 'Text changed. Waiting for a fresh check.', error: 'Checks are unavailable', empty: 'No issues in this category', list: 'View all issues', close: 'Close', collapse: 'Collapse', expand: 'Expand', locate: 'Locate text', apply: 'Apply suggestion', ignore: 'Dismiss this issue', refresh: 'Check again', deep: 'Deep review (AI)', deepBusy: 'Deep review running…', insight: 'Open sources and online verification', scope: 'Scope: body paragraphs. Use full verification for tables, headers and footers.', truncated: 'This check was truncated; it does not cover the whole document.', local: 'Live rule checks', saved: 'Suggestion applied. Undo is available.', failed: 'The document or version changed. Check again.', count: n => `${n} suggestions`, tabs: { all: 'All', supplement: 'Missing info', consistency: 'Consistency', format: 'Format & IDs', ai: 'AI review' } },
}

/** A transient guest DOM layer. Showing, inspecting and dismissing findings never edits the document. */
export function attachInlineReview({ canvas, input, execute, transport, language = 'zh-CN' }) {
  const doc = canvas.ownerDocument, view = doc.defaultView
  const english = language.startsWith('en')
  const t = LABELS[english ? 'en' : 'zh']
  const errors = english ? { REVIEW_INLINE_REVISIONS: 'Switch from inline tracked changes to margin or final view to review.', REVIEW_SNAPSHOT_FAILED: 'The current document could not be read. Try checking again.', REVIEW_FAILED: 'Review failed. Try checking again.', REVIEW_DEEP_INCOMPLETE: 'Deep review did not finish completely. Only completed checks are shown below; you can retry.' } : { REVIEW_INLINE_REVISIONS: '请切换到页边修订或最终视图后再审校。', REVIEW_SNAPSHOT_FAILED: '暂时无法读取当前文档，请重新检查。', REVIEW_FAILED: '本次审校未完成，请重新检查。', REVIEW_DEEP_INCOMPLETE: '深入审校未完整完成，以下仅为已完成的检查，可重试。' }
  let state = { session: '', enabled: false, revision: null, status: 'disabled', findings: [] }
  let generation = 0, sequence = 0, timer = 0, disposed = false, composing = false, inFlight = false, again = false
  let anchor = null, click = null, context = null, panelMode = '', busy = false, activeTab = 'all'
  let panelPosition = null, drag = null, storageKey = ''
  const ignored = new Set()
  const root = doc.createElement('div'); root.className = 'awd-inline-review'
  const style = doc.createElement('style')
  style.textContent = `.awd-inline-review{position:fixed;inset:0;z-index:9998;pointer-events:none;font:13px/1.5 system-ui,sans-serif;color:#26332e}.awd-inline-review button{font:inherit;color:inherit;border:1px solid #ccd6ce;border-radius:6px;background:#fff;padding:5px 9px;cursor:pointer}.awd-inline-review button:focus-visible{outline:2px solid #527866;outline-offset:2px}.awd-inline-review button:disabled{opacity:.55;cursor:default}.awd-ir-status{pointer-events:auto;position:absolute;left:18px;bottom:64px;max-width:calc(100vw - 72px);display:flex;align-items:center;gap:8px;padding:7px 8px 7px 11px;background:#fff;border:1px solid #ccd6ce;border-radius:9px;box-shadow:0 2px 8px #0002;cursor:move;user-select:none}.awd-ir-status-label{white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.awd-ir-chip{pointer-events:auto;position:absolute;box-shadow:0 2px 8px #0002;white-space:nowrap}.awd-ir-panel{pointer-events:auto;position:absolute;width:380px;max-width:calc(100vw - 24px);max-height:70vh;display:flex;flex-direction:column;padding:0;background:#fff;border:1px solid #ccd6ce;border-radius:9px;box-shadow:0 6px 24px #0002}.awd-ir-head,.awd-ir-actions{display:flex;align-items:center;gap:7px;flex-wrap:wrap}.awd-ir-head{justify-content:space-between;padding:10px 12px;cursor:move;user-select:none;border-bottom:1px solid #e1e7e2}.awd-ir-head-actions{display:flex;gap:6px}.awd-ir-body{overflow:auto;padding:0 12px 12px;overscroll-behavior:contain}.awd-ir-tabs{position:sticky;top:0;z-index:1;display:flex;flex-wrap:wrap;gap:5px;padding:9px 0 6px;background:#fff;border-bottom:1px solid #edf0ed}.awd-ir-tabs button{white-space:nowrap}.awd-ir-tabs button[aria-selected=true]{background:#e5eee8;border-color:#8ca798}.awd-ir-item{border-top:1px solid #e1e7e2;margin-top:10px;padding-top:9px}.awd-ir-copy{white-space:pre-wrap;word-break:break-word;margin:6px 0}.awd-ir-note{font-size:11px;color:#64736a;margin:7px 0}.theme-dark .awd-inline-review{color:#e1e8e3}.theme-dark .awd-inline-review button,.theme-dark .awd-ir-panel,.theme-dark .awd-ir-status,.theme-dark .awd-ir-tabs{background:#202622;border-color:#465148}.theme-dark .awd-ir-tabs button[aria-selected=true]{background:#37463c}.theme-dark .awd-ir-note{color:#b0bcb3}.awd-inline-review [hidden]{display:none!important}`
  doc.head.appendChild(style); doc.body.appendChild(root)
  function button(label, action, parent) {
    const b = doc.createElement('button'); b.type = 'button'; b.textContent = label
    b.addEventListener('mousedown', e => e.preventDefault())
    b.addEventListener('click', () => { Promise.resolve().then(action).catch(() => notice(t.failed)) })
    parent.appendChild(b); return b
  }
  const status = doc.createElement('div'); status.className = 'awd-ir-status'; status.hidden = true
  const statusLabel = copy('', status, 'awd-ir-status-label')
  const statusExpand = button(t.expand, openFromStatus, status); statusExpand.setAttribute('aria-label', t.expand)
  root.appendChild(status)
  const chip = button('', () => openPanel('current'), root); chip.className = 'awd-ir-chip'; chip.hidden = true
  const panel = doc.createElement('section'); panel.className = 'awd-ir-panel'; panel.hidden = true; panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-label', t.title); root.appendChild(panel)
  function copy(text, parent = panel, className = 'awd-ir-copy') {
    const el = doc.createElement('div'); el.className = className; el.textContent = text; parent.appendChild(el); return el
  }
  function findings() { return (state.findings || []).filter(f => !ignored.has(String(f.id))) }
  function bucket(f) {
    const kind = String(f?.kind || '').toUpperCase()
    if (kind === 'LOGIC_REVIEW' || kind.startsWith('AI_')) return 'ai'
    if (['PLACEHOLDER', 'BLANK', 'DOCUMENT_NOT_FOUND'].includes(kind)) return 'supplement'
    if (['COUNT_MISMATCH', 'ARITHMETIC', 'DANGLING_REFERENCE'].includes(kind)) return 'consistency'
    if (['SCRIPT_OUTLIER', 'NUMBERING', 'USCC_INVALID'].includes(kind)) return 'format'
    return 'consistency'
  }
  function tabFindings(list, tab = activeTab) { return tab === 'all' ? list : list.filter(f => bucket(f) === tab) }
  function currentFindings() { return findings().filter(f => f.paragraphIndex === context?.paragraphIndex && (!f.expectedParagraph || f.expectedParagraph === context.text)) }
  function hide() { chip.hidden = true; panel.hidden = true; panelMode = ''; renderStatus(); placeStatus() }
  function invalidate(markStale = true) {
    generation++; clearTimeout(timer); timer = 0; context = null; chip.hidden = true
    if (markStale) state = { ...state, status: state.enabled === false ? 'disabled' : 'stale' }
    if (panelMode) openPanel(panelMode); else panel.hidden = true
    renderStatus()
  }
  function request(action, data) {
    if (!state.session || disposed) return
    if (action === 'deep' && state.deepStatus === 'checking') return
    transport.send({ __lo: 'lo-relay', type: 'inline-review-request', session: state.session, id: ++sequence, revision: state.revision, action, ...(data ? { data } : {}) })
    if (action === 'deep') { state = { ...state, deepStatus: 'checking' }; renderStatus(); if (panelMode) openPanel(panelMode) }
  }
  function renderStatus() {
    status.hidden = !state.session || !panel.hidden
    const label = state.enabled === false ? t.disabled : state.status === 'checking' ? t.checking : state.status === 'stale' ? t.stale : state.status === 'error' ? t.error : `${t.title} · ${t.count(findings().length)}`
    statusLabel.textContent = label; status.title = label; placeStatus()
  }
  function clampPosition(pos, element = panel) {
    const width = element.offsetWidth || (element === status ? 210 : 380)
    const height = element.offsetHeight || (element === status ? 42 : 320)
    return { x: Math.max(8, Math.min(pos.x, view.innerWidth - width - 8)), y: Math.max(8, Math.min(pos.y, view.innerHeight - height - 8)) }
  }
  function savePosition() { try { if (storageKey && panelPosition) view.sessionStorage?.setItem(storageKey, JSON.stringify(panelPosition)) } catch {} }
  function placeStatus() {
    if (!panelPosition || status.hidden) return
    panelPosition = clampPosition(panelPosition, status)
    status.style.left = panelPosition.x + 'px'; status.style.top = panelPosition.y + 'px'; status.style.bottom = 'auto'
  }
  function place() {
    if (panelPosition) {
      panelPosition = clampPosition(panelPosition); panel.style.left = panelPosition.x + 'px'; panel.style.top = panelPosition.y + 'px'; return
    }
    const x = chip.hidden ? 18 : parseFloat(chip.style.left) || 18
    const y = chip.hidden ? view.innerHeight - panel.offsetHeight - 54 : (parseFloat(chip.style.top) || 18) + chip.offsetHeight + 5
    panel.style.left = Math.max(8, Math.min(x, view.innerWidth - panel.offsetWidth - 12)) + 'px'
    panel.style.top = Math.max(8, Math.min(y, view.innerHeight - panel.offsetHeight - 12)) + 'px'
  }
  function notice(text) { if (disposed) return; status.hidden = true; panel.hidden = false; panel.replaceChildren(); copy(text); button(t.close, hide, panel); place() }
  function rangeParams(f) {
    return { revision: state.revision, paragraphIndex: f.paragraphIndex, start: f.start, end: f.end, expectedParagraph: f.expectedParagraph, quote: f.quote }
  }
  async function actOnFinding(f, apply) {
    if (busy || state.status !== 'ready' || (apply && !state.writable)) return
    const gen = generation, session = state.session
    busy = true
    try {
      const result = await execute(apply ? 'apply_review_edit' : 'goto_review_range', { ...rangeParams(f), ...(apply ? { replacement: f.replacement } : {}) })
      if (disposed || session !== state.session || (!apply && gen !== generation)) return
      if (!result?.success) { notice(t.failed); return }
      if (apply) { invalidate(); notice(t.saved) }
      else { hide(); schedule() }
    } finally { busy = false }
  }
  function openFromStatus() {
    if (!panelPosition) {
      const rect = status.getBoundingClientRect()
      panelPosition = { x: rect.left || 18, y: rect.top || Math.max(8, view.innerHeight - 42 - 64) }
    }
    openPanel('all')
  }
  function openPanel(mode) {
    if (disposed || !state.session) return
    panelMode = mode; panel.hidden = false; status.hidden = true; panel.replaceChildren()
    const head = copy('', panel, 'awd-ir-head'); const heading = copy(t.title, head); heading.className = 'awd-ir-title'
    const headActions = copy('', head, 'awd-ir-head-actions')
    const collapse = button(t.collapse, hide, headActions)
    collapse.setAttribute('aria-expanded', 'true')
    button(t.close, hide, headActions)
    const body = copy('', panel, 'awd-ir-body')
    copy(errors[state.message] || state.message || (state.status === 'error' ? t.error : state.status === 'stale' ? t.stale : state.status === 'checking' ? t.checking : t.local), body, 'awd-ir-note')
    if (typeof state.summary === 'string' && state.summary) copy(state.summary, body)
    else if (Number.isFinite(state.summary?.findingCount)) copy(t.count(state.summary.findingCount), body, 'awd-ir-note')
    const fresh = context?.revision === state.revision && state.status === 'ready' && state.enabled !== false
    const base = fresh ? (mode === 'current' ? currentFindings() : findings()) : []
    if (mode === 'all' && fresh) {
      const tabs = copy('', body, 'awd-ir-tabs'); tabs.setAttribute('role', 'tablist')
      for (const key of ['all', 'supplement', 'consistency', 'format', 'ai']) {
        const count = tabFindings(base, key).length
        const tab = button(`${t.tabs[key]} ${count}`, () => { activeTab = key; openPanel(mode) }, tabs)
        tab.setAttribute('role', 'tab')
        tab.setAttribute('aria-selected', String(activeTab === key))
      }
    }
    const list = mode === 'all' ? tabFindings(base) : base
    if (!list.length && fresh && state.deepStatus !== 'error' && !state.truncated) copy(t.empty, body)
    for (const f of list) {
      const item = copy('', body, 'awd-ir-item')
      const title = doc.createElement('strong'); title.textContent = f.title || f.kind; item.appendChild(title)
      if (f.quote) copy(f.quote, item, 'awd-ir-note')
      copy(f.message || '', item)
      for (const related of f.related || []) if (related.quote) {
        copy(related.quote, item, 'awd-ir-note')
        if (typeof related.expectedParagraph === 'string') button(t.locate, () => actOnFinding(related, false), item)
      }
      const actions = copy('', item, 'awd-ir-actions')
      if (typeof f.expectedParagraph === 'string' && Number.isInteger(f.start) && Number.isInteger(f.end)) {
        button(t.locate, () => actOnFinding(f, false), actions)
        if (state.writable && typeof f.replacement === 'string') button(t.apply, () => actOnFinding(f, true), actions)
      }
      button(t.ignore, () => { ignored.add(String(f.id)); openPanel(mode); renderStatus(); renderChip() }, actions)
    }
    if (mode === 'current') button(t.list, () => openPanel('all'), body)
    copy(t.scope, body, 'awd-ir-note'); if (state.truncated) copy(t.truncated, body, 'awd-ir-note')
    const actions = copy('', body, 'awd-ir-actions')
    button(t.refresh, () => request('refresh'), actions).disabled = state.status === 'checking' || !state.enabled
    button(state.deepStatus === 'checking' ? t.deepBusy : t.deep, () => request('deep'), actions).disabled = state.deepStatus === 'checking' || !state.enabled || !state.writable
    button(t.insight, () => request('open-insight'), actions)
    button(state.enabled ? t.disable : t.enable, () => request('preferences', { enabled: !state.enabled }), body)
    place()
  }
  function renderChip() {
    chip.hidden = true
    if ((!anchor && !context?.cursorRectRaw?.nativeCaret) || !context?.available || context.hasSelection || context.revision !== state.revision || !state.enabled || state.status !== 'ready' || composing || !currentFindings().length) return
    if (!doc.querySelector('.awd-wa-panel')?.hidden && doc.querySelector('.awd-wa-panel')) return
    const rect = nativeCursorRectToPixels(context.cursorRectRaw, canvas.getBoundingClientRect()) || cursorRectToPixels(context.cursorRectRaw, anchor)
    if (!rect || rect.top < 0 || rect.top > view.innerHeight - 35 || rect.left < 0 || rect.left > view.innerWidth) return
    chip.textContent = t.count(currentFindings().length); chip.hidden = false
    chip.style.left = Math.max(8, Math.min(rect.left + 22, view.innerWidth - chip.offsetWidth - 12)) + 'px'
    chip.style.top = Math.max(8, Math.min(rect.top + rect.height + 4, view.innerHeight - chip.offsetHeight - 12)) + 'px'
  }
  async function refreshContext() {
    if (disposed || composing || !state.session || !state.enabled || (state.status !== 'ready' && !click)) return
    if (inFlight) { again = true; return }
    inFlight = true
    const gen = generation, session = state.session, capturedClick = click
    try {
      const result = await execute('get_review_context', {})
      if (disposed || composing || gen !== generation || session !== state.session) return
      if (capturedClick && capturedClick === click && result.cursorRectRaw?.pos) {
        const raw = result.cursorRectRaw, zero = cursorRectToPixels(raw, { x: 0, y: 0 })
        if (zero) anchor = { x: capturedClick.x - zero.left, y: capturedClick.y - zero.top - zero.height / 2 }
        click = null
      }
      if (result?.revision !== state.revision) return
      context = result
      renderChip()
      if (panelMode) openPanel(panelMode)
    } catch { /* A read failure never interrupts typing. */ }
    finally { inFlight = false; if (again) { again = false; schedule() } }
  }
  function schedule() { clearTimeout(timer); timer = setTimeout(refreshContext, 180) }
  const unsubscribe = transport.subscribe(msg => {
    if (disposed || msg?.__lo !== 'lo-relay' || msg.type !== 'inline-review-state' || !msg.session) return
    const sameRevision = msg.session === state.session && msg.revision != null && msg.revision === state.revision
    const openMode = msg.session === state.session ? panelMode : ''
    const savedContext = sameRevision ? context : null
    if (msg.session !== state.session) {
      ignored.clear(); anchor = null; click = null; panelPosition = null; activeTab = 'all'
      storageKey = msg.layoutKey || `awd_inline_review_panel_${msg.session}`
      try { const saved = JSON.parse(view.sessionStorage?.getItem(storageKey) || 'null'); if (Number.isFinite(saved?.x) && Number.isFinite(saved?.y)) panelPosition = saved } catch {}
    }
    invalidate(false); state = { ...msg, findings: Array.isArray(msg.findings) ? msg.findings.slice(0, 200) : [] }; renderStatus()
    context = savedContext
    if (openMode) openPanel(openMode)
    schedule()
  })
  const moved = () => { invalidate(false); schedule() }
  const onKey = e => { if (e.target === input || e.target === canvas) { click = null; invalidate(false); schedule() } }
  const onClick = e => { if (e.button !== 0) return; invalidate(false); click = { x: e.clientX, y: e.clientY }; schedule() }
  const onScroll = () => { generation++; clearTimeout(timer); timer = 0; context = null; chip.hidden = true; anchor = null; click = null; schedule() }
  const viewport = () => { const r = canvas.getBoundingClientRect(); return [view.innerWidth, view.innerHeight, r.x, r.y, r.width, r.height].join(':') }
  let lastViewport = viewport()
  // The LOWA endpoint also emits synthetic resize heartbeats every second.
  // Only a changed viewport invalidates the coordinate calibration.
  const onResize = () => {
    const next = viewport()
    if (next === lastViewport) return
    lastViewport = next; onScroll()
    if (!panel.hidden) place()
    else placeStatus()
  }
  const onCompositionStart = () => { composing = true; click = null; invalidate(false) }
  const onCompositionEnd = () => { composing = false; schedule() }
  const onBlur = e => { if (!root.contains(e.relatedTarget)) invalidate(false) }
  const onPanelKey = e => { if (e.key === 'Escape') { e.preventDefault(); hide() } }
  const onDragDown = e => {
    const target = e.currentTarget
    if (e.button !== 0 || e.target.closest('button') || (target === panel && !e.target.closest('.awd-ir-head'))) return
    const rect = target.getBoundingClientRect(); drag = { target, dx: e.clientX - rect.left, dy: e.clientY - rect.top }
    e.preventDefault()
  }
  const onPanelMove = e => {
    if (!drag) return
    panelPosition = clampPosition({ x: e.clientX - drag.dx, y: e.clientY - drag.dy }, drag.target)
    if (drag.target === status) placeStatus(); else place()
  }
  const onPanelUp = () => { if (!drag) return; drag = null; savePosition() }
  input.addEventListener('keydown', onKey); input.addEventListener('compositionstart', onCompositionStart); input.addEventListener('compositionend', onCompositionEnd); input.addEventListener('blur', onBlur)
  canvas.addEventListener('mouseup', onClick, true); canvas.addEventListener('wheel', onScroll, { passive: true }); view.addEventListener('resize', onResize); panel.addEventListener('keydown', onPanelKey); panel.addEventListener('mousedown', onDragDown); status.addEventListener('mousedown', onDragDown); view.addEventListener('mousemove', onPanelMove); view.addEventListener('mouseup', onPanelUp)
  const completionPanel = doc.querySelector('.awd-wa-panel')
  const observer = completionPanel ? new view.MutationObserver(() => {
    // The caret-adjacent chip competes with completion choices. A review panel
    // the user explicitly opened and positioned is independent and stays put.
    if (!completionPanel.hidden) chip.hidden = true
    else renderChip()
  }) : null
  observer?.observe(completionPanel, { attributes: true, attributeFilter: ['hidden'] })
  return {
    cursorMoved: moved, committed() { click = null; invalidate(); schedule() }, documentChanged: invalidate,
    destroy() {
      disposed = true; invalidate(); unsubscribe?.(); observer?.disconnect()
      input.removeEventListener('keydown', onKey); input.removeEventListener('compositionstart', onCompositionStart); input.removeEventListener('compositionend', onCompositionEnd); input.removeEventListener('blur', onBlur)
      canvas.removeEventListener('mouseup', onClick, true); canvas.removeEventListener('wheel', onScroll); view.removeEventListener('resize', onResize); panel.removeEventListener('keydown', onPanelKey); panel.removeEventListener('mousedown', onDragDown); status.removeEventListener('mousedown', onDragDown); view.removeEventListener('mousemove', onPanelMove); view.removeEventListener('mouseup', onPanelUp)
      root.remove(); style.remove()
    },
  }
}
