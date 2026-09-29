// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// doc_export_pdf 的前端一半（dev-board#1065 T-25）：worker 回执 → sendEditorResult 的三个参数。
// 病灶是通用回传路径会把 Uint8Array 原样交给 JSON.stringify，展开成 {"0":37,"1":80,...}。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { agentPdfExportResult, bytesToBase64, normalizePdfBytes, MAX_EXPORT_PDF_BYTES } from '../../src/pages/project-overview/agentPdfExport.js'

const pdf = new TextEncoder().encode('%PDF-1.7 测试')

test('成功：只回 base64、字节数与源文件 id，绝不回原始 bytes', () => {
  const out = agentPdfExportResult({ success: true, size: pdf.length, bytes: pdf }, 42)
  assert.equal(out.success, true)
  assert.equal(out.error, null)
  assert.deepEqual(Object.keys(out.data).sort(), ['base64', 'size', 'sourceFileId', 'success'])
  assert.equal(out.data.sourceFileId, 42)
  assert.equal(out.data.size, pdf.length)
  assert.deepEqual(new Uint8Array(Buffer.from(out.data.base64, 'base64')), pdf)
  assert.ok(!JSON.stringify(out.data).includes('"0":'), 'Uint8Array 不许被 JSON 展开')
})

test('relay 两跳后字节形态不定：ArrayBuffer / 视图 / 普通数组都认', () => {
  assert.deepEqual(normalizePdfBytes(pdf.buffer.slice(0)), pdf)
  assert.deepEqual(normalizePdfBytes(new DataView(pdf.buffer)), pdf)
  assert.deepEqual(normalizePdfBytes(Array.from(pdf)), pdf)
  assert.equal(normalizePdfBytes(null), null)
})

test('大文件分块编码与一次编码结果一致（不爆调用栈）', () => {
  const big = new Uint8Array(200000).map((_, i) => i % 251)
  assert.equal(bytesToBase64(big), Buffer.from(big).toString('base64'))
})

test('失败面：worker 报错透传原因；空结果、超限都回 success=false', () => {
  assert.deepEqual(agentPdfExportResult({ success: false, message: '当前文档类型不支持导出 PDF' }, 1),
    { success: false, data: null, error: '当前文档类型不支持导出 PDF' })
  assert.equal(agentPdfExportResult(null, 1).success, false)
  assert.equal(agentPdfExportResult({ success: true, bytes: new Uint8Array(0) }, 1).success, false)
  const tooBig = agentPdfExportResult({ success: true, bytes: new Uint8Array(MAX_EXPORT_PDF_BYTES + 1) }, 1)
  assert.equal(tooBig.success, false)
  assert.match(tooBig.error, /导出为 PDF/)
})

test('上限与后端 DocumentEditTools.MAX_EXPORT_PDF_BYTES 同值', () => {
  const java = readFileSync(new URL('../../../backend/src/main/java/com/checkba/service/ai/tools/DocumentEditTools.java', import.meta.url), 'utf8')
  const m = /MAX_EXPORT_PDF_BYTES = (\d+)L \* 1024 \* 1024/.exec(java)
  assert.ok(m, '后端常量没找到')
  assert.equal(Number(m[1]) * 1024 * 1024, MAX_EXPORT_PDF_BYTES)
})

test('agentClientActions 对 export_pdf 走专门的回传路径（在通用路径之前拦下）', () => {
  const src = readFileSync(new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
  const special = src.indexOf("commandAction === 'export_pdf'")
  const generic = src.indexOf('this.libreOfficeExecutor.executeCommand(\n                commandAction')
  assert.ok(special > 0, '没有 export_pdf 的专门分支')
  assert.ok(generic > special, 'export_pdf 分支必须排在通用 executeCommand 之前')
})
