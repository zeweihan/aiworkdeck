// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// AI 工具 doc_export_pdf 的前端一半（dev-board#1065 T-25）：把 worker export_pdf 回的 PDF 字节
// 编成 base64 回传后端，由后端存进项目（与原文档同一文件夹）。零依赖纯函数，node 测试直接 import。
//
// 为什么不走通用的 editor_command 回传：worker 回的是 { success, size, bytes: Uint8Array }，
// JSON.stringify 会把 Uint8Array 展开成 {"0":37,"1":80,...}，一份 1MB 的 PDF 变成十几 MB 的对象。

/** 与后端 DocumentEditTools.MAX_EXPORT_PDF_BYTES 同值：base64 后要装得进 Jackson 默认的 2000 万字符。 */
export const MAX_EXPORT_PDF_BYTES = 12 * 1024 * 1024

/** worker 回的字节经 relay 两跳结构化克隆后形态不定，同 LibreOfficeEditor.exportPdf 的归一。 */
export function normalizePdfBytes(raw) {
  if (!raw) return null
  if (raw instanceof Uint8Array) return raw
  if (raw instanceof ArrayBuffer) return new Uint8Array(raw)
  if (raw.buffer instanceof ArrayBuffer) return new Uint8Array(raw.buffer, raw.byteOffset || 0, raw.byteLength)
  if (Array.isArray(raw)) return new Uint8Array(raw)
  return null
}

/** Uint8Array → base64。分块拼，避免 String.fromCharCode(...大数组) 爆调用栈。 */
export function bytesToBase64(bytes) {
  let binary = ''
  const CHUNK = 0x8000
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode.apply(null, bytes.subarray(i, i + CHUNK))
  }
  return btoa(binary)
}

/**
 * worker 回执 → sendEditorResult 的三个参数 { success, data, error }。
 * data 里只放后端要的东西：base64、字节数、源文件 id；绝不回传原始 bytes。
 */
export function agentPdfExportResult(res, sourceFileId) {
  if (!res || res.success === false) {
    const error = (res && (res.error || res.message)) || 'PDF 导出失败'
    return { success: false, data: null, error }
  }
  const bytes = normalizePdfBytes(res.bytes)
  if (!bytes || !bytes.length) return { success: false, data: null, error: 'PDF 导出结果为空' }
  if (bytes.length > MAX_EXPORT_PDF_BYTES) {
    const mb = Math.round(MAX_EXPORT_PDF_BYTES / 1024 / 1024)
    return {
      success: false,
      data: null,
      error: '导出的 PDF 超过 ' + mb + 'MB，无法经 AI 存进项目；请用菜单「文件 → 导出为 PDF」另存'
    }
  }
  return {
    success: true,
    data: {
      success: true,
      size: bytes.length,
      base64: bytesToBase64(bytes),
      sourceFileId: sourceFileId == null ? null : sourceFileId
    },
    error: null
  }
}
