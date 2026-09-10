// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { nativeCursorRectToPixels } from '../../src/composables/zetaOfficeImeOverlay.js'

test('live Writer frame geometry includes menu offset and ignores stale saved viewData', () => {
  const raw = { nativeCaret: { x: 343, y: 239, height: 16, frameWidth: 1280, frameHeight: 876 },
    pos: { X: 0, Y: 0 }, viewData: { VisibleTop: 99999, ZoomFactor: 10 } }
  assert.deepEqual(nativeCursorRectToPixels(raw, { left: 20, top: 30, width: 1280, height: 900 }),
    { left: 363, top: 293, height: 16 })
  assert.deepEqual(nativeCursorRectToPixels(raw, { left: 20, top: 30, width: 640, height: 450 }),
    { left: 191.5, top: 161.5, height: 8 })
})

test('unavailable native geometry leaves legacy callers a null fallback', () => {
  assert.equal(nativeCursorRectToPixels({}, { width: 1280, height: 900 }), null)
  assert.equal(nativeCursorRectToPixels({ nativeCaret: { x: NaN } }, {}), null)
})

test('late geometry responses cannot move the input back or refresh stale suggestions', async t => {
  const { JSDOM } = await import('jsdom')
  const { attachImeOverlay } = await import('../../src/composables/zetaOfficeImeOverlay.js')
  const dom = new JSDOM('<div><canvas></canvas></div>', { pretendToBeVisual: true })
  const saved = { document: globalThis.document, getComputedStyle: globalThis.getComputedStyle }
  globalThis.document = dom.window.document; globalThis.getComputedStyle = dom.window.getComputedStyle
  let overlay
  t.after(() => { overlay?.destroy(); dom.window.close(); Object.assign(globalThis, saved) })
  const canvas = document.querySelector('canvas'), reads = []; let moves = 0
  canvas.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1280, height: 900 })
  overlay = attachImeOverlay({ canvas, commit() {}, getCursorRaw: () => new Promise(resolve => reads.push(resolve)), onCursorMoved: () => moves++ })
  const fresh = overlay.reposition()
  const geometry = x => ({ nativeCaret: { x, y: 239, height: 16, frameWidth: 1280, frameHeight: 876 } })
  reads[1](geometry(400)); await fresh
  assert.equal(overlay.element.style.left, '400px')
  reads[0](geometry(200)); await new Promise(r => setTimeout(r, 0))
  assert.equal(overlay.element.style.left, '400px')
  assert.equal(moves, 1)
  const late = overlay.reposition(); overlay.destroy()
  reads[2](geometry(600)); await late
  assert.equal(moves, 1)
})
