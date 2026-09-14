// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// PR#819: margin/balloon views make Writer's undo fragile, so revision resolutions
// taken there run in the inline view and remember their undo entry; undo/redo take
// the same inline detour only while such an entry is on top of the stack.
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const declaration = name => source.slice(source.indexOf(`function ${name}(`), source.indexOf('\n}', source.indexOf(`function ${name}(`)) + 2)
// sameUndoEntry is a single-line declaration, so the block extractor above would swallow its neighbour.
const oneLiner = name => {
  const line = source.split('\n').find(l => l.startsWith(`function ${name}(`))
  assert.ok(line && line.endsWith('}'), `${name} is no longer a single-line declaration`)
  return line
}

// ---- runRevisionResolution: who remembers the inline undo boundary ---------

const buildResolve = ({ showChanges, inMargin }) => {
  const seen = { modes: [], remembered: 0, refreshes: 0, edits: 0 }
  let mode = 'balloons'
  const resolve = new Function(
    'isWriterDoc', 'readShowChanges', 'readShowChangesInMargin', 'rememberInlineUndo',
    'revisionViewState', 'withViewOnlyChange', 'applyRevisionView', 'xModel', 'isReviewWritable',
    `${declaration('runRevisionResolution')};return runRevisionResolution`)(
    () => true, () => showChanges, () => inMargin, () => { seen.remembered++ },
    () => ({ mode }), fn => fn(), next => { seen.modes.push(next); mode = next },
    { refresh() { seen.refreshes++; if (seen.failRefreshAt === seen.refreshes) throw new Error('refresh failed') } },
    () => true)
  return { resolve, seen, edit: () => { seen.edits++; return { success: true } }, modeNow: () => mode }
}

test('a resolution taken in the balloon view remembers exactly one inline undo boundary', () => {
  const { resolve, seen, edit, modeNow } = buildResolve({ showChanges: true, inMargin: true })
  assert.deepEqual(resolve(edit), { success: true })
  assert.equal(seen.edits, 1)
  assert.equal(seen.remembered, 1, 'the inline undo entry is recorded once, after the edit')
  assert.deepEqual(seen.modes, ['all', 'balloons'], 'resolve inline, then restore the user view')
  assert.equal(modeNow(), 'balloons')
})

test('a failed detour into the inline view remembers nothing and restores the balloon view', () => {
  const { resolve, seen, edit, modeNow } = buildResolve({ showChanges: true, inMargin: true })
  seen.failRefreshAt = 1
  assert.throws(() => resolve(edit), /refresh failed/)
  assert.equal(seen.edits, 0)
  assert.equal(seen.remembered, 0, 'no edit happened, so there is no inline entry to remember')
  assert.deepEqual(seen.modes, ['all', 'balloons'])
  assert.equal(modeNow(), 'balloons')
})

test('a resolution taken in the inline view neither switches views nor remembers an entry', () => {
  const { resolve, seen, edit, modeNow } = buildResolve({ showChanges: true, inMargin: false })
  assert.deepEqual(resolve(edit), { success: true })
  assert.equal(seen.edits, 1)
  assert.equal(seen.remembered, 0, 'inline edits already undo where they were made')
  assert.deepEqual(seen.modes, [], 'the user view is left alone')
  assert.equal(modeNow(), 'balloons')
})

// ---- undoStep: the detour fires only for a remembered margin resolution ----

const RESOLUTION = '接受修订'

// kind: which stack the pending step reads. `remembered` is the entry office_thread
// recorded for that stack; `foreignModel` seeds it against a different document.
const buildUndoStep = ({ kind = 'undo', writer = true, inMargin = true, remembered = null, foreignModel = false } = {}) => {
  const seen = { modes: [], runs: 0, refreshes: 0 }
  let mode = 'balloons'
  const xModel = { refresh() { seen.refreshes++ } }
  // The step's own stack holds the resolution; the opposite stack is empty.
  const titles = { undo: [], redo: [] }
  titles[kind] = [RESOLUTION]
  const um = {
    getAllUndoActionTitles: () => titles.undo.slice(),
    getAllRedoActionTitles: () => titles.redo.slice(),
  }
  const other = kind === 'undo' ? 'redo' : 'undo'
  const run = () => { seen.runs++; titles[other].unshift(titles[kind].shift()) }
  const entries = { undo: null, redo: null }
  if (remembered) entries[kind] = { model: foreignModel ? { refresh() {} } : xModel, ...remembered }
  const step = new Function(
    'isWriterDoc', 'readShowChangesInMargin', 'inlineUndoEntries', 'revisionViewState',
    'withViewOnlyChange', 'applyRevisionView', 'xModel',
    `${declaration('topUndoEntry')};${oneLiner('sameUndoEntry')};${declaration('undoStep')};return undoStep`)(
    () => writer, () => inMargin, entries, () => ({ mode }), fn => fn(),
    next => { seen.modes.push(next); mode = next }, xModel)
  return { xModel, step: () => step(um, kind, run), seen, entries, modeNow: () => mode }
}

const onTop = { depth: 1, title: RESOLUTION }

test('undo detours through the inline view while the remembered resolution is on top', () => {
  const { xModel, step, seen, entries, modeNow } = buildUndoStep({ remembered: onTop })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, ['all', 'balloons'], 'undo runs in the view that recorded the edit')
  assert.equal(modeNow(), 'balloons')
  assert.equal(entries.undo, null, 'the entry left the undo stack')
  assert.deepEqual(entries.redo, { model: xModel, depth: 1, title: RESOLUTION }, 'and is now remembered for redo')
})

test('redo takes the same detour and hands the entry back to undo', () => {
  const { xModel, step, seen, entries, modeNow } = buildUndoStep({ kind: 'redo', remembered: onTop })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, ['all', 'balloons'])
  assert.equal(modeNow(), 'balloons')
  assert.equal(entries.redo, null)
  assert.deepEqual(entries.undo, { model: xModel, depth: 1, title: RESOLUTION })
})

test('undo stays put when nothing was recorded in a margin view', () => {
  const { step, seen, modeNow } = buildUndoStep({ remembered: null })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, [], 'a plain edit undoes where it is')
  assert.equal(modeNow(), 'balloons')
})

test('undo stays put once the user has typed past the remembered resolution', () => {
  const { step, seen } = buildUndoStep({ remembered: { depth: 3, title: RESOLUTION } })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, [], 'a deeper stack means somebody else is on top')
})

test('undo stays put when the remembered title no longer matches the top of the stack', () => {
  const { step, seen } = buildUndoStep({ remembered: { depth: 1, title: '输入' } })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, [])
})

test('undo stays put when the remembered entry belongs to another document', () => {
  const { step, seen } = buildUndoStep({ remembered: onTop, foreignModel: true })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, [], 'a stale entry from the previous document must not detour')
})

test('undo stays put in the inline view even for a remembered resolution', () => {
  const { step, seen } = buildUndoStep({ inMargin: false, remembered: onTop })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, [], 'the inline view is already the safe one')
})

test('undo never touches views outside Writer', () => {
  const { step, seen } = buildUndoStep({ writer: false, remembered: onTop })
  step()
  assert.equal(seen.runs, 1)
  assert.deepEqual(seen.modes, [])
})
