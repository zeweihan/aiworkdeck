// zetaOfficeImeOverlay.js — transparent IME overlay for the LibreOffice WASM
// canvas (Epic #43, Phase A+B). DORMANT until a page calls attachImeOverlay().
//
// WHY: Qt5-WASM gives the <canvas> no usable IME (no candidate box) — an upstream
// limitation, not a bug. To host the system IME we overlay a focusable <input> on
// the canvas:
//   - clicks pass THROUGH (pointer-events:none) so Qt still positions the LO
//     view cursor by the click coords;
//   - keystrokes + IME composition land on the overlay (it holds keyboard focus);
//   - on commit the composed/typed text is inserted at the LO view cursor via the
//     caller's `commit` callback — the SAME verified path as agent commands:
//     executor.executeCommand('insert_at_cursor', {text}).
//
// PHASE A (default, no getCursorRaw): the overlay covers the whole canvas,
// transparent — the IME candidate box pops at the canvas top-left.
//
// PHASE B (when getCursorRaw is supplied): the overlay anchors a small box at the
// LO view cursor's pixel rect so the native candidate window appears AT the
// cursor; composing text shows inline (color turned visible), then clears on
// commit while LO renders the real text.
//
//   MAPPING — doc 1/100 mm -> canvas CSS px is affine: px = scale*doc + offset.
//   * scale is STABLE: 96/2540 CSS px per 1/100 mm at 100% zoom (CSS defines
//     96 px/in; 2540 (1/100 mm)/in), times ZoomValue%. Verified exact against a
//     real LibreOffice (click-correspondence calibration).
//   * offset is VIEW-STATE-DEPENDENT (window size, scroll, LO chrome) so it is
//     NOT a constant. We derive it LIVE: every canvas click gives both the click
//     pixel AND (after Qt positions the cursor) the cursor's doc coords, so
//     offset = clickPx - scale*docPos. The normal flow is "click to place the
//     cursor, then type", so the anchor is always fresh where you're about to
//     type — robust to any window/scroll without magic numbers. Before the first
//     click we have no anchor, so the overlay stays full-cover (Phase A) and the
//     candidate box sits top-left until the first click.
//
// Control keys: Enter inserts a paragraph break via onEnter (forwarded to UNO),
// not into the empty <input>. Backspace/arrows are not yet forwarded.

// The STABLE half of the mapping. offset is derived live per click (see above).
export const CURSOR_MAP = {
  scale: 96 / 2540, // CSS px per 1/100 mm at 100% zoom (verified exact)
  useZoom: true,
}

function scaleFromZoom(raw) {
  const z = (CURSOR_MAP.useZoom && raw && raw.zoom) ? raw.zoom / 100 : 1
  return CURSOR_MAP.scale * z
}

/**
 * Map a worker get_cursor_rect raw result to a host CSS-px rect, given the live
 * origin offset. Pure helper (exported for the spike's debug box).
 * @returns {{left,top,height}|null}
 */
export function cursorRectToPixels(raw, offset) {
  if (!raw || !raw.pos || typeof raw.pos.X !== 'number' || !offset) return null
  const s = scaleFromZoom(raw)
  const z = s / CURSOR_MAP.scale
  return {
    left: offset.x + s * raw.pos.X,
    top: offset.y + s * raw.pos.Y,
    height: raw.charHeightPt ? raw.charHeightPt * (96 / 72) * z : 18,
  }
}

/**
 * Attach a transparent IME overlay to a LibreOffice WASM canvas.
 *
 * @param {HTMLCanvasElement} options.canvas REQUIRED. The booted qtcanvas.
 * @param {(text:string)=>(any|Promise<any>)} options.commit REQUIRED. Inserts the
 *        committed text at the LO cursor (e.g. t => executor.executeCommand(
 *        'insert_at_cursor', {text:t})).
 * @param {()=>Promise<object>} [options.getCursorRaw] OPTIONAL. Returns the
 *        worker's get_cursor_rect raw result ({pos:{X,Y}, zoom, charHeightPt}).
 *        Supplying it enables Phase B (cursor-anchored box + inline preview).
 * @param {()=>(any|Promise<any>)} [options.onEnter] OPTIONAL. Inserts a paragraph
 *        break at the LO cursor (e.g. () => executor.executeCommand(
 *        'insert_paragraph')). Without it, Enter does nothing.
 * @param {(msg:string)=>void} [options.onLog] optional progress/diagnostic log.
 * @returns {{element, focus, reposition, computeRect, destroy}}
 */
export function attachImeOverlay({ canvas, commit, getCursorRaw, onEnter, onLog } = {}) {
  if (!canvas) throw new Error('attachImeOverlay: canvas is required')
  if (typeof commit !== 'function') throw new Error('attachImeOverlay: commit(text) is required')
  const log = (m) => { if (onLog) onLog(m) }

  const phaseB = typeof getCursorRaw === 'function'
  let mapOk = phaseB     // flips false (-> Phase A) if a raw read ever throws
  let anchor = null      // {x,y} live origin offset in host px, set on canvas click

  const input = document.createElement('input')
  input.setAttribute('autocomplete', 'off')
  input.setAttribute('aria-hidden', 'true')
  Object.assign(input.style, {
    position: 'absolute', top: '0', left: '0',
    margin: '0', padding: '0', border: '0', outline: '0',
    background: 'transparent', color: 'transparent', caretColor: 'transparent',
    font: 'inherit', lineHeight: '1',
    pointerEvents: 'none', // clicks fall through to the canvas (Qt positions cursor)
    zIndex: '5',
    whiteSpace: 'pre', overflow: 'hidden',
  })

  // The overlay must live in a positioned ancestor that covers the canvas.
  const host = canvas.parentElement || canvas
  if (getComputedStyle(host).position === 'static') host.style.position = 'relative'
  host.appendChild(input)

  function applyCover() {
    Object.assign(input.style, { left: '0', top: '0', width: '100%', height: '100%' })
  }
  function applyCursorBox(rect) {
    Object.assign(input.style, {
      left: Math.round(rect.left) + 'px',
      top: Math.round(rect.top) + 'px',
      width: '16em',
      height: Math.round(rect.height) + 'px',
    })
  }
  applyCover()

  function setPreviewVisible(on) {
    const show = on && mapOk && anchor
    input.style.color = show ? '#111' : 'transparent'
    input.style.caretColor = show ? '#111' : 'transparent'
  }

  // Re-derive the live origin offset from a canvas click: clickPx (host-relative)
  // and the cursor's doc coords AT that click give offset = clickPx - scale*pos.
  // Snap (click -> nearest glyph) leaves sub-char error, self-corrected next click.
  async function anchorFromClick(clientX, clientY) {
    if (!phaseB || !mapOk) return
    try {
      const raw = await getCursorRaw()
      if (!raw || !raw.pos) return
      const s = scaleFromZoom(raw)
      const r = host.getBoundingClientRect()
      // pos.Y is the caret's TOP; a click lands ~mid-line, so the click Y sits
      // ~half a caret-height BELOW the caret top. Subtract that, else the box
      // renders a half-line too low (lands between lines). X snaps to a glyph
      // boundary near the click, so no horizontal correction is needed.
      const z = s / CURSOR_MAP.scale
      const halfCaret = raw.charHeightPt ? raw.charHeightPt * (96 / 72) * z / 2 : 9
      anchor = {
        x: (clientX - r.left) - s * raw.pos.X,
        y: (clientY - r.top) - halfCaret - s * raw.pos.Y,
      }
    } catch (e) {
      mapOk = false
      log('光标映射不可用，退回 Phase A 全覆盖 / cursor map unavailable: ' + (e && e.message || e))
    }
  }

  // Compute the current cursor rect in host px (null until anchored). Exported via
  // the return for the spike's debug box.
  async function computeRect() {
    if (!phaseB || !mapOk || !anchor) return null
    try {
      const raw = await getCursorRaw()
      return cursorRectToPixels(raw, anchor)
    } catch (e) { mapOk = false; return null }
  }

  // Move the box to the current LO cursor. Cover (Phase A) until first click.
  async function reposition() {
    const rect = await computeRect()
    if (rect) applyCursorBox(rect); else applyCover()
  }

  // Commit logic: identical to the verified toolbar bridge. dedup the input event
  // that trails compositionend so a committed phrase isn't inserted twice.
  let composing = false, skipNextInput = false
  const doCommit = (t) => {
    input.value = ''
    if (!t) return
    log('IME 覆盖层 → 上屏「' + t + '」')
    try { Promise.resolve(commit(t)).catch((e) => log('overlay commit error: ' + (e && e.message || e))) }
    catch (e) { log('overlay commit error: ' + (e && e.message || e)) }
  }
  input.addEventListener('compositionstart', () => { composing = true; setPreviewVisible(true) })
  input.addEventListener('compositionend', (e) => {
    composing = false; skipNextInput = true
    setPreviewVisible(false)
    doCommit(e.data)
    reposition() // cursor advanced past the committed text
  })
  input.addEventListener('input', (e) => {
    if (composing) return                                  // mid-composition: wait for end
    if (skipNextInput) { skipNextInput = false; return }   // trailing event after compositionend
    doCommit(e.data != null ? e.data : input.value)
    reposition()
  })

  // Enter -> paragraph break at the LO cursor (NOT into the single-line input).
  // Only when NOT composing — during composition Enter confirms the IME candidate.
  input.addEventListener('keydown', (e) => {
    if (composing || e.isComposing) return
    if (e.key === 'Enter') {
      e.preventDefault()
      if (typeof onEnter !== 'function') return
      log('IME 覆盖层 → 回车换行 / paragraph break')
      try { Promise.resolve(onEnter()).then(reposition).catch((err) => log('overlay enter error: ' + (err && err.message || err))) }
      catch (err) { log('overlay enter error: ' + (err && err.message || err)) }
    }
  })

  // FOCUS-RACE FIX (from the spike): a canvas click positions the LO cursor (Qt
  // handles it) but also steals keyboard focus, so the first keystroke after a
  // click would land on the canvas. Hand focus back after Qt processes the click
  // (mouseup), and at the same time re-anchor the live offset + move the box to
  // the freshly-set cursor so the candidate window shows up there.
  const onMouseUp = (e) => {
    const cx = e.clientX, cy = e.clientY
    setTimeout(() => {
      try { input.focus() } catch (err) {}
      anchorFromClick(cx, cy).then(reposition)
    }, 0)
  }
  canvas.addEventListener('mouseup', onMouseUp)

  const focus = () => { try { input.focus() } catch (e) {} }
  focus()
  reposition()
  log(phaseB
    ? 'IME 覆盖层已挂载 (Phase B：点文档放光标→框贴光标+inline 预览) / overlay attached'
    : 'IME 覆盖层已挂载 (Phase A：全覆盖) / overlay attached')

  return {
    element: input,
    focus,
    reposition,
    computeRect,
    destroy() {
      canvas.removeEventListener('mouseup', onMouseUp)
      input.remove()
    },
  }
}
