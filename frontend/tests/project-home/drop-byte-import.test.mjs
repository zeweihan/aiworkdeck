// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { captureDroppedFiles, importDroppedFile, MAX_DROP_BYTES } from '../../src/utils/fileTreeExternalDrop.js'

function desktop() {
  const calls = []
  return { calls, getPathForFile: () => '',
    stageDroppedFile: async file => { calls.push(['stage', file]); return { ok: true, path: '/tmp/awd-test/fixture.txt', token: 'owned-token' } },
    releaseDroppedFile: async token => { calls.push(['release', token]); return { ok: true } },
  }
}

test('items-only snapshot starts File byte read synchronously, before delayed staging-folder creation', async () => {
  const fs = desktop(), bytes = new Uint8Array([0, 255, 10]).buffer
  let readable = true, reads = 0
  const file = { name: 'fixture.txt', size: 3, arrayBuffer() { assert.equal(readable, true); reads++; return Promise.resolve(bytes) } }
  const dt = { files: [], items: [{ kind: 'file', getAsFile: () => file }] }
  const files = captureDroppedFiles(dt, fs)
  assert.equal(reads, 1)
  dt.items = []; readable = false
  await Promise.resolve() // simulate ensureStagingFolder before importing
  const imported = await importDroppedFile(files[0], fs, async path => { fs.calls.push(['import', path]); return { id: 42 } })
  assert.equal(imported.id, 42)
  assert.deepEqual(fs.calls.map(c => c[0]), ['stage', 'import', 'release'])
  assert.deepEqual(new Uint8Array(fs.calls[0][1].bytes), new Uint8Array([0, 255, 10]))
  assert.equal(reads, 1)
})

test('failed import releases only its returned token and keeps the original failure', async () => {
  const fs = desktop(), file = new File(['data'], 'fixture.txt')
  await assert.rejects(importDroppedFile(file, fs, async () => { throw new Error('copy failed') }), /copy failed/)
  assert.deepEqual(fs.calls.map(c => c[0]), ['stage', 'release'])
  assert.equal(fs.calls[1][1], 'owned-token')
})

test('real local path bypasses File byte reads and staging', async () => {
  const fs = desktop(); fs.getPathForFile = () => '/tmp/awd-test/local.txt'
  const file = { name: 'local.txt', size: MAX_DROP_BYTES + 1, arrayBuffer() { throw Error('must not read') } }
  assert.equal(await importDroppedFile(file, fs, async path => path), '/tmp/awd-test/local.txt')
  assert.deepEqual(fs.calls, [])
})

test('file URL strings never become a local path or staged bytes', () => {
  const fs = desktop()
  assert.deepEqual(captureDroppedFiles({ files: [], items: [], types: ['text/uri-list'], getData: () => 'file:///tmp/awd-test/secret.txt' }, fs), [])
  assert.deepEqual(fs.calls, [])
})

test('unreadable, truncated, oversized and pathless-directory sources never import', async () => {
  const fs = desktop()
  const cases = [
    new File([], 'unmaterialized.docx'),
    { name: 'gone.txt', size: 1, arrayBuffer: () => Promise.reject(Error('unavailable')) },
    { name: 'short.txt', size: 2, arrayBuffer: () => Promise.resolve(new ArrayBuffer(1)) },
    { name: 'large.txt', size: MAX_DROP_BYTES + 1, arrayBuffer: () => { throw Error('must not read') } },
  ]
  for (const file of cases) await assert.rejects(importDroppedFile(file, fs, () => assert.fail('must not import')), e => !!e.dropCode)
  const dir = new File([], 'directory')
  captureDroppedFiles({ files: [dir], items: [{ kind: 'file', webkitGetAsEntry: () => ({ isDirectory: true }) }] }, fs)
  await assert.rejects(importDroppedFile(dir, fs, () => assert.fail('must not import')), { dropCode: 'importDropUnreadable' })
  assert.deepEqual(fs.calls, [])
})

test('batch snapshot bounds total byte fallback, not native-path file sizes', async () => {
  const fs = desktop()
  const file = { name: 'large.txt', size: MAX_DROP_BYTES, arrayBuffer: () => Promise.resolve(new ArrayBuffer(0)) }
  let secondRead = false
  const second = { name: 'extra.txt', size: 1, arrayBuffer: () => { secondRead = true; return Promise.resolve(new ArrayBuffer(1)) } }
  captureDroppedFiles({ files: [file, second] }, fs)
  assert.equal(secondRead, false)
  await assert.rejects(importDroppedFile(second, fs, () => assert.fail()), { dropCode: 'importDropTooLarge' })
})

test('unavailable directory promise before an items-only File cannot misclassify that File', async () => {
  const fs = desktop(), file = new File(['contents'], 'file.txt')
  const files = captureDroppedFiles({ files: [], items: [
    { kind: 'file', getAsFile: () => null, webkitGetAsEntry: () => ({ isDirectory: true }) },
    { kind: 'file', getAsFile: () => file, webkitGetAsEntry: () => ({ isDirectory: false }) },
  ] }, fs)
  assert.deepEqual(files, [file])
  assert.equal(await importDroppedFile(file, fs, async () => 'imported'), 'imported')
})
