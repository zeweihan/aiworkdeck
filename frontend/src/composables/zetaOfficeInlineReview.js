// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { cursorRectToPixels, nativeCursorRectToPixels } from './zetaOfficeImeOverlay.js'
import { isFresh, visibleFindings } from '../utils/inlineReviewGrouping.js'

// 贴边把手：宽度够点中（16px，hover 再宽 4px），高度与浮球同量级。右侧留出引擎
// 自画的纵向滚动条那一条，把手不压滚动条。
const DOCK_WIDTH = 16, DOCK_HEIGHT = 48, SCROLLBAR_GUTTER = 18

const LABELS = {
  zh: {
    title: 'AI 审校', icon: '审', checking: '正在检查', stale: '正文已变化，等待重新检查', error: '检查暂不可用',
    open: '打开 AI 审校清单', count: n => `${n} 条提示`,
    menu: 'AI 审校设置', aiOn: '关闭 AI 审校', aiOff: '开启 AI 审校', runNow: '立即 AI 审校', collapse: '收起到边缘', expand: '展开 AI 审校浮球',
    aiBusy: 'AI 审校中', aiOffState: 'AI 审校已关闭（规则检查照常）',
    blocked: { DEEP_QUOTA: 'AI 审校已暂停：账户额度不足', DEEP_RATE_LIMITED: 'AI 审校已暂停：模型服务限流', DEEP_MODEL_UNAVAILABLE: 'AI 审校已暂停：当前辅助模型不可用', DEEP_REGION: 'AI 审校已暂停：服务商按地域拒绝' },
  },
  en: {
    title: 'AI review', icon: 'R', checking: 'Checking…', stale: 'Text changed. Waiting for a fresh check.', error: 'Checks are unavailable',
    open: 'Open the AI review list', count: n => `${n} suggestions`,
    menu: 'AI review settings', aiOn: 'Turn AI review off', aiOff: 'Turn AI review on', runNow: 'Run AI review now', collapse: 'Collapse to edge', expand: 'Expand the AI review ball',
    aiBusy: 'AI review running', aiOffState: 'AI review is off (rule checks still run)',
    blocked: { DEEP_QUOTA: 'AI review paused: the account is out of credit', DEEP_RATE_LIMITED: 'AI review paused: the model service is rate limiting', DEEP_MODEL_UNAVAILABLE: 'AI review paused: the auxiliary model is unavailable', DEEP_REGION: 'AI review paused: the provider rejected this network region' },
  },
}

/**
 * 正文里的那一层：只有一颗浮球和光标旁的小标记（dev-board#723/#724）。
 *
 * WHY：380px 的浮窗压着正文、关不掉，律师读一段要先把它拖开。清单搬到宿主右栏的
 * 审阅面板（「AI 审校」标签），这里只保留「有几条」和「点一下打开」。
 * 显示、检查、忽略都不改文档；浮球位置与显隐是本机习惯，不落进 docx。
 *
 * dev-board#749：AI 审校的开关也搬到这颗浮球上（工具栏不再有「审校」按钮）——
 * 开关必须和它控制的那个东西待在一起。浮球主体仍是「打开清单」，右半边的 ▾
 * 弹出三项小菜单（开/关、立即跑一次、隐藏浮球）。**浮球不再随开关消失**：
 * 关掉之后还得有地方再打开它。规则检查始终在跑，所以计数在关闭态照常显示。
 *
 * dev-board#866：「隐藏正文浮球」改成「收起到边缘」。真隐藏之后唯一的找回入口在右栏
 * 面板里，面板关着的人根本不知道去哪找。现在收起 = 浮球贴到它所在那一侧的边缘，
 * 只露一截可点的把手（.awd-ir-dock），点一下就展开回原位。持久化沿用原来的
 * hidden 偏好（协议字段与存储键都没改，只是语义从「不挂」变成「贴边」）。
 */
export function attachInlineReview({ canvas, input, execute, transport, language = 'zh-CN' }) {
  const doc = canvas.ownerDocument, view = doc.defaultView
  const english = language.startsWith('en')
  const t = LABELS[english ? 'en' : 'zh']
  let state = { session: '', ai: true, hidden: false, revision: null, status: 'disabled', findings: [], deepStatus: 'idle', autoBlocked: '' }
  let generation = 0, sequence = 0, timer = 0, disposed = false, composing = false, inFlight = false, again = false
  let anchor = null, click = null, context = null
  let ballPosition = null, ballSize = { width: 64, height: 32 }, drag = null, storageKey = ''
  const root = doc.createElement('div'); root.className = 'awd-inline-review'
  // 色值全部走 editor.html 头部那套 --awd-*：本 composable 注入的是编辑器页自己的
  // document（canvas.ownerDocument，桌面壳里是 <webview> 的独立文档），宿主 App.vue
  // 的 :root 令牌继承不进来，所以令牌表由 editor.html 自带一份。深浅两套靠
  // html.theme-dark 上的令牌取值切换，这里不再写 .theme-dark 分支。
  const style = doc.createElement('style')
  style.textContent = `.awd-inline-review{position:fixed;inset:0;z-index:9998;pointer-events:none;font:13px/1.5 system-ui,sans-serif;color:var(--awd-text)}.awd-inline-review button{font:inherit;color:inherit;border:1px solid var(--awd-border);border-radius:6px;background:var(--awd-surface);padding:5px 9px;cursor:pointer}.awd-inline-review button:focus-visible{outline:2px solid var(--awd-accent-text);outline-offset:2px}.awd-ir-ball{pointer-events:auto;position:absolute;left:18px;bottom:64px;display:flex;align-items:center;border:1px solid var(--awd-border);border-radius:999px;background:var(--awd-surface);box-shadow:var(--awd-shadow-md);cursor:pointer;user-select:none}.awd-ir-ball.busy{opacity:.7}.awd-ir-ball.off{opacity:.6}.awd-inline-review .awd-ir-ball button{border:0;background:none;border-radius:999px;padding:6px 9px}.awd-ir-open{display:flex;align-items:center;gap:5px}.awd-ir-more{padding-left:4px!important;opacity:.75}.awd-ir-ball-i{font-weight:600}.awd-ir-ball-n{min-width:17px;padding:0 5px;border-radius:999px;background:var(--awd-accent);color:var(--awd-text-on-accent);font-size:11px;text-align:center}.awd-ir-ball.off .awd-ir-ball-n{background:var(--awd-surface-3);color:var(--awd-text-2)}.awd-ir-menu{pointer-events:auto;position:absolute;display:flex;flex-direction:column;gap:2px;min-width:160px;padding:4px;border:1px solid var(--awd-border);border-radius:8px;background:var(--awd-surface);box-shadow:var(--awd-shadow-md)}.awd-inline-review .awd-ir-menu button{border:0;background:none;text-align:left;white-space:nowrap}.awd-inline-review .awd-ir-menu button:hover{background:var(--awd-surface-2)}.awd-ir-chip{pointer-events:auto;position:absolute;box-shadow:var(--awd-shadow-md);white-space:nowrap}.awd-inline-review .awd-ir-dock{pointer-events:auto;position:absolute;display:flex;align-items:center;justify-content:center;width:${DOCK_WIDTH}px;height:${DOCK_HEIGHT}px;padding:0;border:1px solid var(--awd-border-strong);background:var(--awd-surface);box-shadow:var(--awd-shadow-md);transition:width .12s ease,background-color .12s ease,border-color .12s ease}.awd-inline-review .awd-ir-dock.left{left:0;border-left:0;border-radius:0 12px 12px 0}.awd-inline-review .awd-ir-dock.right{border-right:0;border-radius:12px 0 0 12px}.awd-inline-review .awd-ir-dock:hover{width:${DOCK_WIDTH + 4}px;background:var(--awd-surface-2);border-color:var(--awd-accent-text)}.awd-ir-dock-g{display:block;width:3px;height:20px;border-radius:2px;background:var(--awd-accent-text);transition:height .12s ease}.awd-inline-review .awd-ir-dock:hover .awd-ir-dock-g{height:26px}.awd-ir-dock.has .awd-ir-dock-g{box-shadow:0 0 0 2px var(--awd-accent-soft)}.awd-inline-review [hidden]{display:none!important}`
  doc.head.appendChild(style); doc.body.appendChild(root)
  function button(label, action, parent) {
    const b = doc.createElement('button'); b.type = 'button'; b.textContent = label
    b.addEventListener('mousedown', e => e.preventDefault())
    b.addEventListener('click', () => { Promise.resolve().then(action).catch(() => {}) })
    parent.appendChild(b); return b
  }
  const ball = doc.createElement('div'); ball.className = 'awd-ir-ball'; ball.hidden = true; root.appendChild(ball)
  const ballOpen = button('', openPanel, ball); ballOpen.className = 'awd-ir-open'
  const ballIcon = doc.createElement('span'); ballIcon.className = 'awd-ir-ball-i'; ballIcon.textContent = t.icon
  const ballCount = doc.createElement('span'); ballCount.className = 'awd-ir-ball-n'; ballCount.hidden = true
  ballOpen.replaceChildren(ballIcon, ballCount)
  const ballMore = button('▾', toggleMenu, ball); ballMore.className = 'awd-ir-more'
  ballMore.title = t.menu; ballMore.setAttribute('aria-label', t.menu)
  ballMore.setAttribute('aria-haspopup', 'menu'); ballMore.setAttribute('aria-expanded', 'false')
  const menu = doc.createElement('div'); menu.className = 'awd-ir-menu'; menu.hidden = true
  menu.setAttribute('role', 'menu'); root.appendChild(menu)
  // 三项都是真 <button>：Tab 能走到、Enter/空格能按、Esc 关闭并把焦点还给 ▾。
  const menuAi = button('', () => { request('preferences', { ai: state.ai === false }); closeMenu() }, menu)
  const menuRun = button(t.runNow, () => { request('deep'); closeMenu() }, menu)
  const menuHide = button(t.collapse, () => { request('preferences', { hidden: true }); closeMenu() }, menu)
  for (const item of [menuAi, menuRun, menuHide]) item.setAttribute('role', 'menuitem')
  const chip = button('', openPanel, root); chip.className = 'awd-ir-chip'; chip.hidden = true
  // 收起态的把手：一个真 <button>（Tab 走得到、Enter 按得动），点一下展开回原位。
  const dock = button('', () => request('preferences', { hidden: false }), root); dock.className = 'awd-ir-dock left'; dock.hidden = true
  const dockGrip = doc.createElement('span'); dockGrip.className = 'awd-ir-dock-g'; dock.appendChild(dockGrip)
  function findings() { return visibleFindings(state.findings, null) }
  function currentFindings() { return findings().filter(f => f.paragraphIndex === context?.paragraphIndex && (!f.expectedParagraph || f.expectedParagraph === context.text)) }
  function invalidate(markStale = true) {
    generation++; clearTimeout(timer); timer = 0; context = null; chip.hidden = true
    if (markStale) state = { ...state, status: state.status === 'disabled' ? 'disabled' : 'stale' }
    renderBall()
  }
  function request(action, data) {
    if (!state.session || disposed) return
    transport.send({ __lo: 'lo-relay', type: 'inline-review-request', session: state.session, id: ++sequence, revision: state.revision, action, ...(data ? { data } : {}) })
  }
  /** 浮球/行旁标记的唯一动作：让宿主打开右栏审阅面板的「AI 审校」标签。 */
  function openPanel() { if (!drag || !drag.moved) { closeMenu(); request('open-panel') } }
  function closeMenu(focusBack) {
    if (menu.hidden) return
    menu.hidden = true; ballMore.setAttribute('aria-expanded', 'false')
    if (focusBack) ballMore.focus()
  }
  function toggleMenu() {
    if (drag && drag.moved) return
    if (!menu.hidden) { closeMenu(); return }
    menu.hidden = false; ballMore.setAttribute('aria-expanded', 'true')
    placeMenu()
    menuAi.focus()
  }
  function placeMenu() {
    if (menu.hidden) return
    const rect = ball.getBoundingClientRect(), width = menu.offsetWidth || 160, height = menu.offsetHeight || 96
    const left = rect.left + width + 8 > view.innerWidth ? Math.max(8, rect.right - width) : rect.left
    const above = rect.top - height - 6
    menu.style.left = left + 'px'
    menu.style.top = (above >= 8 ? above : Math.min(rect.bottom + 6, view.innerHeight - height - 8)) + 'px'
  }
  function statusLabel() {
    // AI 那一层的三态压过规则那一层的状态：浮球上只有一行字，先说最要紧的那件事。
    if (state.deepStatus === 'checking') return t.aiBusy
    if (state.ai === false) return t.aiOffState
    if (state.autoBlocked && t.blocked[state.autoBlocked]) return t.blocked[state.autoBlocked]
    return state.status === 'checking' ? t.checking
      : state.status === 'stale' ? t.stale
        : state.status === 'error' ? t.error
          : `${t.title} · ${t.count(findings().length)}`
  }
  function renderBall() {
    // 浮球不再随开关消失（dev-board#749）：开关就在它身上，藏了就没地方再打开。
    // 只有这份文档没有审校（会话结束）时才整个不挂；用户「收起」只是贴边（dev-board#866）。
    const live = !!state.session && state.status !== 'disabled'
    const docked = live && state.hidden === true
    ball.hidden = !live || docked
    dock.hidden = !docked
    if (!live || docked) closeMenu()
    if (!live) return
    // 规则检查始终在跑，所以计数在 AI 关闭态照常显示。
    const count = findings().length
    if (docked) {
      const label = `${t.expand}（${statusLabel()}）`
      dock.title = label; dock.setAttribute('aria-label', label)
      dock.classList.toggle('has', count > 0)
      placeDock(); return
    }
    ballCount.textContent = count ? String(count) : ''
    ballCount.hidden = !count
    ball.classList.toggle('busy', state.status === 'checking' || state.deepStatus === 'checking')
    ball.classList.toggle('off', state.ai === false)
    const label = `${statusLabel()}（${t.open}）`
    ballOpen.title = label; ballOpen.setAttribute('aria-label', label)
    menuAi.textContent = state.ai === false ? t.aiOff : t.aiOn
    menuRun.disabled = state.deepStatus === 'checking' || state.writable === false
    placeBall(); placeMenu()
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
    if (ball.offsetWidth) ballSize = { width: ball.offsetWidth, height: ball.offsetHeight }
  }
  /**
   * 把手贴在浮球所在那一侧（浮球在右就吸右边），纵向对着浮球的中线；没拖过的浮球
   * 在默认的左下角，把手也在左下。右侧让开滚动条那一条。
   */
  function placeDock() {
    if (dock.hidden) return
    const right = !!ballPosition && ballPosition.x + ballSize.width / 2 >= view.innerWidth / 2
    dock.classList.toggle('right', right); dock.classList.toggle('left', !right)
    // 左侧靠 .left 的 left:0；右侧用 right 定位，hover 变宽时向正文一侧长，不压滚动条。
    dock.style.right = right ? SCROLLBAR_GUTTER + 'px' : ''
    if (ballPosition) {
      const top = ballPosition.y + (ballSize.height - DOCK_HEIGHT) / 2
      dock.style.top = Math.max(8, Math.min(top, view.innerHeight - DOCK_HEIGHT - 8)) + 'px'; dock.style.bottom = 'auto'
    } else {
      // 与 .awd-ir-ball 的默认 bottom:64px 同一条中线。
      dock.style.top = 'auto'; dock.style.bottom = Math.max(8, 64 + (ballSize.height - DOCK_HEIGHT) / 2) + 'px'
    }
  }
  function renderChip() {
    chip.hidden = true
    // 收起到边缘就是「别打扰我」：行旁标记一起安静，计数只在把手的提示里。
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
    if (disposed || composing || !state.session || state.status === 'disabled' || (state.status !== 'ready' && !click)) return
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
  const onClick = e => { if (e.button !== 0) return; closeMenu(); invalidate(false); click = { x: e.clientX, y: e.clientY }; schedule() }
  const onScroll = () => { closeMenu(); generation++; clearTimeout(timer); timer = 0; context = null; chip.hidden = true; anchor = null; click = null; schedule() }
  const viewport = () => { const r = canvas.getBoundingClientRect(); return [view.innerWidth, view.innerHeight, r.x, r.y, r.width, r.height].join(':') }
  let lastViewport = viewport()
  // The LOWA endpoint also emits synthetic resize heartbeats every second.
  // Only a changed viewport invalidates the coordinate calibration.
  const onResize = () => {
    const next = viewport()
    if (next === lastViewport) return
    lastViewport = next; onScroll()
    if (ballPosition) { ballPosition = snapPosition(ballPosition); placeBall() }
    placeDock()
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
    closeMenu()
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
  // 菜单自己的键盘：只管自己这几个按钮，不碰画布与覆盖层的按键（见 doc-editor.md
  // 「覆盖层的控制键在 document 捕获阶段还有一层代收」那条——宿主自己的 DOM 面板
  // 里的按键一概不上交）。
  const onMenuKey = e => {
    if (e.key === 'Escape') { e.stopPropagation(); closeMenu(true); return }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return
    const items = [menuAi, menuRun, menuHide].filter(b => !b.disabled)
    const at = items.indexOf(doc.activeElement)
    const next = e.key === 'ArrowDown' ? at + 1 : at - 1
    items[(next + items.length) % items.length]?.focus()
    e.preventDefault()
  }
  input.addEventListener('keydown', onKey); input.addEventListener('compositionstart', onCompositionStart); input.addEventListener('compositionend', onCompositionEnd); input.addEventListener('blur', onBlur)
  canvas.addEventListener('mouseup', onClick, true); canvas.addEventListener('wheel', onScroll, { passive: true }); view.addEventListener('resize', onResize)
  ball.addEventListener('mousedown', onDragDown); view.addEventListener('mousemove', onDragMove); view.addEventListener('mouseup', onDragUp)
  menu.addEventListener('keydown', onMenuKey)
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
      menu.removeEventListener('keydown', onMenuKey)
      root.remove(); style.remove()
    },
  }
}
