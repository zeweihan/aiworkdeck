// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board#886 宿主半边：worker 把原生「导出为 PDF」拦成 export-pdf-request 之后，
// LibreOfficeEditor 必须真的接住——取 export_pdf 字节、走 blob + a[download] 下载、
// 成功失败都给提示。引擎半边（拦截 + PDF 字节）由 tests/lowa-e2e/pdf-export.mjs 真引擎守。
// 还原病灶（删掉 subscribeHostEvents 里 export-pdf-request 分支，或 exportPdf 不再点下载链接）即转红。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const BODY = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')

function setup({ result, throws } = {}) {
  const toasts = [], clicks = [], sent = []
  globalThis.uni = { showToast: (o) => toasts.push(o.title), showLoading() {}, hideLoading() {} }
  globalThis.URL.createObjectURL = (blob) => { clicks.push({ blobType: blob.type, size: blob.size }); return 'blob:x' }
  globalThis.URL.revokeObjectURL = () => {}
  globalThis.document = {
    createElement: () => ({ click() { clicks[clicks.length - 1].download = this.download; clicks[clicks.length - 1].clicked = true }, remove() {} }),
    body: { appendChild() {} },
  }
  // setTimeout 桩掉：exportPdf 60s 后才 revokeObjectURL，真定时器会把测试进程拖住 60s
  const options = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', 'setTimeout', BODY)(null, null, null, () => 0)
  const vm = {
    file: { id: 7, name: '甲版合同.docx' },
    ready: true,
    appendLog() {},
    $t: (k, p) => k + (p ? JSON.stringify(p) : ''),
    executor: { executeCommand: async (a, p) => { sent.push(a); if (throws) throw new Error(throws); return result } },
  }
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  return { vm, toasts, clicks, sent }
}

test('export-pdf-request 消息接到 exportPdf', () => {
  assert.match(SRC, /msg\.type === 'export-pdf-request'\) \{\s*this\.exportPdf\(\)/)
})

test('拿到 PDF 字节：以「原名.pdf」下载，并提示成功', async () => {
  const bytes = new Uint8Array([37, 80, 68, 70, 45, 49])
  const { vm, toasts, clicks, sent } = setup({ result: { success: true, bytes } })
  assert.equal(await vm.exportPdf(), true)
  assert.deepEqual(sent, ['export_pdf'])
  assert.equal(clicks.length, 1)
  assert.equal(clicks[0].clicked, true)
  assert.equal(clicks[0].download, '甲版合同.pdf')
  assert.equal(clicks[0].blobType, 'application/pdf')
  assert.equal(clicks[0].size, 6)
  assert.match(toasts[0], /^editor\.pdfExported/)
})

test('引擎失败或超时：不下载，给出失败原因（不许点了没反应）', async () => {
  const a = setup({ result: { success: false, message: '当前文档类型不支持导出 PDF' } })
  assert.equal(await a.vm.exportPdf(), false)
  assert.equal(a.clicks.length, 0)
  assert.match(a.toasts[0], /^editor\.pdfExportFailed.*不支持导出 PDF/)
  const b = setup({ throws: 'relay timeout' })
  assert.equal(await b.vm.exportPdf(), false)
  assert.match(b.toasts[0], /relay timeout/)
})
