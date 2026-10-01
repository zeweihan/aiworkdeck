// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { createRelayExecutor, serveExecutor } from '../../src/composables/zetaOfficeRelay.js'
import { createLibreOfficeExecutor } from '../../src/composables/libreofficeExecutorClient.js'

// Exercise both real waiting layers, with only the worker's synchronous native
// work replaced by manually delivered results. Advancing time must not finish it.
function pipeline() {
  let hostReceive, guestReceive, workerReceive
  const commands = []
  const client = createLibreOfficeExecutor()
  client.connect({ addEventListener: (_, h) => { workerReceive = h }, postMessage: m => commands.push(m) })
  const guest = serveExecutor({ executor: client, send: m => hostReceive?.(m), subscribe: h => { guestReceive = h; return () => { guestReceive = null } } })
  const host = createRelayExecutor({ send: m => guestReceive?.(m), subscribe: h => { hostReceive = h; return () => { hostReceive = null } } })
  return { host, guest, commands, hostDeliver: m => hostReceive?.({ __lo: 'lo-relay', ...m }), deliver: d => workerReceive({ data: { reqId: commands.at(-1).reqId, ...d } }) }
}
const flush = async () => { for (let i = 0; i < 8; i++) await Promise.resolve() }
for (const action of ['build_comparison_document', 'export_document']) {
  test(`${action}: interactive comparison waits past 180s in both layers and receives a later success`, async t => {
    t.mock.timers.enable({ apis: ['setTimeout'] })
    const h = pipeline(), stages = []
    let settled = false
    const pending = h.host.executeCommand(action, {}, { waitForCompletion: true, onProgress: p => stages.push(p) }).then(r => { settled = true; return r })
    h.deliver({ cmd: 'progress', stage: 'comparing' })
    t.mock.timers.tick(600000)
    await flush()
    assert.equal(settled, false)
    assert.deepEqual(stages, [{ done: 0, total: 0, stage: 'comparing' }])
    h.deliver({ cmd: 'result', result: { success: true, bytes: [1] } })
    assert.deepEqual(await pending, { success: true, bytes: [1] })
    h.host.dispose(); h.guest.dispose()
  })
}
test('ordinary exports retain their deadline; opting unrelated actions out is ignored', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  for (const [action, options] of [['export_document', {}], ['load_document', { waitForCompletion: true }]]) {
    const h = pipeline(), p = h.host.executeCommand(action, {}, options)
    t.mock.timers.tick(180001)
    assert.equal((await p).code, 'EDITOR_RESULT_TIMEOUT')
    await flush(); h.host.dispose(); h.guest.dispose()
  }
})
test('destroyed hidden engine settles outstanding waits immediately, ignores late results and rejects reuse', async () => {
  const h = pipeline(), stages = []
  const p = h.host.executeCommand('build_comparison_document', {}, { waitForCompletion: true, onProgress: x => stages.push(x) })
  h.host.dispose(); h.host.dispose()
  assert.equal((await p).code, 'EDITOR_DISPOSED')
  h.deliver({ cmd: 'progress', stage: 'comparing' })
  h.deliver({ cmd: 'result', result: { success: true } })
  await flush()
  assert.deepEqual(stages, [])
  assert.equal((await h.host.executeCommand('export_document')).code, 'EDITOR_DISPOSED')
  assert.equal(h.commands.length, 1)
  h.guest.dispose()
})


test('confirmed native abort immediately fails unbounded comparison and subsequent calls, without exposing native details', async () => {
  const h = pipeline()
  const p = h.host.executeCommand('build_comparison_document', {}, { waitForCompletion: true })
  h.hostDeliver({ type: 'engine-failed', message: 'untrusted raw native error' })
  const result = await p
  assert.equal(result.code, 'EDITOR_ENGINE_FAILED')
  assert.ok(!result.message.includes('untrusted'))
  assert.equal((await h.host.executeCommand('export_document')).code, 'EDITOR_ENGINE_FAILED')
  h.deliver({ cmd: 'result', result: { success: true } })
  await flush(); h.guest.dispose()
})
