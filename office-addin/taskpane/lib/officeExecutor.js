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

/** body.search 定位锚点，返回命中 Range 数组（未命中返回空数组） */
async function searchRanges(context, needle, matchCase) {
  const results = context.document.body.search(needle, { matchCase: !!matchCase })
  results.load('items')
  await context.sync()
  return results.items
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

/* ---- 律所标准格式 ---- */

/**
 * 律所标准格式常量。**三处同源**：桌面端 LOWA 的 office_thread.js `HOUSE`、
 * 后端 `DocxStyleHelper`（write_docx / AiDocxExportService 两条生成路径）、这里。
 * 数值必须逐字一致，改规范要三处一起改。
 */
const HOUSE = {
  fontAsian: '楷体_GB2312',
  fontWestern: 'Arial',
  bodyPt: 12,
  titlePt: 16,
  spaceAfterPt: 18,
  lineSpacingPt: 16,      // LOWA 侧是「最小值 16 磅」；Office.js 只有固定磅值行距（见下方 lineSpacingMode）
  firstLineIndentPt: 24,  // 首行缩进 2 字符 = 2 × 12 磅
  tablePt: 10
}

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
        const items = await searchRanges(context, searchText, true)
        if (!items.length) {
          throw new Error('未找到目标文本，请确认 searchText 与文档内容精确一致（可先用 search 命令核对）')
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
          const items = await searchRanges(context, anchorText, true)
          if (!items.length) {
            throw new Error('未找到锚点文本，请确认 anchorText 与文档内容精确一致')
          }
          const location = position === 'before' ? Word.InsertLocation.before : Word.InsertLocation.after
          items[0].insertText(text, location)
        } else {
          // 无锚点：落在用户当前光标/选区处（选区被替换，与光标插入语义一致）
          context.document.getSelection().insertText(text, Word.InsertLocation.replace)
        }
        await context.sync()
        return { inserted: true, anchored: !!anchorText, position: anchorText ? position : 'selection' }
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
        throw new Error('未找到批注目标文本，请确认 anchorText 与文档内容精确一致')
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
          throw new Error('未找到目标文本，请确认 anchorText 与文档内容精确一致（可先用 search 命令核对）')
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
          throw new Error('未找到目标文本，请确认 anchorText 与文档内容精确一致（可先用 search 命令核对）')
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
          throw new Error('未找到目标文本，请确认 anchorText 与文档内容精确一致（可先用 search 命令核对）')
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
          throw new Error('未找到锚点段落，请确认 anchorText 与文档内容精确一致（可先用 search 命令核对）')
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

/** PPT 文本读写依赖 PowerPointApi 1.4（TextFrame/TextRange），旧版宿主直接报错 */
function requirePptTextApi() {
  let supported = false
  try {
    supported = Office.context.requirements.isSetSupported('PowerPointApi', '1.4')
  } catch (e) { /* fallthrough */ }
  if (!supported) {
    throw new Error('unsupported: 当前 PowerPoint 版本不支持读写幻灯片文本（需要 PowerPointApi 1.4，Microsoft 365 较新版本）')
  }
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

/** 每个 command 的固定中文名（对话流中的工具活动 chip；与后端 @ToolMeta displayName 对齐） */
export const COMMAND_DISPLAY_NAMES = {
  get_text: '读取文档',
  get_selection: '读取选区',
  search: '查找文本',
  replace_text: '替换文本（修订）',
  insert_text: '插入文本（修订）',
  add_comment: '插入批注',
  format_text: '设置文字格式',
  set_paragraph_format: '设置段落格式',
  get_formatting: '读取格式',
  set_numbering: '设置自动编号',
  format_table: '设置表格格式',
  apply_standard_format: '套用标准格式',
  excel_get_range: '读取区域',
  excel_set_values: '写入区域',
  excel_search: '查找单元格',
  ppt_get_slides: '读取幻灯片',
  ppt_replace_text: '替换幻灯片文本'
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
  excel_get_range: 'excel',
  excel_set_values: 'excel',
  excel_search: 'excel',
  ppt_get_slides: 'powerpoint',
  ppt_replace_text: 'powerpoint'
}

const HOST_LABELS = { word: 'Word', excel: 'Excel', powerpoint: 'PowerPoint' }

export function commandDisplayName(command) {
  return COMMAND_DISPLAY_NAMES[command] || `文档操作（${command}）`
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
