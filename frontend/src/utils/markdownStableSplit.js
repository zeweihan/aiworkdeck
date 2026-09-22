// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * 流式正文的「已经定稿的前缀」在哪儿断开（dev-board#811 K31，审查 C-10）。
 *
 * MarkdownPreview 原来每帧把整篇重新解析、整段重写 v-html。两个后果：一是长回答后期
 * 单帧成本随正文长度线性上涨；二是**每次重写都会把用户在正文里选中的文字清掉**，
 * 生成过程中根本没法复制。
 *
 * 办法是把正文切成「稳定前缀」和「还在长的尾巴」两段各自 v-html：前缀那一段的字符串
 * 不变，Vue 就不碰它的 DOM，选区留得住，也不用重新解析。
 *
 * **切点必须保证「前缀单独渲染」与「整篇渲染的前半段」结果一致**，否则用户会在流式期间
 * 看到错的排版。所以只在下面这种地方切：
 *   - 空行之后（markdown 的块级结构天然按空行分段）；
 *   - 不在 ``` / ~~~ 围栏里面（围栏里的空行是代码的一部分，切进去会把代码块劈成两半）；
 *   - 且空行之后那一行明确是一个新的顶层块——不缩进、不是 `>`、不是列表项。
 *     列表与引用可以跨空行续上一块（松散列表），在那里切会把一张清单变成两张。
 *
 * 剩下的边角（尾巴里才出现的链接引用定义 `[x]: url` 被前缀引用）无法靠切点规避，
 * 由 MarkdownPreview 的定稿校正兜底：正文不再变化后整篇重渲一次，与分段结果不同才写回。
 */

// 太短的正文不值得分段：整篇解析本来就是零点几毫秒，分段只会多一层心智负担。
export const INCREMENTAL_MIN_CHARS = 4000
// 前缀每次至少前进这么多才真的推进。不设下限的话前缀会几乎每帧都增长一点点，
// 而每次增长都要重新解析新增的那一段——分段的意义就没了。
export const STABLE_STEP = 2000

const FENCE = /^(?:```|~~~)/

/** 空行之后的这一行，是不是一个「肯定不会跟上一块连起来」的新顶层块。 */
function startsIndependentBlock(line) {
  if (/^[ \t]/.test(line)) return false                 // 缩进：上一块的延续，或缩进代码块
  if (line.startsWith('>')) return false                // 引用可以跨空行续
  if (/^(?:[-*+]|\d{1,9}[.)])(?:[ \t]|$)/.test(line)) return false  // 列表项可以跨空行续（松散列表）
  return true
}

/**
 * 在 `tail` 里找最后一个安全切点，返回相对 `tail` 的下标（0 = 没有可切的地方）。
 * 只看已经以换行结尾的完整行——流式的最后一行还没写完，据它判断等于猜。
 */
export function safeCutIndex(tail) {
  let insideFence = false
  let cut = 0
  let lineStart = 0
  let blankBefore = false
  for (let i = tail.indexOf('\n'); i >= 0; i = tail.indexOf('\n', lineStart)) {
    const line = tail.slice(lineStart, i)
    const trimmed = line.trim()
    if (FENCE.test(trimmed)) {
      insideFence = !insideFence
      blankBefore = false
    } else if (!insideFence) {
      if (trimmed === '') blankBefore = true
      else {
        if (blankBefore && lineStart > 0 && startsIndependentBlock(line)) cut = lineStart
        blankBefore = false
      }
    }
    lineStart = i + 1
  }
  return cut
}

/**
 * 给定整段正文与当前已定稿的长度，算出新的定稿长度。
 * 只会前进，不会后退；不够 STABLE_STEP 就原地不动。
 */
export function nextStableLength(text, stableLength) {
  if (text.length < INCREMENTAL_MIN_CHARS) return stableLength
  const cut = safeCutIndex(text.slice(stableLength))
  if (cut < STABLE_STEP) return stableLength
  return stableLength + cut
}
