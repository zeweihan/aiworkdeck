// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 提交历史里「这一版合并了什么、逐处留了谁的」的说法（dev-board#630 / #632）
 * ——**纯函数，不许 import**。
 *
 * 病灶：既有裁决文案把 MAIN 一律说成「你这边」。但 MAIN 的物理侧在三个语境里
 * 不是同一件事（方向表见 `.claude/agents/version-control.md`「三语境冲突判定链」）：
 *
 *   | 语境 mergeContext | MAIN | DRAFT |
 *   |---|---|---|
 *   | `adopt`       | 主线     | 这一稿     |
 *   | `cloud`       | 你这边   | 案件库那边 |
 *   | `session-end` | **同事** | **你**     |
 *
 * 结束工作撞车时说反，律师读到的是一句与事实相反的历史记录——他会以为自己留的是
 * 自己那份，实际留的是同事那份。所以方向一律从 `mergeContext` 来；老提交没有这个
 * 尾注，那就两侧都用中性词，不猜。
 */

const s = (v) => (v == null ? '' : String(v))

/** 折叠阈值：一行副标题里最多列几处（设计稿 §5.6）。 */
const FOLD_AT = 6

/**
 * 这一次合并的两侧各该叫什么。
 * @param {Function} t            $t
 * @param {String}   mergeContext adopt | cloud | session-end（缺席 = 老提交）
 * @param {Object}   [names]      {main, other} 那一侧尖端提交的作者展示名，有就优先用
 */
export function mergeSideLabels(t, mergeContext, names = {}) {
  const key = {
    adopt: ['version.mergeSideMainline', 'version.mergeSideDraft'],
    cloud: ['version.mergeSideYours', 'version.mergeSideLibrary'],
    'session-end': ['version.mergeSideColleague', 'version.mergeSideYou'],
  }[s(mergeContext)] || ['version.mergeSideMainNeutral', 'version.mergeSideOtherNeutral']
  return {
    main: s(names && names.main).trim() || t(key[0]),
    other: s(names && names.other).trim() || t(key[1]),
  }
}

/** 单元键翻成律师的话：p12 → 第 13 段（后端段落键 0 基，界面与合并比对稿面板一样按 1 基说）；t1.2.3 → 表格里的一格；s3 → 第 3 页（页键本就 1 基）；xlsx 的格原样。 */
function unitLabel(t, key) {
  const k = s(key)
  let m = /^p(\d+)$/.exec(k)
  if (m) return t('version.mergeUnitParagraph', { n: Number(m[1]) + 1 })
  m = /^t(\d+)\.(\d+)\.(\d+)$/.exec(k)
  if (m) return t('version.mergeUnitCell', { table: Number(m[1]), row: Number(m[2]), col: Number(m[3]) })
  m = /^s(\d+)$/.exec(k)
  if (m) return t('version.mergeUnitSlide', { n: Number(m[1]) })
  return k
}

/**
 * 同一个单元上的几条决定合成一句话。
 * 两边都接受 = 「两边都留」；接受一边（另一边拒不拒都一样）= 「留了那边的」；
 * 只拒绝一边 = 「拒绝了那边的」；X = 律师自己改的；F = 那一侧的格式改动没合。
 */
function decisionPhrase(t, marks, sides) {
  if (marks.has('X')) return t('version.mergeDecisionSelf')
  if (marks.has('F')) return t('version.mergeDecisionFormat', { side: sides.other })
  const mainAccepted = marks.has('MA')
  const otherAccepted = marks.has('TA')
  if (mainAccepted && otherAccepted) return t('version.mergeDecisionBoth')
  if (mainAccepted) return t('version.mergeDecisionKept', { side: sides.main })
  if (otherAccepted) return t('version.mergeDecisionKept', { side: sides.other })
  if (marks.has('MR')) return t('version.mergeDecisionRejected', { side: sides.main })
  if (marks.has('TR')) return t('version.mergeDecisionRejected', { side: sides.other })
  return ''
}

function manualItems(t, decisions, sides) {
  const order = []
  const byKey = new Map()
  for (const d of Array.isArray(decisions) ? decisions : []) {
    if (!d || !d.key) continue
    const key = s(d.key)
    if (!byKey.has(key)) { byKey.set(key, new Set()); order.push(key) }
    const action = s(d.action).toUpperCase()
    byKey.get(key).add(action === 'A' || action === 'R' ? s(d.side).toUpperCase() + action : action)
  }
  const items = []
  for (const key of order) {
    const phrase = decisionPhrase(t, byKey.get(key), sides)
    if (phrase) items.push(unitLabel(t, key) + phrase)
  }
  return items
}

/**
 * 一条版本记录里的合并说明，每份文件一行。
 *
 * @param {Function} t       $t
 * @param {Object}   entry   `/version/history` 的一条 entry（要 mergeContext 与 merges）
 * @param {Object}   [names] {main, other} 两侧尖端提交的作者展示名
 * @returns {Array} [{text, more, full}] —— text 是折叠后的副标题，
 *                  more 是被折叠掉的处数（0 = 没折叠），full 是详情区用的全量那句
 */
export function historyMergeLines(t, entry, names = {}) {
  const merges = (entry && Array.isArray(entry.merges)) ? entry.merges : []
  const sides = mergeSideLabels(t, entry && entry.mergeContext, names)
  const sep = t('version.mergeItemSep')
  const lines = []
  for (const m of merges) {
    if (!m || !m.path) continue
    const path = s(m.path)
    if (s(m.mode) === 'auto') {
      const text = t('version.mergeAutoLine', {
        other: sides.other, path,
        mainName: sides.main, otherName: sides.other,
        mainCount: Number(m.mainCount) || 0, otherCount: Number(m.otherCount) || 0,
      })
      lines.push({ text, more: 0, full: text })
      continue
    }
    const items = manualItems(t, m.decisions, sides)
    if (!items.length) continue
    const more = Math.max(0, items.length - FOLD_AT)
    const shown = more ? items.slice(0, FOLD_AT).concat(t('version.mergeManualMore', { count: more })) : items
    lines.push({
      text: t('version.mergeManualLine', { path, items: shown.join(sep) }),
      more,
      full: t('version.mergeManualLine', { path, items: items.join(sep) }),
    })
  }
  return lines
}

/**
 * 整份三选一那一条的说法。语境在（新提交）就按方向说「留了同事那边的」，
 * 语境缺席（老提交）保持旧文案——历史怎么写的就怎么读，不追认。
 */
export function resolutionLine(t, resolution, mergeContext, names = {}) {
  const path = s(resolution && resolution.path)
  const kept = s(resolution && resolution.kept).toUpperCase()
  if (kept === 'MERGED') return t('version.resolutionMerged', { path })
  if (kept === 'BOTH') return t('version.resolutionKeptBoth', { path })
  if (!s(mergeContext)) {
    const legacy = { MAIN: 'version.resolutionKeptMain', DRAFT: 'version.resolutionKeptDraft' }[kept]
    return t(legacy || 'version.resolutionKeptBoth', { path })
  }
  const sides = mergeSideLabels(t, mergeContext, names)
  if (kept === 'MAIN') return t('version.resolutionKeptSide', { path, side: sides.main })
  if (kept === 'DRAFT') return t('version.resolutionKeptSide', { path, side: sides.other })
  return t('version.resolutionKeptBoth', { path })
}
