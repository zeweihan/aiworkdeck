// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { beginDocumentSave, beginCheckpointSaveBarrier, checkpointSaveBarrier, endCheckpointSaveBarrier, reconcileCheckpointSaveBarrier } from '../../src/utils/checkpointSaveBarrier.js'

test('prepare waits for an upload independently of any editor registration', async () => {
  const save = beginDocumentSave('lease-test', 50)
  const barrier = beginCheckpointSaveBarrier('lease-test', 50, 'old')
  let ready = false
  const waiting = barrier.drained.then(ok => { ready = ok })
  await Promise.resolve()
  assert.equal(ready, false)
  assert.equal(beginDocumentSave('lease-test', 50), null, 'newly created editors cannot start a save after prepare')
  save.finish(true); await waiting
  assert.equal(ready, true)
  assert.equal(beginDocumentSave('lease-test', 50), null, 'the barrier remains after prepare acknowledgement')
  endCheckpointSaveBarrier('lease-test', 50, 'old')
  const next = beginDocumentSave('lease-test', 50)
  assert.ok(next); next.finish(true)
})

test('an uncertain upload result rejects prepare; unrelated project saves are independent', async () => {
  const save = beginDocumentSave('failed-test', 50)
  const barrier = beginCheckpointSaveBarrier('failed-test', 50, 'restore')
  const other = beginDocumentSave('other-project', 50)
  assert.ok(other); other.finish(true)
  save.finish(false)
  assert.equal(await barrier.drained, false, 'network failure is not proof that a server upload stopped')
  endCheckpointSaveBarrier('failed-test', 50, 'restore')
})

test('late release from an old restore cannot clear a newer file barrier', () => {
  beginCheckpointSaveBarrier('tokens', 50, 'old')
  endCheckpointSaveBarrier('tokens', 50, 'old')
  beginCheckpointSaveBarrier('tokens', 50, 'new')
  endCheckpointSaveBarrier('tokens', 50, 'old')
  assert.equal(checkpointSaveBarrier('tokens', 50).restoreId, 'new')
  endCheckpointSaveBarrier('tokens', 50, 'new')
})

test('a lost abort is reconciled only after the backend confirms the write path is finished', async () => {
  const barrier = beginCheckpointSaveBarrier('lost-abort', 50, 'restore')
  barrier.conversationId = 'conv'
  let failed = 0
  barrier.instances = [{ failCheckpointRestore() { failed++ } }]
  await assert.rejects(reconcileCheckpointSaveBarrier('lost-abort', 50, async () => { throw Error('offline') }), /offline/)
  assert.equal(checkpointSaveBarrier('lost-abort', 50), barrier)
  assert.equal(await reconcileCheckpointSaveBarrier('lost-abort', 50, async () => ({ mayWrite: true })), false)
  assert.equal(checkpointSaveBarrier('lost-abort', 50), barrier)
  assert.equal(await reconcileCheckpointSaveBarrier('lost-abort', 50, async (conversation, fileId, restoreId) => {
    assert.deepEqual([conversation, fileId, restoreId], ['conv', 50, 'restore'])
    return { mayWrite: false }
  }), true)
  assert.equal(checkpointSaveBarrier('lost-abort', 50), null)
  assert.equal(barrier.invalidated, true)
  assert.equal(failed, 1)
})

test('a late state query cannot clear a newer restore barrier', async () => {
  const barrier = beginCheckpointSaveBarrier('late-query', 50, 'old')
  barrier.conversationId = 'conv'
  let finish
  const reconciling = reconcileCheckpointSaveBarrier('late-query', 50, () => new Promise(resolve => { finish = resolve }))
  endCheckpointSaveBarrier('late-query', 50, 'old')
  const newer = beginCheckpointSaveBarrier('late-query', 50, 'new')
  finish({ mayWrite: false })
  assert.equal(await reconciling, false)
  assert.equal(checkpointSaveBarrier('late-query', 50), newer)
})
