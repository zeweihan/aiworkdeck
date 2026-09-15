// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { JSDOM } from 'jsdom'
import { attachImeOverlay } from '../../src/composables/zetaOfficeImeOverlay.js'

function fixture(t) {
  const dom = new JSDOM('<div><canvas tabindex="0"></canvas></div>', { pretendToBeVisual: true })
  const keys = ['document', 'navigator', 'getComputedStyle']
  const saved = Object.fromEntries(keys.map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]))
  for (const [key, value] of Object.entries({ document: dom.window.document, navigator: { platform: 'MacIntel' }, getComputedStyle: dom.window.getComputedStyle })) {
    Object.defineProperty(globalThis, key, { configurable: true, value })
  }
  const canvas = document.querySelector('canvas')
  let moves = 0
  const overlay = attachImeOverlay({ canvas, commit() {}, onCursorMoved: () => moves++ })
  t.after(() => {
    overlay.destroy(); dom.window.close()
    for (const key of keys) {
      if (saved[key]) Object.defineProperty(globalThis, key, saved[key])
      else delete globalThis[key]
    }
  })
  return { canvas, input: overlay.element, moves: () => moves,
    release: props => canvas.dispatchEvent(new dom.window.MouseEvent('mouseup', { bubbles: true, ...props })) }
}
const tick = () => new Promise(resolve => setTimeout(resolve, 10))

for (const props of [{ button: 2 }, { button: 1 }, { button: 0, ctrlKey: true }]) {
  test(`secondary release ${JSON.stringify(props)} preserves menu focus and emits no caret move`, async t => {
    const h = fixture(t); await tick()
    const before = h.moves()
    h.canvas.focus(); h.release(props); await tick()
    assert.equal(document.activeElement, h.canvas, 'menu keeps its keyboard focus')
    assert.equal(h.moves(), before, 'right-button release is not a document cursor movement')
  })
}

test('ordinary primary release still restores typing focus and refreshes the caret', async t => {
  const h = fixture(t); await tick()
  const before = h.moves()
  h.canvas.focus(); h.release({ button: 0 }); await tick()
  assert.equal(document.activeElement, h.input)
  assert.equal(h.moves(), before + 1)
})
