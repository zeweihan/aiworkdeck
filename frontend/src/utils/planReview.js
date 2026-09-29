// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 计划审阅：批注重锚定与回喂消息拼装（纯函数）。
 */
export function reanchorComment(comment, currentText) {
  const lines = String(currentText || '').split('\n')
  const q = String(comment.quotedText || '')
  const span = Math.max(1, (comment.toLine || comment.fromLine) - comment.fromLine + 1)
  const matchAt = (i) => q && lines.slice(i, i + span).join('\n').includes(q)
  const tryRange = (from, to) => {
    for (let i = Math.max(0, from); i <= Math.min(lines.length - 1, to); i++) if (matchAt(i)) return i
  }
  let hit = tryRange(comment.fromLine - 1 - 20, comment.fromLine - 1 + 20)
  if (hit === undefined) hit = tryRange(0, lines.length - 1)
  if (hit === undefined) return { fromLine: comment.fromLine, toLine: comment.toLine, found: false }
  return { fromLine: hit + 1, toLine: hit + span, found: true }
}

export function buildPlanReviewPrompt({ lang = 'zh', currentText, diff, comments }) {
  const n = diff ? diff.hunks : 0
  const m = Array.isArray(comments) ? comments.length : 0
  const en = lang === 'en'
  if (!n && !m) {
    return en
      ? { message: 'Implementation plan confirmed, please proceed.', displayText: 'Proceed' }
      : { message: '已确认实施计划，请按此推进。', displayText: '按此推进' }
  }
  const added = diff ? diff.added : 0
  const removed = diff ? diff.removed : 0
  const list = (comments || []).map((c, i) => en
    ? `${i + 1}. On "${c.quotedText}"${c.found === false ? ' (original text has since changed)' : ''}: ${c.body}`
    : `${i + 1}. 针对「${c.quotedText}」${c.found === false ? '（原文已改动）' : ''}：${c.body}`).join('\n')
  const message = en
    ? `I have revised the plan file and added comments. Execute the revised plan below directly; the comments are additional requirements for the corresponding sections and must be followed when you reach them.\nChange summary: ${n} hunk(s) (+${added} / -${removed} lines).\nComments (${m}):\n${list || '(none)'}\nFull revised plan:\n${currentText}`
    : `我已在计划文件中修订并加了批注，请以下方修订版计划为准直接执行；批注是对相应段落的补充要求，执行到该段时必须照办。\n改动摘要：共 ${n} 处（+${added} 行 / -${removed} 行）。\n批注（${m} 条）：\n${list || '（无）'}\n修订版计划全文：\n${currentText}`
  const displayText = en
    ? `Proceed with revised plan (${n} change(s), ${m} comment(s))`
    : `已按修订版推进（${n} 处改动、${m} 条批注）`
  return { message, displayText }
}
