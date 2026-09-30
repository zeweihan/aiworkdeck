// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as barriers from '../../src/utils/checkpointSaveBarrier.js'
let projectSequence = 0
const source = readFileSync(new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
  .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '').replace(/^export\s+/gm, '')
function fixture(instances, send) {
  instances.forEach(inst => { inst.ready = true; inst.executor = {} })
  const replies = []
  const methods = new Function(...Object.keys(barriers), 'getCheckpointRestoreWriteState', 'sendEditorResult', source + '; return agentClientActionMethods')(
    ...Object.values(barriers), async () => ({ mayWrite: true }), async (...args) => { replies.push(args); await send?.(...args) })
  const page = Object.assign({ projectId: 'checkpoint-test-' + (++projectSequence), _libreRefs: Object.fromEntries(instances.map((inst, i) => [i, inst])),
    conversationId: 'conv-' + projectSequence, activeFileIdLeft: 50, activeFileIdRight: null, resolveLibreExecutorFileId: () => 50 }, methods)
  const run = (phase, restoreId = 'restore-1') => page.handleEditorCommand({ action: 'doc_checkpoint_restore',
    conversationId: page.conversationId, requestId: phase, params: { fileId: 50, restoreId, phase } })
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
  assert.equal(barriers.checkpointSaveBarrier(page.projectId, 50).restoreId, 'new')
  assert.equal(replies.at(-1)[2], false, 'the obsolete reload cannot acknowledge success')
  assert.equal(blocked, 1, 'the old catch cannot fail the new restore')
})

// The save may belong to an editor that Vue has not registered yet.
test('prepare drains unregistered saves and keeps the file closed to saves after its ACK', async () => {
  const { page, run, replies } = fixture([])
  page.activeFileIdLeft = null
  const lease = barriers.beginDocumentSave(page.projectId, 50)
  const preparing = run('prepare')
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(replies.length, 0)
  lease.finish(true)
  await preparing
  assert.equal(replies.at(-1)[2], true)
  assert.equal(barriers.beginDocumentSave(page.projectId, 50), null)
  await run('reload')
  const next = barriers.beginDocumentSave(page.projectId, 50)
  assert.ok(next)
  next.finish(true)
})

test('unregistered failed upload before prepare cannot be forgotten on retry', async () => {
  const { page, run, replies } = fixture([])
  page.activeFileIdLeft = null
  barriers.beginDocumentSave(page.projectId, 50).finish(false)
  await run('prepare')
  assert.equal(replies.at(-1)[2], false)
  await run('prepare', 'retry')
  assert.equal(replies.at(-1)[2], false)
})

test('a new registered editor after prepare ACK invalidates restore and remains blocked', async () => {
  const poolSource = readFileSync(new URL('../../src/pages/project-overview/librePool.js', import.meta.url), 'utf8')
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '').replace(/^export\s+/gm, '')
  const pool = new Function(...Object.keys(barriers), poolSource + '; return librePoolMethods')(...Object.values(barriers))
  const original = { file: { id: 50 }, prepareCheckpointRestore: async () => true, reloadFromBackend: async () => true, failCheckpointRestore() {} }
  const { page, run, replies } = fixture([original])
  Object.assign(page, pool)
  await run('prepare')
  let blocked = false
  const fresh = { file: { id: 50 }, failCheckpointRestore() { blocked = true } }
  page.setLibreRef('right', 50, fresh)
  assert.equal(blocked, true)
  await run('reload')
  assert.equal(replies.at(-1)[2], false)
})

test('terminal phases can find the original restore from a replacement page with a different project', async () => {
  let failed = 0
  const first = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    reloadFromBackend: async () => true, failCheckpointRestore() { failed++ } }])
  await first.run('prepare', 'cross-page')
  const second = fixture([])
  second.page.activeFileIdLeft = null
  second.page.conversationId = first.page.conversationId
  await second.run('abort', 'cross-page')
  assert.equal(second.replies.at(-1)[2], true)
  assert.equal(barriers.checkpointSaveBarrier(first.page.projectId, 50), null)
  assert.equal(first.page._checkpointRestores.size, 0)
  assert.equal(failed, 1)
})

test('cross-page reconciliation invalidates an old asynchronous reload without unlocking a newer restore', async () => {
  const late = deferred()
  let failed = 0
  const { page, run, replies } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    reloadFromBackend: () => late.promise, failCheckpointRestore() { failed++ } }])
  await run('prepare', 'old-page')
  const old = run('reload', 'old-page')
  assert.equal(await barriers.reconcileCheckpointSaveBarrier(page.projectId, 50, async () => ({ mayWrite: false })), true)
  await run('prepare', 'new-page')
  late.resolve(true); await old
  assert.equal(replies.at(-1)[2], false)
  assert.equal(barriers.checkpointSaveBarrier(page.projectId, 50).restoreId, 'new-page')
  assert.equal(failed, 1)
})

test('a duplicate prepare cannot release the active write barrier', async () => {
  const { page, run, replies } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    failCheckpointRestore() { throw Error('duplicate prepare must not invalidate owner') } }])
  await run('prepare', 'duplicate')
  const barrier = barriers.checkpointSaveBarrier(page.projectId, 50)
  await run('prepare', 'duplicate')
  assert.equal(replies.at(-1)[2], false)
  assert.equal(barriers.checkpointSaveBarrier(page.projectId, 50), barrier)
})

test('a lost prepare success ACK retains its barrier until the backend write state is reconciled', async () => {
  const { page, run, replies } = fixture([{ file: { id: 50 }, prepareCheckpointRestore: async () => true,
    failCheckpointRestore() {} }], async (...args) => { if (args[2] === true) throw Error('ACK response lost') })
  await run('prepare', 'lost-ack')
  assert.equal(replies.at(-1)[2], false)
  assert.equal(barriers.checkpointSaveBarrier(page.projectId, 50).restoreId, 'lost-ack')
  assert.equal(await barriers.reconcileCheckpointSaveBarrier(page.projectId, 50, async () => ({ mayWrite: true })), false)
  assert.equal(await barriers.reconcileCheckpointSaveBarrier(page.projectId, 50, async () => ({ mayWrite: false })), true)
  assert.equal(barriers.checkpointSaveBarrier(page.projectId, 50), null)
})
