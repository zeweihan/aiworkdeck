// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { cursorRectToPixels } from './zetaOfficeImeOverlay.js'

const LABELS = {
  zh: { title: '即时审校', disabled: '即时审校已关闭', enable: '开启即时检查', disable: '关闭即时检查', checking: '正在检查', stale: '正文已变化，等待重新检查', error: '检查暂不可用', empty: '当前检查未发现问题', list: '查看全部问题', close: '关闭', locate: '定位原文', apply: '采用建议', ignore: '忽略本条', refresh: '重新检查', deep: '深入审校（AI）', deepBusy: '深入审校中…', insight: '打开依据与在线核验', scope: '当前检查范围：正文段落；表格、页眉页脚等请使用完整核验。', truncated: '本次检查有截断，不能视为全文检查完成。', local: '即时规则检查', saved: '已采用建议，可撤销。', failed: '正文或版本已变化，请重新检查。', count: n => `${n} 条提示` },
  en: { title: 'Inline review', disabled: 'Inline review is off', enable: 'Enable live checks', disable: 'Disable live checks', checking: 'Checking…', stale: 'Text changed. Waiting for a fresh check.', error: 'Checks are unavailable', empty: 'No issues found in the checked text', list: 'View all issues', close: 'Close', locate: 'Locate text', apply: 'Apply suggestion', ignore: 'Dismiss this issue', refresh: 'Check again', deep: 'Deep review (AI)', deepBusy: 'Deep review running…', insight: 'Open sources and online verification', scope: 'Scope: body paragraphs. Use full verification for tables, headers and footers.', truncated: 'This check was truncated; it does not cover the whole document.', local: 'Live rule checks', saved: 'Suggestion applied. Undo is available.', failed: 'The document or version changed. Check again.', count: n => `${n} suggestions` },
}

/** A transient guest DOM layer. Showing, inspecting and dismissing findings never edits the document. */
export function attachInlineReview({ canvas, input, execute, transport, language = 'zh-CN' }) {
  const doc = canvas.ownerDocument, view = doc.defaultView
  const english = language.startsWith('en')
  const t = LABELS[english ? 'en' : 'zh']
  const errors = english ? { REVIEW_INLINE_REVISIONS: 'Switch from inline tracked changes to margin or final view to review.', REVIEW_SNAPSHOT_FAILED: 'The current document could not be read. Try checking again.', REVIEW_FAILED: 'Review failed. Try checking again.', REVIEW_DEEP_INCOMPLETE: 'Deep review did not finish completely. Only completed checks are shown below; you can retry.' } : { REVIEW_INLINE_REVISIONS: '请切换到页边修订或最终视图后再审校。', REVIEW_SNAPSHOT_FAILED: '暂时无法读取当前文档，请重新检查。', REVIEW_FAILED: '本次审校未完成，请重新检查。', REVIEW_DEEP_INCOMPLETE: '深入审校未完整完成，以下仅为已完成的检查，可重试。' }
  let state = { session: '', enabled: false, revision: null, status: 'disabled', findings: [] }
  let generation = 0, sequence = 0, timer = 0, disposed = false, composing = false, inFlight = false, again = false
  let anchor = null, click = null, context = null, panelMode = '', busy = false
  const ignored = new Set()
  const root = doc.createElement('div'); root.className = 'awd-inline-review'
  const style = doc.createElement('style')
  style.textContent = `.awd-inline-review{position:fixed;inset:0;z-index:9998;pointer-events:none;font:13px/1.5 system-ui,sans-serif;color:#26332e}.awd-inline-review button{font:inherit;color:inherit;border:1px solid #ccd6ce;border-radius:6px;background:#fff;padding:5px 9px;cursor:pointer}.awd-inline-review button:focus-visible{outline:2px solid #527866;outline-offset:2px}.awd-inline-review button:disabled{opacity:.55;cursor:default}.awd-ir-status{pointer-events:auto;position:absolute;bottom:12px;left:18px;max-width:45vw}.awd-ir-chip{pointer-events:auto;position:absolute;box-shadow:0 2px 8px #0002;white-space:nowrap}.awd-ir-panel{pointer-events:auto;position:absolute;width:360px;max-width:calc(100vw - 24px);max-height:55vh;overflow:auto;padding:12px;background:#fff;border:1px solid #ccd6ce;border-radius:9px;box-shadow:0 6px 24px #0002}.awd-ir-head,.awd-ir-actions{display:flex;align-items:center;gap:7px;flex-wrap:wrap}.awd-ir-head{justify-content:space-between}.awd-ir-item{border-top:1px solid #e1e7e2;margin-top:10px;padding-top:9px}.awd-ir-copy{white-space:pre-wrap;word-break:break-word;margin:6px 0}.awd-ir-note{font-size:11px;color:#64736a;margin:7px 0}.theme-dark .awd-inline-review{color:#e1e8e3}.theme-dark .awd-inline-review button,.theme-dark .awd-ir-panel{background:#202622;border-color:#465148}.theme-dark .awd-ir-note{color:#b0bcb3}.awd-inline-review [hidden]{display:none!important}`
  doc.head.appendChild(style); doc.body.appendChild(root)
  function button(label, action, parent) {
    const b = doc.createElement('button'); b.type = 'button'; b.textContent = label
    b.addEventListener('mousedown', e => e.preventDefault())
    b.addEventListener('click', () => { Promise.resolve().then(action).catch(() => notice(t.failed)) })
    parent.appendChild(b); return b
  }
  const status = button(t.title, () => openPanel('all'), root); status.className = 'awd-ir-status'; status.hidden = true
  const chip = button('', () => openPanel('current'), root); chip.className = 'awd-ir-chip'; chip.hidden = true
  const panel = doc.createElement('section'); panel.className = 'awd-ir-panel'; panel.hidden = true; panel.setAttribute('role', 'dialog'); panel.setAttribute('aria-label', t.title); root.appendChild(panel)
  function copy(text, parent = panel, className = 'awd-ir-copy') {
    const el = doc.createElement('div'); el.className = className; el.textContent = text; parent.appendChild(el); return el
  }
  function findings() { return (state.findings || []).filter(f => !ignored.has(String(f.id))) }
  function currentFindings() { return findings().filter(f => f.paragraphIndex === context?.paragraphIndex && (!f.expectedParagraph || f.expectedParagraph === context.text)) }
  function hide() { chip.hidden = true; panel.hidden = true; panelMode = '' }
  function invalidate() { generation++; clearTimeout(timer); timer = 0; context = null; hide() }
  function request(action, data) {
    if (!state.session || disposed) return
    if (action === 'deep' && state.deepStatus === 'checking') return
    transport.send({ __lo: 'lo-relay', type: 'inline-review-request', session: state.session, id: ++sequence, revision: state.revision, action, ...(data ? { data } : {}) })
    if (action === 'deep') { state = { ...state, deepStatus: 'checking' }; renderStatus(); if (panelMode) openPanel(panelMode) }
  }
  function renderStatus() {
    status.hidden = !state.session
    const label = state.enabled === false ? t.disabled : state.status === 'checking' ? t.checking : state.status === 'stale' ? t.stale : state.status === 'error' ? t.error : `${t.title} · ${t.count(findings().length)}`
    status.textContent = label; status.title = t.list
  }
  function place() {
    const x = chip.hidden ? 18 : parseFloat(chip.style.left) || 18
    const y = chip.hidden ? view.innerHeight - panel.offsetHeight - 54 : (parseFloat(chip.style.top) || 18) + chip.offsetHeight + 5
    panel.style.left = Math.max(8, Math.min(x, view.innerWidth - panel.offsetWidth - 12)) + 'px'
    panel.style.top = Math.max(8, Math.min(y, view.innerHeight - panel.offsetHeight - 12)) + 'px'
  }
  function notice(text) { if (disposed) return; panel.hidden = false; panel.replaceChildren(); copy(text); button(t.close, hide, panel); place() }
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
  function openPanel(mode) {
    if (disposed || composing || !state.session) return
    panelMode = mode; panel.hidden = false; panel.replaceChildren()
    const head = copy('', panel, 'awd-ir-head'); copy(t.title, head); button(t.close, hide, head)
    copy(errors[state.message] || state.message || (state.status === 'error' ? t.error : state.status === 'stale' ? t.stale : state.status === 'checking' ? t.checking : t.local), panel, 'awd-ir-note')
    if (typeof state.summary === 'string' && state.summary) copy(state.summary)
    else if (Number.isFinite(state.summary?.findingCount)) copy(t.count(state.summary.findingCount), panel, 'awd-ir-note')
    const fresh = context?.revision === state.revision && state.status === 'ready' && state.enabled !== false
    const list = fresh ? (mode === 'current' ? currentFindings() : findings()) : []
    if (!list.length && fresh && state.deepStatus !== 'error' && !state.truncated) copy(t.empty)
    for (const f of list) {
      const item = copy('', panel, 'awd-ir-item')
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
    if (mode === 'current') button(t.list, () => openPanel('all'), panel)
    copy(t.scope, panel, 'awd-ir-note'); if (state.truncated) copy(t.truncated, panel, 'awd-ir-note')
    const actions = copy('', panel, 'awd-ir-actions')
    button(t.refresh, () => request('refresh'), actions).disabled = state.status === 'checking' || !state.enabled
    button(state.deepStatus === 'checking' ? t.deepBusy : t.deep, () => request('deep'), actions).disabled = state.deepStatus === 'checking' || !state.enabled || !state.writable
    button(t.insight, () => request('open-insight'), actions)
    button(state.enabled ? t.disable : t.enable, () => request('preferences', { enabled: !state.enabled }), panel)
    place()
  }
  function renderChip() {
    chip.hidden = true
    if (!anchor || !context?.available || context.hasSelection || context.revision !== state.revision || !state.enabled || state.status !== 'ready' || composing || !currentFindings().length) return
    if (!doc.querySelector('.awd-wa-panel')?.hidden && doc.querySelector('.awd-wa-panel')) return
    const rect = cursorRectToPixels(context.cursorRectRaw, anchor)
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
    const openMode = sameRevision ? panelMode : ''
    const savedContext = sameRevision ? context : null
    if (msg.session !== state.session) { ignored.clear(); anchor = null; click = null }
    invalidate(); state = { ...msg, findings: Array.isArray(msg.findings) ? msg.findings.slice(0, 200) : [] }; renderStatus()
    context = savedContext
    if (openMode) openPanel(openMode)
    schedule()
  })
  const moved = () => { invalidate(); schedule() }
  const onKey = e => { if (e.target === input || e.target === canvas) { click = null; moved() } }
  const onClick = e => { if (e.button !== 0) return; invalidate(); click = { x: e.clientX, y: e.clientY }; schedule() }
  const onScroll = () => { invalidate(); anchor = null; click = null }
  const viewport = () => { const r = canvas.getBoundingClientRect(); return [view.innerWidth, view.innerHeight, r.x, r.y, r.width, r.height].join(':') }
  let lastViewport = viewport()
  // The LOWA endpoint also emits synthetic resize heartbeats every second.
  // Only a changed viewport invalidates the coordinate calibration.
  const onResize = () => { const next = viewport(); if (next !== lastViewport) { lastViewport = next; onScroll() } }
  const onCompositionStart = () => { composing = true; click = null; invalidate() }
  const onCompositionEnd = () => { composing = false; schedule() }
  const onBlur = e => { if (!root.contains(e.relatedTarget)) invalidate() }
  const onPanelKey = e => { if (e.key === 'Escape') { e.preventDefault(); hide() } }
  input.addEventListener('keydown', onKey); input.addEventListener('compositionstart', onCompositionStart); input.addEventListener('compositionend', onCompositionEnd); input.addEventListener('blur', onBlur)
  canvas.addEventListener('mouseup', onClick, true); canvas.addEventListener('wheel', onScroll, { passive: true }); view.addEventListener('resize', onResize); panel.addEventListener('keydown', onPanelKey)
  const completionPanel = doc.querySelector('.awd-wa-panel')
  const observer = completionPanel ? new view.MutationObserver(() => { if (!completionPanel.hidden) hide() }) : null
  observer?.observe(completionPanel, { attributes: true, attributeFilter: ['hidden'] })
  return {
    cursorMoved: moved, committed() { click = null; moved() }, documentChanged: invalidate,
    destroy() {
      disposed = true; invalidate(); unsubscribe?.(); observer?.disconnect()
      input.removeEventListener('keydown', onKey); input.removeEventListener('compositionstart', onCompositionStart); input.removeEventListener('compositionend', onCompositionEnd); input.removeEventListener('blur', onBlur)
      canvas.removeEventListener('mouseup', onClick, true); canvas.removeEventListener('wheel', onScroll); view.removeEventListener('resize', onResize); panel.removeEventListener('keydown', onPanelKey)
      root.remove(); style.remove()
    },
  }
}
