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
// PHASE A (default, no getCursorRect): the overlay covers the whole canvas,
// transparent — the IME candidate box pops at the canvas top-left. Functional but
// the candidate window isn't at the cursor.
//
// PHASE B (when a getCursorRect callback is supplied): the overlay shrinks to a
// small box anchored at the LO view cursor's pixel rect, so the native candidate
// window appears AT the cursor; composing text is shown inline (color turned
// visible) for a what-you-see-is-what-you-get feel, then cleared on commit while
// LO renders the real text. If the cursor rect can't be mapped (WASM mapping
// unstable / API missing), it degrades cleanly back to Phase A (full-cover
// transparent) — the inline-preview half still works wherever the box lands.
//
// Control keys (Enter/Backspace/arrows) act on the overlay, not the doc — same
// limitation as before; forwarding them to Qt is a later item.

// ---------------------------------------------------------------------------
// CALIBRATE (Phase B): map the worker's get_cursor_rect raw output (doc 1/100 mm)
// to host CSS pixels. These constants are refined empirically via the spike's
// debug box + screenshots — the doc-coord ORIGIN (page vs visible-area top-left)
// and the canvas gray-margin offset are not derivable from the API alone.
//   scale   : CSS px per 1/100 mm at zoom 100% (96 px/in ÷ 2540 (1/100 mm)/in).
//   offsetX : px from canvas left to the page's text origin (gray margin + indent).
//   offsetY : px from canvas top  to the page's text origin.
//   useZoom : multiply scale by ZoomValue%/100.
// ---------------------------------------------------------------------------
export const CURSOR_MAP = {
  scale: 96 / 2540,
  offsetX: 0,
  offsetY: 0,
  useZoom: true,
}

/**
 * Convert the worker's get_cursor_rect raw result to a host-relative CSS-px rect.
 * Single source of the mm->px formula so the spike and the app share calibration.
 * @returns {{left:number, top:number, height:number}|null} null if unmappable.
 */
export function cursorRectToPixels(raw) {
  if (!raw || !raw.pos || typeof raw.pos.X !== 'number') return null
  const z = (CURSOR_MAP.useZoom && raw.zoom) ? raw.zoom / 100 : 1
  const k = CURSOR_MAP.scale * z
  const left = CURSOR_MAP.offsetX + raw.pos.X * k
  const top = CURSOR_MAP.offsetY + raw.pos.Y * k
  // caret height: points -> px (1pt = 1/72 in) * zoom; fall back to a sane line.
  const height = raw.charHeightPt ? raw.charHeightPt * (96 / 72) * z : 18
  return { left, top, height }
}

/**
 * Attach a transparent IME overlay to a LibreOffice WASM canvas.
 *
 * @param {HTMLCanvasElement} options.canvas REQUIRED. The booted qtcanvas.
 * @param {(text:string)=>(any|Promise<any>)} options.commit REQUIRED. Inserts the
 *        committed text at the LO cursor (e.g. t => executor.executeCommand(
 *        'insert_at_cursor', {text:t})).
 * @param {()=>Promise<{left:number,top:number,height:number}|null>} [options.getCursorRect]
 *        OPTIONAL. Returns the cursor rect in host CSS px. Supplying it enables
 *        Phase B (cursor-anchored box + inline preview); omitting it keeps Phase A.
 * @param {(msg:string)=>void} [options.onLog] optional progress/diagnostic log.
 * @returns {{element:HTMLInputElement, focus:()=>void, reposition:()=>void, destroy:()=>void}}
 */
export function attachImeOverlay({ canvas, commit, getCursorRect, onLog } = {}) {
  if (!canvas) throw new Error('attachImeOverlay: canvas is required')
  if (typeof commit !== 'function') throw new Error('attachImeOverlay: commit(text) is required')
  const log = (m) => { if (onLog) onLog(m) }

  const phaseB = typeof getCursorRect === 'function'
  let mapOk = phaseB // flips false (and stays Phase A) if mapping ever fails

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

  // Two layout states. Phase A / idle-fallback: cover the whole canvas. Phase B:
  // a small box at the cursor (the native candidate window anchors to its caret).
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

  // Show composing text inline (Phase B) vs invisible (idle / Phase A).
  function setPreviewVisible(on) {
    const show = on && mapOk
    input.style.color = show ? '#111' : 'transparent'
    input.style.caretColor = show ? '#111' : 'transparent'
  }

  // Move the box to the current LO cursor. No-op (cover) in Phase A or if the
  // mapping is unavailable. Async: one worker round-trip per call.
  async function reposition() {
    if (!phaseB || !mapOk) { applyCover(); return }
    try {
      const rect = await getCursorRect()
      if (rect) applyCursorBox(rect)
      else applyCover()
    } catch (e) {
      mapOk = false
      applyCover()
      log('光标映射不可用，退回 Phase A 全覆盖 / cursor map unavailable, Phase A fallback: ' + (e && e.message || e))
    }
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

  // FOCUS-RACE FIX (from the spike): a canvas click positions the LO cursor (Qt
  // handles it) but also steals keyboard focus, so the first keystroke after a
  // click would land on the canvas. Hand focus back after Qt processes the click
  // (mouseup) so ALL typing — including the first char — goes through the overlay,
  // and move the box to the freshly-set cursor so the candidate window anchors there.
  const onMouseUp = () => setTimeout(() => { try { input.focus() } catch (e) {} ; reposition() }, 0)
  canvas.addEventListener('mouseup', onMouseUp)

  const focus = () => { try { input.focus() } catch (e) {} }
  focus()
  reposition()
  log(phaseB
    ? 'IME 覆盖层已挂载 (Phase B：贴光标+inline 预览) / overlay attached — 点文档定位光标后直接打字'
    : 'IME 覆盖层已挂载 (Phase A：全覆盖) / overlay attached — 点文档定位光标后直接打字')

  return {
    element: input,
    focus,
    reposition,
    destroy() {
      canvas.removeEventListener('mouseup', onMouseUp)
      input.remove()
    },
  }
}
