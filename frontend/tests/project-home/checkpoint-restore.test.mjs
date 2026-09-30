// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '').replace(/^export\s+/gm, '')
function fixture(instances) {
  instances.forEach(inst => { inst.ready = true; inst.executor = {} })
  const replies = []
  const methods = new Function('sendEditorResult', source + '; return agentClientActionMethods')(
    async (...args) => { replies.push(args) })
  const page = Object.assign({ _libreRefs: Object.fromEntries(instances.map((inst, i) => [i, inst])),
    activeFileIdLeft: 50, activeFileIdRight: null, resolveLibreExecutorFileId: () => 50 }, methods)
  const run = (phase, restoreId = 'restore-1') => page.handleEditorCommand({ action: 'doc_checkpoint_restore',
    conversationId: 'conv', requestId: phase, params: { fileId: 50, restoreId, phase } })
  return { page, replies, run }
}
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }

test('prepare and reload acknowledgements wait for actual completion; intermediate reads never reach the worker', async () => {
  const prepared = deferred(), reloaded = deferred()
  const inst = { file: { id: 50 }, prepareCheckpointRestore: () => prepared.promise,
    reloadFromBackend: () => reloaded.promise, failCheckpointRestore() {} }
  const { page, replies, run } = fixture([inst])
  const first = run('prepare')
  assert.equal(replies.length, 0)
  prepared.resolve(true); await first
  assert.equal(replies[0][2], true)
  await page.handleEditorCommand({ action: 'get_document_text', requestId: 'read', conversationId: 'conv' })
  assert.equal(replies[1][2], false)
  assert.match(replies[1][4], /正在恢复/)
  const second = run('reload')
  assert.equal(replies.length, 2)
  reloaded.resolve(true); await second
  assert.equal(replies[2][2], true)
  assert.equal(page._checkpointRestores.size, 0)
})

test('all instances for the target file are paused; other documents are untouched', async () => {
  const calls = []
  const inst = (id, label) => ({ file: { id }, async prepareCheckpointRestore() { calls.push('prepare:' + label); return true },
    async reloadFromBackend() { calls.push('reload:' + label); return true }, failCheckpointRestore() {} })
  const { run } = fixture([inst(50, 'left'), inst(50, 'right'), inst(51, 'other')])
  await run('prepare'); await run('reload')
  assert.deepEqual(calls, ['prepare:left', 'prepare:right', 'reload:left', 'reload:right'])
})

test('reload failure reports failure and leaves the stale model blocked from saving', async () => {
  let blocked = false
  const { replies, run } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    reloadFromBackend: async () => false, failCheckpointRestore: () => { blocked = true } }])
  await run('prepare'); await run('reload')
  assert.equal(replies.at(-1)[2], false)
  assert.equal(blocked, true)
})

test('a stale restore token cannot reload or unlock a newer restore', async () => {
  const { page, replies, run } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    reloadFromBackend: () => { throw Error('must not reload') }, failCheckpointRestore: () => { throw Error('must not unlock') } }])
  await run('prepare')
  await run('abort', 'stale-token')
  assert.equal(replies.at(-1)[2], false)
  assert.equal(page._checkpointRestores.size, 1)
})


test('closing the prepared editor cannot turn a missing reload into success', async () => {
  let loaded = false, blocked = false
  const { page, replies, run } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    reloadFromBackend: async () => { loaded = true; return true }, failCheckpointRestore: () => { blocked = true } }])
  await run('prepare')
  page._libreRefs = {}
  await run('reload')
  assert.equal(replies.at(-1)[2], false)
  assert.equal(loaded, false)
  assert.equal(blocked, true)
})

test('prepare abandonment can abort by the same restore token without leaving a stale lock', async () => {
  let blocked = false
  const { page, replies, run } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    failCheckpointRestore: () => { blocked = true } }])
  await run('prepare'); await run('abort')
  assert.equal(replies.at(-1)[2], true)
  assert.equal(page._checkpointRestores.size, 0)
  assert.equal(blocked, true)
})


test('a late aborted reload cannot delete or fail the newer restore lock', async () => {
  const late = deferred()
  let blocked = 0
  const { page, replies, run } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    reloadFromBackend: () => late.promise, failCheckpointRestore: () => { blocked++ } }])
  await run('prepare', 'old')
  const old = run('reload', 'old')
  await run('abort', 'old')
  await run('prepare', 'new')
  late.resolve(true); await old
  assert.equal(page._checkpointRestores.get('50').restoreId, 'new')
  assert.equal(replies.at(-1)[2], false, 'the obsolete reload cannot acknowledge success')
  assert.equal(blocked, 1, 'the old catch cannot fail the new restore')
})
