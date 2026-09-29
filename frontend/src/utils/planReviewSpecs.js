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
 * （先看删除位之后、再看之前），配上的算改动，并把被替换的原文按当前行号记下
 * （editedOriginals，供改动行悬停查看）；只把多出来的删除行画成删除标记，
 * 标记落在配对的新增行之后。
 */
function netDeletions(changedLines, deletions) {
  const added = new Set(changedLines)
  const used = new Set()
  const out = []
  const editedOriginals = {}
  for (const d of deletions) {
    const lines = String(d.text).split('\n')
    const afterLines = []
    for (let ln = d.beforeLine; afterLines.length < d.count && added.has(ln) && !used.has(ln); ln++) {
      used.add(ln); afterLines.push(ln)
    }
    const beforeLines = []
    for (let ln = d.beforeLine - 1; afterLines.length + beforeLines.length < d.count && added.has(ln) && !used.has(ln); ln--) {
      used.add(ln); beforeLines.unshift(ln)
    }
    const paired = [...beforeLines, ...afterLines].sort((a, b) => a - b)
    // 删除行在前、按序对应配对行；多出的删除行留作删除标记
    paired.forEach((ln, k) => { editedOriginals[ln] = lines[k] })
    const rest = d.count - paired.length
    if (rest <= 0) continue
    out.push({ beforeLine: d.beforeLine + afterLines.length, count: rest, text: lines.slice(paired.length).join('\n') })
  }
  return { deletions: out, editedOriginals }
}

export function buildDecorationSpecs({ baseline, current, comments }) {
  const { changedLines, deletions: raw } = lineDiff(baseline, current)
  const { deletions, editedOriginals } = netDeletions(changedLines, raw)
  const commentRanges = (Array.isArray(comments) ? comments : []).map((c) => {
    const r = reanchorComment(c, current)
    return { id: c.id, fromLine: r.fromLine, toLine: r.toLine, found: r.found }
  })
  return { editedLines: changedLines, editedOriginals, deletions, commentRanges }
}

/**
 * 当前主选区的行号与原文；选区为空返回 null。只读传入对象的方法，不依赖 CodeMirror。
 * 三击选整行时 to 落在下一行行首：那一行不算进批注区间，原文也去掉尾部换行。
 */
export function selectionSnapshot(state) {
  const sel = state && state.selection && state.selection.main
  if (!sel || sel.from === sel.to) return null
  let to = sel.to
  if (to > sel.from && state.doc.lineAt(to).from === to) to -= 1
  return {
    fromLine: state.doc.lineAt(sel.from).number,
    toLine: state.doc.lineAt(to).number,
    quotedText: state.sliceDoc(sel.from, sel.to).replace(/\n+$/, '')
  }
}
