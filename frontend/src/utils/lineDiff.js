// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 行级 diff：返回 { hunks, added, removed, changedLines, deletions }。
 * changedLines 是当前文本中新增/改动的行号（1 起）；
 * deletions 是被删段在当前文本里的插入位（beforeLine：删除发生在当前第几行之前）。
 * 计划文本通常几十行，LCS DP 足够；超大文本退化为整体一处改动。
 */
export function lineDiff(baseline, current) {
  const A = String(baseline == null ? '' : baseline).split('\n')
  const B = String(current == null ? '' : current).split('\n')
  const n = A.length
  const m = B.length
  if (n * m > 400000) {
    return {
      hunks: 1,
      added: Math.max(0, m - n),
      removed: Math.max(0, n - m),
      changedLines: B.map((_, k) => k + 1),
      deletions: []
    }
  }
  const dp = Array.from({ length: n + 1 }, () => new Uint16Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  let i = 0
  let j = 0
  let added = 0
  let removed = 0
  let hunks = 0
  let inHunk = false
  const changedLines = []
  const deletions = []
  let lastWasDelete = false
  const markHunk = () => {
    if (!inHunk) {
      hunks++
      inHunk = true
    }
  }
  const del = () => {
    markHunk()
    removed++
    if (lastWasDelete) {
      const d = deletions[deletions.length - 1]
      d.count++
      d.text += '\n' + A[i]
    } else {
      deletions.push({ beforeLine: j + 1, count: 1, text: A[i] })
    }
    lastWasDelete = true
    i++
  }
  const add = () => {
    markHunk()
    added++
    changedLines.push(j + 1)
    lastWasDelete = false
    j++
  }
  while (i < n && j < m) {
    if (A[i] === B[j]) {
      i++
      j++
      inHunk = false
      lastWasDelete = false
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      del()
    } else {
      add()
    }
  }
  while (i < n) del()
  while (j < m) add()
  return { hunks, added, removed, changedLines, deletions }
}

export function lineDiffStats(a, b) {
  const { hunks, added, removed } = lineDiff(a, b)
  return { hunks, added, removed }
}
