// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 行级 diff：返回 { hunks, added, removed, changedLines, deletions }。
 * changedLines 是当前文本中新增/改动的行号（1 起）；
 * deletions 是被删段在当前文本里的插入位（beforeLine：删除发生在当前第几行之前）。
 * 计划文本通常几十行，LCS DP 足够；先剪掉公共前缀/后缀行再判阈值，
 * 剪完仍超大才把中间那段退化为整体一处改动。
 */
export function lineDiff(baseline, current) {
  const a = String(baseline == null ? '' : baseline)
  const b = String(current == null ? '' : current)
  if (a === b) return { hunks: 0, added: 0, removed: 0, changedLines: [], deletions: [] }
  const A = a.split('\n')
  const B = b.split('\n')
  let n = A.length
  let m = B.length
  // 公共前缀 p 行、公共后缀（剪到 n / m 为止）；DP 只跑中间 [p, n) × [p, m)
  let p = 0
  while (p < n && p < m && A[p] === B[p]) p++
  while (n > p && m > p && A[n - 1] === B[m - 1]) { n--; m-- }
  if ((n - p) * (m - p) > 400000) {
    const changedLines = []
    for (let k = p; k < m; k++) changedLines.push(k + 1)
    return {
      hunks: 1,
      added: Math.max(0, m - n),
      removed: Math.max(0, n - m),
      changedLines,
      deletions: []
    }
  }
  const dp = Array.from({ length: n - p + 1 }, () => new Uint16Array(m - p + 1))
  for (let i = n - 1; i >= p; i--) {
    for (let j = m - 1; j >= p; j--) {
      dp[i - p][j - p] = A[i] === B[j]
        ? dp[i - p + 1][j - p + 1] + 1
        : Math.max(dp[i - p + 1][j - p], dp[i - p][j - p + 1])
    }
  }
  let i = p
  let j = p
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
    } else if (dp[i - p + 1][j - p] >= dp[i - p][j - p + 1]) {
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
