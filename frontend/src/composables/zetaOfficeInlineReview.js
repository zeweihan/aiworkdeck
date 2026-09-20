// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { cursorRectToPixels, nativeCursorRectToPixels } from './zetaOfficeImeOverlay.js'
import { isFresh, visibleFindings } from '../utils/inlineReviewGrouping.js'

const LABELS = {
  zh: { title: '即时审校', icon: '审', checking: '正在检查', stale: '正文已变化，等待重新检查', error: '检查暂不可用', open: '打开审校清单', count: n => `${n} 条提示` },
  en: { title: 'Inline review', icon: 'R', checking: 'Checking…', stale: 'Text changed. Waiting for a fresh check.', error: 'Checks are unavailable', open: 'Open the review list', count: n => `${n} suggestions` },
}

/**
 * 正文里的那一层：只有一颗浮球和光标旁的小标记（dev-board#723/#724）。
 *
 * WHY：380px 的浮窗压着正文、关不掉，律师读一段要先把它拖开。清单搬到宿主右栏的
 * 审阅面板（第五个标签「审校」），这里只保留「有几条」和「点一下打开」。
 * 显示、检查、忽略都不改文档；浮球位置与显隐是本机习惯，不落进 docx。
 */
export function attachInlineReview({ canvas, input, execute, transport, language = 'zh-CN' }) {
  const doc = canvas.ownerDocument, view = doc.defaultView
  const english = language.startsWith('en')
  const t = LABELS[english ? 'en' : 'zh']
  let state = { session: '', enabled: false, hidden: false, revision: null, status: 'disabled', findings: [] }
  let generation = 0, sequence = 0, timer = 0, disposed = false, composing = false, inFlight = false, again = false
  let anchor = null, click = null, context = null
  let ballPosition = null, drag = null, storageKey = ''
  const root = doc.createElement('div'); root.className = 'awd-inline-review'
  // 色值全部走 editor.html 头部那套 --awd-*：本 composable 注入的是编辑器页自己的
  // document（canvas.ownerDocument，桌面壳里是 <webview> 的独立文档），宿主 App.vue
  // 的 :root 令牌继承不进来，所以令牌表由 editor.html 自带一份。深浅两套靠
  // html.theme-dark 上的令牌取值切换，这里不再写 .theme-dark 分支。
  const style = doc.createElement('style')
  style.textContent = `.awd-inline-review{position:fixed;inset:0;z-index:9998;pointer-events:none;font:13px/1.5 system-ui,sans-serif;color:var(--awd-text)}.awd-inline-review button{font:inherit;color:inherit;border:1px solid var(--awd-border);border-radius:6px;background:var(--awd-surface);padding:5px 9px;cursor:pointer}.awd-inline-review button:focus-visible{outline:2px solid var(--awd-accent-text);outline-offset:2px}.awd-ir-ball{pointer-events:auto;position:absolute;left:18px;bottom:64px;display:flex;align-items:center;gap:5px;padding:6px 10px;border-radius:999px;box-shadow:var(--awd-shadow-md);cursor:pointer;user-select:none}.awd-ir-ball.busy{opacity:.7}.awd-ir-ball-i{font-weight:600}.awd-ir-ball-n{min-width:17px;padding:0 5px;border-radius:999px;background:var(--awd-accent);color:var(--awd-text-on-accent);font-size:11px;text-align:center}.awd-ir-chip{pointer-events:auto;position:absolute;box-shadow:var(--awd-shadow-md);white-space:nowrap}.awd-inline-review [hidden]{display:none!important}`
  doc.head.appendChild(style); doc.body.appendChild(root)
  function button(label, action, parent) {
    const b = doc.createElement('button'); b.type = 'button'; b.textContent = label
    b.addEventListener('mousedown', e => e.preventDefault())
    b.addEventListener('click', () => { Promise.resolve().then(action).catch(() => {}) })
    parent.appendChild(b); return b
  }
  const ball = button('', openPanel, root); ball.className = 'awd-ir-ball'; ball.hidden = true
  const ballIcon = doc.createElement('span'); ballIcon.className = 'awd-ir-ball-i'; ballIcon.textContent = t.icon
  const ballCount = doc.createElement('span'); ballCount.className = 'awd-ir-ball-n'; ballCount.hidden = true
  ball.replaceChildren(ballIcon, ballCount)
  const chip = button('', openPanel, root); chip.className = 'awd-ir-chip'; chip.hidden = true
  function findings() { return visibleFindings(state.findings, null) }
  function currentFindings() { return findings().filter(f => f.paragraphIndex === context?.paragraphIndex && (!f.expectedParagraph || f.expectedParagraph === context.text)) }
  function invalidate(markStale = true) {
    generation++; clearTimeout(timer); timer = 0; context = null; chip.hidden = true
    if (markStale) state = { ...state, status: state.enabled === false ? 'disabled' : 'stale' }
    renderBall()
  }
  function request(action, data) {
    if (!state.session || disposed) return
    transport.send({ __lo: 'lo-relay', type: 'inline-review-request', session: state.session, id: ++sequence, revision: state.revision, action, ...(data ? { data } : {}) })
  }
  /** 浮球/行旁标记的唯一动作：让宿主打开右栏审阅面板的「审校」标签。 */
  function openPanel() { if (!drag || !drag.moved) request('open-panel') }
  function statusLabel() {
    return state.status === 'checking' ? t.checking
      : state.status === 'stale' ? t.stale
        : state.status === 'error' ? t.error
          : `${t.title} · ${t.count(findings().length)}`
  }
  function renderBall() {
    // 关闭态在正文里不挂任何东西——「已关闭」的提示本身就是打扰（dev-board#723）。
    const on = !!state.session && state.enabled !== false && state.hidden !== true
    ball.hidden = !on
    if (!on) return
    const count = findings().length
    ballCount.textContent = count ? String(count) : ''
    ballCount.hidden = !count
    ball.classList.toggle('busy', state.status === 'checking')
    const label = `${statusLabel()}（${t.open}）`
    ball.title = label; ball.setAttribute('aria-label', label)
    placeBall()
  }
  function clampPosition(pos) {
    const width = ball.offsetWidth || 64, height = ball.offsetHeight || 32
    return { x: Math.max(8, Math.min(pos.x, view.innerWidth - width - 8)), y: Math.max(8, Math.min(pos.y, view.innerHeight - height - 8)) }
  }
  /** 靠边吸附：松手后回到最近的一侧，只保留纵向位置。 */
  function snapPosition(pos) {
    const width = ball.offsetWidth || 64
    const left = pos.x + width / 2 < view.innerWidth / 2
    return clampPosition({ x: left ? 12 : view.innerWidth - width - 12, y: pos.y })
  }
  // 位置用 localStorage：客体页每开一份文档就是一个新 webview（sessionStorage 每次重来），
  // 而浮球摆在哪是跨文档的本机习惯，与 awd_sidebar_collapsed 同一口径。
  function savePosition() { try { if (storageKey && ballPosition) view.localStorage?.setItem(storageKey, JSON.stringify(ballPosition)) } catch {} }
  function placeBall() {
    if (!ballPosition || ball.hidden) return
    ballPosition = clampPosition(ballPosition)
    ball.style.left = ballPosition.x + 'px'; ball.style.top = ballPosition.y + 'px'; ball.style.bottom = 'auto'
  }
  function renderChip() {
    chip.hidden = true
    if (state.hidden === true) return
    if ((!anchor && !context?.cursorRectRaw?.nativeCaret) || !context?.available || context.hasSelection || context.revision !== state.revision || !isFresh(state) || composing || !currentFindings().length) return
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
    } catch { /* A read failure never interrupts typing. */ }
    finally { inFlight = false; if (again) { again = false; schedule() } }
  }
  function schedule() { clearTimeout(timer); timer = setTimeout(refreshContext, 180) }
  const unsubscribe = transport.subscribe(msg => {
    if (disposed || msg?.__lo !== 'lo-relay' || msg.type !== 'inline-review-state' || !msg.session) return
    const sameRevision = msg.session === state.session && msg.revision != null && msg.revision === state.revision
    const savedContext = sameRevision ? context : null
    if (msg.session !== state.session) {
      anchor = null; click = null; ballPosition = null
      storageKey = msg.layoutKey || `awd_inline_review_panel_${msg.session}`
      try { const saved = JSON.parse(view.localStorage?.getItem(storageKey) || 'null'); if (Number.isFinite(saved?.x) && Number.isFinite(saved?.y)) ballPosition = saved } catch {}
    }
    invalidate(false); state = { ...msg, findings: Array.isArray(msg.findings) ? msg.findings.slice(0, 200) : [] }; renderBall()
    context = savedContext
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
    if (ballPosition) { ballPosition = snapPosition(ballPosition); placeBall() }
  }
  const onCompositionStart = () => { composing = true; click = null; invalidate(false) }
  const onCompositionEnd = () => { composing = false; schedule() }
  const onBlur = e => { if (!root.contains(e.relatedTarget)) invalidate(false) }
  const onDragDown = e => {
    if (e.button !== 0) return
    const rect = ball.getBoundingClientRect()
    drag = { dx: e.clientX - rect.left, dy: e.clientY - rect.top, x0: e.clientX, y0: e.clientY, moved: false }
    e.preventDefault()
  }
  const onDragMove = e => {
    if (!drag) return
    // 4px 阈值：手抖不算拖动，否则每次点浮球都变成「拖了一下」而打不开面板。
    if (!drag.moved && Math.abs(e.clientX - drag.x0) + Math.abs(e.clientY - drag.y0) <= 4) return
    drag.moved = true
    ballPosition = clampPosition({ x: e.clientX - drag.dx, y: e.clientY - drag.dy })
    placeBall()
  }
  const onDragUp = () => {
    if (!drag) return
    if (drag.moved && ballPosition) { ballPosition = snapPosition(ballPosition); placeBall(); savePosition() }
    const finished = drag
    // click 紧接着 mouseup 派发，等它过去再清掉拖动标记。
    view.setTimeout(() => { if (drag === finished) drag = null }, 0)
  }
  input.addEventListener('keydown', onKey); input.addEventListener('compositionstart', onCompositionStart); input.addEventListener('compositionend', onCompositionEnd); input.addEventListener('blur', onBlur)
  canvas.addEventListener('mouseup', onClick, true); canvas.addEventListener('wheel', onScroll, { passive: true }); view.addEventListener('resize', onResize)
  ball.addEventListener('mousedown', onDragDown); view.addEventListener('mousemove', onDragMove); view.addEventListener('mouseup', onDragUp)
  const completionPanel = doc.querySelector('.awd-wa-panel')
  const observer = completionPanel ? new view.MutationObserver(() => {
    // The caret-adjacent chip competes with completion choices.
    if (!completionPanel.hidden) chip.hidden = true
    else renderChip()
  }) : null
  observer?.observe(completionPanel, { attributes: true, attributeFilter: ['hidden'] })
  return {
    cursorMoved: moved, committed() { click = null; invalidate(); schedule() }, documentChanged: invalidate,
    destroy() {
      disposed = true; invalidate(); unsubscribe?.(); observer?.disconnect()
      input.removeEventListener('keydown', onKey); input.removeEventListener('compositionstart', onCompositionStart); input.removeEventListener('compositionend', onCompositionEnd); input.removeEventListener('blur', onBlur)
      canvas.removeEventListener('mouseup', onClick, true); canvas.removeEventListener('wheel', onScroll); view.removeEventListener('resize', onResize)
      ball.removeEventListener('mousedown', onDragDown); view.removeEventListener('mousemove', onDragMove); view.removeEventListener('mouseup', onDragUp)
      root.remove(); style.remove()
    },
  }
}
