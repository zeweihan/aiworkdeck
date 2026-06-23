// zetaOfficeImeOverlay.js — transparent IME overlay for the LibreOffice WASM
// canvas (Epic #43, Phase A). DORMANT until a page calls attachImeOverlay().
//
// WHY: Qt5-WASM gives the <canvas> no usable IME (no candidate box) — an upstream
// limitation, not a bug. To host the system IME we overlay a transparent, focusable
// <input> on the canvas:
//   - clicks pass THROUGH (pointer-events:none) so Qt still positions the LO
//     view cursor by the click coords;
//   - keystrokes + IME composition land on the overlay (it holds keyboard focus);
//   - on commit the composed/typed text is inserted at the LO view cursor via the
//     caller's `commit` callback — the SAME verified path as agent commands:
//     executor.executeCommand('insert_at_cursor', {text}).
//
// PHASE A (this module): the overlay is NOT positioned at the cursor and shows no
// inline composition preview — the IME candidate box pops at the overlay's
// top-left. This matches the previously-accepted toolbar-input bridge, just moved
// onto the document so typing feels in-place. PHASE B will map the UNO view-cursor
// rect to canvas pixels so the overlay follows the cursor and previews inline.
//
// Control keys (Enter/Backspace/arrows) act on the empty overlay, not the doc —
// same limitation as the toolbar bridge; forwarding them to Qt is a Phase B item.

/**
 * Attach a transparent IME overlay to a LibreOffice WASM canvas.
 *
 * @param {HTMLCanvasElement} options.canvas REQUIRED. The booted qtcanvas.
 * @param {(text:string)=>(any|Promise<any>)} options.commit REQUIRED. Inserts the
 *        committed text at the LO cursor (e.g. t => executor.executeCommand(
 *        'insert_at_cursor', {text:t})).
 * @param {(msg:string)=>void} [options.onLog] optional progress/diagnostic log.
 * @returns {{element:HTMLInputElement, focus:()=>void, destroy:()=>void}}
 */
export function attachImeOverlay({ canvas, commit, onLog } = {}) {
  if (!canvas) throw new Error('attachImeOverlay: canvas is required')
  if (typeof commit !== 'function') throw new Error('attachImeOverlay: commit(text) is required')
  const log = (m) => { if (onLog) onLog(m) }

  const input = document.createElement('input')
  input.setAttribute('autocomplete', 'off')
  input.setAttribute('aria-hidden', 'true')
  Object.assign(input.style, {
    position: 'absolute', top: '0', left: '0', width: '100%', height: '100%',
    margin: '0', padding: '0', border: '0', outline: '0',
    background: 'transparent', color: 'transparent', caretColor: 'transparent',
    font: 'inherit',
    pointerEvents: 'none', // clicks fall through to the canvas (Qt positions cursor)
    zIndex: '5',
  })

  // The overlay must live in a positioned ancestor that covers the canvas.
  const host = canvas.parentElement || canvas
  if (getComputedStyle(host).position === 'static') host.style.position = 'relative'
  host.appendChild(input)

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
  input.addEventListener('compositionstart', () => { composing = true })
  input.addEventListener('compositionend', (e) => { composing = false; skipNextInput = true; doCommit(e.data) })
  input.addEventListener('input', (e) => {
    if (composing) return                                  // mid-composition: wait for end
    if (skipNextInput) { skipNextInput = false; return }   // trailing event after compositionend
    doCommit(e.data != null ? e.data : input.value)
  })

  // FOCUS-RACE FIX (from the spike): a canvas click positions the LO cursor (Qt
  // handles it) but also steals keyboard focus, so the first keystroke after a
  // click would land on the canvas. Hand focus back after Qt processes the click
  // (mouseup) so ALL typing — including the first char — goes through the overlay.
  // The cursor position is set by the click coords, not by sustained focus.
  const refocus = () => setTimeout(() => { try { input.focus() } catch (e) {} }, 0)
  canvas.addEventListener('mouseup', refocus)

  const focus = () => { try { input.focus() } catch (e) {} }
  focus()
  log('IME 覆盖层已挂载 / overlay attached — 点文档定位光标后直接打字')

  return {
    element: input,
    focus,
    destroy() {
      canvas.removeEventListener('mouseup', refocus)
      input.remove()
    },
  }
}
