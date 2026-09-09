// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import {
  authoritative,
  caseRecognition,
  caseRecord,
  companyRows,
  lawArticle,
  resultRecords,
  unwrapResult,
} from './insightDetail.js'

const ERROR_STATUSES = new Set(['ERROR', 'FAILED', 'NOT_FOUND', 'UNAVAILABLE'])

function parseDetail(value) {
  if (!value) return null
  if (typeof value === 'object') return value
  if (typeof value !== 'string') return null
  try {
    const parsed = JSON.parse(value)
    return parsed && typeof parsed === 'object' ? parsed : null
  } catch {
    return null
  }
}

function pick(record, keys) {
  if (!record || typeof record !== 'object') return ''
  for (const key of keys) {
    const value = record[key]
    if (value != null && typeof value !== 'object' && String(value).trim()) return String(value)
  }
  return ''
}

function insertionText(content, source, date) {
  if (!content) return undefined
  const footer = [
    source ? `来源 / Source：${source}` : '',
    date ? `查询日期：${date}` : '',
  ].filter(Boolean)
  return footer.length ? `${content}\n\n${footer.join('\n')}` : content
}

function citationText(lines, content, source, date) {
  if (!content) return undefined
  return insertionText(`${lines.filter(Boolean).join('\n')}\n\n${content}`, source, date)
}

function recordDetails(detail) {
  return resultRecords(unwrapResult(detail)).records
}

function lawVariants(detail, source, date, fallbackTitle) {
  const records = recordDetails(detail)
  if (!records.length) {
    const law = lawArticle(detail)
    if (!law.content) return []
    const title = [law.title, law.article].filter(Boolean).join(' ') || fallbackTitle
    return [{
      title,
      text: citationText([law.title || fallbackTitle, law.article], law.content, source, date),
    }]
  }
  return records.map((record) => {
    const law = lawArticle({ result: record })
    const url = pick(record, ['url', 'link', '链接'])
    const title = [law.title, law.article].filter(Boolean).join(' ') || law.title || law.article
    return {
      title,
      ...(law.content ? { text: citationText([
        law.title,
        law.article,
        law.timeliness ? `时效性：${law.timeliness}` : '',
        url ? `URL：${url}` : '',
      ], law.content, source, date) } : {}),
    }
  }).filter((variant) => variant.text)
}

function caseVariants(detail, source, date, fallbackTitle) {
  const records = recordDetails(detail)
  if (!records.length) {
    const item = caseRecord(detail)
    const content = item.sections.map((section) => section.text).filter(Boolean).join('\n\n')
    if (!content) return []
    const title = item.title || item.caseNumber || fallbackTitle
    return [{
      title,
      text: citationText([
        item.title || fallbackTitle,
        item.caseNumber ? `案号：${item.caseNumber}` : '',
        item.court ? `法院：${item.court}` : '',
        item.date ? `裁判日期：${item.date}` : '',
      ], content, source, date),
    }]
  }
  return records.map((record) => {
    const item = caseRecord({ result: record })
    const content = item.sections.map((section) => section.text).filter(Boolean).join('\n\n')
    const url = pick(record, ['url', 'link', '链接'])
    return {
      title: item.title || item.caseNumber,
      ...(content ? { text: citationText([
        item.title,
        item.caseNumber ? `案号：${item.caseNumber}` : '',
        item.court ? `法院：${item.court}` : '',
        item.date ? `裁判日期：${item.date}` : '',
        url ? `URL：${url}` : '',
      ], content, source, date) } : {}),
    }
  }).filter((variant) => variant.text)
}

function authoritativeVariant(detail, source, date) {
  const item = authoritative(detail)
  if (!item) return null
  return {
    title: item.title,
    ...(item.text ? { text: citationText([
      item.title,
      item.date ? `实施日期：${item.date}` : '',
      item.url ? `URL：${item.url}` : '',
    ], item.text, source, date) } : {}),
  }
}

function recognitionNote(detail) {
  const item = caseRecognition(detail)
  if (!item) return ''
  return [item.title, item.caseNumber, item.court, item.url].filter(Boolean).join(' / ')
}

export function completionDetails(entityView) {
  const entity = entityView && typeof entityView === 'object' ? entityView : {}
  const detail = parseDetail(entity.detail)
  const source = entity.retrievalSource || (detail && typeof detail.source === 'string' ? detail.source : '')
  const date = entity.fetchedAt == null ? '' : String(entity.fetchedAt)
  const note = entity.retrievalNote == null ? '' : String(entity.retrievalNote)
  const base = { title: entity.name == null ? '' : String(entity.name), source, date, note, variants: [] }
  if (!detail) return base

  const failed = ERROR_STATUSES.has(String(entity.retrievalStatus || '').toUpperCase())
  if (entity.kind === 'COMPANY') {
    if (!failed) {
      const rows = companyRows(detail)
      if (rows.length) base.variants.push({ title: base.title, rows: rows.map(({ label, value }) => [label, value]) })
    }
  } else if (entity.kind === 'LAW' || entity.kind === 'ARTICLE') {
    if (!failed) {
      base.variants.push(...lawVariants(detail, source, date, base.title))
    }
    const authority = authoritativeVariant(detail, source, date)
    if (authority?.text) base.variants.push(authority)
  } else if (entity.kind === 'CASE') {
    if (!failed) {
      base.variants.push(...caseVariants(detail, source, date, base.title))
    }
    const recognition = recognitionNote(detail)
    if (recognition) base.note = [base.note, recognition].filter(Boolean).join('\n')
  }
  return base
}
