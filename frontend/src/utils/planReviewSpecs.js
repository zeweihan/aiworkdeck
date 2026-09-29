// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 计划审阅装饰的纯函数层（dev-board#1022）：算出「哪些行改了、哪里删了、批注落在哪」，
 * 由 planReviewExtensions.js 翻译成 CodeMirror 装饰。
 * 这个文件不许 import 任何 @codemirror/*：node:test 加载模块会执行全部顶层 import，
 * CodeMirror 包在 node 里不可直接加载，单测就只能测这一层。
 */
import { lineDiff } from './lineDiff.js'
import { reanchorComment } from './planReview.js'

/**
 * lineDiff 把「改了一行」记成一删一增；审阅视图里那一行只该显示为「改动」，
 * 不该再冒一条「已删除 1 行」。所以每段删除先与紧挨着它的新增行一一配对
 * （先看删除位之后、再看之前），配上的算改动，只把多出来的删除行画成删除标记，
 * 标记落在配对的新增行之后。
 */
function netDeletions(changedLines, deletions) {
  const added = new Set(changedLines)
  const used = new Set()
  const out = []
  for (const d of deletions) {
    let paired = 0
    let after = 0
    for (let ln = d.beforeLine; paired < d.count && added.has(ln) && !used.has(ln); ln++) {
      used.add(ln); paired++; after++
    }
    for (let ln = d.beforeLine - 1; paired < d.count && added.has(ln) && !used.has(ln); ln--) {
      used.add(ln); paired++
    }
    const rest = d.count - paired
    if (rest <= 0) continue
    const lines = String(d.text).split('\n')
    out.push({ beforeLine: d.beforeLine + after, count: rest, text: lines.slice(lines.length - rest).join('\n') })
  }
  return out
}

export function buildDecorationSpecs({ baseline, current, comments }) {
  const { changedLines, deletions: raw } = lineDiff(baseline, current)
  const deletions = netDeletions(changedLines, raw)
  const commentRanges = (Array.isArray(comments) ? comments : []).map((c) => {
    const r = reanchorComment(c, current)
    return { id: c.id, fromLine: r.fromLine, toLine: r.toLine, found: r.found }
  })
  return { editedLines: changedLines, deletions, commentRanges }
}

/** 当前主选区的行号与原文；选区为空返回 null。只读传入对象的方法，不依赖 CodeMirror。 */
export function selectionSnapshot(state) {
  const sel = state && state.selection && state.selection.main
  if (!sel || sel.from === sel.to) return null
  return {
    fromLine: state.doc.lineAt(sel.from).number,
    toLine: state.doc.lineAt(sel.to).number,
    quotedText: state.sliceDoc(sel.from, sel.to)
  }
}
