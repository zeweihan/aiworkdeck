// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** Qt positions the cursor; the host previews the link without navigating Writer. */
export function attachDocumentLinkClicks({ canvas, execute, send, cursorContext }) {
  if (!canvas) return () => {}
  const doc = canvas.ownerDocument
  const win = doc.defaultView
  const forwarded = new WeakSet()
  let down = null, generation = 0, timer = null
  function mouse(ev) {
    if (ev.target !== canvas || forwarded.has(ev)) return
    if (ev.type === 'mousedown') {
      generation++
      down = ev.button === 0 && !ev.shiftKey && !ev.altKey
        ? { x: ev.clientX, y: ev.clientY, metaKey: ev.metaKey, ctrlKey: ev.ctrlKey, generation } : null
    }
    const d = down
    // Capture before Qt's canvas listeners. A modified click is a normal
    // positioning click to Qt, so #bookmarks/reference fields cannot jump first.
    if (d && (d.metaKey || d.ctrlKey)) {
      ev.preventDefault()
      ev.stopImmediatePropagation()
      const copy = new win.MouseEvent(ev.type, {
        bubbles: true, cancelable: true, clientX: ev.clientX, clientY: ev.clientY,
        screenX: ev.screenX, screenY: ev.screenY, button: ev.button, buttons: ev.buttons,
        detail: ev.detail, view: win,
      })
      forwarded.add(copy)
      canvas.dispatchEvent(copy)
    }
    if (ev.type !== 'mouseup') return
    down = null
    if (!d || ev.button !== 0 || ev.detail > 1 || Math.abs(ev.clientX - d.x) > 5 || Math.abs(ev.clientY - d.y) > 5) return
    clearTimeout(timer)
    timer = setTimeout(async () => {
      if (generation !== d.generation) return
      const meta = { metaKey: d.metaKey, ctrlKey: d.ctrlKey, clientX: d.x, clientY: d.y }
      // Ordinary clicks keep editing. Only explicit Cmd/Ctrl clicks activate.
      if (d.metaKey || d.ctrlKey) {
        try {
          const link = await execute('get_hyperlink_at_cursor', {})
          if (generation !== d.generation) return
          if (link?.success && link.url) {
            send({ __lo: 'lo-relay', type: 'open-url', url: link.url, target: link.target || null, meta })
            return // A hyperlink must not also open a competing entity card.
          }
        } catch (_) { /* Entity lookup remains available when there is no link. */ }
      }
      if (generation === d.generation) cursorContext(meta)
    }, 150)
  }
  const invalidate = () => { generation++; down = null; clearTimeout(timer) }
  doc.addEventListener('mousedown', mouse, true)
  doc.addEventListener('mouseup', mouse, true)
  doc.addEventListener('keydown', invalidate, true)
  doc.addEventListener('wheel', invalidate, true)
  win.addEventListener('blur', invalidate)
  return () => {
    invalidate()
    doc.removeEventListener('mousedown', mouse, true)
    doc.removeEventListener('mouseup', mouse, true)
    doc.removeEventListener('keydown', invalidate, true)
    doc.removeEventListener('wheel', invalidate, true)
    win.removeEventListener('blur', invalidate)
  }
}
