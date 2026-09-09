// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

const MAX_ENTRIES = 50
const SENSITIVE_NUMBER = /(?:\d[\s-]*){11,19}/
const ORGANIZATION_SUFFIX = '(?:有限责任公司|股份有限公司|有限公司|律师事务所|人民法院|人民检察院|仲裁委员会|合伙企业|委员会|人民政府|集团|银行|学校|医院|中心|局)'
const ORGANIZATION_RE = new RegExp(`[\\p{Script=Han}A-Za-z0-9（）()·]{2,60}?${ORGANIZATION_SUFFIX}`, 'gu')
const ORGANIZATION_END_RE = new RegExp(`${ORGANIZATION_SUFFIX}$`, 'u')
const LAW_ARTICLE_RE = /《[^》\r\n]{2,80}》(?:第[零〇一二三四五六七八九十百千万亿两\d]+条(?:之[零〇一二三四五六七八九十百千万亿两\d]+)?)?/gu
const CASE_NUMBER_RE = /[（(]\d{4}[）)][\p{Script=Han}A-Za-z0-9]{2,30}号/gu
const PERSON_RE = /(?:法定代表人|原告|被告|姓名|联系人)\s*(?:为|是)?\s*[：:]?\s*([\p{Script=Han}·]{2,8}?)(?=\s|[，,。；;、]|与|和|及|$)/gu

function isSafeEntry(text) {
  return text.length >= 2 && text.length <= 160 && !SENSITIVE_NUMBER.test(text)
}

function cleanOrganization(candidate, leadingText) {
  let value = candidate.replace(/^(?:本协议由|甲方为|乙方为|甲方是|乙方是|原告为|被告为|申请人为|被申请人为)/u, '')
  if (value.startsWith('与') && ORGANIZATION_END_RE.test(leadingText)) value = value.slice(1)
  if (/(?:原告|被告)[：:]\s*$/u.test(leadingText)) {
    value = value.replace(/^[\p{Script=Han}·]{2,8}与(?=[\p{Script=Han}A-Za-z0-9（）()·]+$)/u, '')
  }
  return value
}

function resolveSegmenter(segmenter) {
  if (segmenter === null) return null
  if (segmenter) return segmenter
  if (typeof Intl?.Segmenter !== 'function') return null
  return new Intl.Segmenter('zh-CN', { granularity: 'word' })
}

export function extractCompletionEntries(text, { segmenter } = {}) {
  if (typeof text !== 'string' || !text) return []

  const entries = []
  const seen = new Set()
  const add = (value, kind) => {
    const normalized = String(value || '').trim()
    if (!isSafeEntry(normalized) || seen.has(normalized)) return
    seen.add(normalized)
    entries.push({ text: normalized, kind })
  }

  for (const match of text.matchAll(ORGANIZATION_RE)) {
    add(cleanOrganization(match[0], text.slice(Math.max(0, match.index - 20), match.index)), 'COMPANY')
  }
  for (const match of text.matchAll(LAW_ARTICLE_RE)) {
    const value = match[0]
    const law = value.match(/^《[^》]+》/u)?.[0]
    if (law) add(law, 'LAW')
    if (value.length > law?.length) add(value, 'ARTICLE')
  }
  for (const match of text.matchAll(CASE_NUMBER_RE)) add(match[0], 'CASE')
  for (const match of text.matchAll(PERSON_RE)) add(match[1], 'PERSON')

  const wordSegmenter = resolveSegmenter(segmenter)
  if (wordSegmenter) {
    for (const part of wordSegmenter.segment(text)) {
      if (part.isWordLike && /^[\p{Script=Han}A-Za-z]{2,40}$/u.test(part.segment)) add(part.segment, 'WORD')
    }
  }
  for (const phrase of text.split(/[，,。；;！？!?\r\n]+/u)) {
    const normalized = phrase.trim()
    if (normalized.length >= 4 && normalized.length <= 80) add(normalized, 'PHRASE')
  }

  return entries.slice(0, MAX_ENTRIES)
}

function isUsablePrefix(prefix) {
  const chineseCount = prefix.match(/\p{Script=Han}/gu)?.length || 0
  const latinCount = prefix.match(/[A-Za-z]/g)?.length || 0
  const caseNumberLead = /[（(]\d{4}[）)][\p{Script=Han}A-Za-z0-9]+$/u.test(prefix)
  return chineseCount >= 2 || latinCount >= 3 || caseNumberLead
}

function longestTailPrefix(before, text) {
  const maxLength = Math.min(before.length, text.length - 1)
  for (let length = maxLength; length > 0; length -= 1) {
    const prefix = before.slice(-length)
    if (isUsablePrefix(prefix) && text.startsWith(prefix)) return prefix
  }
  return ''
}

function itemRank(a, b) {
  return b.prefix.length - a.prefix.length
    || Number(b.scope === 'project') - Number(a.scope === 'project')
    || Number(b.uses || 0) - Number(a.uses || 0)
    || Number(b.lastUsedAt || 0) - Number(a.lastUsedAt || 0)
}

export function matchCompletionItems(before, items, { limit = 8 } = {}) {
  if (typeof before !== 'string' || !Array.isArray(items) || limit <= 0) return []

  const matches = []
  for (const item of items) {
    if (!item || typeof item.text !== 'string' || item.text.length < 2) continue
    const prefix = longestTailPrefix(before, item.text)
    if (prefix) matches.push({ ...item, prefix })
  }
  matches.sort(itemRank)

  const unique = []
  const seen = new Set()
  for (const item of matches) {
    if (seen.has(item.text)) continue
    seen.add(item.text)
    unique.push(item)
    if (unique.length >= limit) break
  }
  return unique
}
