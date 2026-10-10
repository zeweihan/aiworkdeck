// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { normalizePdfBytes, bytesToBase64, MAX_EXPORT_PDF_BYTES } from '../pages/project-overview/agentPdfExport.js'

export function visualPageRange(value) {
  const m = /^\s*(\d+)(?:\s*[-–]\s*(\d+))?\s*$/.exec(value || '')
  if (!m) return null
  const startPage = Number(m[1]), endPage = Number(m[2] || m[1])
  return Number.isSafeInteger(startPage) && Number.isSafeInteger(endPage) && startPage > 0
    && endPage >= startPage && endPage - startPage < 6 ? { startPage, endPage } : null
}

/** User gesture only. No export or model request before confirmation. */
export async function runDocumentVisualReview({ execute, request, dialog, busy, current, t, fileId }) {
  const choice = await dialog({ title: t('pagesTitle'), editable: true, content: '1-6', placeholderText: t('pagesHint') })
  if (!choice.confirm || !current()) return false
  const range = visualPageRange(choice.content)
  if (!range) { await dialog({ title: t('title'), content: t('badPages'), showCancel: false }); return false }
  const consent = await dialog({ title: t('title'), content: t('consent', range), confirmText: t('start'), cancelText: t('cancel') })
  if (!consent.confirm || !current()) return false
  busy(true)
  try {
    const readRevision = async () => {
      if (!current()) throw new Error(t('stale'))
      const r = await execute('get_document_text', { maxParagraphs: 1 })
      if (!current()) throw new Error(t('stale'))
      if (!r?.success || r.revision == null) throw new Error(t('snapshotFailed'))
      return r.revision
    }
    const revision = await readRevision()
    const pdf = await execute('export_pdf', {})
    if (!pdf?.success) throw new Error(pdf?.message || t('exportFailed'))
    const bytes = normalizePdfBytes(pdf.bytes)
    if (!bytes?.length || bytes.length > MAX_EXPORT_PDF_BYTES) throw new Error(t('sizeLimit'))
    if (await readRevision() !== revision) throw new Error(t('stale'))
    const raw = await request({ docFileId: fileId, base64: bytesToBase64(bytes), ...range, confirmed: true, revision })
    const result = raw?.data || raw
    if (!current() || await readRevision() !== revision || result?.revision !== revision) throw new Error(t('stale'))
    if (!result?.report || !Array.isArray(result.checkedPages) || !result.checkedPages.length) throw new Error(t('empty'))
    busy(false)
    if (!current()) throw new Error(t('stale'))
    await dialog({ title: t('title'), content: t('scope', { pages: result.checkedPages.join(', '), total: result.totalPages })
      + '\n' + (result.complete ? t('allPages') : t('partial')) + '\n\n' + result.report, showCancel: false })
    return true
  } catch (error) {
    busy(false)
    if (current()) await dialog({ title: t('title'), content: error?.message || t('failed'), showCancel: false })
    return false
  } finally { busy(false) }
}
