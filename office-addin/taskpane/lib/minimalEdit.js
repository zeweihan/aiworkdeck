// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 词级最小编辑差分（纯函数模块，不依赖 Office.js，便于单测）。
 *
 * 用途：Word 原生修订（TrackAll）下把整段 Range 直接 insertText(replace)
 * 会记成「整段删除 + 整段插入」。本模块算出最小编辑段，调用方只对差异段
 * 落笔，修订面板里就只剩真正改动的那几处。
 *
 * **颗粒度是词，不是字符（dev-board#716）**。早先这里按 UTF-16 码元做 LCS，
 * 英文改写会被拆成交错的字母段：AI 把 "kicking off" 改成 "commencing"，
 * 修订面板读作 "commenc~~kick~~ing ~~off~~"——一句改写约二十段插入/删除，
 * 行内标记根本没法读（真机截图见 office-addin/appsource/screenshots/）。
 * 现在先把两串切成词（英文按字母数字连写成词、中文逐字、空白与标点各自成词），
 * 在词序列上做 LCS，再把挨得很近的改动并成一块，一句改写落成 1~3 段连续的
 * 删/插。**接受全部修订后的正文与字符级算法完全一致**，变的只是留痕形态。
 *
 * 与桌面端 LOWA（`frontend/src/zetaoffice/public/office_thread.js` 的
 * minimalEdits）的关系：LOWA 那边仍是字符级 Myers，本模块**刻意不再与它同口径**。
 * 两条链的落笔方式不同——LOWA 按字符偏移走 UNO 光标，想多细就多细；插件这边
 * 没有按偏移切 Range 的 API，每段差异都要拿 oldText 回文档里二次 search 定位，
 * 段越碎越容易撞上「定位不唯一」而整段回退。词级对两边都更好读，对插件还顺带
 * 把定位成功率提上去了。
 *
 * 返回值形态（未变）：`{start, end, oldText, newText}`，按 start 升序、互不重叠，
 * start/end 是 **oldStr 内的 UTF-16 偏移**。调用方要从右到左应用（右侧的写入会
 * 推移左侧之后的定位）。
 */

/**
 * 中段 LCS 的 DP 单元上限（500x500 个**词**）。超过就退回「整个中段一次替换」——
 * 到这个量级说明整段面目全非，本来就是重写。
 */
const LCS_CELL_LIMIT = 250000

/**
 * 合并阈值：两处改动之间未变的部分**少于 3 个词**（即 0/1/2 个词）就并成一处替换。
 * 取 3 的理由：
 *  - 0 个词（中间只隔空白）：本来就是同一处改动被 LCS 拆开了，必须并；
 *  - 1 个词：LCS 在自然语言里必然捞到的巧合匹配（中文的「的/、」、英文的
 *    the/of/and），拆开读就是噪音；
 *  - 2 个词：仍短于它两侧被改掉的文字，把它排在修订之外，读者要在两段红线之间
 *    找回两个字的连贯，代价大于收益；
 *  - 3 个词及以上：是真正被保留下来的句子成分，值得让审阅者一眼看到它没被动。
 * 中文逐字成词，所以这条规则在中文里等价于「中间隔着不到 3 个字就并」。
 * 每次合并最多吞掉 2 个未改词，被吞的原文会原样写回 newText，最终正文不变。
 */
const MERGE_GAP_WORDS = 3

/**
 * 合并后单段旧文的字符上限。Word 的查找串最长 255 个字符（officeExecutor 的
 * WORD_SEARCH_MAX_CHARS），超了就没法拿 oldText 回文档里定位，整个命中 Range
 * 只能退回整段替换——那正是要治的病。宁可少并一次，也不要把一段可定位的差异
 * 并成一段不可定位的。
 */
const MERGE_MAX_SEGMENT_CHARS = 255

const SPACE_RE = /\s/

/** 空白（含 U+00A0 不换行空格与 U+3000 全角空格，JS 的 \s 都认） */
function isSpace(ch) {
  return SPACE_RE.test(ch)
}

/**
 * 连写成词的字符：ASCII 字母数字 + 拉丁扩展 + 希腊 + 西里尔。
 * 汉字/假名、标点、符号、全角字符都不在内——它们各自单独成词，对中文而言
 * 「逐字成词」就是本模块要的颗粒度。没列进来的冷门文字退化成逐字，与改造前
 * 的行为一致，不会更差。
 */
function isWordChar(cp) {
  return (cp >= 0x30 && cp <= 0x39)
    || (cp >= 0x41 && cp <= 0x5a)
    || (cp >= 0x61 && cp <= 0x7a)
    || (cp >= 0xc0 && cp <= 0x24f)
    || (cp >= 0x386 && cp <= 0x3ff)
    || (cp >= 0x400 && cp <= 0x4ff)
}

/**
 * 切词。返回 `{text, start}` 两个等长数组：text[i] 是第 i 个词的文字，
 * start[i] 是它在原串里的 UTF-16 偏移。词之间首尾相接、不漏字：
 * 连续空白合成一个词，英文字母数字连写成一个词，其余（汉字、标点、
 * emoji 等）逐个码点成词——以码点为单位所以永远不会劈开代理对。
 */
function tokenize(s) {
  const text = []
  const start = []
  const len = s.length
  let i = 0
  while (i < len) {
    const cp = s.codePointAt(i)
    let j = i + (cp > 0xffff ? 2 : 1)
    if (isSpace(s.charAt(i))) {
      while (j < len && isSpace(s.charAt(j))) j++
    } else if (isWordChar(cp)) {
      while (j < len) {
        const next = s.codePointAt(j)
        if (!isWordChar(next)) break
        j += next > 0xffff ? 2 : 1
      }
    }
    text.push(s.slice(i, j))
    start.push(i)
    i = j
  }
  return { text, start }
}

/** 第 idx 个词的起始字符偏移；idx 越界（末尾之后）即串长 */
function charAt(tok, str, idx) {
  return idx < tok.start.length ? tok.start[idx] : str.length
}

/**
 * 计算把 oldStr 变成 newStr 的最小编辑段（词级）。
 *
 * @param {string} oldStr 原文
 * @param {string} newStr 新文
 * @returns {Array<{start:number, end:number, oldText:string, newText:string}>}
 *   start/end 为 oldStr 内的字符偏移（左闭右开）；oldStr[start,end) 这一段被
 *   newText 取代。纯插入时 start === end 且 oldText 为空；纯删除时 newText
 *   为空。结果按 start 升序、互不重叠；两串相同时返回空数组。
 *   逐段替换后得到的文本恒等于 newStr。
 */
export function minimalEdits(oldStr, newStr) {
  const o = oldStr == null ? '' : String(oldStr)
  const n = newStr == null ? '' : String(newStr)
  if (o === n) return []

  const oTok = tokenize(o)
  const nTok = tokenize(n)
  const oCount = oTok.text.length
  const nCount = nTok.text.length

  // 1) 裁掉公共前缀词与公共后缀词（两者不许重叠）
  const maxP = Math.min(oCount, nCount)
  let p = 0
  while (p < maxP && oTok.text[p] === nTok.text[p]) p++
  let sfx = 0
  while (sfx < maxP - p && oTok.text[oCount - 1 - sfx] === nTok.text[nCount - 1 - sfx]) sfx++

  const m = oCount - sfx - p
  const q = nCount - sfx - p
  if (!m && !q) return []

  // runs：{tokStart, tokDel, insText}，按 tokStart 降序（回溯天然产出的顺序）
  const runs = (!m || !q || m * q > LCS_CELL_LIMIT)
    // 纯插入 / 纯删除 / 中段过大：一条连续替换已经是最小可用形态
    ? [{ tokStart: p, tokDel: m, insText: n.slice(charAt(nTok, n, p), charAt(nTok, n, p + q)) }]
    : lcsRuns(oTok, nTok, p, m, q)

  mergeNearbyRuns(runs, oTok, o)

  const edits = []
  for (let k = runs.length - 1; k >= 0; k--) {
    const run = runs[k]
    const start = charAt(oTok, o, run.tokStart)
    const end = charAt(oTok, o, run.tokStart + run.tokDel)
    edits.push({ start, end, oldText: o.slice(start, end), newText: run.insText })
  }
  return edits
}

/** 中段跑词级 LCS DP 再回溯，把相邻的删+插合并成一条替换。返回按 tokStart 降序的 runs。 */
function lcsRuns(oTok, nTok, p, m, q) {
  const W = q + 1
  const dp = new Uint16Array((m + 1) * W)
  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= q; j++) {
      dp[i * W + j] = oTok.text[p + i - 1] === nTok.text[p + j - 1]
        ? dp[(i - 1) * W + (j - 1)] + 1
        : Math.max(dp[(i - 1) * W + j], dp[i * W + (j - 1)])
    }
  }

  const runs = []
  let i = m
  let j = q
  let curDel = 0
  let curIns = ''
  const flush = (atOld) => {
    if (curDel || curIns) {
      runs.push({ tokStart: p + atOld, tokDel: curDel, insText: curIns })
      curDel = 0
      curIns = ''
    }
  }
  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oTok.text[p + i - 1] === nTok.text[p + j - 1]) {
      flush(i)
      i--
      j--
    } else if (j > 0 && (i === 0 || dp[i * W + (j - 1)] >= dp[(i - 1) * W + j])) {
      curIns = nTok.text[p + j - 1] + curIns
      j--
    } else {
      curDel++
      i--
    }
  }
  flush(0)
  return runs
}

/**
 * 把「中间只隔着不到 MERGE_GAP_WORDS 个未改词」的相邻 runs 并成一条替换。
 * runs 按 tokStart 降序，所以 [k] 是右邻、[k+1] 是左邻；被跨过的原文原样接进
 * 左邻的 insText，最终正文不变。
 */
function mergeNearbyRuns(runs, oTok, o) {
  for (let k = 0; k + 1 < runs.length;) {
    const right = runs[k]
    const left = runs[k + 1]
    const gapFrom = left.tokStart + left.tokDel
    const gapTo = right.tokStart
    let words = 0
    for (let t = gapFrom; t < gapTo; t++) {
      if (!isSpace(oTok.text[t].charAt(0))) words++
    }
    const mergedStart = charAt(oTok, o, left.tokStart)
    const mergedEnd = charAt(oTok, o, right.tokStart + right.tokDel)
    if (words < MERGE_GAP_WORDS && mergedEnd - mergedStart <= MERGE_MAX_SEGMENT_CHARS) {
      left.insText = left.insText + o.slice(charAt(oTok, o, gapFrom), charAt(oTok, o, gapTo)) + right.insText
      left.tokDel = right.tokStart + right.tokDel - left.tokStart
      runs.splice(k, 1)
    } else k++
  }
}

/**
 * 与 minimalEdits 同一份差分，但**每一段都至少覆盖 1 个原文字符**（dev-board#717）。
 *
 * 给「切不出零长度区间」的宿主用：跨文档写入的撤销要把 PPT 文本框改回原文，整框回写
 * 会抹掉框内的分段格式与超链接，所以按差异段落笔；而 PowerPoint 的 getSubstring(start, 0)
 * 与 WPS 的 Characters(start, 0) 行为都未经验证。纯插入因此借一个相邻字符（优先右邻，
 * 插在末尾时借左邻，代理对整体借）变成「替换 1 个字」，逐段替换后的结果不变。
 *
 * 原串为空时没有字符可借，原样返回那一条零长度插入，由调用方整体赋值。
 */
export function substringEdits(oldStr, newStr) {
  const o = oldStr == null ? '' : String(oldStr)
  const edits = minimalEdits(o, newStr)
  if (!o.length) return edits
  const widened = edits.map((e) => {
    if (e.end > e.start) return e
    if (e.start < o.length) {
      const ch = String.fromCodePoint(o.codePointAt(e.start))
      return { start: e.start, end: e.start + ch.length, oldText: ch, newText: e.newText + ch }
    }
    let from = e.start - 1
    const low = o.charCodeAt(from)
    if (low >= 0xdc00 && low <= 0xdfff && from > 0) from--
    const ch = o.slice(from, e.start)
    return { start: from, end: e.start, oldText: ch, newText: ch + e.newText }
  })
  // 借来的字符与邻段重叠时（理论上不会：LCS 的相邻段之间至少隔一个公共词，这里只是兜底）
  // 退成一段覆盖全部差异的替换——段外的前后缀在新旧文里本来就相同
  for (let k = 1; k < widened.length; k++) {
    if (widened[k].start < widened[k - 1].end) {
      const n = newStr == null ? '' : String(newStr)
      const start = widened[0].start
      const end = widened[widened.length - 1].end
      return [{ start, end, oldText: o.slice(start, end), newText: n.slice(start, n.length - (o.length - end)) }]
    }
  }
  return widened
}
