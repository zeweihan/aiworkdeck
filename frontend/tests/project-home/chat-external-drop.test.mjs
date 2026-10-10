// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { nativeDataTransfer, captureDroppedFiles, isExternalFileDrag, importDroppedFile } from '../../src/utils/fileTreeExternalDrop.js'
const chat = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
const overview = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')

function makeChat({ failure = false } = {}) {
  const calls = [], attached = [], toasts = [], isUploading = { value: false }
  const host = { fs: {
    getPathForFile: file => file.localPath || '',
    stageDroppedFile: async file => { calls.push(['stage', file]); return { ok: true, path: '/tmp/awd-drop-test/source.txt', token: 'token' } },
    releaseDroppedFile: async token => { calls.push(['release', token]); return { ok: true } },
  } }
  const importLocalFile = async (...args) => {
    calls.push(['import', ...args])
    if (failure) throw Error('import failed')
    return { data: { id: 4, name: 'source.txt', fileType: 'txt' } }
  }
  const body = chat.slice(chat.indexOf('    const uploadFilesAndAttach ='), chat.indexOf('    // Upload file content to storage'))
  const names = ['props', 'isUploading', 'host', 'captureDroppedFiles', 'importDroppedFile', 'importLocalFile', 'addFile', 'uni', 't', 'createFile']
  const api = new Function(...names, body + '; return { uploadLocalFilesAndAddContext }')(
    { projectId: '42' }, isUploading, host, captureDroppedFiles, importDroppedFile, importLocalFile,
    file => attached.push(file), { showToast: o => toasts.push(o.title) }, key => key,
    () => assert.fail('desktop must never create an empty placeholder'),
  )
  const handler = overview.slice(overview.indexOf('    handleAiDrop(e) {'), overview.indexOf('    /** 把一份项目文件挂进 AI 上下文'))
  let pending
  const handleAiDrop = new Function('nativeDataTransfer', 'captureDroppedFiles', 'isExternalFileDrag', 'host', 'uni', 'return ({' + handler + '}).handleAiDrop')(
    nativeDataTransfer, captureDroppedFiles, isExternalFileDrag, host, { showToast: o => toasts.push(o.title) },
  )
  const vm = { $t: key => key, droppedEntriesHaveDirectory: () => false, $refs: { chatInterface: {
    uploadLocalFilesAndAddContext: files => { pending = api.uploadLocalFilesAndAddContext(files) },
  } } }
  return { api, calls, attached, toasts, isUploading, drop: async dt => { handleAiDrop.call(vm, { dataTransfer: dt }); await pending } }
}

test('AI input items-only File bytes are imported once and attached only after successful copy', async () => {
  const c = makeChat(), file = new File(['exact bytes'], 'source.txt')
  await c.drop({ files: [], items: [{ kind: 'file', getAsFile: () => file }], types: [], getData: () => '' })
  assert.deepEqual(c.calls.map(c => c[0]), ['stage', 'import', 'release'])
  assert.deepEqual(c.calls[1].slice(1), [42, '/tmp/awd-drop-test/source.txt', null])
  assert.equal(c.attached.length, 1)
  assert.equal(c.isUploading.value, false)
})

test('AI import failure releases the temp token and never attaches an empty document', async () => {
  const c = makeChat({ failure: true })
  await c.api.uploadLocalFilesAndAddContext([new File(['bytes'], 'source.txt')])
  assert.deepEqual(c.calls.map(c => c[0]), ['stage', 'import', 'release'])
  assert.deepEqual(c.attached, [])
})

test('AI URI-only and unavailable File sources give actionable errors without reading arbitrary paths', async () => {
  const c = makeChat()
  await c.drop({ files: [], items: [], types: ['text/uri-list'], getData: type => type === 'text/uri-list' ? 'file:///tmp/awd-drop-test/private.txt' : '' })
  assert.ok(c.toasts.includes('fileTree.importDropUnreadable'))
  assert.deepEqual(c.calls, [])
  await c.api.uploadLocalFilesAndAddContext([{ name: 'unavailable.txt', size: 10 }])
  assert.equal(c.toasts.at(-1), 'fileTree.importDropUnreadable')
  assert.deepEqual(c.attached, [])
})
