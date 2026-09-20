// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 即时审校结果的分类、计数与新鲜度判定。
//
// 纯函数、不 import Vue / uni / i18n：客体页的浮球（zetaOfficeInlineReview.js，
// 跑在 webview 里、没有 Vue）与宿主右栏的审校面板（InlineReviewPanel.vue）
// 共用同一份判据。两边各写一份的话，浮球上的数字和面板里的条数会对不上。

export const REVIEW_BUCKETS = ['all', 'supplement', 'consistency', 'format', 'ai']

/** 一条 finding 归哪一类。kind 由后端 DocInsightService 下发，认不出的算「一致性」。 */
export function bucketOf(finding) {
  const kind = String((finding && finding.kind) || '').toUpperCase()
  if (kind === 'LOGIC_REVIEW' || kind.startsWith('AI_')) return 'ai'
  if (['PLACEHOLDER', 'BLANK', 'DOCUMENT_NOT_FOUND'].includes(kind)) return 'supplement'
  if (['COUNT_MISMATCH', 'ARITHMETIC', 'DANGLING_REFERENCE'].includes(kind)) return 'consistency'
  if (['SCRIPT_OUTLIER', 'NUMBERING', 'USCC_INVALID'].includes(kind)) return 'format'
  return 'consistency'
}

export function filterByBucket(findings, bucket) {
  const list = Array.isArray(findings) ? findings : []
  return !bucket || bucket === 'all' ? list.slice() : list.filter((f) => bucketOf(f) === bucket)
}

/** 四类各几条 + 总数。计数恒按传进来的全量算，不受当前选中的分类影响。 */
export function countByBucket(findings) {
  const list = Array.isArray(findings) ? findings : []
  const out = { all: list.length, supplement: 0, consistency: 0, format: 0, ai: 0 }
  for (const f of list) out[bucketOf(f)]++
  return out
}

/** 去掉用户「忽略」过的条目（忽略只在本次会话有效，不写进文档）。 */
export function visibleFindings(findings, ignored) {
  const skip = ignored instanceof Set ? ignored : new Set(ignored || [])
  return (Array.isArray(findings) ? findings : []).filter((f) => !skip.has(String(f && f.id)))
}

/**
 * 这批结果还对得上当前正文吗。
 * 对不上就只许看、不许定位/采用——旧 revision 的段落偏移落在改过的正文上会改错地方。
 */
export function isFresh(state) {
  return !!state && state.enabled !== false && state.status === 'ready' && state.revision != null
}

export function isLocatable(finding) {
  return !!finding && typeof finding.expectedParagraph === 'string'
    && Number.isInteger(finding.start) && Number.isInteger(finding.end)
}

export function isApplicable(finding, state) {
  return isLocatable(finding) && typeof (finding && finding.replacement) === 'string'
    && !!state && state.writable !== false
}
