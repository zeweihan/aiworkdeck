/**
 * 锚点文本定位的归一化层（六个宿主面共用：Office/WPS × 文字/表格/演示）。
 *
 * 为什么需要它（dev-board#286）：模型给出的 anchorText 与文档里的原文之间存在一整类
 * **系统性差异**，它们在屏幕上长得一模一样，逐字比较却必然失配——
 *   - 全角/半角：（甲方） vs (甲方)、Ａ vs A、％ vs %
 *   - 弯直引号与撇号：“合同” vs "合同"、don’t vs don't
 *   - 各种空白：不间断空格 U+00A0、窄空格 U+202F、表意空格 U+3000、制表符、
 *     Word 的软回车 U+000B、段落符 \r，以及连续空格
 *   - 零宽字符与软连字符：U+200B-200D、U+FEFF、U+00AD（复制粘贴、PDF 转出的文档里常见）
 *   - 各式连字符：‐ ‑ ‒ – — ―
 *   - WPS 文字正文里的单元格/行结束符 \x07（它进了我们上送给模型的正文，模型会照抄）
 * 于是用户看到的就是那句「未找到锚点文本，请确认 anchorText 与文档内容精确一致」——
 * 而律师核对一遍，两边确实"一模一样"。
 *
 * 设计要点：
 * 1. **逐字符归一 + 显式偏移映射**。不能直接 `String.normalize('NFKC')` 了事：NFKC 会
 *    改变字符串长度（连字、兼容分解），一旦长度变了就再也换算不回文档里的位置，
 *    而所有写入命令都要拿原文坐标去落笔。这里为每个输出字符记下它来自哪个源下标，
 *    定位结果一律换算回**原文坐标**。
 * 2. **归一只用于"找"，不用于"改"**。找到之后落笔仍然用原文区间；返回值里带上命中处的
 *    原文，调用方可以（也应该）逐笔校验。归一化让匹配变宽松，逐笔校验保证不会改错地方
 *    ——两者缺一不可（WPS 面 dev-board#264 已经用这个思路把"兜底＝猜"变成了"兜底＝可验证"）。
 * 3. 零依赖、纯 ES2018 语法：WPS 任务窗格跑在版本参差的 CEF 内核里，构建目标压在 es2018。
 */

// 弯引号/撇号 → 直引号。刻意**不**动 「」『』〈〉《》——那是中文里语义明确的成对符号，
// 归并它们只会制造假命中，而不会修好任何真实的失配。
const QUOTE_MAP = {
  '‘': "'", '’': "'", '‚': "'", '‛': "'", '′': "'",
  '“': '"', '”': '"', '„': '"', '‟': '"', '″': '"'
}

// 各式连字符/破折号 → ASCII 连字符。模型很爱把 - 写成 —，反过来也常见。
const DASH_MAP = {
  '‐': '-', '‑': '-', '‒': '-', '–': '-', '—': '-',
  '―': '-', '−': '-'
}

// 归一化时整体丢弃的字符：零宽、字节序标记、软连字符、连接符。
// 它们不可见，却让逐字比较必然失败——PDF 转出来的文书里成片都是。
const DROPPED = new Set([
  '​', '‌', '‍', '⁠', '﻿', '­'
])

/**
 * 视为空白的字符。除了常规空白，还包括：
 * -   不间断空格（Word/WPS 里"不要在这里断行"的产物，肉眼就是个空格）
 * - 　 表意空格（中文排版的全角空格）
 * -  软回车（Word 的 Shift+Enter）、\r 段落符、\f 分页
 * - \x07 WPS 文字正文里的单元格/行结束符——它随 doc.Range().Text 一起进了我们上送给
 *   模型的正文，模型照抄进 anchorText 就再也匹配不上任何东西
 */
function isSpaceLike(ch) {
  if (ch === ' ' || ch === '\t' || ch === '\n' || ch === '\r' || ch === '\f' || ch === '\v') return true
  if (ch === ' ' || ch === '　' || ch === ' ' || ch === ' ') return true
  if (ch === '\x07') return true
  const code = ch.charCodeAt(0)
  // U+2000-U+200A 各种定宽空格
  return code >= 0x2000 && code <= 0x200A
}

/**
 * 单个字符的归一化形式（可能是空串＝丢弃，也可能是多个字符＝NFKC 展开）。
 * 全角→半角走 NFKC（U+FF01-FF5E 这一段正是它的职责），中文标点 。，、；：！？ 不受影响。
 */
function normalizeChar(ch, foldCase) {
  if (DROPPED.has(ch)) return ''
  const mapped = QUOTE_MAP[ch] || DASH_MAP[ch]
  if (mapped) return mapped
  let out = ch
  try {
    // NFKC 只对确有兼容分解的字符生效；对绝大多数汉字是恒等变换
    out = ch.normalize('NFKC')
  } catch (e) { /* 老内核没有 normalize，退回原字符 */ }
  if (foldCase) out = out.toLowerCase()
  return out
}

/**
 * 归一化一段文本，并给出每个输出字符对应的原文区间。
 *
 * @returns {{text:string, starts:number[], ends:number[]}}
 *   text     归一化后的文本
 *   starts[i] 第 i 个输出字符在原文里的起始下标
 *   ends[i]   第 i 个输出字符在原文里的结束下标（不含）——连续空白折叠成一个空格时，
 *             这个区间会覆盖整段空白，换算回去才不会把半截空格留在外面
 */
export function normalizeForMatch(input, options) {
  const opts = options || {}
  const foldCase = opts.foldCase !== false
  const collapseSpace = opts.collapseSpace !== false
  const src = String(input == null ? '' : input)
  let text = ''
  const starts = []
  const ends = []
  let i = 0
  while (i < src.length) {
    const ch = src[i]
    if (isSpaceLike(ch)) {
      const runStart = i
      while (i < src.length && isSpaceLike(src[i])) i++
      if (!collapseSpace) {
        // 不折叠时逐个空白各出一个空格，映射保持一一对应
        for (let k = runStart; k < i; k++) { text += ' '; starts.push(k); ends.push(k + 1) }
      } else {
        // 折叠：整段空白只出一个空格。**首尾的空白不出字符**——锚点两端的空格有无
        // 不该影响命中，否则模型多打一个空格就整条失配
        if (text.length && !isTrailingRun(src, i)) {
          text += ' '
          starts.push(runStart)
          ends.push(i)
        } else if (text.length) {
          // 尾部空白：不产出字符，但要让上一个字符的区间把它吃掉，
          // 免得命中区间的右边界卡在空白中间
          ends[ends.length - 1] = i
        }
      }
      continue
    }
    const normalized = normalizeChar(ch, foldCase)
    for (let k = 0; k < normalized.length; k++) {
      text += normalized[k]
      starts.push(i)
      ends.push(i + 1)
    }
    i++
  }
  return { text, starts, ends }
}

/** src 从 pos 起是否已经只剩空白（用于判断"这段空白是不是尾部空白"） */
function isTrailingRun(src, pos) {
  for (let k = pos; k < src.length; k++) {
    if (!isSpaceLike(src[k])) return false
  }
  return true
}

/**
 * 在 haystack 里找 needle 的全部命中，**返回原文坐标**。
 * 两侧都先归一化，所以全角/半角、弯直引号、NBSP、零宽字符、连续空白的差异都不再致命。
 *
 * @returns {Array<{start:number, end:number, text:string}>} text 是命中处的**原文**（未归一）
 */
export function findAllNormalized(haystack, needle, options) {
  const hay = normalizeForMatch(haystack, options)
  const pin = normalizeForMatch(needle, options)
  const out = []
  if (!pin.text) return out
  let from = 0
  while (true) {
    const at = hay.text.indexOf(pin.text, from)
    if (at === -1) break
    const start = hay.starts[at]
    const end = hay.ends[at + pin.text.length - 1]
    out.push({ start, end, text: String(haystack).slice(start, end) })
    // 允许重叠命中会让"替换全部"陷入自噬，逐个命中按整段推进
    from = at + pin.text.length
  }
  return out
}

/** 归一化后是否逐字相同——写入前的逐笔校验用它，而不是原始字符串比较 */
export function equalsNormalized(a, b, options) {
  return normalizeForMatch(a, options).text === normalizeForMatch(b, options).text
}

/**
 * 找不到时的"最接近的一段"，用来把**证据**回给模型，而不是只说一句
 * 「请确认 anchorText 与文档内容精确一致」——那句话对模型毫无信息量，
 * 它只能把锚点越猜越短，越短越容易命中多处，最后越改越乱（dev-board#286 实况）。
 *
 * 算法：以 needle 的长度为窗口在归一化后的 haystack 上滑动，取字符重合度最高的一段。
 * 这不是要用来定位（定位必须逐字相同），只是要给模型一段可对照的原文。
 * 为控制开销，窗口按步长跳（长文档下步长自适应），并对超长 needle 只取前若干字符做探针。
 *
 * @returns {{text:string, similarity:number}|null} text 是**原文**片段
 */
export function closestFragment(haystack, needle, options) {
  const hay = normalizeForMatch(haystack, options)
  const pin = normalizeForMatch(needle, options)
  if (!pin.text || !hay.text) return null
  const probe = pin.text.length > 120 ? pin.text.slice(0, 120) : pin.text
  const win = probe.length
  if (hay.text.length < win) return null
  const wanted = charCount(probe)
  // 步长：短文档逐字滑动，长文档按窗口的 1/8 跳（够用来定位"大概在哪一段"）
  const step = hay.text.length > 200000 ? Math.max(1, Math.floor(win / 8)) : 1
  let bestAt = -1
  let bestScore = 0
  for (let at = 0; at + win <= hay.text.length; at += step) {
    const score = overlapScore(hay.text, at, win, wanted)
    if (score > bestScore) { bestScore = score; bestAt = at }
  }
  if (bestAt < 0 || bestScore <= 0) return null
  const start = hay.starts[bestAt]
  const end = hay.ends[bestAt + win - 1]
  return { text: String(haystack).slice(start, end), similarity: Math.round(bestScore * 100) / 100 }
}

function charCount(s) {
  const m = new Map()
  for (const ch of s) m.set(ch, (m.get(ch) || 0) + 1)
  return m
}

/** 窗口与目标的字符重合度（0-1）。字符袋比较，不追求精确编辑距离——这里只要"哪一段最像" */
function overlapScore(text, at, win, wanted) {
  const seen = new Map()
  for (let k = at; k < at + win; k++) {
    const ch = text[k]
    seen.set(ch, (seen.get(ch) || 0) + 1)
  }
  let hit = 0
  let total = 0
  wanted.forEach((n, ch) => {
    total += n
    hit += Math.min(n, seen.get(ch) || 0)
  })
  return total ? hit / total : 0
}

/**
 * 拼一句**带证据**的定位失败说明，交给模型自纠。
 * 三件事缺一不可：说清没找到什么、给出文档里最接近的原文、告诉它下一步怎么做。
 */
export function describeAnchorFailure(kind, needle, haystack, options) {
  const shown = String(needle == null ? '' : needle)
  const head = `${kind}：在文档中未找到该文本。已按全角/半角、直弯引号、不间断空格与零宽字符做过归一化后仍无命中。`
  const near = closestFragment(haystack, needle, options)
  if (!near) {
    return head + '文档中没有与之相近的片段，请先用读取类工具确认文档当前内容，不要凭上一轮的印象拼锚点。'
  }
  const frag = near.text.length > 160 ? near.text.slice(0, 160) + '…' : near.text
  return head
    + `文档里最接近的一段原文是：「${frag}」（相似度约 ${Math.round(near.similarity * 100)}%）。`
    + '请改用这段原文里逐字照抄的一小段（建议 10-30 字、且在全文唯一）作为锚点重试；'
    + '若差异来自你自己改写过的措辞，请先重新读取该处的当前内容。'
}
