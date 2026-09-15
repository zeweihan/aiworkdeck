// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// B4 的接线用例：LibreOfficeEditor 的保存路径必须「导出 → 打标 → 上传」这个次序，
// 上传出去的是**打过标的**字节，不是导出的原字节。
//
// 为什么必须有这一条：lowa-e2e 跑的是 dist/zetaoffice 的 editor-main.js（客体页），
// 打标发生在 Vue 宿主的 saveDocument 里，e2e 那条链路根本经不过来——纯函数由
// tests/lowa-unit/docxAppProps.test.mjs 守，接线只能在这里守。
// 还原病灶（把 saveDocument 里的 stampGeneratorMetadata 一行删掉）本文件即转红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const BODY = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')

/**
 * @param stamp  桩掉的 stampApplication（(bytes, app) => bytes'）
 * @param appOf  桩掉的 documentStampApplication（() => Promise<string|null>）
 */
function makeVm({ stamp, appOf, exportBytes }) {
  const uploads = []
  const options = new Function(
    'ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'getFileUploadUrl',
    'stampApplication', 'documentStampApplication', BODY)(
    null, null, null, (id) => '/upload/' + id, stamp, appOf)
  const vm = {
    ready: true, file: { id: 7, name: '合同.docx' }, statusKey: 'ready',
    dirty: true, saving: false, _dirtySince: Date.now(),
    appendLog() {}, scheduleAnchorCheck() {}, $t: (k) => k,
    executor: { executeCommand: async () => ({ success: true, bytes: exportBytes }) },
    uploadBytes: async (url, u8) => { uploads.push(u8) },
  }
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  Object.assign(vm, { appendLog() {}, scheduleAnchorCheck() {} })
  vm.uploadBytes = async (url, u8) => { uploads.push(u8) }
  return { vm, uploads }
}

test('开关开着：上传的是打过标的字节，且 application 串来自后端下发的值', async () => {
  const calls = []
  const { vm, uploads } = makeVm({
    exportBytes: [1, 2, 3, 4],
    appOf: async () => 'AI WorkDeck 0.36.0',
    stamp: async (bytes, app) => { calls.push([Array.from(bytes), app]); return new Uint8Array([9, 9]) },
  })
  assert.equal(await vm.saveDocument(), true)
  assert.equal(calls.length, 1, 'stampApplication 必须被调用一次')
  assert.deepEqual(calls[0][0], [1, 2, 3, 4], '喂给打标的必须是导出的原字节')
  assert.equal(calls[0][1], 'AI WorkDeck 0.36.0', '串必须是后端给的，不许前端自己拼')
  assert.equal(uploads.length, 1)
  assert.deepEqual(Array.from(uploads[0]), [9, 9], '上传出去的必须是打标后的字节')
})

test('开关关着：一次 stampApplication 都不调，上传导出原字节', async () => {
  let stamped = 0
  const { vm, uploads } = makeVm({
    exportBytes: [1, 2, 3, 4],
    appOf: async () => null,
    stamp: async (bytes) => { stamped++; return bytes },
  })
  assert.equal(await vm.saveDocument(), true)
  assert.equal(stamped, 0)
  assert.deepEqual(Array.from(uploads[0]), [1, 2, 3, 4])
})

test('打标抛异常不许影响保存：照旧上传导出原字节并成功', async () => {
  const { vm, uploads } = makeVm({
    exportBytes: [5, 6, 7],
    appOf: async () => 'AI WorkDeck 0.36.0',
    stamp: async () => { throw new Error('zip parse blew up') },
  })
  assert.equal(await vm.saveDocument(), true)
  assert.equal(vm.statusKey, 'ready')
  assert.deepEqual(Array.from(uploads[0]), [5, 6, 7])
})

test('开关读不到（documentStampApplication 抛）也不许影响保存', async () => {
  const { vm, uploads } = makeVm({
    exportBytes: [5, 6, 7],
    appOf: async () => { throw new Error('backend down') },
    stamp: async () => new Uint8Array([0]),
  })
  assert.equal(await vm.saveDocument(), true)
  assert.deepEqual(Array.from(uploads[0]), [5, 6, 7])
})

test('打标返回空字节时退回导出原字节（绝不上传空文件）', async () => {
  const { vm, uploads } = makeVm({
    exportBytes: [5, 6, 7],
    appOf: async () => 'AI WorkDeck 0.36.0',
    stamp: async () => new Uint8Array(0),
  })
  assert.equal(await vm.saveDocument(), true)
  assert.deepEqual(Array.from(uploads[0]), [5, 6, 7])
})
