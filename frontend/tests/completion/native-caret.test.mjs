// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const nativeSource = source.slice(source.indexOf('let caretWindowCache = null;'), source.indexOf('function completionUnavailable('))

for (const density of [1, 2]) test(`native window units at DPR ${density} track the live viewport`, () => {
  const realm = vm.createContext({ xModel: {}, css: { util: { MeasureUnit: { MM_100TH: 0 } } } })
  const read = vm.runInContext(nativeSource + '; readNativeCaretRect', realm)
  const scaleRect = r => Object.fromEntries(Object.entries(r).map(([k, v]) => [k, v * density]))
  const editing = { getPosSize: () => scaleRect({ X: 0, Y: 26, Width: 1218, Height: 745 }), getWindows: () => [] }
  const component = { getPosSize: () => scaleRect({ X: 0, Y: 78, Width: 1280, Height: 771 }), getWindows: () => [editing],
    convertPointToPixel: () => ({ X: 96 * density, Y: 96 * density }) }
  const frame = { getComponentWindow: () => component, getContainerWindow: () => ({ getPosSize: () => scaleRect({ Width: 1280, Height: 876 }) }) }
  const caret = read(frame, '5359;1720;100;0;0;18255;11160;0;0', 12)
  assert.ok(caret, 'the editing window must match in physical native pixels')
  assert.ok(Math.abs(caret.x / density - 357.2667) < 0.01)
  assert.ok(Math.abs(caret.y / density - 218.6667) < 0.01)
  assert.equal(caret.height / density, 16)
})
