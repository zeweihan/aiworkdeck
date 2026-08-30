/**
 * office_command 执行器（Phase C 工具桥）：
 * 后端 OfficeBridgeService 经 SSE client_action 下发 {tool:'office_command',
 * requestId, command, args, conversationId}，本模块按 command 分发到 Office.js
 * 实现并返回 {ok, data|error}，由调用方 POST /api/agent/office/result 回传。
 *
 * 硬规则：后端注册的每个 office_* 工具都必须在这里有对应实现——
 * 没有客户端实现的远端工具 = 30 秒超时空转（PptxEditTools 死路径教训）。
 * 未知 command 立即回 {ok:false, error:'unsupported command'}，绝不静默吞掉。
 *
 * 宿主细分：Word 面命令要求 Word 宿主、excel_* 命令要求 Excel 宿主、
 * ppt_* 命令要求 PowerPoint 宿主——宿主不符或版本不支持时立即回
 * {ok:false, error:'unsupported ...'}（正常情况下后端已按 officeHost 过滤工具，
 * 这里是最后一道防线）。
 *
 * Word 修改类命令（replace_text / insert_text）执行前把 document.changeTrackingMode
 * 设为 TrackAll（Word 原生修订），执行后恢复原值；宿主不支持 WordApi 1.4 时
 * 降级为直接修改并在结果里标注 tracked:false。Excel/PowerPoint 没有修订机制，
 * 写入直接生效。
 *
 * replace_text 走字符级最小修订（见下方 applyMinimalRedline）：只对新旧文的
 * 差异段落笔，避免修订面板里出现「整段删 + 整段插」。
 */

import { officeAvailable, detectHost } from './wordDoc.js'
import { minimalEdits } from './minimalEdit.js'
import { findAllNormalized, describeAnchorFailure } from './textMatch.js'
import { t } from './i18n.js'
// 律所标准格式（HOUSE）单源：backend/src/main/resources/style-profiles/house-default.json 的字节副本，
// 由 frontend/scripts/sync-house-profile.mjs 同步（npm run build 前自动跑），构建时内联进产物；
// houseProfile.test.js 断言与后端源 sha256 一致。
import houseProfile from './house-default.json' with { type: 'json' }

// 与后端 ContextAssemblerService.MAX_INLINE_CONTENT_CHARS 一致的截断上限
const MAX_TEXT_CHARS = 200_000
// search 命中上下文的最大条数（防超长工具输出撑爆模型上下文）
const MAX_SEARCH_HITS = 20
// Word 的查找串上限（超了直接判非法）
const WORD_SEARCH_MAX_CHARS = 255

function trackingSupported() {
  try {
    return Office.context.requirements.isSetSupported('WordApi', '1.4')
  } catch (e) {
    return false
  }
}

/** paragraph.styleBuiltIn（标题级别）属 WordApi 1.3，比其余格式属性门槛高一档 */
function builtInStyleSupported() {
  try {
    return Office.context.requirements.isSetSupported('WordApi', '1.3')
  } catch (e) {
    return false
  }
}

/**
 * WordApi 1.3 门槛：自动编号（Word.List / startNewList / attachToList / detachFromList /
 * paragraph.isListItem）与表格格式（body.tables / table.getBorder / alignment / autoFitWindow）
 * 都在这一档。与 builtInStyleSupported 是同一个版本号、不同的能力面，分开命名以免误读。
 */
function wordApi13Supported() {
  try {
    return Office.context.requirements.isSetSupported('WordApi', '1.3')
  } catch (e) {
    return false
  }
}

/**
 * font.nameAscii / nameFarEast（中西文分开设字体）属 WordApiDesktop 1.3，只有较新的
 * 桌面版 Word 才有。不支持时只能用单一的 font.name（标准格式退化为中文字体统管全篇）。
 */
function farEastFontSupported() {
  try {
    return Office.context.requirements.isSetSupported('WordApiDesktop', '1.3')
  } catch (e) {
    return false
  }
}

/**
 * TableColumnCollection.add()（表格中间位置插列）属 WordApiDesktop 1.3——同一版本号、
 * 同一门槛为 farEastFontSupported，但语义不同（表格结构 vs 字体），分开命名以免误读。
 */
function wordApiDesktop13Supported() {
  try {
    return Office.context.requirements.isSetSupported('WordApiDesktop', '1.3')
  } catch (e) {
    return false
  }
}

/** Word.TrackedChange 集合（accept/reject/acceptAll/rejectAll）与脚注/尾注的公共门槛（批次 9） */
function trackedChangesSupported() {
  try {
    return Office.context.requirements.isSetSupported('WordApi', '1.6')
  } catch (e) {
    return false
  }
}

/** Range.insertFootnote/insertEndnote 属 WordApi 1.5（批次 9） */
function footnoteApiSupported() {
  try {
    return Office.context.requirements.isSetSupported('WordApi', '1.5')
  } catch (e) {
    return false
  }
}

function truncate(text) {
  const s = text || ''
  return s.length > MAX_TEXT_CHARS
    ? { text: s.slice(0, MAX_TEXT_CHARS), truncated: true, totalChars: s.length }
    : { text: s, truncated: false, totalChars: s.length }
}

/**
 * 在开启 Word 原生修订（TrackAll）的前提下执行 fn，结束后恢复原模式。
 * 返回 fn 的结果并附 tracked 标记。
 */
async function withTracking(context, fn) {
  if (!trackingSupported()) {
    const data = await fn()
    return { ...data, tracked: false }
  }
  const doc = context.document
  doc.load('changeTrackingMode')
  await context.sync()
  const previousMode = doc.changeTrackingMode
  doc.changeTrackingMode = Word.ChangeTrackingMode.trackAll
  await context.sync()
  try {
    const data = await fn()
    return { ...data, tracked: true }
  } finally {
    doc.changeTrackingMode = previousMode
    await context.sync()
  }
}

/** body.search 一次，返回命中 Range 数组（未命中返回空数组）。不做任何降级。 */
async function searchExact(context, needle, matchCase) {
  const results = context.document.body.search(needle, { matchCase: !!matchCase })
  results.load('items')
  await context.sync()
  return results.items
}

/**
 * 定位锚点，返回命中 Range 数组（未命中返回空数组）。
 *
 * 两级（dev-board#286）：
 *  1. 宿主原生 `body.search` 精确找；
 *  2. 找不到时**归一化重定位**——把正文与锚点都按 textMatch.js 的规则归一
 *     （全角半角、弯直引号、NBSP/零宽字符、连续空白、各式连字符、大小写），
 *     在归一化文本上命中后，取命中处的**文档原文**再问一次宿主的 search。
 *
 * 为什么第二步还要绕回宿主 search：Office.js 没有「按字符下标取 Range」的 API，
 * 而我们**绝不自己造坐标**——归一化只负责把模型给的串换成「文档里真实存在的串」，
 * 取 Range 仍旧由宿主完成。这样归一化最坏只是找不到，不会把「找不到」变成「改错地方」。
 * （WPS 面 dev-board#264 用的是同一条纪律：兜底必须可验证。）
 */
async function searchRanges(context, needle, matchCase) {
  const direct = await searchExact(context, needle, matchCase)
  if (direct.length) return direct
  const relocated = await relocateByNormalization(context, needle)
  return relocated ? relocated.items : []
}

/**
 * 归一化重定位。返回 { items, matchedText } 或 null。
 * 命中处的原文若跨段（含 \r/\n，Word 的 search 不跨段），退而取其中最长的一段
 * ——仍旧是文档里逐字存在的串，宿主照样能定位。
 */
async function relocateByNormalization(context, needle) {
  const raw = String(needle || '')
  if (!raw.trim()) return null
  let bodyText = ''
  try {
    const body = context.document.body
    body.load('text')
    await context.sync()
    bodyText = body.text || ''
  } catch (e) {
    return null
  }
  if (!bodyText) return null
  const hits = findAllNormalized(bodyText, raw)
  for (const hit of hits) {
    for (const candidate of searchableVariants(hit.text)) {
      const items = await searchExact(context, candidate, true)
      if (items.length) return { items, matchedText: hit.text }
    }
  }
  return null
}

/**
 * 把一段文档原文拆成「宿主 search 吃得下」的候选串：整串优先，
 * 跨段时退到最长的单段。`searchable()` 已经挡掉超长、含 ^、码元不完整三种情况。
 */
function searchableVariants(text) {
  const out = []
  const whole = String(text || '')
  if (searchable(whole)) out.push(whole)
  if (/[\r\n]/.test(whole)) {
    const longest = whole.split(/\r\n|\n|\r/)
      .map((x) => x.trim())
      .filter((x) => x.length >= ANCHOR_FALLBACK_MIN_CHARS)
      .reduce((a, b) => (b.length > a.length ? b : a), '')
    if (longest && searchable(longest)) out.push(longest)
  }
  return out
}

/**
 * 定位失败时的报错：**带证据**。
 * 只回一句「请确认 anchorText 与文档内容精确一致」对模型毫无信息量——它只会把锚点
 * 越猜越短，越短越容易命中多处，最后越改越乱（dev-board#286 用户实况）。
 * 这里把文档里最接近的一段原文摆出来，让模型能一次改对。
 */
async function anchorNotFound(context, kind, needle) {
  let bodyText = ''
  try {
    const body = context.document.body
    body.load('text')
    await context.sync()
    bodyText = body.text || ''
  } catch (e) { /* 读不到正文就退回不带证据的说明 */ }
  if (!bodyText) {
    return new Error(`${kind}：在文档中未找到该文本，请先用读取类工具确认文档当前内容。`)
  }
  return new Error(describeAnchorFailure(kind, needle, bodyText))
}

/** 降级选段时要求的最短长度：太短容易在全文里误命中别处（dev-board#149） */
const ANCHOR_FALLBACK_MIN_CHARS = 4

/**
 * 锚点跨段（含 \n/\r）时 body.search 匹配不到——search 不跨段落，模型从内联正文
 * 摘的 anchorText/target 若跨段会首次必然报「未找到」，此前只能靠模型换短锚点
 * 重试（dev-board#149）。这里把该重试动作自动化：按 \r\n/\n/\r 拆段，挑一段再
 * search 一次。
 *   position 为 null（replace_text，锚点本身就是要被替换/定位的目标，无方向语义）
 *     → 取最长的一段，最大化命中概率；
 *   position 为 'after'/'before'（insert_text，锚点表达的是「插在这段文字之后/
 *   之前」，有方向语义）→ 取靠插入方向的那一段（after 取最后一段、before 取第
 *   一段），保住「插在哪一侧」的原始意图。
 * 拆出的段 trim 后需 ≥4 字符才可用；没有可用段时返回 null。
 */
export function pickAnchorFallback(anchor, position) {
  const segments = String(anchor || '')
    .split(/\r\n|\n|\r/)
    .map((s) => s.trim())
    .filter((s) => s.length >= ANCHOR_FALLBACK_MIN_CHARS)
  if (!segments.length) return null
  if (position === 'after') return segments[segments.length - 1]
  if (position === 'before') return segments[0]
  return segments.reduce((longest, s) => (s.length > longest.length ? s : longest))
}

/** search 串超过 Word 的 255 字符上限会直接抛异常，降级段落也要守住这条线 */
export function boundForSearch(s) {
  return s.length > WORD_SEARCH_MAX_CHARS ? s.slice(0, WORD_SEARCH_MAX_CHARS) : s
}

/**
 * 客户端直连的「定位到原文」（dev-board#150 引用定位）：AI 回答里引用的原文片段，
 * 点击即在文档里选中并滚动到位。不走 office_command 协议（无需模型往返）。
 * 仅 Word 宿主；未命中返回 {found:false}，调用方给轻提示即可。
 */
export async function locateInDocument(text) {
  const needle = String(text || '').trim()
  if (!needle || typeof Word === 'undefined') return { found: false }
  try {
    return await Word.run(async (context) => {
      let items = await searchRanges(context, boundForSearch(needle), false)
      if (!items.length && /[\r\n]/.test(needle)) {
        const fallback = pickAnchorFallback(needle, null)
        if (fallback) items = await searchRanges(context, boundForSearch(fallback), false)
      }
      if (!items.length) return { found: false }
      items[0].select()
      await context.sync()
      return { found: true, count: items.length }
    })
  } catch (e) {
    return { found: false, error: (e && e.message) || String(e) }
  }
}

/* ==================== Word 最小修订（minimal redline） ====================
 * TrackAll 下对整个命中 Range 直接 insertText(replace) 会记成「整段删除 +
 * 整段插入」——「我爱你」改「我恨你」在修订面板里读作删「我爱你」加
 * 「我恨你」。这里先用 minimalEdits 算出字符级差异段，再只对差异段落笔，
 * 修订面板里就只剩删「爱」加「恨」（与桌面端 LOWA 的 applyMinimalRedline
 * 同口径，PR#188）。
 *
 * Office.js 没有按字符偏移切 Range 的 API，差异段只能靠在命中 Range 内二次
 * search 锚串来定位——所以「定位是否唯一」是整条路径的安全阀：任何一段定位
 * 不到唯一结果，整个 Range 放弃最小修订、回退原来的整段替换。全部定位都在
 * 任何写入之前完成，绝不会出现「改了一半又回退」的双重编辑。
 */

/** needle 在 hay 中的非重叠出现次数 */
function countOccurrences(hay, needle) {
  if (!needle) return 0
  let n = 0
  let i = hay.indexOf(needle)
  while (i !== -1) {
    n++
    i = hay.indexOf(needle, i + needle.length)
  }
  return n
}

/** 含孤立代理项的串（差分按 UTF-16 码元切，可能劈开 emoji）不能拿去 search */
function hasLoneSurrogate(s) {
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i)
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = s.charCodeAt(i + 1)
      if (!(next >= 0xdc00 && next <= 0xdfff)) return true
      i++
    } else if (c >= 0xdc00 && c <= 0xdfff) return true
  }
  return false
}

/** Word 的 search 能安全吃下的查找串：非空、不超 255、不含特殊码前缀 ^、码元完整 */
function searchable(s) {
  return !!s && s.length <= WORD_SEARCH_MAX_CHARS && !s.includes('^') && !hasLoneSurrogate(s)
}

/**
 * 为一段编辑构造在命中 Range 内的定位方案，返回 null 表示无法安全定位。
 * 消歧策略：
 *  - 替换/删除段（oldText 非空）：差异段在 Range 文本中唯一 → 直接 search
 *    （mode 'direct'）。不唯一 → 以差异段为中心向两侧对称扩上下文窗口，直到
 *    「窗口在 Range 内唯一」且「差异段在窗口内唯一」→ 先 search 窗口、再在
 *    窗口内 search 差异段（mode 'window'）；扩到整段仍不满足就放弃。
 *  - 纯插入段（oldText 为空）：以插入点左侧的一段文本为锚，锚唯一即
 *    insertText(..., After)；左侧不可用（插入点在最左/左锚不唯一）时改用
 *    右侧文本 + Before。
 */
function buildLocator(rangeText, edit) {
  const seg = edit.oldText
  const len = rangeText.length
  if (seg) {
    if (!searchable(seg)) return null
    if (countOccurrences(rangeText, seg) === 1) return { mode: 'direct', needle: seg }
    for (let pad = 1; ; pad++) {
      const lo = Math.max(0, edit.start - pad)
      const hi = Math.min(len, edit.end + pad)
      const win = rangeText.slice(lo, hi)
      if (!searchable(win)) return null
      if (countOccurrences(rangeText, win) === 1 && countOccurrences(win, seg) === 1) {
        return { mode: 'window', window: win, needle: seg }
      }
      if (lo === 0 && hi === len) return null
    }
  }
  for (let pad = 1; pad <= edit.start; pad++) {
    const anchor = rangeText.slice(edit.start - pad, edit.start)
    if (!searchable(anchor)) break
    if (countOccurrences(rangeText, anchor) === 1) return { mode: 'insertAfter', needle: anchor }
  }
  for (let pad = 1; edit.end + pad <= len; pad++) {
    const anchor = rangeText.slice(edit.end, edit.end + pad)
    if (!searchable(anchor)) break
    if (countOccurrences(rangeText, anchor) === 1) return { mode: 'insertBefore', needle: anchor }
  }
  return null
}

/**
 * 把 newText 以字符级最小修订写入命中 Range。
 * @returns {Promise<number|null>} 实际落笔的编辑段数（0 = 新旧文一致，不留痕迹）；
 *   null = 无法最小化，调用方回退整段 insertText(replace)。返回 null 时保证
 *   一个字都还没写。
 */
async function applyMinimalRedline(context, range, rangeText, newText) {
  const edits = minimalEdits(rangeText, newText)
  if (!edits.length) return 0
  // 差异覆盖整段：没有比整段替换更细的写法了
  if (edits.length === 1 && edits[0].start === 0 && edits[0].end === rangeText.length) return null

  const plans = []
  for (const edit of edits) {
    const loc = buildLocator(rangeText, edit)
    if (!loc) return null
    plans.push({ edit, loc })
  }

  // 第一轮定位：direct/纯插入的锚串，window 模式的外层窗口
  for (const plan of plans) {
    plan.primary = range.search(plan.loc.mode === 'window' ? plan.loc.window : plan.loc.needle, { matchCase: true })
    plan.primary.load('items')
  }
  await context.sync()
  for (const plan of plans) {
    if (plan.primary.items.length !== 1) return null
    plan.target = plan.primary.items[0]
  }

  // 第二轮定位：window 模式在唯一窗口内再切出差异段
  const windowed = plans.filter((plan) => plan.loc.mode === 'window')
  if (windowed.length) {
    for (const plan of windowed) {
      plan.inner = plan.target.search(plan.loc.needle, { matchCase: true })
      plan.inner.load('items')
    }
    await context.sync()
    for (const plan of windowed) {
      if (plan.inner.items.length !== 1) return null
      plan.target = plan.inner.items[0]
    }
  }

  // 应用：从右到左。左侧编辑的定位不会被右侧的写入推移（与 LOWA 同理由）。
  for (let i = plans.length - 1; i >= 0; i--) {
    const { edit, loc, target } = plans[i]
    if (loc.mode === 'insertAfter') target.insertText(edit.newText, Word.InsertLocation.after)
    else if (loc.mode === 'insertBefore') target.insertText(edit.newText, Word.InsertLocation.before)
    else if (edit.newText) target.insertText(edit.newText, Word.InsertLocation.replace)
    else target.delete()
  }
  await context.sync()
  return edits.length
}

/* ==================== Word 格式（字符面 + 段落面） ====================
 * 后端下发的枚举一律是小写短名（none/single/center/heading1…），这里映射成
 * Office.js 的枚举字符串值。映射表写死成字面量而不是引用 Word.UnderlineType，
 * 是因为本模块在 Office.js 就绪前就被 import——引用 Word.* 会在加载期炸。
 */

/** underline → Word.UnderlineType（本批次只开常用五种，wave=波浪线） */
const UNDERLINE_TYPES = {
  none: 'None',
  single: 'Single',
  double: 'Double',
  dotted: 'Dotted',
  wave: 'Wave'
}

/** alignment → Word.Alignment */
const ALIGNMENTS = {
  left: 'Left',
  center: 'Centered',
  right: 'Right',
  justify: 'Justified'
}

/** styleBuiltIn → Word.BuiltInStyleName（标题级别） */
const PARAGRAPH_STYLES = {
  normal: 'Normal',
  heading1: 'Heading1',
  heading2: 'Heading2',
  heading3: 'Heading3',
  heading4: 'Heading4'
}

/** 段落格式里按磅取值的字段（Office.js 侧同名） */
const PARAGRAPH_POINT_FIELDS = [
  'lineSpacing', 'spaceBefore', 'spaceAfter', 'firstLineIndent', 'leftIndent', 'rightIndent'
]

/* ---- 自动编号 ---- */

/** 后端下发的编号类型（chinese 没有 Office.js 枚举，只能手写编号文字） */
const NUMBERING_KINDS = ['bullet', 'decimal', 'chinese', 'none']

/** Word.ListNumbering.arabic / Word.ListBullet.solid（枚举写死成字面量，理由同上方映射表） */
const LIST_NUMBERING_ARABIC = 'Arabic'
const LIST_BULLET_SOLID = 'Solid'

/** 一次套用自动编号的最大段数（与后端 MAX_NUMBERING_PARAGRAPHS 一致） */
const MAX_NUMBERING_PARAGRAPHS = 200

const CHINESE_DIGITS = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九']

/** 1~200 的中文数字（十一、二十、一百零一、一百一十…） */
function chineseNumeral(n) {
  if (n < 10) return CHINESE_DIGITS[n]
  if (n < 20) return '十' + (n % 10 ? CHINESE_DIGITS[n % 10] : '')
  if (n < 100) return CHINESE_DIGITS[Math.floor(n / 10)] + '十' + (n % 10 ? CHINESE_DIGITS[n % 10] : '')
  const head = CHINESE_DIGITS[Math.floor(n / 100)] + '百'
  const rest = n % 100
  if (!rest) return head
  if (rest < 10) return head + '零' + CHINESE_DIGITS[rest]
  if (rest < 20) return head + '一十' + (rest % 10 ? CHINESE_DIGITS[rest % 10] : '')
  return head + CHINESE_DIGITS[Math.floor(rest / 10)] + '十' + (rest % 10 ? CHINESE_DIGITS[rest % 10] : '')
}

/** 手写编号回退时写到段首的前缀（i 从 0 起） */
function numberingPrefix(kind, i) {
  if (kind === 'chinese') return chineseNumeral(i + 1) + '、'
  if (kind === 'decimal') return `${i + 1}. `
  return '- '
}

/* ---- 表格格式 ---- */

/** 后端下发的边框范围 → Word.BorderLocation（none 走 All + type=None，见 format_table） */
const TABLE_BORDER_LOCATIONS = { all: 'All', outside: 'Outside', inside: 'Inside' }

/** 表格整体对齐 → Word.Alignment。表格没有两端对齐，所以不能复用 ALIGNMENTS */
const TABLE_ALIGNMENTS = { left: 'Left', center: 'Centered', right: 'Right' }

/** Word.BorderType 的两个取值 */
const BORDER_TYPE_SINGLE = 'Single'
const BORDER_TYPE_NONE = 'None'

/** 单元格坐标 "B2"（列字母+行号，行号 1 开始）→ 0 起的 {row, col}（批次 8） */
function parseCellRef(ref) {
  const m = /^([A-Za-z]{1,3})(\d{1,6})$/.exec(String(ref || '').trim())
  if (!m) throw new Error(`单元格坐标格式非法：${ref}（应为列字母+行号，如 B2）`)
  const letters = m[1].toUpperCase()
  let col = 0
  for (let i = 0; i < letters.length; i++) col = col * 26 + (letters.charCodeAt(i) - 64)
  return { row: Number(m[2]) - 1, col: col - 1 }
}

/* ---- 律所标准格式 ---- */

/**
 * 律所标准格式常量，从 house-default.json（后端 style-profiles 的副本）派生——三处写端
 * （后端 DocxStyleHelper / LOWA worker / 这里）同一份源，改规范只改那一个 JSON。
 * Office.js 只有固定磅值行距，画像的「最小值 16 磅」在这里落成 exact 16（见 lineSpacingMode）；
 * 首行缩进「2 字符」按正文字号折磅。
 */
export function houseFromProfile(p) {
  const d = (p && p.defaults) || {}
  const body = (p && p.body) || {}
  const h1 = ((p && p.headings) || []).find((h) => h && Number(h.level) === 1) || {}
  const cell = ((p && p.table) || {}).cell || {}
  const pt = (len, fontPt, fallback) => {
    if (!len || len.value == null) return fallback
    const v = Number(len.value)
    if (!isFinite(v)) return fallback
    switch (len.unit || 'pt') {
      case 'pt': return v
      case 'chars': return v * fontPt
      case 'lines': return v * fontPt * 1.2
      case 'mm': return v * 72 / 25.4
      case 'cm': return v * 720 / 25.4
      case 'twips': return v / 20
      default: return fallback
    }
  }
  const bodyPt = pt(body.size, 12, pt(d.size, 12, 12))
  const ls = body.lineSpacing || {}
  return {
    fontAsian: (body.font && body.font.eastAsia) || (d.font && d.font.eastAsia) || '楷体_GB2312',
    fontWestern: (body.font && body.font.western) || (d.font && d.font.western) || 'Arial',
    bodyPt,
    titlePt: pt(h1.size, bodyPt, 16),
    spaceAfterPt: pt(body.spaceAfter, bodyPt, 18),
    lineSpacingPt: ls.rule === 'atLeast' || ls.rule === 'exactly' ? pt({ value: ls.value, unit: ls.unit || 'pt' }, bodyPt, 16) : 16,
    firstLineIndentPt: pt(body.firstLineIndent, bodyPt, 2 * bodyPt),
    tablePt: pt(cell.size, 10, 10)
  }
}
const HOUSE = houseFromProfile(houseProfile)

/**
 * 小标题启发式：第X条/章/节/款/项、「一、」「（一）」「1.」这类序号开头且不长的段落。
 * 与 LOWA 的规范一致——小标题与正文同款字号，只是加粗且不首行缩进。
 */
const HEADING_RE = /^(第[一二三四五六七八九十百千零〇\d]+[条章节款项]|[一二三四五六七八九十]+[、.．]|[（(][一二三四五六七八九十\d]+[)）]|\d+[、.．])/

/** 主标题（文档首个非空段）的长度上限，超了当正文处理 */
const HOUSE_TITLE_MAX_CHARS = 50
/** 小标题的长度上限 */
const HOUSE_HEADING_MAX_CHARS = 40
/** 单次套用标准格式处理的段落上限（长文档只处理前这么多段并标 truncated） */
const MAX_STANDARD_FORMAT_PARAGRAPHS = 500

/** 按标准格式设置一个段落。kind: 'title' | 'heading' | 'body' */
function applyHouseParagraph(paragraph, kind, fontSplit) {
  const font = paragraph.font
  font.name = HOUSE.fontAsian
  if (fontSplit) {
    // 中西文分开设（WordApiDesktop 1.3）；不支持时上面的 name 已让中文字体统管全篇
    font.nameFarEast = HOUSE.fontAsian
    font.nameAscii = HOUSE.fontWestern
  }
  font.size = kind === 'title' ? HOUSE.titlePt : HOUSE.bodyPt
  font.bold = kind !== 'body'
  font.color = '#000000'
  paragraph.alignment = kind === 'title' ? ALIGNMENTS.center : ALIGNMENTS.justify
  paragraph.lineSpacing = HOUSE.lineSpacingPt
  paragraph.spaceBefore = 0
  paragraph.spaceAfter = HOUSE.spaceAfterPt
  paragraph.firstLineIndent = kind === 'body' ? HOUSE.firstLineIndentPt : 0
}

/** 小写短名 → Office.js 枚举值；非法值报错并列出合法值（后端已拦一道，这里是防线） */
function toEnumValue(table, raw, field) {
  const key = String(raw).trim().toLowerCase()
  const value = table[key]
  if (!value) {
    throw new Error(`${field} 值非法：${raw}（合法值：${Object.keys(table).join('/')}）`)
  }
  return value
}

/** Office.js 枚举值 → 小写短名（读格式时回译，让模型读到的词与能填的词一致） */
function fromEnumValue(table, raw) {
  if (raw == null) return raw
  const hit = Object.keys(table).find((key) => table[key] === raw)
  return hit || raw
}

/** 只把给出的字段落到 Office.js 代理对象上（未给的保持原样） */
function applyProps(target, patch) {
  for (const [key, value] of Object.entries(patch)) target[key] = value
}

/** 从 args 里挑出字符格式字段（空对象 = 调用方没给任何格式参数） */
function buildFontPatch(args) {
  const patch = {}
  if (args.fontName) patch.name = String(args.fontName)
  if (args.fontSize != null) patch.size = Number(args.fontSize)
  if (args.bold != null) patch.bold = !!args.bold
  if (args.italic != null) patch.italic = !!args.italic
  if (args.underline != null) patch.underline = toEnumValue(UNDERLINE_TYPES, args.underline, 'underline')
  if (args.strikeThrough != null) patch.strikeThrough = !!args.strikeThrough
  if (args.doubleStrikeThrough != null) patch.doubleStrikeThrough = !!args.doubleStrikeThrough
  if (args.color) patch.color = String(args.color)
  return patch
}

/** 从 args 里挑出段落格式字段（styleBuiltIn 单独处理，见 set_paragraph_format） */
function buildParagraphPatch(args) {
  const patch = {}
  if (args.alignment != null) patch.alignment = toEnumValue(ALIGNMENTS, args.alignment, 'alignment')
  for (const field of PARAGRAPH_POINT_FIELDS) {
    if (args[field] != null) patch[field] = Number(args[field])
  }
  return patch
}

const HANDLERS = {
  async get_text() {
    return Word.run(async (context) => {
      const body = context.document.body
      body.load('text')
      await context.sync()
      return truncate(body.text)
    })
  },

  async get_selection() {
    return Word.run(async (context) => {
      const selection = context.document.getSelection()
      selection.load('text')
      await context.sync()
      return truncate(selection.text)
    })
  },

  async search(args) {
    const query = String(args.query || '')
    if (!query) throw new Error('查找文本不能为空')
    return Word.run(async (context) => {
      const items = await searchRanges(context, query, false)
      const hits = items.slice(0, MAX_SEARCH_HITS)
      // 命中上下文 = 命中所在段落的完整文本
      const paragraphs = hits.map((range) => {
        const p = range.paragraphs.getFirst()
        p.load('text')
        return p
      })
      await context.sync()
      return {
        count: items.length,
        shown: hits.length,
        matches: hits.map((range, i) => ({
          index: i + 1,
          context: (paragraphs[i].text || '').slice(0, 500)
        }))
      }
    })
  },

  async replace_text(args) {
    const searchText = String(args.searchText || '')
    const replaceText = args.replaceText == null ? '' : String(args.replaceText)
    const replaceAll = !!args.replaceAll
    if (!searchText) throw new Error('查找文本不能为空')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        // 跨段 searchText 不做静默降级（有意区别于 insert_text）：降级只会命中其中
        // 一段，却要把完整的多段 replaceText 塞进去——旧的其余段落原地不动，结果是
        // 内容重复/错位。这里快速失败并把改法说清楚，让模型一次重试就改对（dev-board#149）。
        if (/[\r\n]/.test(searchText)) {
          throw new Error('searchText 跨段落（含换行），Word 的查找不支持跨段匹配。'
            + '请逐段替换：每次以单段内的文本为 searchText，并只提供该段的替换文本')
        }
        const items = await searchRanges(context, searchText, true)
        if (!items.length) {
          throw await anchorNotFound(context, '替换文本', searchText)
        }
        const targets = replaceAll ? items : [items[0]]
        for (const range of targets) range.load('text')
        await context.sync()
        // 跨段（\r/\n）交给整段替换：段落结构不该被字符级差分拆着改
        const multiline = /[\r\n]/.test(searchText) || /[\r\n]/.test(replaceText)
        let minimal = 0
        let fallbacks = 0
        let editSegments = 0
        // 多个命中同样从右到左处理：前一处的写入不会推移后面命中的定位
        for (let i = targets.length - 1; i >= 0; i--) {
          const range = targets[i]
          const rangeText = range.text == null ? '' : String(range.text)
          const applied = multiline || !rangeText
            ? null
            : await applyMinimalRedline(context, range, rangeText, replaceText)
          if (applied == null) {
            range.insertText(replaceText, Word.InsertLocation.replace)
            await context.sync()
            fallbacks++
          } else {
            minimal++
            editSegments += applied
          }
        }
        const result = {
          replaced: targets.length,
          totalMatches: items.length,
          via: fallbacks === 0 ? 'minimalRedline' : (minimal === 0 ? 'fullReplace' : 'mixed'),
          edits: editSegments
        }
        if (fallbacks) result.fallbacks = fallbacks
        if (!editSegments && !fallbacks) result.note = '新旧文本一致，未产生修订'
        return result
      })
    })
  },

  async insert_text(args) {
    const text = String(args.text || '')
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    if (!text) throw new Error('插入文本不能为空')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        if (anchorText) {
          let items = await searchRanges(context, anchorText, true)
          if (!items.length && /[\r\n]/.test(anchorText)) {
            // 锚点跨段：降级为按插入方向取一段再试一次（after 取最后一段、before
            // 取第一段，保住「插在哪一侧」的原意，dev-board#149）
            const fallback = pickAnchorFallback(anchorText, position)
            if (fallback) items = await searchRanges(context, boundForSearch(fallback), true)
          }
          if (!items.length) {
            throw await anchorNotFound(context, '插入文本（修订）', anchorText)
          }
          const location = position === 'before' ? Word.InsertLocation.before : Word.InsertLocation.after
          items[0].insertText(text, location)
          await context.sync()
          // 锚点命中多处时**如实交代用了第一处**（dev-board#286）：此前是静默取 items[0]，
          // 模型以为插在了自己想的那一条，实际可能落在了另一条同名条款后面，
          // 而返回值一个字都不提——这类"静默改到别处"比报错难查得多。
          const out = { inserted: true, anchored: true, position }
          if (items.length > 1) {
            out.totalMatches = items.length
            out.note = `锚点在文档中命中 ${items.length} 处，已插入到第 1 处之${position === 'before' ? '前' : '后'}；`
              + '若不是你要的位置，请换一段在全文唯一的锚点重试。'
          }
          return out
        }
        // 无锚点：落在用户当前光标/选区处。**选区会被替换**——用户正选着一段文字时，
        // 这一下就是把他选中的内容删掉换成新文本，所以返回值要说清楚（dev-board#286）。
        const selection = context.document.getSelection()
        selection.load('text')
        await context.sync()
        const replacedText = String(selection.text || '')
        selection.insertText(text, Word.InsertLocation.replace)
        await context.sync()
        const out = { inserted: true, anchored: false, position: 'selection' }
        if (replacedText.trim()) {
          out.replacedSelection = replacedText.length > 80 ? replacedText.slice(0, 80) + '…' : replacedText
          out.note = '未提供 anchorText，内容插入在用户当前选区处，并替换掉了原本选中的文字（见 replacedSelection）。'
        }
        return out
      })
    })
  },

  async add_comment(args) {
    const anchorText = String(args.anchorText || '')
    const comment = String(args.comment || '')
    if (!anchorText) throw new Error('批注目标文本不能为空')
    if (!comment) throw new Error('批注内容不能为空')
    if (!trackingSupported()) {
      // insertComment 同属 WordApi 1.4
      throw new Error('当前 Word 版本不支持插入批注（需要 WordApi 1.4）')
    }
    return Word.run(async (context) => {
      const items = await searchRanges(context, anchorText, true)
      if (!items.length) {
        throw await anchorNotFound(context, '插入批注', anchorText)
      }
      items[0].insertComment(comment)
      await context.sync()
      return { commented: true }
    })
  },

  // ==================== 格式（word 面，走 Word 原生修订） ====================

  async format_text(args) {
    const anchorText = String(args.anchorText || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    const font = buildFontPatch(args)
    if (!Object.keys(font).length) {
      throw new Error('未给出任何格式参数（fontName/fontSize/bold/italic/underline/strikeThrough/doubleStrikeThrough/color 至少给一个）')
    }
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const items = await searchRanges(context, anchorText, true)
        if (!items.length) {
          throw await anchorNotFound(context, '定位锚点', anchorText)
        }
        const targets = args.applyToAll ? items : [items[0]]
        for (const range of targets) applyProps(range.font, font)
        await context.sync()
        return { formatted: targets.length, totalMatches: items.length, applied: font }
      })
    })
  },

  async set_paragraph_format(args) {
    const anchorText = String(args.anchorText || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    const patch = buildParagraphPatch(args)
    const styleBuiltIn = args.styleBuiltIn == null
      ? null
      : toEnumValue(PARAGRAPH_STYLES, args.styleBuiltIn, 'styleBuiltIn')
    if (!styleBuiltIn && !Object.keys(patch).length) {
      throw new Error('未给出任何格式参数（alignment/lineSpacing/spaceBefore/spaceAfter/firstLineIndent/leftIndent/rightIndent/styleBuiltIn 至少给一个）')
    }
    if (styleBuiltIn && !builtInStyleSupported()) {
      throw new Error('当前 Word 版本不支持设置标题级别（需要 WordApi 1.3），可改用字号与加粗参数')
    }
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const items = await searchRanges(context, anchorText, true)
        if (!items.length) {
          throw await anchorNotFound(context, '定位锚点', anchorText)
        }
        const targets = args.applyToAll ? items : [items[0]]
        const paragraphs = targets.map((range) => range.paragraphs.getFirst())
        // 套内置样式会把段落格式重置成样式自带的那套，必须先落样式再落其余参数
        if (styleBuiltIn) {
          for (const paragraph of paragraphs) paragraph.styleBuiltIn = styleBuiltIn
          await context.sync()
        }
        if (Object.keys(patch).length) {
          for (const paragraph of paragraphs) applyProps(paragraph, patch)
          await context.sync()
        }
        const applied = styleBuiltIn ? { ...patch, styleBuiltIn: args.styleBuiltIn } : patch
        return { formatted: paragraphs.length, totalMatches: items.length, applied }
      })
    })
  },

  async get_formatting(args) {
    const anchorText = String(args.anchorText || '')
    return Word.run(async (context) => {
      let range
      if (anchorText) {
        const items = await searchRanges(context, anchorText, true)
        if (!items.length) {
          throw await anchorNotFound(context, '定位锚点', anchorText)
        }
        range = items[0]
      } else {
        // 无锚点：读用户当前选区；选区为空时 getFirst 取到的就是光标所在段落
        range = context.document.getSelection()
      }
      const paragraph = range.paragraphs.getFirst()
      range.load('text')
      range.font.load('name,size,bold,italic,underline,strikeThrough,color')
      // styleBuiltIn 属 WordApi 1.3：旧宿主上既不能 load 也不能读（未 load 的属性直接抛）
      const withBuiltIn = builtInStyleSupported()
      const paragraphFields = PARAGRAPH_POINT_FIELDS.concat(['alignment', 'style'])
      if (withBuiltIn) paragraphFields.push('styleBuiltIn')
      paragraph.load(paragraphFields.join(','))
      await context.sync()
      const font = range.font
      return {
        source: anchorText ? 'anchor' : 'selection',
        text: (range.text || '').slice(0, 200),
        font: {
          name: font.name,
          size: font.size,
          bold: font.bold,
          italic: font.italic,
          underline: fromEnumValue(UNDERLINE_TYPES, font.underline),
          strikeThrough: font.strikeThrough,
          color: font.color
        },
        paragraph: {
          alignment: fromEnumValue(ALIGNMENTS, paragraph.alignment),
          lineSpacing: paragraph.lineSpacing,
          spaceBefore: paragraph.spaceBefore,
          spaceAfter: paragraph.spaceAfter,
          firstLineIndent: paragraph.firstLineIndent,
          leftIndent: paragraph.leftIndent,
          rightIndent: paragraph.rightIndent,
          style: paragraph.style,
          styleBuiltIn: withBuiltIn ? fromEnumValue(PARAGRAPH_STYLES, paragraph.styleBuiltIn) : undefined
        }
      }
    })
  },

  async set_numbering(args) {
    const anchorText = String(args.anchorText || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    const kind = String(args.kind || '').trim().toLowerCase()
    if (!NUMBERING_KINDS.includes(kind)) {
      throw new Error(`kind 值非法：${args.kind}（合法值：${NUMBERING_KINDS.join('/')}）`)
    }
    let count = Math.floor(Number(args.paragraphCount))
    if (!Number.isFinite(count) || count < 1) count = 1
    count = Math.min(count, MAX_NUMBERING_PARAGRAPHS)
    const listApi = wordApi13Supported()
    if (kind === 'none' && !listApi) {
      throw new Error('当前 Word 版本不支持清除编号（需要 WordApi 1.3）')
    }
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const paragraphs = context.document.body.paragraphs
        // isListItem 属 WordApi 1.3：旧宿主上既不能 load 也不能读（styleBuiltIn 同款门槛）
        paragraphs.load(listApi ? 'text,isListItem' : 'text')
        await context.sync()
        const items = paragraphs.items
        const start = items.findIndex((p) => String(p.text || '').includes(anchorText))
        if (start < 0) {
          throw await anchorNotFound(context, '定位段落', anchorText)
        }
        const targets = items.slice(start, start + count)

        if (kind === 'none') {
          // 不是列表项的段落静默跳过（detachFromList 对非列表段没有意义）
          let detached = 0
          for (const paragraph of targets) {
            if (!paragraph.isListItem) continue
            paragraph.detachFromList()
            detached++
          }
          await context.sync()
          return { paragraphs: targets.length, detached, kind, via: 'listApi' }
        }

        if (listApi && kind !== 'chinese') {
          const list = targets[0].startNewList()
          list.load('id')
          await context.sync()
          if (kind === 'bullet') list.setLevelBullet(0, LIST_BULLET_SOLID)
          else list.setLevelNumbering(0, LIST_NUMBERING_ARABIC)
          for (let i = 1; i < targets.length; i++) targets[i].attachToList(list.id, 0)
          await context.sync()
          return { paragraphs: targets.length, kind, via: 'listApi' }
        }

        // 手写编号回退：chinese 没有对应的 Word.ListNumbering 枚举（原生编号根本没有中文数字这一档），
        // 旧宿主（无 WordApi 1.3）则是连 List API 都没有——两种情况都退化成往段首写编号文字。
        targets.forEach((paragraph, i) => {
          paragraph.insertText(numberingPrefix(kind, i), Word.InsertLocation.start)
        })
        await context.sync()
        return {
          paragraphs: targets.length,
          kind,
          via: 'literalText',
          note: kind === 'chinese'
            ? 'Word 原生编号没有中文数字，编号已作为文字写入各段段首'
            : '当前 Word 版本不支持自动编号（需要 WordApi 1.3），编号已作为文字写入各段段首'
        }
      })
    })
  },

  async format_table(args) {
    let index = Math.floor(Number(args.tableIndex))
    if (!Number.isFinite(index) || index < 0) index = 0
    const borders = args.borders == null ? null : String(args.borders).trim().toLowerCase()
    if (borders && borders !== 'none' && !TABLE_BORDER_LOCATIONS[borders]) {
      throw new Error(`borders 值非法：${args.borders}（合法值：all/outside/inside/none）`)
    }
    const alignment = args.alignment == null ? null : toEnumValue(TABLE_ALIGNMENTS, args.alignment, 'alignment')
    const fontSize = args.fontSize == null ? null : Number(args.fontSize)
    if (!borders && !alignment && args.headerBold == null && args.autoFit == null && fontSize == null) {
      throw new Error('未给出任何格式参数（borders/alignment/headerBold/autoFit/fontSize 至少给一个）')
    }
    // 表格集合、getBorder、alignment、autoFitWindow 都属 WordApi 1.3
    if (!wordApi13Supported()) {
      throw new Error('当前 Word 版本不支持表格格式设置（需要 WordApi 1.3）')
    }
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const tables = context.document.body.tables
        tables.load('items')
        await context.sync()
        if (!tables.items.length) throw new Error('当前文档中没有表格')
        if (index >= tables.items.length) {
          throw new Error(`tableIndex ${index} 越界：文档中共 ${tables.items.length} 张表格（序号从 0 开始）`)
        }
        const table = tables.items[index]
        const applied = {}
        if (borders === 'none') {
          table.getBorder(TABLE_BORDER_LOCATIONS.all).type = BORDER_TYPE_NONE
          applied.borders = 'none'
        } else if (borders) {
          const color = String(args.borderColor || '#000000')
          const width = Number(args.borderWidth) > 0 ? Number(args.borderWidth) : 1
          const border = table.getBorder(TABLE_BORDER_LOCATIONS[borders])
          border.type = BORDER_TYPE_SINGLE
          border.color = color
          border.width = width
          applied.borders = borders
          applied.borderColor = color
          applied.borderWidth = width
        }
        if (alignment) {
          table.alignment = alignment
          applied.alignment = String(args.alignment).trim().toLowerCase()
        }
        if (args.headerBold != null) {
          table.rows.getFirst().font.bold = !!args.headerBold
          applied.headerBold = !!args.headerBold
        }
        if (fontSize != null) {
          table.font.size = fontSize
          applied.fontSize = fontSize
        }
        if (args.autoFit) {
          table.autoFitWindow()
          applied.autoFit = true
        }
        table.load('rowCount')
        await context.sync()
        return { tableIndex: index, tableCount: tables.items.length, rowCount: table.rowCount, applied }
      })
    })
  },

  async apply_standard_format(args) {
    const scope = String(args.scope || 'document').trim().toLowerCase() === 'selection' ? 'selection' : 'document'
    const fontSplit = farEastFontSupported()
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const root = scope === 'selection' ? context.document.getSelection() : context.document.body
        const paragraphs = root.paragraphs
        paragraphs.load('text')
        await context.sync()
        const all = paragraphs.items
        const truncated = all.length > MAX_STANDARD_FORMAT_PARAGRAPHS
        const items = truncated ? all.slice(0, MAX_STANDARD_FORMAT_PARAGRAPHS) : all

        let firstNonEmptySeen = false
        let titles = 0
        let headings = 0
        let bodies = 0
        for (const paragraph of items) {
          const text = String(paragraph.text || '').trim()
          if (!text) continue
          let kind = 'body'
          if (!firstNonEmptySeen) {
            // 主标题启发式：文档（或选区）的第一个非空段，且短到像个标题
            firstNonEmptySeen = true
            if (text.length <= HOUSE_TITLE_MAX_CHARS) kind = 'title'
          } else if (text.length <= HOUSE_HEADING_MAX_CHARS && HEADING_RE.test(text)) {
            kind = 'heading'
          }
          applyHouseParagraph(paragraph, kind, fontSplit)
          if (kind === 'title') titles++
          else if (kind === 'heading') headings++
          else bodies++
        }
        await context.sync()

        // 表格字号只在全文范围处理（选区里的表格不在 v1 范围）
        let tableCount = 0
        if (scope === 'document' && wordApi13Supported()) {
          const tables = context.document.body.tables
          tables.load('items')
          await context.sync()
          for (const table of tables.items) table.font.size = HOUSE.tablePt
          tableCount = tables.items.length
          await context.sync()
        }

        const result = {
          scope,
          paragraphs: titles + headings + bodies,
          titles,
          headings,
          tables: tableCount,
          fontSplit,
          // Office.js 的 paragraph.lineSpacing 只有固定磅值行距，没有「最小值」这一档
          lineSpacingMode: 'exact'
        }
        if (truncated) {
          result.truncated = true
          result.totalParagraphs = all.length
        }
        if (scope === 'selection') result.note = '选区范围不处理表格字号'
        return result
      })
    })
  },

  // ==================== 表格（批次 8，走 Word 原生修订） ====================

  async insert_table(args) {
    const rows = args.rows
    if (!Array.isArray(rows) || !rows.length || !Array.isArray(rows[0]) || !rows[0].length) {
      throw new Error('rowsJson 不能为空表')
    }
    const rowCount = rows.length
    const colCount = rows[0].length
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持插入表格（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        let table
        if (anchorText) {
          const items = await searchRanges(context, anchorText, true)
          if (!items.length) throw await anchorNotFound(context, '定位锚点', anchorText)
          const location = position === 'before' ? Word.InsertLocation.before : Word.InsertLocation.after
          table = items[0].insertTable(rowCount, colCount, location, rows)
        } else {
          // 无锚点：插在当前光标/选区之后（与 insert_text 的无锚点语义一致）
          table = context.document.getSelection().insertTable(rowCount, colCount, Word.InsertLocation.after, rows)
        }
        if (args.headerBold) table.rows.getFirst().font.bold = true
        await context.sync()
        return { inserted: true, rows: rowCount, cols: colCount, anchored: !!anchorText }
      })
    })
  },

  async table_read(args) {
    let index = Math.floor(Number(args.tableIndex))
    if (!Number.isFinite(index) || index < 0) index = 0
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持读取表格（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      const tables = context.document.body.tables
      tables.load('items')
      await context.sync()
      if (!tables.items.length) throw new Error('当前文档中没有表格')
      if (index >= tables.items.length) {
        throw new Error(`tableIndex ${index} 越界：文档中共 ${tables.items.length} 张表格（序号从 0 开始）`)
      }
      const table = tables.items[index]
      const rows = table.rows
      rows.load('items')
      await context.sync()
      const rowItems = rows.items
      rowItems.forEach((row) => row.cells.load('items'))
      await context.sync()
      rowItems.forEach((row) => row.cells.items.forEach((cell) => cell.body.load('text')))
      await context.sync()
      const cells = rowItems.map((row) => row.cells.items.map((cell) => String(cell.body.text || '').replace(/\r$/, '').trim()))
      return {
        tableIndex: index,
        tableCount: tables.items.length,
        rowCount: cells.length,
        colCount: cells[0] ? cells[0].length : 0,
        cells
      }
    })
  },

  async table_set_cell(args) {
    let index = Math.floor(Number(args.tableIndex))
    if (!Number.isFinite(index) || index < 0) index = 0
    const { row, col } = parseCellRef(args.cell)
    const text = args.text == null ? '' : String(args.text)
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持修改表格单元格（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const tables = context.document.body.tables
        tables.load('items')
        await context.sync()
        if (index >= tables.items.length) {
          throw new Error(`tableIndex ${index} 越界：文档中共 ${tables.items.length} 张表格（序号从 0 开始）`)
        }
        const table = tables.items[index]
        const cell = table.getCellOrNullObject(row, col)
        cell.load('isNullObject')
        await context.sync()
        if (cell.isNullObject) {
          throw new Error(`单元格 ${args.cell} 不存在（可能越界或落在合并单元格里），请先用 office_table_read 核对`)
        }
        const body = cell.body
        body.load('text')
        await context.sync()
        const rangeText = String(body.text == null ? '' : body.text).replace(/\r$/, '')
        const multiline = /[\r\n]/.test(text)
        const applied = multiline || !rangeText ? null : await applyMinimalRedline(context, body, rangeText, text)
        let via
        if (applied == null) {
          body.insertText(text, Word.InsertLocation.replace)
          await context.sync()
          via = 'fullReplace'
        } else {
          via = applied ? 'minimalRedline' : 'unchanged'
        }
        return { cell: args.cell, via, edits: applied || 0 }
      })
    })
  },

  async table_add_row(args) {
    let index = Math.floor(Number(args.tableIndex))
    if (!Number.isFinite(index) || index < 0) index = 0
    let position = Math.floor(Number(args.position))
    if (!Number.isFinite(position)) position = -1
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持表格行操作（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const tables = context.document.body.tables
        tables.load('items')
        await context.sync()
        if (index >= tables.items.length) {
          throw new Error(`tableIndex ${index} 越界：文档中共 ${tables.items.length} 张表格（序号从 0 开始）`)
        }
        const table = tables.items[index]
        table.load('rowCount')
        await context.sync()
        const rowCount = table.rowCount
        if (position < 0 || position >= rowCount) {
          table.addRows(Word.InsertLocation.end, count)
        } else if (position === 0) {
          table.addRows(Word.InsertLocation.start, count)
        } else {
          const rows = table.rows
          rows.load('items')
          await context.sync()
          rows.items[position].insertRows(Word.InsertLocation.before, count)
        }
        table.load('rowCount')
        await context.sync()
        return { tableIndex: index, added: count, rowCount: table.rowCount }
      })
    })
  },

  // 表格结构删除走 API 直接删除，不产生修订（同桌面端 doc_table_delete_row/col 的已知限制）
  async table_delete_row(args) {
    let index = Math.floor(Number(args.tableIndex))
    if (!Number.isFinite(index) || index < 0) index = 0
    const position = Math.floor(Number(args.position))
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    if (!Number.isFinite(position) || position < 0) throw new Error('缺少要删的行号（position，0 开始）')
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持表格行操作（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      const tables = context.document.body.tables
      tables.load('items')
      await context.sync()
      if (index >= tables.items.length) {
        throw new Error(`tableIndex ${index} 越界：文档中共 ${tables.items.length} 张表格（序号从 0 开始）`)
      }
      const table = tables.items[index]
      table.load('rowCount')
      await context.sync()
      if (position >= table.rowCount) {
        throw new Error(`rowIndex ${position} 越界：表格共 ${table.rowCount} 行（序号从 0 开始）`)
      }
      if (table.rowCount - count < 1) {
        throw new Error(`表格至少要留一行，当前 ${table.rowCount} 行删不了 ${count} 行`)
      }
      table.deleteRows(position, count)
      await context.sync()
      table.load('rowCount')
      await context.sync()
      return { tableIndex: index, deleted: count, rowCount: table.rowCount, tracked: false }
    })
  },

  async table_add_col(args) {
    let index = Math.floor(Number(args.tableIndex))
    if (!Number.isFinite(index) || index < 0) index = 0
    let position = Math.floor(Number(args.position))
    if (!Number.isFinite(position)) position = -1
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持表格列操作（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const tables = context.document.body.tables
        tables.load('items')
        await context.sync()
        if (index >= tables.items.length) {
          throw new Error(`tableIndex ${index} 越界：文档中共 ${tables.items.length} 张表格（序号从 0 开始）`)
        }
        const table = tables.items[index]
        const cols = table.columns
        cols.load('items')
        await context.sync()
        const colCount = cols.items.length
        if (position < 0 || position >= colCount) {
          table.addColumns(Word.InsertLocation.end, count)
        } else if (position === 0) {
          table.addColumns(Word.InsertLocation.start, count)
        } else {
          if (!wordApiDesktop13Supported()) {
            throw new Error('在表格中间位置插入列需要较新的桌面版 Word（WordApiDesktop 1.3，Word 网页版不支持），可改用 colIndex=0（最前）或 -1（最后）')
          }
          const target = cols.items[position]
          for (let i = 0; i < count; i++) cols.add(target)
        }
        await context.sync()
        return { tableIndex: index, added: count, colCount: colCount + count }
      })
    })
  },

  async table_delete_col(args) {
    let index = Math.floor(Number(args.tableIndex))
    if (!Number.isFinite(index) || index < 0) index = 0
    const position = Math.floor(Number(args.position))
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    if (!Number.isFinite(position) || position < 0) throw new Error('缺少要删的列号（position，0 开始）')
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持表格列操作（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      const tables = context.document.body.tables
      tables.load('items')
      await context.sync()
      if (index >= tables.items.length) {
        throw new Error(`tableIndex ${index} 越界：文档中共 ${tables.items.length} 张表格（序号从 0 开始）`)
      }
      const table = tables.items[index]
      const cols = table.columns
      cols.load('items')
      await context.sync()
      const colCount = cols.items.length
      if (position >= colCount) {
        throw new Error(`colIndex ${position} 越界：表格共 ${colCount} 列（序号从 0 开始）`)
      }
      if (colCount - count < 1) {
        throw new Error(`表格至少要留一列，当前 ${colCount} 列删不了 ${count} 列`)
      }
      table.deleteColumns(position, count)
      await context.sync()
      return { tableIndex: index, deleted: count, colCount: colCount - count, tracked: false }
    })
  },

  // ==================== 结构（分页符/超链接/页眉页脚，批次 8） ====================

  async insert_break(args) {
    const breakType = args.breakType === 'sectionNext' ? Word.BreakType.sectionNext : Word.BreakType.page
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const location = position === 'before' ? Word.InsertLocation.before : Word.InsertLocation.after
        if (anchorText) {
          const items = await searchRanges(context, anchorText, true)
          if (!items.length) throw await anchorNotFound(context, '定位锚点', anchorText)
          items[0].insertBreak(breakType, location)
        } else {
          context.document.getSelection().insertBreak(breakType, location)
        }
        await context.sync()
        return { inserted: true, breakType: args.breakType || 'page', anchored: !!anchorText }
      })
    })
  },

  async set_hyperlink(args) {
    const anchorText = String(args.anchorText || '')
    const url = String(args.url || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!url) throw new Error('url 不能为空')
    if (!wordApi13Supported()) throw new Error('当前 Word 版本不支持设置超链接（需要 WordApi 1.3）')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const items = await searchRanges(context, anchorText, true)
        if (!items.length) {
          throw await anchorNotFound(context, '定位锚点', anchorText)
        }
        items[0].hyperlink = url
        await context.sync()
        return { linked: true, url }
      })
    })
  },

  async edit_header_footer(args) {
    const part = args.part === 'footer' ? 'footer' : 'header'
    const text = args.text == null ? '' : String(args.text)
    const alignment = args.alignment == null ? null : toEnumValue(ALIGNMENTS, args.alignment, 'alignment')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const section = context.document.sections.getFirst()
        const body = part === 'footer'
          ? section.getFooter(Word.HeaderFooterType.primary)
          : section.getHeader(Word.HeaderFooterType.primary)
        body.insertText(text, Word.InsertLocation.replace)
        await context.sync()
        if (alignment) {
          const paragraphs = body.paragraphs
          paragraphs.load('items')
          await context.sync()
          paragraphs.items.forEach((p) => { p.alignment = alignment })
          await context.sync()
        }
        return { part, textLength: text.length, alignment: args.alignment || null }
      })
    })
  },

  // ==================== 批注（读取/回复/解决，批次 8） ====================

  async get_comments() {
    if (!trackingSupported()) throw new Error('当前 Word 版本不支持读取批注（需要 WordApi 1.4）')
    return Word.run(async (context) => {
      const comments = context.document.body.getComments()
      comments.load('items')
      await context.sync()
      const items = comments.items
      items.forEach((c) => c.load('id,authorName,content,creationDate,resolved'))
      const ranges = items.map((c) => {
        const r = c.getRange()
        r.load('text')
        return r
      })
      await context.sync()
      return {
        count: items.length,
        comments: items.map((c, i) => ({
          index: i,
          id: c.id,
          author: c.authorName,
          content: c.content,
          createdAt: c.creationDate ? new Date(c.creationDate).toISOString() : null,
          resolved: !!c.resolved,
          anchorText: (ranges[i].text || '').slice(0, 200)
        }))
      }
    })
  },

  async reply_comment(args) {
    const commentId = String(args.commentId || '')
    const commentIndex = args.commentIndex
    const reply = String(args.reply || '')
    if (!reply) throw new Error('回复内容不能为空')
    if (!commentId && (commentIndex == null || commentIndex < 0)) {
      throw new Error('缺少批注定位参数（commentId 或 commentIndex）')
    }
    if (!trackingSupported()) throw new Error('当前 Word 版本不支持批注（需要 WordApi 1.4）')
    return Word.run(async (context) => {
      const comments = context.document.body.getComments()
      comments.load('items')
      await context.sync()
      const items = comments.items
      if (commentId) items.forEach((c) => c.load('id'))
      await context.sync()
      const target = commentId
        ? items.find((c) => c.id === commentId) || null
        : (commentIndex >= 0 && commentIndex < items.length ? items[commentIndex] : null)
      if (!target) throw new Error('未找到指定批注，请先用 office_get_comments 核对 commentId/commentIndex')
      target.reply(reply)
      await context.sync()
      return { replied: true }
    })
  },

  async resolve_comment(args) {
    const commentId = String(args.commentId || '')
    const commentIndex = args.commentIndex
    const resolved = args.resolved !== false
    if (!commentId && (commentIndex == null || commentIndex < 0)) {
      throw new Error('缺少批注定位参数（commentId 或 commentIndex）')
    }
    if (!trackingSupported()) throw new Error('当前 Word 版本不支持批注（需要 WordApi 1.4）')
    return Word.run(async (context) => {
      const comments = context.document.body.getComments()
      comments.load('items')
      await context.sync()
      const items = comments.items
      if (commentId) items.forEach((c) => c.load('id'))
      await context.sync()
      const target = commentId
        ? items.find((c) => c.id === commentId) || null
        : (commentIndex >= 0 && commentIndex < items.length ? items[commentIndex] : null)
      if (!target) throw new Error('未找到指定批注，请先用 office_get_comments 核对 commentId/commentIndex')
      target.resolved = resolved
      await context.sync()
      return { resolved }
    })
  },

  // ==================== 修订接受/拒绝（批次 9，WordApi 1.6） ====================

  async get_revisions() {
    if (!trackedChangesSupported()) throw new Error('当前 Word 版本不支持读取修订（需要 WordApi 1.6）')
    return Word.run(async (context) => {
      const changes = context.document.body.getTrackedChanges()
      changes.load('items')
      await context.sync()
      const items = changes.items
      items.forEach((c) => c.load('author,date,text,type'))
      await context.sync()
      return {
        count: items.length,
        revisions: items.map((c, i) => ({
          index: i,
          author: c.author,
          date: c.date ? new Date(c.date).toISOString() : null,
          type: c.type,
          text: (c.text || '').slice(0, 200)
        }))
      }
    })
  },

  async accept_revision(args) {
    if (!trackedChangesSupported()) throw new Error('当前 Word 版本不支持修订操作（需要 WordApi 1.6）')
    return Word.run(async (context) => {
      const changes = context.document.body.getTrackedChanges()
      if (args.all) {
        changes.acceptAll()
        await context.sync()
        return { acceptedAll: true }
      }
      changes.load('items')
      await context.sync()
      const index = Math.floor(Number(args.revisionIndex))
      if (!Number.isFinite(index) || index < 0 || index >= changes.items.length) {
        throw new Error(`revisionIndex ${args.revisionIndex} 越界：文档共 ${changes.items.length} 条修订（序号从 0 开始）`)
      }
      changes.items[index].accept()
      await context.sync()
      return { accepted: true, revisionIndex: index }
    })
  },

  async reject_revision(args) {
    if (!trackedChangesSupported()) throw new Error('当前 Word 版本不支持修订操作（需要 WordApi 1.6）')
    return Word.run(async (context) => {
      const changes = context.document.body.getTrackedChanges()
      if (args.all) {
        changes.rejectAll()
        await context.sync()
        return { rejectedAll: true }
      }
      changes.load('items')
      await context.sync()
      const index = Math.floor(Number(args.revisionIndex))
      if (!Number.isFinite(index) || index < 0 || index >= changes.items.length) {
        throw new Error(`revisionIndex ${args.revisionIndex} 越界：文档共 ${changes.items.length} 条修订（序号从 0 开始）`)
      }
      changes.items[index].reject()
      await context.sync()
      return { rejected: true, revisionIndex: index }
    })
  },

  // ==================== 脚注/尾注（批次 9，WordApi 1.5） ====================

  async insert_footnote(args) {
    const anchorText = String(args.anchorText || '')
    const text = String(args.text || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!text) throw new Error('脚注正文内容不能为空')
    if (!footnoteApiSupported()) throw new Error('当前 Word 版本不支持插入脚注（需要 WordApi 1.5）')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const items = await searchRanges(context, anchorText, true)
        if (!items.length) throw await anchorNotFound(context, '定位锚点', anchorText)
        items[0].insertFootnote(text)
        await context.sync()
        return { inserted: true }
      })
    })
  },

  async insert_endnote(args) {
    const anchorText = String(args.anchorText || '')
    const text = String(args.text || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!text) throw new Error('尾注正文内容不能为空')
    if (!footnoteApiSupported()) throw new Error('当前 Word 版本不支持插入尾注（需要 WordApi 1.5）')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const items = await searchRanges(context, anchorText, true)
        if (!items.length) throw await anchorNotFound(context, '定位锚点', anchorText)
        items[0].insertEndnote(text)
        await context.sync()
        return { inserted: true }
      })
    })
  },

  // ==================== 图片插入（批次 9，insert_image，WordApi 1.2） ====================

  async insert_image(args) {
    const base64 = String(args.imageBase64 || '')
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    if (!base64) throw new Error('缺少图片数据')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        let picture
        if (anchorText) {
          const items = await searchRanges(context, anchorText, true)
          if (!items.length) throw await anchorNotFound(context, '定位锚点', anchorText)
          const location = position === 'before' ? Word.InsertLocation.before : Word.InsertLocation.after
          picture = items[0].insertInlinePictureFromBase64(base64, location)
        } else {
          picture = context.document.getSelection().insertInlinePictureFromBase64(base64, Word.InsertLocation.replace)
        }
        if (args.width != null) {
          picture.lockAspectRatio = true
          picture.width = Number(args.width)
        }
        await context.sync()
        return { inserted: true, anchored: !!anchorText }
      })
    })
  },

  // ==================== 样式应用（批次 9，apply_style，WordApi 1.1） ====================

  async apply_style(args) {
    const anchorText = String(args.anchorText || '')
    const styleName = String(args.styleName || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!styleName) throw new Error('样式名不能为空')
    return Word.run(async (context) => {
      return withTracking(context, async () => {
        const items = await searchRanges(context, anchorText, true)
        if (!items.length) {
          throw await anchorNotFound(context, '定位锚点', anchorText)
        }
        const targets = args.applyToAll ? items : [items[0]]
        const paragraphs = targets.map((range) => range.paragraphs.getFirst())
        paragraphs.forEach((p) => { p.style = styleName })
        await context.sync()
        return { applied: paragraphs.length, totalMatches: items.length, styleName }
      })
    })
  },

  // ==================== 内容控件（批次 9，manage_content_control，WordApi 1.1） ====================

  async manage_content_control(args) {
    const action = String(args.action || '')
    const tag = String(args.tag || '')
    if (!tag) throw new Error('tag 不能为空')
    return Word.run(async (context) => {
      if (action === 'insert') {
        const anchorText = String(args.anchorText || '')
        if (!anchorText) throw new Error('insert 需要 anchorText')
        return withTracking(context, async () => {
          const items = await searchRanges(context, anchorText, true)
          if (!items.length) throw await anchorNotFound(context, '定位锚点', anchorText)
          // 包裹整段（Paragraph.insertContentControl，比 Range 级更明确支持），锚点定位所在段落
          const paragraph = items[0].paragraphs.getFirst()
          const cc = paragraph.insertContentControl()
          cc.tag = tag
          if (args.title) cc.title = String(args.title)
          await context.sync()
          return { inserted: true, tag }
        })
      }
      const ccs = context.document.contentControls.getByTag(tag)
      ccs.load('items')
      await context.sync()
      if (!ccs.items.length) throw new Error(`未找到 tag 为 ${tag} 的内容控件`)
      const cc = ccs.items[0]
      if (action === 'read') {
        cc.load('text,title,tag')
        await context.sync()
        return { tag, title: cc.title, text: cc.text }
      }
      if (action === 'set_text') {
        const text = args.text == null ? '' : String(args.text)
        return withTracking(context, async () => {
          cc.insertText(text, Word.InsertLocation.replace)
          await context.sync()
          return { updated: true, tag }
        })
      }
      if (action === 'delete') {
        const keepContent = !!args.keepContent
        return withTracking(context, async () => {
          cc.delete(keepContent)
          await context.sync()
          return { deleted: true, tag, keepContent }
        })
      }
      throw new Error(`action 值非法：${args.action}`)
    })
  },

  // ==================== 文档属性（批次 9，set_document_properties，WordApi 1.3） ====================

  async set_document_properties(args) {
    return Word.run(async (context) => {
      const props = context.document.properties
      const applied = {}
      if (args.title != null) { props.title = String(args.title); applied.title = args.title }
      if (args.subject != null) { props.subject = String(args.subject); applied.subject = args.subject }
      if (args.author != null) { props.author = String(args.author); applied.author = args.author }
      if (args.keywords != null) { props.keywords = String(args.keywords); applied.keywords = args.keywords }
      if (args.comments != null) { props.comments = String(args.comments); applied.comments = args.comments }
      if (args.category != null) { props.category = String(args.category); applied.category = args.category }
      await context.sync()
      return { applied }
    })
  },

  // ==================== Excel（excel_*，宿主须为 Excel） ====================

  async excel_get_range(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      sheet.load('name')
      const range = rangeAddress
        ? sheet.getRange(rangeAddress)
        : sheet.getUsedRangeOrNullObject(true)
      range.load('values,address,rowCount,columnCount,isNullObject')
      await context.sync()
      if (range.isNullObject) {
        return { sheet: sheet.name, address: '', rows: 0, cols: 0, values: [], note: '工作表为空' }
      }
      let values = range.values
      let truncated = false
      if (values.length > MAX_EXCEL_RESULT_ROWS) {
        values = values.slice(0, MAX_EXCEL_RESULT_ROWS)
        truncated = true
      }
      return {
        sheet: sheet.name,
        address: range.address,
        rows: range.rowCount,
        cols: range.columnCount,
        values,
        truncated
      }
    })
  },

  async excel_set_values(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const values = args.values
    if (!rangeAddress) throw new Error('区域地址不能为空')
    if (!Array.isArray(values) || !values.length || !Array.isArray(values[0])) {
      throw new Error('values 必须是非空二维数组')
    }
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      let range = sheet.getRange(rangeAddress)
      range.load('rowCount,columnCount,address')
      await context.sync()
      const rows = values.length
      const cols = values[0].length
      if (range.rowCount === 1 && range.columnCount === 1 && (rows > 1 || cols > 1)) {
        // 单元格起点：按 values 尺寸向右下展开
        range = range.getResizedRange(rows - 1, cols - 1)
      } else if (range.rowCount !== rows || range.columnCount !== cols) {
        throw new Error(`区域尺寸（${range.rowCount}x${range.columnCount}）与 values 尺寸（${rows}x${cols}）不一致`)
      }
      range.values = values
      range.load('address')
      await context.sync()
      return { written: rows * cols, address: range.address }
    })
  },

  async excel_search(args) {
    const query = String(args.query || '')
    const sheetName = String(args.sheetName || '')
    if (!query) throw new Error('查找文本不能为空')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      sheet.load('name')
      const used = sheet.getUsedRangeOrNullObject(true)
      used.load('values,rowIndex,columnIndex,isNullObject')
      await context.sync()
      if (used.isNullObject) return { sheet: sheet.name, count: 0, matches: [] }
      const needle = query.toLowerCase()
      const matches = []
      let count = 0
      for (let r = 0; r < used.values.length; r++) {
        for (let c = 0; c < used.values[r].length; c++) {
          const cell = used.values[r][c]
          if (cell == null) continue
          const text = String(cell)
          if (text.toLowerCase().includes(needle)) {
            count++
            if (matches.length < MAX_SEARCH_HITS) {
              matches.push({ address: cellAddress(used.rowIndex + r, used.columnIndex + c), value: text.slice(0, 500) })
            }
          }
        }
      }
      return { sheet: sheet.name, count, shown: matches.length, matches }
    })
  },

  // ==================== Excel 格式/结构（批次6，excel_*） ====================

  async excel_format_cells(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(rangeAddress)
      const applied = {}
      if (args.fontName) { range.format.font.name = String(args.fontName); applied.fontName = args.fontName }
      if (args.fontSize != null) { range.format.font.size = Number(args.fontSize); applied.fontSize = args.fontSize }
      if (args.bold != null) { range.format.font.bold = !!args.bold; applied.bold = !!args.bold }
      if (args.italic != null) { range.format.font.italic = !!args.italic; applied.italic = !!args.italic }
      if (args.fontColor) { range.format.font.color = String(args.fontColor); applied.fontColor = args.fontColor }
      if (args.fillColor) { range.format.fill.color = String(args.fillColor); applied.fillColor = args.fillColor }
      if (args.horizontalAlignment) {
        range.format.horizontalAlignment = toEnumValue(EXCEL_H_ALIGN, args.horizontalAlignment, 'horizontalAlignment')
        applied.horizontalAlignment = String(args.horizontalAlignment).trim().toLowerCase()
      }
      if (args.verticalAlignment) {
        range.format.verticalAlignment = toEnumValue(EXCEL_V_ALIGN, args.verticalAlignment, 'verticalAlignment')
        applied.verticalAlignment = String(args.verticalAlignment).trim().toLowerCase()
      }
      if (args.wrapText != null) { range.format.wrapText = !!args.wrapText; applied.wrapText = !!args.wrapText }
      if (args.numberFormat) {
        range.load('rowCount,columnCount')
        await context.sync()
        const fmt = String(args.numberFormat)
        range.numberFormat = Array.from({ length: range.rowCount }, () => Array.from({ length: range.columnCount }, () => fmt))
        applied.numberFormat = fmt
      }
      range.load('address')
      await context.sync()
      return { address: range.address, applied }
    })
  },

  async excel_set_borders(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const borders = String(args.borders || '').trim().toLowerCase()
    if (borders !== 'none' && !EXCEL_BORDER_LOCATIONS[borders]) {
      throw new Error(`borders 值非法：${args.borders}（合法值：all/outside/inside/none）`)
    }
    const weight = EXCEL_BORDER_WEIGHTS[String(args.style || 'thin').trim().toLowerCase()] || 'Thin'
    const color = String(args.color || '#000000')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(rangeAddress)
      const ids = borders === 'none' ? EXCEL_BORDER_LOCATIONS.all : EXCEL_BORDER_LOCATIONS[borders]
      for (const id of ids) {
        const border = range.format.borders.getItem(id)
        border.style = borders === 'none' ? 'None' : 'Continuous'
        if (borders !== 'none') {
          border.weight = weight
          border.color = color
        }
      }
      range.load('address')
      await context.sync()
      const result = { address: range.address, borders }
      if (borders !== 'none') {
        result.style = String(args.style || 'thin').trim().toLowerCase()
        result.color = color
      }
      return result
    })
  },

  async excel_edit_rows_cols(args) {
    const sheetName = String(args.sheetName || '')
    const action = String(args.action || '').trim().toLowerCase()
    if (!EXCEL_EDIT_ROWS_COLS_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：${EXCEL_EDIT_ROWS_COLS_ACTIONS.join('/')}）`)
    }
    let index = Math.floor(Number(args.index))
    if (!Number.isFinite(index) || index < 0) throw new Error('index 不能为负')
    let count = args.count == null ? 1 : Math.floor(Number(args.count))
    if (!Number.isFinite(count) || count < 1) count = 1
    const needsSize = action === 'set_width' || action === 'set_height'
    let size = null
    if (needsSize) {
      size = Number(args.size)
      if (!Number.isFinite(size) || size <= 0) throw new Error('size 须为正数')
      if (!excelApiSupported('1.2')) {
        throw new Error('当前 Excel 版本不支持设置行高/列宽（需要 ExcelApi 1.2）')
      }
    }
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const isRowAction = action === 'insert_rows' || action === 'delete_rows' || action === 'set_height'
      const range = isRowAction
        ? sheet.getRange(`${index + 1}:${index + count}`)
        : sheet.getRange(`${columnLetter(index)}:${columnLetter(index + count - 1)}`)
      const result = { action, index, count }
      if (action === 'insert_rows') range.insert(Excel.InsertShiftDirection.down)
      else if (action === 'delete_rows') range.delete(Excel.DeleteShiftDirection.up)
      else if (action === 'insert_cols') range.insert(Excel.InsertShiftDirection.right)
      else if (action === 'delete_cols') range.delete(Excel.DeleteShiftDirection.left)
      else if (action === 'set_width') { range.format.columnWidth = size; result.size = size }
      else if (action === 'set_height') { range.format.rowHeight = size; result.size = size }
      await context.sync()
      return result
    })
  },

  async excel_merge_cells(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const action = String(args.action || '').trim().toLowerCase()
    if (!EXCEL_MERGE_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：merge/unmerge）`)
    }
    if (!excelApiSupported('1.2')) {
      throw new Error('当前 Excel 版本不支持合并/取消合并单元格（需要 ExcelApi 1.2）')
    }
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(rangeAddress)
      if (action === 'merge') range.merge()
      else range.unmerge()
      range.load('address')
      await context.sync()
      return { address: range.address, action }
    })
  },

  async excel_sort_range(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const keyColumn = Math.floor(Number(args.keyColumn))
    if (!Number.isFinite(keyColumn) || keyColumn < 0) throw new Error('keyColumn 不能为负')
    const ascending = args.ascending !== false
    const hasHeader = !!args.hasHeader
    if (!excelApiSupported('1.2')) {
      throw new Error('当前 Excel 版本不支持区域排序（需要 ExcelApi 1.2）')
    }
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(rangeAddress)
      range.sort.apply([{ key: keyColumn, ascending }], false, hasHeader)
      range.load('address')
      await context.sync()
      return { address: range.address, keyColumn, ascending, hasHeader }
    })
  },

  async excel_manage_sheets(args) {
    const action = String(args.action || '').trim().toLowerCase()
    if (!EXCEL_SHEET_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：${EXCEL_SHEET_ACTIONS.join('/')}）`)
    }
    const sheetName = String(args.sheetName || '')
    if (action !== 'add' && !sheetName) throw new Error('sheetName 不能为空')
    return Excel.run(async (context) => {
      const worksheets = context.workbook.worksheets
      const result = { action }
      let target
      if (action === 'add') {
        target = worksheets.add(sheetName || undefined)
      } else {
        if (action === 'delete') {
          worksheets.load('items')
          await context.sync()
          if (worksheets.items.length <= 1) {
            throw new Error('无法删除：工作簿至少要保留一张工作表')
          }
        }
        target = worksheets.getItem(sheetName)
      }
      if (action === 'rename') {
        const newName = String(args.newName || '')
        if (!newName) throw new Error('newName 不能为空')
        target.name = newName
      } else if (action === 'delete') {
        target.delete()
      } else if (action === 'move') {
        const position = Math.floor(Number(args.position))
        if (!Number.isFinite(position) || position < 0) throw new Error('position 不能为负')
        target.position = position
      } else if (action === 'activate') {
        target.activate()
      }
      if (action === 'delete') {
        await context.sync()
      } else {
        target.load('name,position')
        await context.sync()
        result.name = target.name
        result.position = target.position
      }
      return result
    })
  },

  async excel_freeze_panes(args) {
    const action = String(args.action || '').trim().toLowerCase()
    if (!EXCEL_FREEZE_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：${EXCEL_FREEZE_ACTIONS.join('/')}）`)
    }
    if (!excelApiSupported('1.7')) {
      throw new Error('当前 Excel 版本不支持冻结窗格（需要 ExcelApi 1.7）')
    }
    const sheetName = String(args.sheetName || '')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      if (action === 'freeze_rows' || action === 'freeze_cols') {
        let count = Math.floor(Number(args.count))
        if (!Number.isFinite(count) || count < 1) count = 1
        if (action === 'freeze_rows') sheet.freezePanes.freezeRows(count)
        else sheet.freezePanes.freezeColumns(count)
        await context.sync()
        return { action, count }
      }
      if (action === 'freeze_at') {
        const cellAddr = String(args.cellAddress || '')
        if (!cellAddr) throw new Error('cellAddress 不能为空')
        sheet.freezePanes.freezeAt(sheet.getRange(cellAddr))
        await context.sync()
        return { action, cellAddress: cellAddr }
      }
      sheet.freezePanes.unfreeze()
      await context.sync()
      return { action }
    })
  },

  async excel_set_formulas(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const formulas = args.formulas
    if (!rangeAddress) throw new Error('区域地址不能为空')
    if (!Array.isArray(formulas) || !formulas.length || !Array.isArray(formulas[0])) {
      throw new Error('formulas 必须是非空二维数组')
    }
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      let range = sheet.getRange(rangeAddress)
      range.load('rowCount,columnCount,address')
      await context.sync()
      const rows = formulas.length
      const cols = formulas[0].length
      if (range.rowCount === 1 && range.columnCount === 1 && (rows > 1 || cols > 1)) {
        range = range.getResizedRange(rows - 1, cols - 1)
      } else if (range.rowCount !== rows || range.columnCount !== cols) {
        throw new Error(`区域尺寸（${range.rowCount}x${range.columnCount}）与 formulas 尺寸（${rows}x${cols}）不一致`)
      }
      range.formulas = formulas
      range.load('address,values,rowIndex,columnIndex')
      await context.sync()
      const formulaErrors = []
      const values = range.values || []
      for (let r = 0; r < values.length; r++) {
        const row = values[r] || []
        for (let c = 0; c < row.length; c++) {
          const v = row[c]
          if (typeof v === 'string' && v.startsWith('#')) {
            formulaErrors.push({ address: cellAddress(range.rowIndex + r, range.columnIndex + c), value: v })
          }
        }
      }
      const result = { written: rows * cols, address: range.address }
      if (formulaErrors.length) result.formulaErrors = formulaErrors
      return result
    })
  },

  async excel_get_overview() {
    return Excel.run(async (context) => {
      const worksheets = context.workbook.worksheets
      const active = worksheets.getActiveWorksheet()
      active.load('name')
      worksheets.load('items/name')
      await context.sync()
      const sheets = worksheets.items
      const usedRanges = sheets.map((sheet) => sheet.getUsedRangeOrNullObject(true))
      usedRanges.forEach((u) => u.load('address,rowCount,columnCount,isNullObject'))
      await context.sync()
      const activeName = active.name
      const result = sheets.map((sheet, i) => {
        const u = usedRanges[i]
        return {
          name: sheet.name,
          active: sheet.name === activeName,
          usedRange: u.isNullObject ? null : { address: u.address, rows: u.rowCount, cols: u.columnCount }
        }
      })
      return { sheetCount: sheets.length, sheets: result }
    })
  },

  async excel_select_range(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      if (sheetName) sheet.activate()
      const range = sheet.getRange(rangeAddress)
      range.select()
      range.load('address')
      await context.sync()
      return { address: range.address }
    })
  },

  async excel_set_autofilter(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const action = String(args.action || '').trim().toLowerCase()
    if (!EXCEL_AUTOFILTER_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：apply/clear/remove）`)
    }
    if (action === 'apply' && !rangeAddress) throw new Error('apply 需要 rangeAddress')
    if (!excelApiSupported('1.9')) {
      throw new Error('当前 Excel 版本不支持自动筛选（需要 ExcelApi 1.9）')
    }
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      if (action === 'apply') {
        sheet.autoFilter.apply(sheet.getRange(rangeAddress))
      } else if (action === 'clear') {
        sheet.autoFilter.clearCriteria()
      } else {
        sheet.autoFilter.remove()
      }
      await context.sync()
      const result = { action }
      if (action === 'apply') result.rangeAddress = rangeAddress
      return result
    })
  },

  async excel_conditional_format(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const action = String(args.action || 'apply').trim().toLowerCase()
    if (!excelApiSupported('1.6')) {
      throw new Error('当前 Excel 版本不支持条件格式（需要 ExcelApi 1.6）')
    }
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(rangeAddress)
      if (action === 'clearall') {
        range.conditionalFormats.clearAll()
        await context.sync()
        return { action: 'clearAll' }
      }
      // apply：每次先清空该区域现有规则再套用新规则，不叠加（与桌面端 sheet_conditional_format 同口径）
      range.conditionalFormats.clearAll()
      const ruleType = String(args.ruleType || '').trim().toLowerCase()
      const officeType = EXCEL_CF_RULE_TYPES[ruleType]
      if (!officeType) throw new Error(`ruleType 值非法：${args.ruleType}（合法值：cellValue/colorScale）`)
      const cf = range.conditionalFormats.add(officeType)
      if (ruleType === 'cellvalue') {
        const operator = EXCEL_CF_OPERATORS[String(args.operator || '').trim().toLowerCase()]
        if (!operator) throw new Error(`operator 值非法：${args.operator}（合法值：greaterThan/lessThan/between/equalTo）`)
        const rule = { operator, formula1: String(args.value1) }
        if (operator === 'Between') rule.formula2 = String(args.value2)
        cf.cellValue.rule = rule
        cf.cellValue.format.fill.color = String(args.fillColor || '#FFC7CE')
      } else {
        cf.colorScale.criteria = EXCEL_CF_DEFAULT_COLOR_SCALE
      }
      await context.sync()
      return { action: 'apply', ruleType }
    })
  },

  // ==================== Excel 批注（批次 9，excel_*，ExcelApi 1.10） ====================

  async excel_add_comment(args) {
    const sheetName = String(args.sheetName || '')
    const cellAddress = String(args.cellAddress || '')
    const comment = String(args.comment || '')
    if (!cellAddress) throw new Error('cellAddress 不能为空')
    if (!comment) throw new Error('批注内容不能为空')
    if (!excelApiSupported('1.10')) throw new Error('当前 Excel 版本不支持批注（需要 ExcelApi 1.10）')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(cellAddress)
      context.workbook.comments.add(range, comment)
      await context.sync()
      return { cellAddress, added: true }
    })
  },

  async excel_get_comments(args) {
    const sheetName = String(args.sheetName || '')
    const scope = args.scope === 'workbook' ? 'workbook' : 'sheet'
    if (!excelApiSupported('1.10')) throw new Error('当前 Excel 版本不支持批注（需要 ExcelApi 1.10）')
    return Excel.run(async (context) => {
      const comments = scope === 'workbook'
        ? context.workbook.comments
        : resolveSheet(context, sheetName).comments
      comments.load('items')
      await context.sync()
      const items = comments.items
      items.forEach((c) => c.load('content,authorName,creationDate,resolved'))
      const locations = items.map((c) => c.getLocation())
      locations.forEach((r) => r.load(scope === 'workbook' ? 'address,worksheet/name' : 'address'))
      const repliesCols = items.map((c) => c.replies)
      repliesCols.forEach((rc) => rc.load('items'))
      await context.sync()
      repliesCols.forEach((rc) => rc.items.forEach((rep) => rep.load('content,authorName,creationDate')))
      await context.sync()
      const result = items.map((c, i) => {
        const entry = {
          cellAddress: locations[i].address,
          content: c.content,
          author: c.authorName,
          createdAt: c.creationDate ? new Date(c.creationDate).toISOString() : null,
          resolved: !!c.resolved,
          replies: repliesCols[i].items.map((rep) => ({
            content: rep.content,
            author: rep.authorName,
            createdAt: rep.creationDate ? new Date(rep.creationDate).toISOString() : null
          }))
        }
        if (scope === 'workbook') entry.sheet = locations[i].worksheet.name
        return entry
      })
      return { count: result.length, comments: result }
    })
  },

  async excel_reply_comment(args) {
    const sheetName = String(args.sheetName || '')
    const cellAddress = String(args.cellAddress || '')
    const reply = String(args.reply || '')
    if (!cellAddress) throw new Error('cellAddress 不能为空')
    if (!reply) throw new Error('回复内容不能为空')
    if (!excelApiSupported('1.10')) throw new Error('当前 Excel 版本不支持批注（需要 ExcelApi 1.10）')
    return Excel.run(async (context) => {
      const comment = getExcelComment(context, sheetName, cellAddress)
      comment.replies.add(reply)
      await context.sync()
      return { cellAddress, replied: true }
    })
  },

  async excel_resolve_comment(args) {
    const sheetName = String(args.sheetName || '')
    const cellAddress = String(args.cellAddress || '')
    const resolved = args.resolved !== false
    if (!cellAddress) throw new Error('cellAddress 不能为空')
    if (!excelApiSupported('1.10')) throw new Error('当前 Excel 版本不支持批注（需要 ExcelApi 1.10）')
    return Excel.run(async (context) => {
      const comment = getExcelComment(context, sheetName, cellAddress)
      comment.resolved = resolved
      await context.sync()
      return { cellAddress, resolved }
    })
  },

  async excel_delete_comment(args) {
    const sheetName = String(args.sheetName || '')
    const cellAddress = String(args.cellAddress || '')
    if (!cellAddress) throw new Error('cellAddress 不能为空')
    if (!excelApiSupported('1.10')) throw new Error('当前 Excel 版本不支持批注（需要 ExcelApi 1.10）')
    return Excel.run(async (context) => {
      const comment = getExcelComment(context, sheetName, cellAddress)
      comment.delete()
      await context.sync()
      return { cellAddress, deleted: true }
    })
  },

  // ==================== Excel 数据验证（批次 9，excel_set_data_validation，ExcelApi 1.8） ====================

  async excel_set_data_validation(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const action = String(args.action || 'apply').trim().toLowerCase()
    if (!rangeAddress) throw new Error('区域地址不能为空')
    if (!excelApiSupported('1.8')) throw new Error('当前 Excel 版本不支持数据验证（需要 ExcelApi 1.8）')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(rangeAddress)
      if (action === 'clear') {
        range.dataValidation.clear()
        await context.sync()
        return { action: 'clear' }
      }
      const type = String(args.type || '').trim().toLowerCase()
      let rule
      if (type === 'list') {
        const source = String(args.listSource || '')
        if (!source) throw new Error('type=list 时 listSource 不能为空')
        rule = { list: { inCellDropDown: true, source } }
      } else if (type === 'wholenumber' || type === 'date') {
        const operator = EXCEL_DV_OPERATORS[String(args.operator || '').trim().toLowerCase()]
        if (!operator) throw new Error(`operator 值非法：${args.operator}`)
        const basic = { formula1: args.value1, operator }
        if (operator === 'Between') basic.formula2 = args.value2
        rule = type === 'wholenumber' ? { wholeNumber: basic } : { date: basic }
      } else {
        throw new Error(`type 值非法：${args.type}（合法值：wholeNumber/list/date）`)
      }
      range.dataValidation.rule = rule
      range.load('address')
      await context.sync()
      return { action: 'apply', type, address: range.address }
    })
  },

  // ==================== Excel 图表（批次 9，excel_add_chart，ExcelApi 1.1） ====================

  async excel_add_chart(args) {
    const sheetName = String(args.sheetName || '')
    const dataRangeAddress = String(args.dataRangeAddress || '')
    if (!dataRangeAddress) throw new Error('数据源区域不能为空')
    const chartType = EXCEL_CHART_TYPES[String(args.chartType || '').trim().toLowerCase()]
    if (!chartType) throw new Error(`chartType 值非法：${args.chartType}（合法值：column/line/pie/bar）`)
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(dataRangeAddress)
      const chart = sheet.charts.add(chartType, range, Excel.ChartSeriesBy.auto)
      if (args.title) {
        chart.title.text = String(args.title)
        chart.title.visible = true
      }
      chart.load('name')
      await context.sync()
      return { added: true, name: chart.name, chartType: String(args.chartType).trim().toLowerCase() }
    })
  },

  // ==================== Excel 命名区域（批次 9，excel_define_name，ExcelApi 1.1） ====================

  async excel_define_name(args) {
    const sheetName = String(args.sheetName || '')
    const action = String(args.action || '').trim().toLowerCase()
    const name = String(args.name || '')
    if (!name) throw new Error('name 不能为空')
    if (action !== 'add' && action !== 'remove') throw new Error(`action 值非法：${args.action}（合法值：add/remove）`)
    return Excel.run(async (context) => {
      if (action === 'add') {
        const rangeAddress = String(args.rangeAddress || '')
        if (!rangeAddress) throw new Error('add 需要 rangeAddress')
        const sheet = resolveSheet(context, sheetName)
        const range = sheet.getRange(rangeAddress)
        context.workbook.names.add(name, range)
        await context.sync()
        return { action: 'add', name }
      }
      const item = context.workbook.names.getItemOrNullObject(name)
      item.load('isNullObject')
      await context.sync()
      if (item.isNullObject) throw new Error(`未找到命名区域：${name}`)
      item.delete()
      await context.sync()
      return { action: 'remove', name }
    })
  },

  // ==================== Excel 工作表保护（批次 9，excel_protect_sheet，ExcelApi 1.2/1.7） ====================

  async excel_protect_sheet(args) {
    const sheetName = String(args.sheetName || '')
    const action = String(args.action || '').trim().toLowerCase()
    const password = args.password ? String(args.password) : undefined
    if (action !== 'protect' && action !== 'unprotect') throw new Error(`action 值非法：${args.action}（合法值：protect/unprotect）`)
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      if (action === 'protect') {
        sheet.protection.protect(undefined, password)
      } else {
        sheet.protection.unprotect(password)
      }
      await context.sync()
      return { action }
    })
  },

  // ==================== Excel 行列分组（批次 9，excel_group_rows_cols，ExcelApi 1.10） ====================

  async excel_group_rows_cols(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const action = String(args.action || '').trim().toLowerCase()
    const by = String(args.by || '').trim().toLowerCase()
    if (!rangeAddress) throw new Error('rangeAddress 不能为空')
    if (action !== 'group' && action !== 'ungroup') throw new Error(`action 值非法：${args.action}（合法值：group/ungroup）`)
    if (by !== 'rows' && by !== 'cols') throw new Error(`by 值非法：${args.by}（合法值：rows/cols）`)
    if (!excelApiSupported('1.10')) throw new Error('当前 Excel 版本不支持分组（需要 ExcelApi 1.10）')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const range = sheet.getRange(rangeAddress)
      const groupOption = by === 'rows' ? Excel.GroupOption.byRows : Excel.GroupOption.byColumns
      if (action === 'group') range.group(groupOption)
      else range.ungroup(groupOption)
      await context.sync()
      return { action, by }
    })
  },

  // ==================== Excel 透视表（批次 9，excel_add_pivot_table，ExcelApi 1.8） ====================

  async excel_add_pivot_table(args) {
    const sheetName = String(args.sheetName || '')
    const sourceRangeAddress = String(args.sourceRangeAddress || '')
    const destinationCellAddress = String(args.destinationCellAddress || '')
    const rowFields = Array.isArray(args.rowFields) ? args.rowFields : []
    const valueFields = Array.isArray(args.valueFields) ? args.valueFields : []
    if (!sourceRangeAddress) throw new Error('sourceRangeAddress 不能为空')
    if (!destinationCellAddress) throw new Error('destinationCellAddress 不能为空')
    if (!rowFields.length) throw new Error('rowFields 不能为空')
    if (!valueFields.length) throw new Error('valueFields 不能为空')
    if (!excelApiSupported('1.8')) throw new Error('当前 Excel 版本不支持透视表（需要 ExcelApi 1.8）')
    return Excel.run(async (context) => {
      const sheet = resolveSheet(context, sheetName)
      const source = sheet.getRange(sourceRangeAddress)
      const destination = sheet.getRange(destinationCellAddress)
      const name = args.pivotName ? String(args.pivotName) : `PivotTable_${Date.now()}`
      const pivot = sheet.pivotTables.add(name, source, destination)
      pivot.load('name')
      await context.sync()
      for (const field of rowFields) {
        const hierarchy = pivot.hierarchies.getItem(String(field))
        pivot.rowHierarchies.add(hierarchy)
      }
      for (const field of valueFields) {
        const hierarchy = pivot.hierarchies.getItem(String(field))
        pivot.dataHierarchies.add(hierarchy)
      }
      await context.sync()
      return { added: true, name: pivot.name, rowFields, valueFields }
    })
  },

  // ==================== PowerPoint（ppt_*，宿主须为 PowerPoint） ====================

  async ppt_get_slides() {
    requirePptTextApi()
    return PowerPoint.run(async (context) => {
      const frames = await loadPptTextFrames(context)
      const slides = frames.map((slideFrames, i) => ({
        slide: i + 1,
        texts: slideFrames
          .filter((tf) => !tf.isNullObject && tf.hasText)
          .map((tf) => (tf.textRange.text || '').trim())
          .filter(Boolean)
      }))
      return { slideCount: slides.length, slides }
    })
  },

  async ppt_replace_text(args) {
    const searchText = String(args.searchText || '')
    const replaceText = args.replaceText == null ? '' : String(args.replaceText)
    if (!searchText) throw new Error('查找文本不能为空')
    requirePptTextApi()
    return PowerPoint.run(async (context) => {
      const frames = await loadPptTextFrames(context)
      let replaced = 0
      const touchedSlides = []
      frames.forEach((slideFrames, i) => {
        let slideTouched = false
        for (const tf of slideFrames) {
          if (tf.isNullObject || !tf.hasText) continue
          const text = tf.textRange.text || ''
          if (!text.includes(searchText)) continue
          replaced += text.split(searchText).length - 1
          tf.textRange.text = text.split(searchText).join(replaceText)
          slideTouched = true
        }
        if (slideTouched) touchedSlides.push(i + 1)
      })
      if (!replaced) {
        throw new Error('未找到目标文本，请确认 searchText 与幻灯片文本精确一致（可先用 ppt_get_slides 核对）')
      }
      await context.sync()
      return { replaced, slides: touchedSlides }
    })
  },

  async ppt_format_text(args) {
    const searchText = String(args.searchText || '')
    if (!searchText) throw new Error('查找文本不能为空')
    const font = {}
    if (args.fontName) font.name = String(args.fontName)
    if (args.fontSize != null) font.size = Number(args.fontSize)
    if (args.bold != null) font.bold = !!args.bold
    if (args.italic != null) font.italic = !!args.italic
    if (args.underline != null) font.underline = toEnumValue(PPT_UNDERLINE_TYPES, args.underline, 'underline')
    if (args.color) font.color = String(args.color)
    if (!Object.keys(font).length) {
      throw new Error('未给出任何格式参数（fontName/fontSize/bold/italic/underline/color 至少给一个）')
    }
    requirePptTextApi()
    return PowerPoint.run(async (context) => {
      const frames = await loadPptTextFrames(context)
      // 逐个 TextFrame 在其纯文本上找偏移，再用 getSubstring 切出精确子串设字体——
      // 不改变文本长度，找到的偏移不会因为落格而失效，不需要像 Word 的字符级修订那样从右到左应用。
      const targets = []
      outer:
      for (const slideFrames of frames) {
        for (const tf of slideFrames) {
          if (tf.isNullObject || !tf.hasText) continue
          const text = tf.textRange.text || ''
          // 归一化定位（dev-board#286）：命中区间是原文坐标，长度按命中原文算
          for (const h of findAllNormalized(text, searchText)) {
            targets.push({ tf, start: h.start, len: h.end - h.start })
            if (!args.applyToAll) break outer
          }
        }
      }
      if (!targets.length) {
        throw new Error('未找到目标文本，请确认 searchText 与幻灯片文本精确一致（可先用 ppt_get_slides 核对）')
      }
      for (const t of targets) {
        const sub = t.tf.textRange.getSubstring(t.start, t.len)
        applyProps(sub.font, font)
      }
      await context.sync()
      return { formatted: targets.length, applied: font }
    })
  },

  async ppt_add_slide(args) {
    const position = args.position != null ? Math.floor(Number(args.position)) : null
    const title = args.title ? String(args.title) : ''
    const body = args.body ? String(args.body) : ''
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      const countResult = slides.getCount()
      await context.sync()
      const beforeCount = countResult.value

      // slides.add()（PowerPointApi 1.3）总是追加到末尾——没有生产可用的"插到第 N 页"参数
      // （AddSlideOptions.index 文档标注 preview-only，不能用）。要挪位置只能追加后再用
      // Slide.moveTo（PowerPointApi 1.8，门槛更高）搬过去，旧宿主上退化为"留在末尾"。
      slides.add()
      await context.sync()

      const newSlide = slides.getItemAt(beforeCount)
      let finalPosition = beforeCount + 1
      let moved = false
      let note

      if (position != null) {
        const clamped = Math.max(1, Math.min(position, beforeCount + 1))
        if (clamped !== finalPosition) {
          if (pptApiSupported('1.8')) {
            newSlide.moveTo(clamped - 1)
            await context.sync()
            moved = true
            finalPosition = clamped
          } else {
            note = '当前 PowerPoint 版本不支持移动幻灯片位置（需要 PowerPointApi 1.8），已追加到演示文稿末尾'
          }
        }
      }

      let titleAdded = false
      let bodyAdded = false
      if (title || body) {
        if (!pptApiSupported('1.4')) {
          const result = { slideAdded: true, position: finalPosition, moved, titleAdded, bodyAdded }
          result.note = (note ? note + '；' : '') +
            '当前 PowerPoint 版本不支持插入文本框（需要 PowerPointApi 1.4），标题/正文未写入'
          return result
        }
        const shapes = newSlide.shapes
        if (title) {
          const box = shapes.addTextBox(title, { left: 40, top: 30, width: 600, height: 60 })
          box.textFrame.textRange.font.size = 28
          box.textFrame.textRange.font.bold = true
          titleAdded = true
        }
        if (body) {
          shapes.addTextBox(body, { left: 40, top: 110, width: 600, height: 300 })
          bodyAdded = true
        }
        await context.sync()
      }

      const result = { slideAdded: true, position: finalPosition, moved, titleAdded, bodyAdded }
      if (note) result.note = note
      return result
    })
  },

  async ppt_delete_slide(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      const countResult = slides.getCount()
      await context.sync()
      const count = countResult.value
      if (count <= 1) {
        throw new Error('演示文稿只剩一页，无法删除（PowerPoint 不允许空演示文稿）')
      }
      if (slideNumber > count) {
        throw new Error(`slideNumber ${slideNumber} 越界：演示文稿共 ${count} 页（页码从 1 开始）`)
      }
      const slide = slides.getItemAt(slideNumber - 1)
      slide.delete()
      await context.sync()
      return { deleted: true, slideNumber, remaining: count - 1 }
    })
  },

  async ppt_add_text_box(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    const text = String(args.text || '')
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    if (!text) throw new Error('文本框内容不能为空')
    requirePptTextApi()
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const box = slide.shapes.addTextBox(text, {
        left: args.left != null ? Number(args.left) : PPT_SHAPE_DEFAULTS.left,
        top: args.top != null ? Number(args.top) : PPT_SHAPE_DEFAULTS.top,
        width: args.width != null ? Number(args.width) : PPT_SHAPE_DEFAULTS.width,
        height: args.height != null ? Number(args.height) : PPT_SHAPE_DEFAULTS.height
      })
      const font = {}
      if (args.fontSize != null) font.size = Number(args.fontSize)
      if (args.bold != null) font.bold = !!args.bold
      if (args.color) font.color = String(args.color)
      if (Object.keys(font).length) applyProps(box.textFrame.textRange.font, font)
      await context.sync()
      return { added: true, slideNumber }
    })
  },

  async ppt_move_slide(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    const toPosition = Math.floor(Number(args.toPosition))
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    if (!Number.isFinite(toPosition) || toPosition < 1) throw new Error('toPosition 须为大于等于 1 的整数')
    if (!pptApiSupported('1.8')) {
      throw new Error('unsupported: 当前 PowerPoint 版本不支持移动幻灯片（需要 PowerPointApi 1.8，Microsoft 365 较新版本）')
    }
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const count = slides.items.length
      if (slideNumber > count) {
        throw new Error(`slideNumber ${slideNumber} 越界：演示文稿共 ${count} 页（页码从 1 开始）`)
      }
      const clamped = Math.max(1, Math.min(toPosition, count))
      const slide = slides.getItemAt(slideNumber - 1)
      slide.moveTo(clamped - 1)
      await context.sync()
      return { moved: true, from: slideNumber, to: clamped }
    })
  },

  async ppt_add_shape(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    const shapeType = toEnumValue(PPT_GEOMETRIC_SHAPE_TYPES, args.shapeType, 'shapeType')
    requirePptTextApi()
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const shape = slide.shapes.addGeometricShape(shapeType, {
        left: args.left != null ? Number(args.left) : PPT_SHAPE_DEFAULTS.left,
        top: args.top != null ? Number(args.top) : PPT_SHAPE_DEFAULTS.top,
        width: args.width != null ? Number(args.width) : 200,
        height: args.height != null ? Number(args.height) : 150
      })
      if (args.fillColor) shape.fill.setSolidColor(String(args.fillColor))
      await context.sync()
      return { added: true, slideNumber, shapeType: String(args.shapeType).trim().toLowerCase() }
    })
  },

  async ppt_get_slide_details(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    requirePptTextApi()
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const shapes = slide.shapes
      shapes.load('items/id,items/type,items/left,items/top,items/width,items/height')
      await context.sync()
      const items = shapes.items
      const frames = items.map((shape) => {
        const tf = shape.getTextFrameOrNullObject()
        tf.load('hasText,isNullObject')
        tf.textRange.load('text')
        return tf
      })
      await context.sync()
      const result = items.map((shape, i) => {
        const tf = frames[i]
        const hasText = !tf.isNullObject && tf.hasText
        return {
          id: shape.id,
          type: shape.type,
          left: shape.left,
          top: shape.top,
          width: shape.width,
          height: shape.height,
          text: hasText ? (tf.textRange.text || '').slice(0, 500) : ''
        }
      })
      return { slideNumber, shapeCount: result.length, shapes: result }
    })
  },

  async ppt_delete_shape(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    const shapeId = args.shapeId ? String(args.shapeId) : ''
    const textMatch = args.textMatch ? String(args.textMatch) : ''
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    if (!shapeId && !textMatch) throw new Error('shapeId 与 textMatch 须至少给一个')
    if (!shapeId && textMatch) requirePptTextApi() // 按文字定位要读 TextFrame，走 1.4 门槛；按 id 定位只需 1.3
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const shapes = slide.shapes
      shapes.load('items/id')
      await context.sync()
      let target
      if (shapeId) {
        target = shapes.items.find((s) => s.id === shapeId)
        if (!target) {
          throw new Error(`未找到 id 为 ${shapeId} 的形状（可先用 ppt_get_slide_details 核对）`)
        }
      } else {
        const frames = shapes.items.map((shape) => {
          const tf = shape.getTextFrameOrNullObject()
          tf.load('hasText,isNullObject')
          tf.textRange.load('text')
          return tf
        })
        await context.sync()
        const idx = frames.findIndex((tf) => !tf.isNullObject && tf.hasText && (tf.textRange.text || '') === textMatch)
        if (idx === -1) {
          throw new Error('未找到文字内容与 textMatch 精确一致的形状（可先用 ppt_get_slide_details 核对）')
        }
        target = shapes.items[idx]
      }
      const deletedId = target.id
      target.delete()
      await context.sync()
      return { deleted: true, slideNumber, shapeId: deletedId }
    })
  },

  // ==================== PPT 表格与超链接（批次 9） ====================

  async ppt_add_table(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    const rows = Array.isArray(args.rows) ? args.rows : null
    const rowCount = rows ? rows.length : Math.floor(Number(args.rowCount))
    const colCount = rows ? (rows[0] ? rows[0].length : 0) : Math.floor(Number(args.colCount))
    if (!rowCount || rowCount < 1 || !colCount || colCount < 1) throw new Error('表格行列数非法')
    requirePptTableApi()
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const shape = slide.shapes.addTable(rowCount, colCount)
      if (args.left != null) shape.left = Number(args.left)
      if (args.top != null) shape.top = Number(args.top)
      if (args.width != null) shape.width = Number(args.width)
      if (args.height != null) shape.height = Number(args.height)
      if (rows) {
        const table = shape.getTable()
        for (let r = 0; r < rowCount; r++) {
          for (let c = 0; c < colCount; c++) {
            const cell = table.getCellOrNullObject(r, c)
            cell.text = String(rows[r][c] == null ? '' : rows[r][c])
          }
        }
      }
      await context.sync()
      return { added: true, slideNumber, rows: rowCount, cols: colCount }
    })
  },

  async ppt_table_read(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    requirePptTableApi()
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const table = await getPptTableOrThrow(context, slide, args.shapeId)
      table.load('rowCount,columnCount,values')
      await context.sync()
      return { slideNumber, rowCount: table.rowCount, colCount: table.columnCount, cells: table.values }
    })
  },

  async ppt_table_set_cell(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    const row = Math.floor(Number(args.row))
    const col = Math.floor(Number(args.col))
    if (!Number.isFinite(row) || row < 0) throw new Error('row 不能为负')
    if (!Number.isFinite(col) || col < 0) throw new Error('col 不能为负')
    const text = args.text == null ? '' : String(args.text)
    requirePptTableApi()
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const table = await getPptTableOrThrow(context, slide, args.shapeId)
      const cell = table.getCellOrNullObject(row, col)
      cell.load('isNullObject')
      await context.sync()
      if (cell.isNullObject) throw new Error(`单元格 (${row},${col}) 不存在，请先用 office_ppt_table_read 核对`)
      cell.text = text
      await context.sync()
      return { slideNumber, row, col, updated: true }
    })
  },

  async ppt_set_hyperlink(args) {
    const slideNumber = Math.floor(Number(args.slideNumber))
    const searchText = String(args.searchText || '')
    const url = String(args.url || '')
    if (!Number.isFinite(slideNumber) || slideNumber < 1) throw new Error('slideNumber 须为大于等于 1 的整数')
    if (!searchText) throw new Error('查找文本不能为空')
    if (!url) throw new Error('url 不能为空')
    requirePptTextApi()
    requirePptHyperlinkApi()
    return PowerPoint.run(async (context) => {
      const slides = context.presentation.slides
      slides.load('items/$none')
      await context.sync()
      const slide = getSlideOrThrow(slides, slideNumber)
      const shapes = slide.shapes
      shapes.load('items')
      await context.sync()
      const frames = shapes.items.map((shape) => {
        const tf = shape.getTextFrameOrNullObject()
        tf.load('hasText,isNullObject')
        tf.textRange.load('text')
        return tf
      })
      await context.sync()
      for (const tf of frames) {
        if (tf.isNullObject || !tf.hasText) continue
        const text = tf.textRange.text || ''
        const hit = findAllNormalized(text, searchText)[0]
        if (!hit) continue
        const sub = tf.textRange.getSubstring(hit.start, hit.end - hit.start)
        sub.setHyperlink({ address: url })
        await context.sync()
        return { slideNumber, linked: true, url }
      }
      throw new Error('未找到目标文本，请确认 searchText 与幻灯片文本精确一致（可先用 office_ppt_get_slides 核对）')
    })
  }
}

/** excel_get_range 返回值的行数上限（防超长工具输出撑爆模型上下文） */
const MAX_EXCEL_RESULT_ROWS = 500

/** 按名取工作表；名为空取活动工作表 */
function resolveSheet(context, sheetName) {
  return sheetName
    ? context.workbook.worksheets.getItem(sheetName)
    : context.workbook.worksheets.getActiveWorksheet()
}

/** 0 起的行列号转 A1 地址 */
function cellAddress(rowIndex, colIndex) {
  let col = ''
  let n = colIndex + 1
  while (n > 0) {
    const rem = (n - 1) % 26
    col = String.fromCharCode(65 + rem) + col
    n = Math.floor((n - 1) / 26)
  }
  return col + (rowIndex + 1)
}

/* ==================== Excel 格式/结构（批次6，excel_*） ====================
 * Word 面走原生修订（changeTrackingMode），Excel 没有对应机制——这些工具写入即生效，
 * 误操作的安全网是 Ctrl+Z 与文档检查点（后端 fileEffect="MODIFIED" 已保证检查点触发）。
 */

/** 0 起的列号转字母（不含行号，供行列范围拼 A1 引用用，如 "C:E"） */
function columnLetter(colIndex) {
  let col = ''
  let n = colIndex + 1
  while (n > 0) {
    const rem = (n - 1) % 26
    col = String.fromCharCode(65 + rem) + col
    n = Math.floor((n - 1) / 26)
  }
  return col
}

/** ExcelApi 需求集守卫（merge/sort/columnWidth/rowHeight 属 1.2，freezePanes 属 1.7） */
function excelApiSupported(version) {
  try {
    return Office.context.requirements.isSetSupported('ExcelApi', version)
  } catch (e) {
    return false
  }
}

/** horizontalAlignment → Excel.HorizontalAlignment（本批次只开常用三种） */
const EXCEL_H_ALIGN = { left: 'Left', center: 'Center', right: 'Right' }
/** verticalAlignment → Excel.VerticalAlignment（Excel 的垂直居中叫 Center，不是 Middle） */
const EXCEL_V_ALIGN = { top: 'Top', middle: 'Center', bottom: 'Bottom' }

/** borders → 参与的 Excel.BorderIndex 集合（none 复用 all 的边去清空） */
const EXCEL_BORDER_LOCATIONS = {
  all: ['EdgeTop', 'EdgeBottom', 'EdgeLeft', 'EdgeRight', 'InsideHorizontal', 'InsideVertical'],
  outside: ['EdgeTop', 'EdgeBottom', 'EdgeLeft', 'EdgeRight'],
  inside: ['InsideHorizontal', 'InsideVertical']
}
/** style → Excel.BorderWeight */
const EXCEL_BORDER_WEIGHTS = { thin: 'Thin', medium: 'Medium', thick: 'Thick' }

const EXCEL_EDIT_ROWS_COLS_ACTIONS = ['insert_rows', 'delete_rows', 'insert_cols', 'delete_cols', 'set_width', 'set_height']
const EXCEL_MERGE_ACTIONS = ['merge', 'unmerge']
const EXCEL_SHEET_ACTIONS = ['add', 'rename', 'delete', 'move', 'activate']
const EXCEL_FREEZE_ACTIONS = ['freeze_rows', 'freeze_cols', 'freeze_at', 'unfreeze']
const EXCEL_AUTOFILTER_ACTIONS = ['apply', 'clear', 'remove']

/** ruleType 小写短名 → Excel.ConditionalFormatType */
const EXCEL_CF_RULE_TYPES = { cellvalue: 'CellValue', colorscale: 'ColorScale' }
/** operator 小写短名 → Excel.ConditionalCellValueOperator */
const EXCEL_CF_OPERATORS = { greaterthan: 'GreaterThan', lessthan: 'LessThan', between: 'Between', equalto: 'EqualTo' }
/** colorScale 默认三色刻度（低到高：红-黄-绿），与桌面端 sheet_conditional_format 视觉口径一致 */
const EXCEL_CF_DEFAULT_COLOR_SCALE = {
  minimum: { formula: null, type: 'LowestValue', color: '#F8696B' },
  midpoint: { formula: '50', type: 'Percent', color: '#FFEB84' },
  maximum: { formula: null, type: 'HighestValue', color: '#63BE7B' }
}

/* ==================== Excel 批注/校验/图表/命名区域/保护/分组/透视表（批次 9） ==================== */

/**
 * 定位 Excel 单元格上的批注线程：用 Range 对象（而非 "Sheet!A1" 限定字符串）传给
 * workbook.comments.getItemByCell，规避跨工作表的地址歧义。同步的构造，
 * 调用方仍需在 context.sync() 前完成本次批处理里的其余排队操作。
 */
function getExcelComment(context, sheetName, cellAddress) {
  const sheet = resolveSheet(context, sheetName)
  const range = sheet.getRange(cellAddress)
  return context.workbook.comments.getItemByCell(range)
}

/** operator 小写短名 → Excel.DataValidationOperator（与条件格式的 EXCEL_CF_OPERATORS 同款归一） */
const EXCEL_DV_OPERATORS = {
  between: 'Between', greaterthan: 'GreaterThan', lessthan: 'LessThan', equalto: 'EqualTo'
}

/** chartType 小写短名 → Excel.ChartType（v1 起步四种） */
const EXCEL_CHART_TYPES = { column: 'ColumnClustered', line: 'Line', pie: 'Pie', bar: 'BarClustered' }

/** 通用 PowerPoint API 需求集探测；version 形如 '1.4'/'1.8'，探测失败按不支持处理 */
function pptApiSupported(version) {
  try {
    return Office.context.requirements.isSetSupported('PowerPointApi', version)
  } catch (e) {
    return false
  }
}

/**
 * PowerPointApi 1.4 是本插件 PPT 面绝大多数命令的公共门槛：TextFrame/TextRange 读写、
 * Shape.left/top/width/height/type、addTextBox/addGeometricShape、ShapeFill 全在这一档
 * （Slide.delete 是 1.2、slides.add 是 1.3，门槛更低，不受此函数约束）。旧版宿主直接报错。
 */
function requirePptTextApi() {
  if (!pptApiSupported('1.4')) {
    throw new Error('unsupported: 当前 PowerPoint 版本不支持该操作（需要 PowerPointApi 1.4，Microsoft 365 较新版本）')
  }
}

/**
 * PPT 表格操作门槛（批次 9）：ShapeCollection.addTable / Shape.getTable / Table.rowCount|columnCount|values|
 * getCellOrNullObject 全在 PowerPointApi 1.8（TableRowCollection/TableColumnCollection 才是 1.9，
 * 本批次不枚举行列集合，1.8 已够用）。
 */
function requirePptTableApi() {
  if (!pptApiSupported('1.8')) {
    throw new Error('unsupported: 当前 PowerPoint 版本不支持表格操作（需要 PowerPointApi 1.8，Microsoft 365 较新版本）')
  }
}

/** TextRange.setHyperlink / HyperlinkCollection 属 PowerPointApi 1.10（批次 9） */
function requirePptHyperlinkApi() {
  if (!pptApiSupported('1.10')) {
    throw new Error('unsupported: 当前 PowerPoint 版本不支持超链接（需要 PowerPointApi 1.10，Microsoft 365 较新版本）')
  }
}

/** 定位一页幻灯片上的表格：shapeId 精确指定，或缺省取该页第一个 Table 类型形状（批次 9） */
async function getPptTableOrThrow(context, slide, shapeId) {
  const shapes = slide.shapes
  shapes.load('items/id,items/type')
  await context.sync()
  let target
  if (shapeId) {
    target = shapes.items.find((s) => s.id === shapeId)
    if (!target) throw new Error(`未找到 id 为 ${shapeId} 的形状（可先用 office_ppt_get_slide_details 核对）`)
  } else {
    target = shapes.items.find((s) => s.type === 'Table')
    if (!target) throw new Error('该页没有表格，请先用 office_ppt_add_table 插入或核对 shapeId')
  }
  return target.getTable()
}

/** PPT 字符格式下划线线型 → PowerPoint.ShapeFontUnderlineStyle（wave 映射为 Wavy） */
const PPT_UNDERLINE_TYPES = { none: 'None', single: 'Single', double: 'Double', dotted: 'Dotted', wave: 'Wavy' }

/** PPT 几何形状类型 → PowerPoint.GeometricShapeType（v1 起步三种） */
const PPT_GEOMETRIC_SHAPE_TYPES = { rectangle: 'Rectangle', ellipse: 'Ellipse', triangle: 'Triangle' }

/** 新增文本框/形状的默认位置尺寸（磅） */
const PPT_SHAPE_DEFAULTS = { left: 50, top: 50, width: 400, height: 100 }

/** 按 1 起页码取幻灯片；slides 须已 load('items/$none') 或等价并 sync 过 */
function getSlideOrThrow(slides, slideNumber) {
  const idx = slideNumber - 1
  if (idx < 0 || idx >= slides.items.length) {
    throw new Error(`slideNumber ${slideNumber} 越界：演示文稿共 ${slides.items.length} 页（页码从 1 开始）`)
  }
  return slides.items[idx]
}

/** 载入全部幻灯片各形状的 TextFrame（含 hasText 与 textRange.text），返回按页分组的数组 */
async function loadPptTextFrames(context) {
  const slides = context.presentation.slides
  slides.load('items')
  await context.sync()
  slides.items.forEach((slide) => slide.shapes.load('items'))
  await context.sync()
  const frames = slides.items.map((slide) =>
    slide.shapes.items.map((shape) => {
      const tf = shape.getTextFrameOrNullObject()
      tf.load('hasText,isNullObject')
      tf.textRange.load('text')
      return tf
    }))
  await context.sync()
  return frames
}

/** 每个 command 的固定显示名（对话流中的工具活动 chip；与后端 @ToolMeta displayName 对齐）
 *  按语言取字典（dev-board#150）：值来自 lib/i18n.js 的 cmd* key，随 currentLang 定死一次。 */
export const COMMAND_DISPLAY_NAMES = {
  get_text: t('cmdGetText'),
  get_selection: t('cmdGetSelection'),
  search: t('cmdSearch'),
  replace_text: t('cmdReplaceText'),
  insert_text: t('cmdInsertText'),
  add_comment: t('cmdAddComment'),
  format_text: t('cmdFormatText'),
  set_paragraph_format: t('cmdSetParagraphFormat'),
  get_formatting: t('cmdGetFormatting'),
  set_numbering: t('cmdSetNumbering'),
  format_table: t('cmdFormatTable'),
  apply_standard_format: t('cmdApplyStandardFormat'),
  insert_table: t('cmdInsertTable'),
  table_read: t('cmdTableRead'),
  table_set_cell: t('cmdTableSetCell'),
  table_add_row: t('cmdTableAddRow'),
  table_delete_row: t('cmdTableDeleteRow'),
  table_add_col: t('cmdTableAddCol'),
  table_delete_col: t('cmdTableDeleteCol'),
  insert_break: t('cmdInsertBreak'),
  set_hyperlink: t('cmdSetHyperlink'),
  edit_header_footer: t('cmdEditHeaderFooter'),
  get_comments: t('cmdGetComments'),
  reply_comment: t('cmdReplyComment'),
  resolve_comment: t('cmdResolveComment'),
  get_revisions: t('cmdGetRevisions'),
  accept_revision: t('cmdAcceptRevision'),
  reject_revision: t('cmdRejectRevision'),
  insert_footnote: t('cmdInsertFootnote'),
  insert_endnote: t('cmdInsertEndnote'),
  insert_image: t('cmdInsertImage'),
  apply_style: t('cmdApplyStyle'),
  manage_content_control: t('cmdManageContentControl'),
  set_document_properties: t('cmdSetDocumentProperties'),
  excel_get_range: t('cmdExcelGetRange'),
  excel_set_values: t('cmdExcelSetValues'),
  excel_search: t('cmdExcelSearch'),
  excel_format_cells: t('cmdExcelFormatCells'),
  excel_set_borders: t('cmdExcelSetBorders'),
  excel_edit_rows_cols: t('cmdExcelEditRowsCols'),
  excel_merge_cells: t('cmdExcelMergeCells'),
  excel_sort_range: t('cmdExcelSortRange'),
  excel_manage_sheets: t('cmdExcelManageSheets'),
  excel_freeze_panes: t('cmdExcelFreezePanes'),
  excel_set_formulas: t('cmdExcelSetFormulas'),
  excel_get_overview: t('cmdExcelGetOverview'),
  excel_select_range: t('cmdExcelSelectRange'),
  excel_set_autofilter: t('cmdExcelSetAutofilter'),
  excel_conditional_format: t('cmdExcelConditionalFormat'),
  excel_add_comment: t('cmdExcelAddComment'),
  excel_get_comments: t('cmdExcelGetComments'),
  excel_reply_comment: t('cmdExcelReplyComment'),
  excel_resolve_comment: t('cmdExcelResolveComment'),
  excel_delete_comment: t('cmdExcelDeleteComment'),
  excel_set_data_validation: t('cmdExcelSetDataValidation'),
  excel_add_chart: t('cmdExcelAddChart'),
  excel_define_name: t('cmdExcelDefineName'),
  excel_protect_sheet: t('cmdExcelProtectSheet'),
  excel_group_rows_cols: t('cmdExcelGroupRowsCols'),
  excel_add_pivot_table: t('cmdExcelAddPivotTable'),
  ppt_get_slides: t('cmdPptGetSlides'),
  ppt_replace_text: t('cmdPptReplaceText'),
  ppt_format_text: t('cmdPptFormatText'),
  ppt_add_slide: t('cmdPptAddSlide'),
  ppt_delete_slide: t('cmdPptDeleteSlide'),
  ppt_add_text_box: t('cmdPptAddTextBox'),
  ppt_move_slide: t('cmdPptMoveSlide'),
  ppt_add_shape: t('cmdPptAddShape'),
  ppt_get_slide_details: t('cmdPptGetSlideDetails'),
  ppt_delete_shape: t('cmdPptDeleteShape'),
  ppt_add_table: t('cmdPptAddTable'),
  ppt_table_read: t('cmdPptTableRead'),
  ppt_table_set_cell: t('cmdPptTableSetCell'),
  ppt_set_hyperlink: t('cmdPptSetHyperlink')
}

/** 每个 command 要求的宿主（与后端按 officeHost 的工具可见性过滤对齐） */
const COMMAND_HOSTS = {
  get_text: 'word',
  get_selection: 'word',
  search: 'word',
  replace_text: 'word',
  insert_text: 'word',
  add_comment: 'word',
  format_text: 'word',
  set_paragraph_format: 'word',
  get_formatting: 'word',
  set_numbering: 'word',
  format_table: 'word',
  apply_standard_format: 'word',
  insert_table: 'word',
  table_read: 'word',
  table_set_cell: 'word',
  table_add_row: 'word',
  table_delete_row: 'word',
  table_add_col: 'word',
  table_delete_col: 'word',
  insert_break: 'word',
  set_hyperlink: 'word',
  edit_header_footer: 'word',
  get_comments: 'word',
  reply_comment: 'word',
  resolve_comment: 'word',
  get_revisions: 'word',
  accept_revision: 'word',
  reject_revision: 'word',
  insert_footnote: 'word',
  insert_endnote: 'word',
  insert_image: 'word',
  apply_style: 'word',
  manage_content_control: 'word',
  set_document_properties: 'word',
  excel_get_range: 'excel',
  excel_set_values: 'excel',
  excel_search: 'excel',
  excel_format_cells: 'excel',
  excel_set_borders: 'excel',
  excel_edit_rows_cols: 'excel',
  excel_merge_cells: 'excel',
  excel_sort_range: 'excel',
  excel_manage_sheets: 'excel',
  excel_freeze_panes: 'excel',
  excel_set_formulas: 'excel',
  excel_get_overview: 'excel',
  excel_select_range: 'excel',
  excel_set_autofilter: 'excel',
  excel_conditional_format: 'excel',
  excel_add_comment: 'excel',
  excel_get_comments: 'excel',
  excel_reply_comment: 'excel',
  excel_resolve_comment: 'excel',
  excel_delete_comment: 'excel',
  excel_set_data_validation: 'excel',
  excel_add_chart: 'excel',
  excel_define_name: 'excel',
  excel_protect_sheet: 'excel',
  excel_group_rows_cols: 'excel',
  excel_add_pivot_table: 'excel',
  ppt_get_slides: 'powerpoint',
  ppt_replace_text: 'powerpoint',
  ppt_format_text: 'powerpoint',
  ppt_add_slide: 'powerpoint',
  ppt_delete_slide: 'powerpoint',
  ppt_add_text_box: 'powerpoint',
  ppt_move_slide: 'powerpoint',
  ppt_add_shape: 'powerpoint',
  ppt_get_slide_details: 'powerpoint',
  ppt_delete_shape: 'powerpoint',
  ppt_add_table: 'powerpoint',
  ppt_table_read: 'powerpoint',
  ppt_table_set_cell: 'powerpoint',
  ppt_set_hyperlink: 'powerpoint'
}

const HOST_LABELS = { word: 'Word', excel: 'Excel', powerpoint: 'PowerPoint' }

export function commandDisplayName(command) {
  return COMMAND_DISPLAY_NAMES[command] || t('cmdFallback', { command })
}

/**
 * 执行一条 office_command。永不 throw：一律返回 {ok:true, data} 或 {ok:false, error}。
 */
export async function executeOfficeCommand(command, args) {
  if (!officeAvailable()) {
    return { ok: false, error: 'Office 环境不可用：请在 Office 任务窗格中使用本插件' }
  }
  const handler = HANDLERS[command]
  if (!handler) {
    return { ok: false, error: `unsupported command: ${command}` }
  }
  const requiredHost = COMMAND_HOSTS[command]
  const host = detectHost()
  if (requiredHost && host !== requiredHost) {
    return {
      ok: false,
      error: `unsupported host: 该命令只在 ${HOST_LABELS[requiredHost]} 中可用（当前宿主：${HOST_LABELS[host] || '未知'}）`
    }
  }
  try {
    const data = await handler(args || {})
    return { ok: true, data: data == null ? {} : data }
  } catch (e) {
    const message = (e && e.message) || String(e)
    console.warn('[Addin] office_command 执行失败', command, e)
    return { ok: false, error: message }
  }
}
