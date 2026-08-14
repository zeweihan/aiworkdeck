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
// PHASE A (default, no getCursorRaw): the overlay covers the whole canvas until
// the first click, then follows the last click point — the IME candidate box
// pops there instead of the canvas top-left.
//
// PHASE B (when getCursorRaw is supplied): the overlay anchors a small box at the
// LO view cursor's pixel rect so the native candidate window appears AT the
// cursor.
//
// 组合中的文字（"输入过程"）由一个独立的不透明预览条显示，贴在光标框上方——
// 输入框本身必须全透明（它压在画布上，显字会与正文叠印），而预览条不依赖光标
// 映射，Phase A 下照样可见。
//
//   MAPPING — doc 1/100 mm -> canvas CSS px is affine: px = origin + scale*(doc - scroll).
//   * scale is STABLE: 96/2540 CSS px per 1/100 mm at 100% zoom (CSS defines
//     96 px/in; 2540 (1/100 mm)/in), times ZoomValue%. Verified exact against a
//     real LibreOffice (click-correspondence calibration).
//   * scroll is the SCROLLED view origin (VisibleTop/Left from get_cursor_rect's
//     viewData). getPosition() is in document coords (from the page top), so once
//     the view scrolls the cursor's doc Y jumps while its pixel stays in view.
//     Subtracting `scroll` makes the mapping track the cursor through scroll
//     WITHOUT re-clicking — the fix for the "anchor goes stale after auto-scroll"
//     limitation. Absent viewData (older LOWA) -> scroll=0 -> identical to the
//     pre-scroll-aware behavior. Unit (1/100 mm vs twips) is baked once on a real
//     device via CURSOR_MAP.viewDataToMm.
//   * origin is the canvas pixel of the visible-area top-left — VIEW-STATE-
//     DEPENDENT (window size, LO chrome) but STABLE under scroll. We derive it
//     LIVE from each canvas click: the click gives both the click pixel AND
//     (after Qt positions the cursor) the cursor's doc coords + scroll, so
//     origin = clickPx - scale*(docPos - scroll). The normal flow is "click to
//     place the cursor, then type", so the anchor is always fresh. Before the
//     first click we have no anchor, so the overlay stays full-cover (Phase A)
//     and the candidate box sits top-left until the first click.
//
// Control keys (Phase B, when sendCommand is supplied): Enter inserts a paragraph
// break via onEnter; Backspace/Delete delete around the cursor; arrow keys move
// the LO view cursor; desktop shortcuts (Cmd/Ctrl+Z/Y undo-redo, +A select all,
// +C/X/V clipboard, +B/I/U format toggles, Home/End(+Shift sel), Cmd/Option/Ctrl
// +arrows line-word-doc nav, Tab, Shift+Enter soft break, Esc deselect, PageUp/
// Down) — all forwarded to UNO, not applied to the empty <input>. Without
// sendCommand these keys fall through to the (harmless, empty) input.

// The STABLE half of the mapping. offset is derived live per click (see above).
// nudgeX/nudgeY are a small constant px correction for the residual between the
// click-derived anchor and the true caret (glyph-snap + caret-top vs click
// estimate) — roughly constant at a given zoom; tune once, then bake.
export const CURSOR_MAP = {
  scale: 96 / 2540, // CSS px per 1/100 mm at 100% zoom (verified exact)
  useZoom: true,
  nudgeX: 0,
  nudgeY: 0,
  // Unit of viewData scroll (VisibleTop/Left) -> 1/100 mm. 1 if viewData is
  // already 1/100 mm; set 2540/1440 (~1.7639) if a real device shows it's twips.
  viewDataToMm: 1,
}

function scaleFromZoom(raw) {
  const z = (CURSOR_MAP.useZoom && raw && raw.zoom) ? raw.zoom / 100 : 1
  return CURSOR_MAP.scale * z
}

// Scrolled view origin in 1/100 mm from get_cursor_rect's viewData, or null if
// absent. Prefer VisibleTop/Left; fall back to ViewTop/Left.
function scrollMm(raw) {
  const vd = raw && raw.viewData
  if (!vd) return null
  const k = CURSOR_MAP.viewDataToMm || 1
  const top = typeof vd.VisibleTop === 'number' ? vd.VisibleTop : vd.ViewTop
  const left = typeof vd.VisibleLeft === 'number' ? vd.VisibleLeft : vd.ViewLeft
  if (typeof top !== 'number' || typeof left !== 'number') return null
  return { x: left * k, y: top * k }
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
  const sc = scrollMm(raw) || { x: 0, y: 0 }
  return {
    left: offset.x + s * (raw.pos.X - sc.x) + (CURSOR_MAP.nudgeX || 0),
    top: offset.y + s * (raw.pos.Y - sc.y) + (CURSOR_MAP.nudgeY || 0),
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
 * @param {(action:string,params:object)=>Promise<any>} [options.sendCommand]
 *        OPTIONAL. A raw UI-command channel to the worker (e.g. (a,p) =>
 *        workerRequest(a,p)). Enables control-key forwarding: Backspace ->
 *        delete_backward, Delete -> delete_forward, arrow keys -> move_cursor,
 *        plus desktop shortcuts (undo/redo/select-all/clipboard/format/line-nav).
 *        Without it, those keys fall through to the (empty) input and the
 *        document is unaffected.
 * @param {(msg:string)=>void} [options.onLog] optional progress/diagnostic log.
 * @returns {{element, focus, reposition, computeRect, destroy}}
 */
export function attachImeOverlay({ canvas, commit, getCursorRaw, onEnter, sendCommand, onLog } = {}) {
  if (!canvas) throw new Error('attachImeOverlay: canvas is required')
  if (typeof commit !== 'function') throw new Error('attachImeOverlay: commit(text) is required')
  const log = (m) => { if (onLog) onLog(m) }

  const phaseB = typeof getCursorRaw === 'function'
  let mapOk = phaseB     // flips false (-> Phase A) if a raw read ever throws
  let anchor = null      // {x,y} live origin offset in host px, set on canvas click
  let lastClick = null   // 最近一次画布点击（host 相对 px）——没有光标映射时的摆位依据
  let lastBox = null     // 最近一次算出的光标框，组合预览条据此贴在光标上方

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

  // 组合预览条。输入法的候选窗只显示候选，正在拼的那串字（"输入过程"）是画在
  // 输入框里的——而这个框必须全透明（它压在画布上，显字会和正文叠在一起）。所以
  // 单独挂一个不透明小条来显示组合中的文字：它独立于映射是否可用，未点过画布、
  // 光标映射失败时照样看得见（真机反馈：看不到输入过程）。
  const preview = document.createElement('div')
  preview.setAttribute('aria-hidden', 'true')
  Object.assign(preview.style, {
    position: 'absolute', display: 'none', zIndex: '6',
    maxWidth: '32em', padding: '2px 7px',
    background: '#fff', border: '1px solid #C7CDD3', borderRadius: '5px',
    boxShadow: '0 2px 8px rgba(15, 23, 42, 0.16)',
    font: '14px/1.45 system-ui, -apple-system, "PingFang SC", sans-serif',
    color: '#1F2937', whiteSpace: 'pre', overflow: 'hidden', textOverflow: 'ellipsis',
    textDecoration: 'underline', textDecorationColor: '#9CA3AF',
    pointerEvents: 'none',
  })
  host.appendChild(preview)

  function applyCover() {
    Object.assign(input.style, { left: '0', top: '0', width: '100%', height: '100%' })
    lastBox = null
    positionPreview()
  }
  function applyCursorBox(rect) {
    Object.assign(input.style, {
      left: Math.round(rect.left) + 'px',
      top: Math.round(rect.top) + 'px',
      width: '16em',
      height: Math.round(rect.height) + 'px',
    })
    lastBox = rect
    positionPreview()
  }

  // 预览条贴在光标框**上方**：系统候选窗弹在输入框下方，两者错开才不会互相盖住。
  // 顶到画布上边时翻到下方。没有光标框就退回最后点击处，再退回左上角。
  function positionPreview() {
    if (preview.style.display === 'none') return
    const box = lastBox
      || (lastClick ? { left: lastClick.x, top: Math.max(0, lastClick.y - 9), height: 18 } : { left: 8, top: 24, height: 18 })
    const above = box.top - preview.offsetHeight - 4
    preview.style.left = Math.max(4, Math.round(box.left)) + 'px'
    preview.style.top = Math.round(above >= 2 ? above : box.top + box.height + 4) + 'px'
  }
  function showPreview(text) {
    if (!text) { preview.style.display = 'none'; return }
    preview.textContent = text
    preview.style.display = 'block'
    positionPreview()
  }
  function hidePreview() { preview.style.display = 'none' }

  applyCover()

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
      // ~half a caret-height below the caret top — subtract that so the box top
      // is near the caret top, not a half-line low. This assumes a roughly
      // center-of-line click; the residual (click-height variance, glyph snap on
      // X) is a small ~constant the maintainer zeroes once via CURSOR_MAP.nudge.
      const z = s / CURSOR_MAP.scale
      const halfCaret = raw.charHeightPt ? raw.charHeightPt * (96 / 72) * z / 2 : 8
      const sc = scrollMm(raw) || { x: 0, y: 0 }
      // origin = visible-area top-left in px (stable under scroll); see header.
      anchor = {
        x: (clientX - r.left) - s * (raw.pos.X - sc.x),
        y: (clientY - r.top) - halfCaret - s * (raw.pos.Y - sc.y),
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

  // Move the box to the current LO cursor. 拿不到光标映射时（没点过画布 / 映射
  // 失败）退回**最后一次点击处**而不是全覆盖：系统候选窗跟着输入框走，落在用户
  // 刚点的地方总比钉在画布左上角强。一次都没点过才全覆盖。
  async function reposition() {
    const rect = await computeRect()
    if (rect) applyCursorBox(rect)
    else if (lastClick) applyCursorBox({ left: lastClick.x, top: Math.max(0, lastClick.y - 9), height: 18 })
    else applyCover()
  }

  // Commit logic: identical to the verified toolbar bridge. dedup the input event
  // that trails compositionend so a committed phrase isn't inserted twice.
  //
  // 这个闩曾经是「中文标点要按两次」的根因：compositionend 后无条件置位，指望
  // 紧跟着一定有一个 input 事件来把它消费掉。可**不是每次都有**——中文态下标点
  // 直接上屏、组合被取消等情形都不补发，闩就一直挂着，把用户随后敲的第一个字符
  // 吃掉，于是"按两次才过去"。两道保险：
  //   1) 有 inputType 时只吞组合产物（insertCompositionText/insertFromComposition），
  //      普通字符（直接上屏的标点走 insertText）一律照常上屏；
  //   2) 无论如何都在下一个宏任务里自动解闩——尾随 input 与 compositionend 由浏览器
  //      在同一个任务里连发，解闩排在它之后，闩绝不跨事件循环存活。
  let composing = false, skipNextInput = false
  const armSkip = () => {
    skipNextInput = true
    setTimeout(() => { skipNextInput = false }, 0)
  }
  const isCompositionInput = (e) => e.inputType === 'insertCompositionText' || e.inputType === 'insertFromComposition'
  const doCommit = (t) => {
    input.value = ''
    if (!t) return
    log('IME 覆盖层 → 上屏「' + t + '」')
    try { Promise.resolve(commit(t)).catch((e) => log('overlay commit error: ' + (e && e.message || e))) }
    catch (e) { log('overlay commit error: ' + (e && e.message || e)) }
  }
  input.addEventListener('compositionstart', () => { composing = true })
  input.addEventListener('compositionupdate', (e) => showPreview(e.data || ''))
  input.addEventListener('compositionend', (e) => {
    composing = false; armSkip()
    hidePreview()
    doCommit(e.data)
    reposition() // cursor advanced past the committed text
  })
  input.addEventListener('input', (e) => {
    if (composing) { showPreview(input.value); return }    // mid-composition: wait for end
    if (skipNextInput) {
      skipNextInput = false
      // 只吞组合产物；直接上屏的标点带 insertText，必须放行（见 armSkip 注释）
      if (!e.inputType || isCompositionInput(e)) return
    }
    doCommit(e.data != null ? e.data : input.value)
    reposition()
  })

  // Forward a worker UI command, then move the box to the (now-moved) cursor.
  const forward = (action, params, label) => {
    log('IME 覆盖层 → ' + label)
    try { Promise.resolve(sendCommand(action, params || {})).then(reposition).catch((err) => log('overlay ' + action + ' error: ' + (err && err.message || err))) }
    catch (err) { log('overlay ' + action + ' error: ' + (err && err.message || err)) }
  }
  const ARROW_DIR = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' }
  const isMac = /Mac/i.test((navigator && navigator.platform) || '')

  // Clipboard: the document selection lives in LO (not the DOM), so native
  // copy/cut/paste can't see it — bridge through navigator.clipboard + the
  // worker's selection primitives instead. Paste replaces the selection (same
  // as desktop); multi-line text becomes real paragraph breaks downstream.
  const copySelection = (cut) => {
    log('IME 覆盖层 → ' + (cut ? 'Cmd+X 剪切 / cut' : 'Cmd+C 复制 / copy'))
    Promise.resolve(sendCommand('get_selection', {}))
      .then((r) => {
        const text = r && r.text
        if (!text) return
        return navigator.clipboard.writeText(text).then(() => {
          // selection-aware tracked delete — same engine path as Backspace
          if (cut) return Promise.resolve(sendCommand('delete_backward', {})).then(reposition)
        })
      })
      .catch((err) => log('overlay clipboard error: ' + (err && err.message || err)))
  }
  const pasteClipboard = () => {
    navigator.clipboard.readText()
      .then((t) => {
        if (!t) return
        log('IME 覆盖层 → Cmd+V 粘贴 / paste「' + (t.length > 20 ? t.slice(0, 20) + '…' : t) + '」')
        return Promise.resolve(sendCommand('replace_selection', { text: t })).then(reposition)
      })
      .catch((err) => log('overlay paste error: ' + (err && err.message || err)))
  }

  // Control keys -> UNO (NOT into the single-line input). Only when NOT composing:
  // during composition these keys drive the IME candidate window. Enter routes to
  // onEnter; Backspace/arrows route through sendCommand (skipped if not supplied,
  // letting them fall through to the harmless empty input).
  input.addEventListener('keydown', (e) => {
    if (composing || e.isComposing) return
    if (e.key === 'Enter') {
      e.preventDefault()
      // Shift+Enter = soft line break (same paragraph), like the desktop app.
      if (e.shiftKey && typeof sendCommand === 'function') {
        forward('ui_command', { name: 'line_break' }, 'Shift+Enter 软回车 / line break')
        return
      }
      if (typeof onEnter !== 'function') return
      log('IME 覆盖层 → 回车换行 / paragraph break')
      try { Promise.resolve(onEnter()).then(reposition).catch((err) => log('overlay enter error: ' + (err && err.message || err))) }
      catch (err) { log('overlay enter error: ' + (err && err.message || err)) }
      return
    }
    if (typeof sendCommand !== 'function') return
    // Desktop shortcuts (Cmd on mac / Ctrl on win). Unmatched mod-combos fall
    // through UNPREVENTED so host-level accelerators (e.g. save) keep working.
    if (e.metaKey || e.ctrlKey) {
      const k = String(e.key).toLowerCase()
      if (k === 'z') {
        e.preventDefault()
        forward(e.shiftKey ? 'redo' : 'undo', {}, e.shiftKey ? '重做 / redo' : '撤销 / undo')
      } else if (k === 'y') {
        e.preventDefault()
        forward('redo', {}, '重做 / redo')
      } else if (k === 'a') {
        e.preventDefault()
        forward('ui_command', { name: 'select_all' }, '全选 / select all')
      } else if (k === 'b' || k === 'i' || k === 'u') {
        e.preventDefault()
        const name = k === 'b' ? 'bold' : k === 'i' ? 'italic' : 'underline'
        forward('ui_command', { name }, '格式 ' + name)
      } else if (k === 'c') {
        e.preventDefault(); copySelection(false)
      } else if (k === 'x') {
        e.preventDefault(); copySelection(true)
      } else if (k === 'v') {
        e.preventDefault(); pasteClipboard()
      } else if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        // mac：Cmd+←/→ = 行首/行尾；Windows：Ctrl+←/→ = 上一词/下一词。
        // Shift 叠加 = 同方向选择。
        e.preventDefault()
        const left = e.key === 'ArrowLeft'
        const base = isMac ? (left ? 'line_start' : 'line_end') : (left ? 'word_left' : 'word_right')
        const name = e.shiftKey ? base + '_sel' : base
        forward('ui_command', { name }, name)
      } else if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
        // mac 惯用 Cmd+↑/↓ = 文首/文尾
        e.preventDefault()
        forward('goto', { type: e.key === 'ArrowUp' ? 'start' : 'end' }, e.key === 'ArrowUp' ? '文首 / doc start' : '文尾 / doc end')
      }
      return
    }
    // Option/Alt+←/→ = 上一词/下一词（mac 惯用；Shift 叠加选择）
    if (e.altKey && (e.key === 'ArrowLeft' || e.key === 'ArrowRight')) {
      e.preventDefault()
      const base = e.key === 'ArrowLeft' ? 'word_left' : 'word_right'
      const name = e.shiftKey ? base + '_sel' : base
      forward('ui_command', { name }, name)
      return
    }
    if (e.key === 'Home' || e.key === 'End') {
      e.preventDefault()
      const base = e.key === 'Home' ? 'line_start' : 'line_end'
      const name = e.shiftKey ? base + '_sel' : base
      forward('ui_command', { name }, e.key + (e.shiftKey ? '+Shift 选择' : ''))
      return
    }
    if (e.key === 'PageUp' || e.key === 'PageDown') {
      e.preventDefault()
      forward('ui_command', { name: e.key === 'PageUp' ? 'page_up' : 'page_down' }, e.key)
      return
    }
    if (e.key === 'Escape') {
      e.preventDefault()
      forward('ui_command', { name: 'escape' }, 'Esc 取消选区 / deselect')
      return
    }
    if (e.key === 'Tab') {
      // 制表符入文档（浏览器默认的焦点切换在画布上无意义）
      e.preventDefault()
      forward('insert_at_cursor', { text: '\t' }, 'Tab 制表符 / tab')
      return
    }
    if (e.key === 'Backspace') {
      e.preventDefault()
      forward('delete_backward', {}, 'Backspace 删除 / delete')
    } else if (e.key === 'Delete') {
      e.preventDefault()
      forward('delete_forward', {}, 'Delete 前删 / forward delete')
    } else if (ARROW_DIR[e.key]) {
      e.preventDefault()
      forward('move_cursor', { dir: ARROW_DIR[e.key], extend: e.shiftKey }, '方向键 ' + ARROW_DIR[e.key] + (e.shiftKey ? '+选择' : ''))
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
      // 光标映射不可用时就靠它摆输入框/预览条——所以每次点击都记，不看 phaseB。
      const r = host.getBoundingClientRect()
      lastClick = { x: cx - r.left, y: cy - r.top }
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
      preview.remove()
    },
  }
}
