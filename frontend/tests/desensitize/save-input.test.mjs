// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import test from 'node:test'
import assert from 'node:assert/strict'
import { saveSensitiveInput } from '../../src/pages/project-overview/sensitiveWorkflow.js'

function editor(fileId, overrides = {}) {
  const inst = { file: { id: fileId }, dirty: true, ready: true, saving: false, calls: 0, ...overrides }
  inst.flushSave ||= async () => { inst.calls++; inst.dirty = false }
  return inst
}

test('waits for the selected editor only, including normalized ids in either pane', async () => {
  let finish
  const target = editor('12', { flushSave: async () => { await new Promise(r => { finish = r }); target.dirty = false } })
  const other = editor(99)
  let complete = false
  const pending = saveSensitiveInput(12, { 'right:12': target, 'left:99': other }, {}).then(() => { complete = true })
  await Promise.resolve()
  assert.equal(complete, false); assert.equal(other.calls, 0)
  finish(); await pending; assert.equal(complete, true)
})

test('a save that resolves but leaves failure status blocks processing', async () => {
  const target = editor(12, { flushSave: async () => { target.dirty = false; target.statusKey = 'saveFailed' } })
  await assert.rejects(saveSensitiveInput(12, { a: target }, {}))
})

test('plain text still dirty after save is blocked instead of processing stale disk content', async () => {
  const target = editor(12, { flushSave: async () => {} })
  await assert.rejects(saveSensitiveInput(12, {}, { left: target }))
})

test('unready or failed office editors with pending changes cannot be saved or processed', async () => {
  for (const options of [{ ready: false }, { isError: true }, { docLoadFailed: true }, { _reloading: true }]) {
    const target = editor(12, options)
    await assert.rejects(saveSensitiveInput(12, { a: target }, {})); assert.equal(target.calls, 0)
  }
})

test('already saved or unopened documents require no extra save', async () => {
  const target = editor(12, { dirty: false })
  assert.equal(await saveSensitiveInput(12, { a: target }, {}), false)
  assert.equal(await saveSensitiveInput(13, { a: target }, {}), false)
  assert.equal(target.calls, 0)
})
