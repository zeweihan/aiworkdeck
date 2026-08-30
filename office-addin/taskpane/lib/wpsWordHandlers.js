/**
 * WPS 文字宿主的 office_command 执行实现（33 个 Word 面命令）。
 *
 * 契约以 officeExecutor.js 为准绳：命令名、参数字段、返回值结构、错误文案腔调
 * 全部对齐 Office 面——两个家族对后端和模型呈现同一张工具表，行为差异只体现
 * 在返回值的说明字段里（如本宿主的 lineSpacingMode:'atLeast'、fontSplit:true）。
 *
 * API 依据：调研映射表 wps-word-api.md（open.wps.cn 文档 + wps-jsapi typings），
 * 对象模型为 VBA 同构的**同步**调用。不依赖 wps.Enum——本文件内置 VBA 同值的
 * 数值常量表（常量名保留 VBA 名以便对照）。
 *
 * 核心口径（与 Office 面的差异）：
 * - 定位统一走「全文快照 indexOf + Document.Range(start, end) 字符偏移直切」，
 *   绕开 Find 的 255 上限与不跨段限制；但偏移口径（内嵌对象/域/批注标记可能
 *   占位）是调研标注的头号真机验证项，所以**每次按偏移落笔前必须校验
 *   range.Text 与预期旧文一致**，不一致立即回退（整段替换或 Find 兜底）。
 * - 修改类命令包 withTracking：保存 Document.TrackRevisions → 置 true →
 *   finally 恢复；TrackRevisions 读写抛异常时降级直改并标 tracked:false。
 * - 批注/修订定位符：WPS 只有 1-based Index、无 GUID——对外 id 就是 0 起的
 *   序号字符串（与 index 同口径），每次操作前重新读集合（序号随接受/拒绝/
 *   删除塌缩重排，officeExecutor 批次 9 同款地雷）。
 *
 * 真机验证清单要点（按风险排序，详见调研文档末节）：
 *  1. 字符偏移一致性：doc.Range().Text 的 JS 索引 ↔ doc.Range(s,e) 映射
 *     （含表格 \x07、域、批注引用标记的占位）——本文件的逐笔校验是安全阀；
 *  2. Comment.Replies.Add 能否创建线程回复（已备 appendText 降级）；
 *  3. 修订开启下 .Text='' 删除的占位行为与右到左偏移稳定性；
 *  4. Find.Execute 位置传参在 JS 桥的兼容性（仅作偏移失准时的兜底路）；
 *  5. Font.Color（BGR）与 TextColor.RGB（RGB）两条颜色通路的字节序；
 *  6. 逐段/逐格循环的跨桥性能（500 段 apply_standard_format、大表读写）。
 */

import { minimalEdits } from './minimalEdit.js'
import { findAllNormalized, describeAnchorFailure } from './textMatch.js'
// 律所标准格式（HOUSE）单源：backend/src/main/resources/style-profiles/house-default.json
// 的字节副本，由 frontend/scripts/sync-house-profile.mjs 同步（与 officeExecutor 同一份）。
import houseProfile from './house-default.json' with { type: 'json' }

/* ==================== VBA 同值常量表（不依赖 wps.Enum） ==================== */
// 数值出处：wps-jsapi@1.0.5 typings，与 Word VBA 逐一相同（调研文档已抄录核对）。
// 注意：勿混入 WebOffice(docs-center) SDK 那套不同数值的枚举。

// MsoTriState / 特殊值
const msoTrue = -1
const msoFalse = 0
const wdUndefined = 9999999
// WdUnderline
const wdUnderlineNone = 0
const wdUnderlineSingle = 1
const wdUnderlineDouble = 3
const wdUnderlineDotted = 4
const wdUnderlineWavy = 11
// WdParagraphAlignment
const wdAlignParagraphLeft = 0
const wdAlignParagraphCenter = 1
const wdAlignParagraphRight = 2
const wdAlignParagraphJustify = 3
// WdBuiltinStyle
const wdStyleNormal = -1
const wdStyleHeading1 = -2
const wdStyleHeading2 = -3
const wdStyleHeading3 = -4
const wdStyleHeading4 = -5
// WdLineSpacing
const wdLineSpaceAtLeast = 3
// WdListGalleryType / WdListNumberStyle / WdListApplyTo / WdDefaultListBehavior
const wdNumberGallery = 2
const wdListNumberStyleSimpChinNum2 = 38 // 「一、二、三」样式
const wdListApplyToWholeList = 0
const wdWord9ListBehavior = 1
// WdFindWrap / WdReplace
const wdFindStop = 0
const wdReplaceOne = 1
// WdBreakType
const wdSectionBreakNextPage = 2
const wdPageBreak = 7
// WdHeaderFooterIndex
const wdHeaderFooterPrimary = 1
// WdRowAlignment
const wdAlignRowLeft = 0
const wdAlignRowCenter = 1
const wdAlignRowRight = 2
// WdLineStyle
const wdLineStyleNone = 0
const wdLineStyleSingle = 1
// WdAutoFitBehavior
const wdAutoFitWindow = 2
// WdBorderType（Borders.Item 的六个索引）
const wdBorderTop = -1
const wdBorderLeft = -2
const wdBorderBottom = -3
const wdBorderRight = -4
const wdBorderHorizontal = -5
const wdBorderVertical = -6
// WdContentControlType
const wdContentControlRichText = 0
// WdBuiltInProperty
const wdPropertyTitle = 1
const wdPropertySubject = 2
const wdPropertyAuthor = 3
const wdPropertyKeywords = 4
const wdPropertyComments = 5
const wdPropertyCategory = 18
// WdSelectionType
const wdSelectionIP = 1

/* ==================== 通用上限（与 officeExecutor 同口径） ==================== */

const MAX_TEXT_CHARS = 200_000
const MAX_SEARCH_HITS = 20
const MAX_NUMBERING_PARAGRAPHS = 200
const MAX_STANDARD_FORMAT_PARAGRAPHS = 500
const HOUSE_TITLE_MAX_CHARS = 50
const HOUSE_HEADING_MAX_CHARS = 40
const ANCHOR_FALLBACK_MIN_CHARS = 4

/* ==================== 入口与基础工具 ==================== */

/** 文字宿主 Application 入口（全局 wps.WpsApplication()），全文件唯一收敛点 */
function app() {
  const w = globalThis.wps
  if (!w || typeof w.WpsApplication !== 'function') {
    throw new Error('WPS 文字环境不可用：请在 WPS 文字任务窗格中使用本插件')
  }
  const a = w.WpsApplication()
  if (!a) throw new Error('WPS 文字环境不可用：请在 WPS 文字任务窗格中使用本插件')
  return a
}

/** 当前活动文档；没有打开文档时报错（官方原文：ActiveDocument 无文档会出错） */
function activeDoc() {
  let doc
  try {
    doc = app().ActiveDocument
  } catch (e) {
    doc = null
  }
  if (!doc) throw new Error('当前没有打开的文档')
  return doc
}

function truncate(text) {
  const s = text || ''
  return s.length > MAX_TEXT_CHARS
    ? { text: s.slice(0, MAX_TEXT_CHARS), truncated: true, totalChars: s.length }
    : { text: s, truncated: false, totalChars: s.length }
}

/** 全文快照。WPS/VBA 的段落符是 \r（Chr(13)），不做归一化——偏移定位必须用原文 */
function bodyText(doc) {
  return String(doc.Range().Text || '')
}

/** 模型给的锚点/插入文本可能带 \n，写入/比对前统一成 WPS 的段落符 \r */
function normalizeNewlines(s) {
  return String(s == null ? '' : s).replace(/\r\n/g, '\r').replace(/\n/g, '\r')
}

/**
 * 在开启 WPS 原生修订（Document.TrackRevisions）的前提下执行 fn，结束后恢复原值。
 * TrackRevisions 读或写抛异常（旧版/受限文档）时降级为直接修改，标 tracked:false
 * ——与 officeExecutor.withTracking 同契约。fn 为同步函数（WPS JSAPI 是同步桥）。
 */
function withTracking(doc, fn) {
  let prev = null
  let trackable = true
  try {
    prev = doc.TrackRevisions
    doc.TrackRevisions = true
  } catch (e) {
    trackable = false
  }
  if (!trackable) {
    return { ...fn(), tracked: false }
  }
  try {
    return { ...fn(), tracked: true }
  } finally {
    try {
      doc.TrackRevisions = prev
    } catch (e) { /* 恢复失败不掩盖主结果 */ }
  }
}

/**
 * 结构性删除（删行/删列）契约上 tracked:false（与 Office 面一致）。WPS 下若用户
 * 开着修订，删除会记成结构修订、被删行还留在表中导致连删错位——所以执行期临时
 * 关掉修订，结束后恢复。TrackRevisions 不可用时按原样直删。
 */
function withTrackingOff(doc, fn) {
  let prev = null
  let restorable = true
  try {
    prev = doc.TrackRevisions
    doc.TrackRevisions = false
  } catch (e) {
    restorable = false
  }
  try {
    return fn()
  } finally {
    if (restorable) {
      try {
        doc.TrackRevisions = prev
      } catch (e) { /* 恢复失败不掩盖主结果 */ }
    }
  }
}

/**
 * 关掉宿主重绘跑 fn，结束后恢复原值。WPS JSAPI 是同步跨进程桥，逐段循环里每一次
 * Font/Format 写入都会触发宿主重绘；关掉重绘是这类长循环里代价最低的一项优化
 * （零语义变化）。属性不可用时照常执行，不假装成功。fn 为同步函数。
 */
function withoutScreenUpdating(fn) {
  let a = null
  let prev = null
  let toggled = false
  try {
    a = app()
    prev = a.ScreenUpdating
    a.ScreenUpdating = false
    toggled = true
  } catch (e) { /* 宿主不支持 ScreenUpdating：照常执行 */ }
  try {
    return fn()
  } finally {
    if (toggled) {
      try {
        // 读不出原值时恢复成 true——绝不能把宿主留在停止重绘的状态
        a.ScreenUpdating = prev == null ? true : prev
      } catch (e) { /* 恢复失败不掩盖主结果 */ }
    }
  }
}

/** needle 在 hay 中的全部非重叠命中偏移（升序） */
function allIndexes(hay, needle) {
  const out = []
  if (!needle) return out
  let i = hay.indexOf(needle)
  while (i !== -1) {
    out.push(i)
    i = hay.indexOf(needle, i + needle.length)
  }
  return out
}

/**
 * 锚点跨段降级选段。抄自 officeExecutor.pickAnchorFallback（dev-board#149）——
 * WPS 的 indexOf 定位天然跨段，这里只作「归一化后仍未命中」时的兜底。
 */
function pickAnchorFallback(anchor, position) {
  const segments = String(anchor || '')
    .split(/\r\n|\n|\r/)
    .map((s) => s.trim())
    .filter((s) => s.length >= ANCHOR_FALLBACK_MIN_CHARS)
  if (!segments.length) return null
  if (position === 'after') return segments[segments.length - 1]
  if (position === 'before') return segments[0]
  return segments.reduce((longest, s) => (s.length > longest.length ? s : longest))
}

/**
 * 锚点定位：全文 indexOf（区分大小写），跨段锚点（\n 归一成 \r 后）原生支持；
 * 仍未命中时按 pickAnchorFallback 拆段兜底。返回全部命中偏移与实际使用的 needle。
 */
function locateAnchorAll(doc, anchorText, { position = null, message } = {}) {
  const body = bodyText(doc)
  let needle = normalizeNewlines(anchorText)
  let offsets = allIndexes(body, needle)
  let degraded = false
  if (!offsets.length && /\r/.test(needle)) {
    const fallback = pickAnchorFallback(needle, position)
    if (fallback) {
      const alt = allIndexes(body, fallback)
      if (alt.length) {
        offsets = alt
        needle = fallback
        degraded = true
      }
    }
  }
  if (!offsets.length) {
    // 第三级：归一化重定位（dev-board#286）。全角/半角、弯直引号、NBSP、零宽字符、
    // 连续空白、各式连字符、大小写——这些差异在屏幕上看不出来，逐字比较却必然失配，
    // 而 WPS 文字上送给模型的正文里还带着表格的 \x07 结束符，模型照抄就更没戏。
    //
    // **命中之后用的是文档原文（hit.text）而不是模型给的锚点**：后面的 verifiedRange
    // 要拿它逐笔校验取到的文本，用归一化后的串会当场判失败。取原文既让匹配变宽松，
    // 又保住了「取到的文本必须与预期逐字相同」这条不变式。
    const hits = findAllNormalized(body, anchorText)
    if (hits.length) {
      // 各处命中的原文可能不完全相同（这处是 NBSP、那处是普通空格）。offsets 与
      // needle 是一对一配套往下传的，所以只收与第一处原文完全一致的那些，
      // 宁可少改几处、也不能拿一个串去当另一个串的坐标（total 会如实报数）。
      const first = hits[0].text
      offsets = hits.filter((h) => h.text === first).map((h) => h.start)
      needle = first
      degraded = true
    }
  }
  if (!offsets.length) {
    throw new Error(describeAnchorFailure(message || '定位锚点', anchorText, body))
  }
  // coords 与 offsets 出自同一份快照，必须一起传给落笔方——分开取会用错坐标系
  return { offsets, needle, degraded, coords: makeDocCoords(body) }
}

/**
 * 文档坐标映射。真机实测（WPS 12.1.0.28043，2026-08-29，原始报告存
 * `scripts/measurements/2026-08-29-offset-verify.json`）：`doc.Range().Text` 与
 * `doc.Range(start, end)` 是**两套坐标系**——表格的单元格结束符与行结束符在文本里
 * 是 `\r\x07` 两个 UTF-16 单元，在 Range 坐标系里只占 1 个位置（`\r` 占、`\x07` 不占）。
 * 于是：
 *
 *     文档位置 = JS 下标 − 该下标之前的 \x07 个数
 *
 * 26 个锚点的实测对照：直接拿 JS 下标去切只有 2 个对得上，按本式换算 26 个全对
 * （含跨两张表格、含一个装了两段文字的单元格）。这正是「含表格文档成片死路」的
 * 根因——表格之前的锚点碰巧对齐，之后的全错，错的量等于中间的 `\x07` 个数。
 *
 * 推算不出来的两类仍然存在，靠逐笔校验兜住、再退 Find：批注引用标记（占文档位置
 * 但不进文本，实测每条 +1 起）与域（超链接的域代码占一大段位置）；修订开着改过一轮
 * 之后也会漂。
 */
function makeDocCoords(body) {
  const bells = []
  for (let i = 0; i < body.length; i++) {
    if (body.charCodeAt(i) === 7) bells.push(i)
  }
  return {
    body,
    hasPlaceholders: bells.length > 0,
    /** JS 下标 → 文档字符位置 */
    pos(jsIndex) {
      let lo = 0
      let hi = bells.length
      while (lo < hi) {
        const mid = (lo + hi) >> 1
        if (bells[mid] < jsIndex) lo = mid + 1
        else hi = mid
      }
      return jsIndex - lo
    }
  }
}

/** 按坐标映射取 Range 并逐笔校验文本；对不上返回 null，由调用方决定兜底 */
function rangeAtJsOffset(doc, coords, jsStart, expected) {
  try {
    const r = doc.Range(coords.pos(jsStart), coords.pos(jsStart + expected.length))
    return String(r.Text) === expected ? r : null
  } catch (e) {
    return null
  }
}

/**
 * Find 定位（只定位、不替换）。真机实测：`Execute` 不带替换参数时会把调用它的
 * Range **重定义为命中区间**（Word VBA 同款语义，WPS 侧已实测确认），且 WPS 没有
 * Word 那条 255 字上限、也能跨段匹配（都是带文本校验实测的，见 measurements/）。
 *
 * **命中后必须逐笔校验 `rng.Text` 与锚点逐字相同**——有了这道校验，兜底就不是「猜」：
 * 对不上就当没找到，绝不落笔。skip 用来取第 N 处命中（每次命中后折叠到末尾再找，
 * 否则会永远命中最靠前那一处——这就是 replace_text 早年踩过的 leftmost-repeat 坑）。
 */
function findRange(doc, needle, skip = 0) {
  let rng
  try {
    rng = doc.Range()
  } catch (e) {
    return null
  }
  const find = rng && rng.Find
  if (!find || typeof find.Execute !== 'function') return null
  try {
    if (typeof find.ClearFormatting === 'function') find.ClearFormatting()
    pinFindStrictness(find)
    for (let n = 0; n <= skip; n++) {
      if (!find.Execute(needle, true, false, false, false, false, true, wdFindStop, false)) return null
      if (n < skip) rng.Collapse(0) // wdCollapseEnd：折叠到命中末尾再找下一处
    }
    return String(rng.Text) === needle ? rng : null
  } catch (e) {
    return null
  }
}

/**
 * 锚点 → Range 安全阀：先按坐标映射直切，映射也对不上（域/批注/修订漂移）再退
 * Find 定位。两条路都以「取到的文本与锚点逐字相同」为通过条件，任何一条都不会猜。
 */
function verifiedRange(doc, coords, jsStart, expected, occurrence = 0) {
  const direct = rangeAtJsOffset(doc, coords, jsStart, expected)
  if (direct) return direct
  const found = findRange(doc, expected, occurrence)
  if (found) return found
  throw new Error('定位校验失败：按字符偏移与查找定位都没能取到与锚点逐字相同的文本（文档可能含域、批注等无法推算的占位内容，或该处已被修订标记打断），请换一段更独特的锚点文本重试')
}

/** 单命中锚点（写入类命令通用）：取第一处命中并校验 */
function anchorRange(doc, anchorText, opts = {}) {
  const { offsets, needle, coords } = locateAnchorAll(doc, anchorText, opts)
  return { range: verifiedRange(doc, coords, offsets[0], needle), start: offsets[0], needle, total: offsets.length, coords }
}

/* ==================== 枚举映射（小写短名 ↔ VBA 数值） ==================== */

const UNDERLINE_TYPES = {
  none: wdUnderlineNone,
  single: wdUnderlineSingle,
  double: wdUnderlineDouble,
  dotted: wdUnderlineDotted,
  wave: wdUnderlineWavy
}

const ALIGNMENTS = {
  left: wdAlignParagraphLeft,
  center: wdAlignParagraphCenter,
  right: wdAlignParagraphRight,
  justify: wdAlignParagraphJustify
}

const PARAGRAPH_STYLES = {
  normal: wdStyleNormal,
  heading1: wdStyleHeading1,
  heading2: wdStyleHeading2,
  heading3: wdStyleHeading3,
  heading4: wdStyleHeading4
}

const TABLE_ALIGNMENTS = { left: wdAlignRowLeft, center: wdAlignRowCenter, right: wdAlignRowRight }

const NUMBERING_KINDS = ['bullet', 'decimal', 'chinese', 'none']

/** WdRevisionType 数值 → 可读类型名（回译给模型；数值出处见调研 WdRevisionType 全表） */
const REVISION_TYPE_NAMES = {
  1: 'inserted',
  2: 'deleted',
  3: 'property',
  4: 'paragraphNumber',
  5: 'displayField',
  6: 'reconcile',
  7: 'conflict',
  8: 'style',
  9: 'replace',
  10: 'paragraphProperty',
  11: 'tableProperty',
  12: 'sectionProperty',
  13: 'styleDefinition',
  14: 'movedFrom',
  15: 'movedTo',
  16: 'cellInsertion',
  17: 'cellDeletion',
  18: 'cellMerge'
}

/** 模型常给英文/中文内置样式名，样式名直赋失败时按此表落枚举常量（apply_style 垫底） */
const BUILTIN_STYLE_BY_NAME = {
  'normal': wdStyleNormal,
  '正文': wdStyleNormal,
  'heading 1': wdStyleHeading1,
  'heading1': wdStyleHeading1,
  '标题 1': wdStyleHeading1,
  '标题1': wdStyleHeading1,
  'heading 2': wdStyleHeading2,
  'heading2': wdStyleHeading2,
  '标题 2': wdStyleHeading2,
  '标题2': wdStyleHeading2,
  'heading 3': wdStyleHeading3,
  'heading3': wdStyleHeading3,
  '标题 3': wdStyleHeading3,
  '标题3': wdStyleHeading3,
  'heading 4': wdStyleHeading4,
  'heading4': wdStyleHeading4,
  '标题 4': wdStyleHeading4,
  '标题4': wdStyleHeading4
}

/** 读格式时把样式名回译成 styleBuiltIn 短名（读不出对应关系就不给这个字段） */
const STYLE_SHORT_BY_NAME = {
  'normal': 'normal',
  '正文': 'normal',
  'heading 1': 'heading1',
  '标题 1': 'heading1',
  'heading 2': 'heading2',
  '标题 2': 'heading2',
  'heading 3': 'heading3',
  '标题 3': 'heading3',
  'heading 4': 'heading4',
  '标题 4': 'heading4'
}

/** 小写短名 → 数值；非法值报错并列出合法值（0 是合法枚举值，必须用 in 判断） */
function toEnumValue(table, raw, field) {
  const key = String(raw).trim().toLowerCase()
  if (!(key in table)) {
    throw new Error(`${field} 值非法：${raw}（合法值：${Object.keys(table).join('/')}）`)
  }
  return table[key]
}

/** 数值 → 小写短名（读格式时回译，让模型读到的词与能填的词一致） */
function fromEnumValue(table, raw) {
  if (raw == null) return raw
  const hit = Object.keys(table).find((key) => table[key] === raw)
  return hit || raw
}

/* ==================== 颜色（WPS 是 BGR Long，不是 hex 串） ==================== */

function parseHexColor(hex) {
  const m = /^#?([0-9a-f]{6})$/i.exec(String(hex).trim())
  if (!m) throw new Error(`颜色格式非法：${hex}（应为 #RRGGBB）`)
  const v = parseInt(m[1], 16)
  return { r: (v >> 16) & 0xff, g: (v >> 8) & 0xff, b: v & 0xff }
}

/** '#RRGGBB' → Font.Color 的 BGR Long */
function hexToBgr(hex) {
  const { r, g, b } = parseHexColor(hex)
  return (b << 16) | (g << 8) | r
}

/** '#RRGGBB' → TextColor.RGB 的 RGB Long（与 Font.Color 字节序相反，备胎通路用） */
function hexToRgbLong(hex) {
  const { r, g, b } = parseHexColor(hex)
  return r | (g << 8) | (b << 16)
}

/** Font.Color 的 BGR Long → '#RRGGBB'；自动色（负值）与混合（wdUndefined）回 null */
function bgrToHex(bgr) {
  const n = Number(bgr)
  if (!Number.isFinite(n) || n < 0 || n === wdUndefined) return null
  const b = (n >> 16) & 0xff
  const g = (n >> 8) & 0xff
  const r = n & 0xff
  return '#' + [r, g, b].map((x) => x.toString(16).padStart(2, '0')).join('')
}

/** MsoTriState/混合值 → 布尔或 null（wdUndefined=混合格式读不出单一值） */
function triToBool(v) {
  if (v == null || v === wdUndefined) return null
  return v !== 0
}

/* ==================== 字符/段落格式计划 ==================== */

function buildFontPlan(args) {
  const props = {}
  const applied = {}
  if (args.fontName) {
    props.Name = String(args.fontName)
    applied.name = String(args.fontName)
  }
  if (args.fontSize != null) {
    props.Size = Number(args.fontSize)
    applied.size = Number(args.fontSize)
  }
  if (args.bold != null) {
    props.Bold = args.bold ? msoTrue : msoFalse
    applied.bold = !!args.bold
  }
  if (args.italic != null) {
    props.Italic = args.italic ? msoTrue : msoFalse
    applied.italic = !!args.italic
  }
  if (args.underline != null) {
    props.Underline = toEnumValue(UNDERLINE_TYPES, args.underline, 'underline')
    applied.underline = String(args.underline).trim().toLowerCase()
  }
  if (args.strikeThrough != null) {
    props.StrikeThrough = args.strikeThrough ? msoTrue : msoFalse
    applied.strikeThrough = !!args.strikeThrough
  }
  if (args.doubleStrikeThrough != null) {
    props.DoubleStrikeThrough = args.doubleStrikeThrough ? msoTrue : msoFalse
    applied.doubleStrikeThrough = !!args.doubleStrikeThrough
  }
  let colorBgr = null
  let colorRgb = null
  if (args.color) {
    colorBgr = hexToBgr(args.color)
    colorRgb = hexToRgbLong(args.color)
    applied.color = String(args.color)
  }
  return { props, applied, colorBgr, colorRgb, empty: !Object.keys(applied).length }
}

function applyFontPlan(font, plan) {
  for (const [key, value] of Object.entries(plan.props)) font[key] = value
  if (plan.colorBgr != null) {
    try {
      font.Color = plan.colorBgr // 主路：Font.Color，BGR 序
    } catch (e) {
      font.TextColor.RGB = plan.colorRgb // 备胎：TextColor.RGB，RGB 序（调研第 5 验证项）
    }
  }
}

/**
 * 段落格式计划。lineSpacing 落成「最小值」行距（LineSpacingRule=wdLineSpaceAtLeast
 * + LineSpacing 两件套，WPS 官方口径必须两个都设）——这是 WPS 相对 Office.js 的
 * 能力升级（Office 面只能 exact），返回值用 lineSpacingMode:'atLeast' 向模型交底。
 */
function buildParagraphPlan(args) {
  const ops = []
  const applied = {}
  if (args.alignment != null) {
    const v = toEnumValue(ALIGNMENTS, args.alignment, 'alignment')
    ops.push((pf) => { pf.Alignment = v })
    applied.alignment = String(args.alignment).trim().toLowerCase()
  }
  if (args.lineSpacing != null) {
    const v = Number(args.lineSpacing)
    ops.push((pf) => {
      pf.LineSpacingRule = wdLineSpaceAtLeast
      pf.LineSpacing = v
    })
    applied.lineSpacing = v
  }
  const POINT_FIELDS = [
    ['spaceBefore', 'SpaceBefore'],
    ['spaceAfter', 'SpaceAfter'],
    ['firstLineIndent', 'FirstLineIndent'],
    ['leftIndent', 'LeftIndent'],
    ['rightIndent', 'RightIndent']
  ]
  for (const [argKey, propKey] of POINT_FIELDS) {
    if (args[argKey] != null) {
      const v = Number(args[argKey])
      ops.push((pf) => { pf[propKey] = v })
      applied[argKey] = v
    }
  }
  return { ops, applied, empty: !Object.keys(applied).length }
}

/* ==================== 律所标准格式（HOUSE） ==================== */

/**
 * 抄自 officeExecutor.houseFromProfile（不 import officeExecutor：会把 Office 面
 * 代码拽进依赖图）。三处写端（后端 DocxStyleHelper / LOWA worker / 插件）同一份
 * JSON 源，改规范只改那一个 JSON。
 */
function houseFromProfile(p) {
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

/** 小标题启发式（抄自 officeExecutor，与 LOWA 规范一致） */
const HEADING_RE = /^(第[一二三四五六七八九十百千零〇\d]+[条章节款项]|[一二三四五六七八九十]+[、.．]|[（(][一二三四五六七八九十\d]+[)）]|\d+[、.．])/

/**
 * 把 HOUSE 规范落到一个区间上。区间可以是单个段落，也可以是「连续同类段落」
 * 合并出来的 run——两种情形的写入内容逐字相同，差别只在跨桥往返次数与修订颗粒度。
 * 用 `Range.ParagraphFormat`（作用于区间内全部段落）而不是 `Paragraph.Format`，
 * 与 edit_header_footer 的既有用法同源。
 */
function applyHouseFormat(range, kind) {
  const f = range.Font
  // 中西文分设字体（NameFarEast/NameAscii/NameOther）在 WPS 无版本门槛
  f.NameFarEast = HOUSE.fontAsian
  f.NameAscii = HOUSE.fontWestern
  f.NameOther = HOUSE.fontWestern
  f.Size = kind === 'title' ? HOUSE.titlePt : HOUSE.bodyPt
  f.Bold = kind !== 'body' ? msoTrue : msoFalse
  f.Color = 0 // 黑色（BGR）
  const pf = range.ParagraphFormat
  pf.Alignment = kind === 'title' ? wdAlignParagraphCenter : wdAlignParagraphJustify
  // 真·最小值行距（wdLineSpaceAtLeast + LineSpacing 两件套）——Office 面做不到
  pf.LineSpacingRule = wdLineSpaceAtLeast
  pf.LineSpacing = HOUSE.lineSpacingPt
  pf.SpaceBefore = 0
  pf.SpaceAfter = HOUSE.spaceAfterPt
  pf.FirstLineIndent = kind === 'body' ? HOUSE.firstLineIndentPt : 0
}

/* ==================== 中文数字（编号降级用） ==================== */

const CHINESE_DIGITS = ['零', '一', '二', '三', '四', '五', '六', '七', '八', '九']

/** 1~200 的中文数字。抄自 officeExecutor.chineseNumeral（同一份口径，改要两处同步） */
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

/* ==================== 表格工具 ==================== */

/** 单元格坐标 "B2" → 0 起的 {row, col}。抄自 officeExecutor.parseCellRef（批次 8） */
function parseCellRef(ref) {
  const m = /^([A-Za-z]{1,3})(\d{1,6})$/.exec(String(ref || '').trim())
  if (!m) throw new Error(`单元格坐标格式非法：${ref}（应为列字母+行号，如 B2）`)
  const letters = m[1].toUpperCase()
  let col = 0
  for (let i = 0; i < letters.length; i++) col = col * 26 + (letters.charCodeAt(i) - 64)
  return { row: Number(m[2]) - 1, col: col - 1 }
}

/** 命令入参 tableIndex 是 0 起，WPS 集合 1-based——这里统一换算并做越界校验 */
function getTable(doc, rawIndex) {
  let index = Math.floor(Number(rawIndex))
  if (!Number.isFinite(index) || index < 0) index = 0
  const tables = doc.Tables
  const count = tables.Count
  if (!count) throw new Error('当前文档中没有表格')
  if (index >= count) {
    throw new Error(`tableIndex ${index} 越界：文档中共 ${count} 张表格（序号从 0 开始）`)
  }
  return { table: tables.Item(index + 1), index, count }
}

/** 剥掉单元格文本尾部的结束符 \r\x07（VBA 铁律，WPS 未明文——两种残留形态都剥） */
function cleanCellText(raw) {
  return String(raw == null ? '' : raw).replace(/\r?\x07$/, '').replace(/\r$/, '')
}

/** borderWidth 磅数 → WdLineWidth 枚举（值 = 磅 × 8，取最近档位） */
const LINE_WIDTH_STEPS = [[0.25, 2], [0.5, 4], [0.75, 6], [1, 8], [1.5, 12], [2.25, 18], [3, 24]]
function toLineWidth(ptWidth) {
  let best = LINE_WIDTH_STEPS[0]
  for (const step of LINE_WIDTH_STEPS) {
    if (Math.abs(step[0] - ptWidth) < Math.abs(best[0] - ptWidth)) best = step
  }
  return best[1]
}

/* ==================== 批注/修订定位（1-based Index，无 GUID） ==================== */

/**
 * WPS 批注没有 GUID：commentId 与 commentIndex 都按 0 起序号解释（get_comments
 * 返回的 id 就是序号字符串）。每次调用重新读集合再定位——序号随删除/解决塌缩。
 */
function resolveCommentIndex(args, count) {
  const hasId = args.commentId != null && String(args.commentId).trim() !== ''
  const hasIndex = args.commentIndex != null && args.commentIndex >= 0
  if (!hasId && !hasIndex) {
    throw new Error('缺少批注定位参数（commentId 或 commentIndex）')
  }
  const idx = Math.floor(Number(hasId ? args.commentId : args.commentIndex))
  if (!Number.isFinite(idx)) {
    throw new Error('WPS 宿主的批注没有 GUID，commentId 只接受批注序号（0 开始）——请先用 office_get_comments 获取序号')
  }
  if (idx < 0 || idx >= count) {
    throw new Error(`批注序号 ${idx} 越界：文档共 ${count} 条批注（序号从 0 开始），请先用 office_get_comments 核对`)
  }
  return idx
}

/** Comment.Date / Revision.Date 的字符串尽力转 ISO；转不动就原样返回 */
function toIso(raw) {
  if (raw == null || raw === '') return null
  try {
    const t = new Date(raw)
    return isNaN(t.getTime()) ? String(raw) : t.toISOString()
  } catch (e) {
    return String(raw)
  }
}

/* ==================== replace_text 的最小修订落笔 ==================== */

/**
 * 对单个命中做字符级最小修订。返回：
 *  - null：命中 Range 的偏移校验失败（文档含占位符），调用方走 Find 兜底；
 *  - { via:'minimal', edits:n }：最小修订落笔完成（n=0 表示新旧一致未动笔）；
 *  - { via:'full' }：整段替换（多行新文/差异覆盖整段/差异段校验失败）。
 * 全部校验在任何写入之前完成；应用从右到左（修订开着时被删文本仍留在正文占位，
 * 左侧偏移不动——这也是右到左顺序的保险）。
 */
function applyHit(doc, coords, hitStart, needle, newText, multiline) {
  const hitRange = doc.Range(coords.pos(hitStart), coords.pos(hitStart + needle.length))
  if (String(hitRange.Text) !== needle) return null
  if (!multiline) {
    const edits = minimalEdits(needle, newText)
    if (!edits.length) return { via: 'minimal', edits: 0 }
    const whole = edits.length === 1 && edits[0].start === 0 && edits[0].end === needle.length
    if (!whole) {
      let verified = true
      for (const e of edits) {
        if (e.start === e.end) continue // 纯插入段没有旧文可校
        const seg = doc.Range(coords.pos(hitStart + e.start), coords.pos(hitStart + e.end))
        if (String(seg.Text) !== e.oldText) {
          verified = false
          break
        }
      }
      if (verified) {
        for (let k = edits.length - 1; k >= 0; k--) {
          const e = edits[k]
          if (e.start === e.end) {
            doc.Range(coords.pos(hitStart + e.start), coords.pos(hitStart + e.start)).InsertBefore(e.newText)
          } else {
            // 替换或删除（newText 为空即删除；修订开着时表现为删除修订标记）
            doc.Range(coords.pos(hitStart + e.start), coords.pos(hitStart + e.end)).Text = e.newText
          }
        }
        return { via: 'minimal', edits: edits.length }
      }
    }
  }
  hitRange.Text = newText
  return { via: 'full' }
}

/**
 * 把 Find 的「匹配宽松度」逐项钉死。`ClearFormatting()` 只清格式——
 * IgnorePunct / IgnoreSpace / MatchFuzzy / MatchByte 是 Find 对象上的持久属性
 * （对应查找对话框里的「忽略标点符号」「忽略空格」等复选框），既不在 Execute 的
 * 15 参签名里，也会从用户上一次手动查找继承下来。不钉死的话同一份文档在两台机器上
 * 命中范围可能不同，而兜底路的命中会被直接拿去替换，是最难复现的一类错。
 * MatchByte 的极性与其余三个相反：true 才表示「区分全角/半角」。
 * 各自 try/catch：WPS 未必暴露全部属性，取不到就维持宿主默认。
 */
function pinFindStrictness(find) {
  try {
    if (typeof find.ClearAllFuzzyOptions === 'function') find.ClearAllFuzzyOptions()
  } catch (e) { /* 旧宿主无此方法 */ }
  for (const [prop, value] of [['IgnorePunct', false], ['IgnoreSpace', false], ['MatchFuzzy', false], ['MatchByte', true]]) {
    try {
      find[prop] = value
    } catch (e) { /* 该属性不可写即跳过 */ }
  }
}

/**
 * 进 Find 兜底前的硬拦截。Find 与 indexOf 偏移路径的能力边界不同，落差全在这里，
 * 越界时宁可报一条模型能照着改的错，也不要让 Find 去猜——猜错是静默改错文档。
 */
function assertFindFallbackUsable(searchText, newText, hitCount) {
  // 曾经这里还拦「searchText 超 255 字」（Word 查找引擎的经典上限）。
  // 2026-08-29 真机实测把它证伪了：WPS 对 300 字的查找串照样命中，且回读的命中
  // 文本逐字相同、长度也是 300（没有截断后匹配）。律师的锚点常常是一整条条款，
  // 留着这条守卫等于把最需要兜底的长锚点挡在门外，所以撤掉。
  if (searchText.includes('^')) {
    // FindText 里 ^ 是特殊字符转义前导符（^p 段落标记、^t 制表符……），
    // 即使关掉通配符也仍然生效，会命中到别处
    throw new Error('替换失败：文档字符偏移与文本快照不一致，需走查找替换兜底，但 searchText 含 ^ 字符（查找语法的转义前导符）。请换一段不含 ^ 的文本作为 searchText。')
  }
  if (/\r/.test(newText)) {
    // 查找替换的「替换为」只认 ^p，塞裸段落符要么抛异常要么写进一个字面控制字符
    throw new Error('替换失败：文档字符偏移与文本快照不一致，需走查找替换兜底，但兜底路不支持跨段落的替换文本。请拆成逐段替换：每次只替换一个段落内的文本。')
  }
  if (hitCount > 1 && newText.includes(searchText)) {
    // 兜底是「循环替换最靠前一处」，替换产物里再含 searchText 就会反复替换自己
    // 刚生成的文本（「甲方」→「甲方（以下简称甲方）」这类律师常做的改写就是），
    // 结果是第一处堆出嵌套垃圾、其余各处一个没动，返回值还报全部成功
    throw new Error('替换失败：文档字符偏移与文本快照不一致，需走查找替换兜底，但 replaceText 里包含了 searchText，多处替换会反复命中刚替换出来的文本。请改为逐处替换（replaceAll 置否，并用更长的上下文区分每一处）。')
  }
}

/** 偏移口径失准时的兜底：Find.Execute 位置传参整替一处（官方 15 参签名的前 11 参） */
function findReplaceOnce(doc, needle, replaceText) {
  let find
  try {
    find = doc.Range().Find
  } catch (e) {
    return false
  }
  if (!find || typeof find.Execute !== 'function') return false
  try {
    if (typeof find.ClearFormatting === 'function') find.ClearFormatting()
    pinFindStrictness(find)
    // Execute(FindText, MatchCase, MatchWholeWord, MatchWildcards, MatchSoundsLike,
    //         MatchAllWordForms, Forward, Wrap, Format, ReplaceWith, Replace)
    return !!find.Execute(needle, true, false, false, false, false, true, wdFindStop, false, replaceText, wdReplaceOne)
  } catch (e) {
    return false
  }
}

/* ==================== 引用定位（正文引文 chip 点击选中） ==================== */

/** Find 定位语义的 WPS 实现：indexOf 命中 + Range.Select()。不区分大小写。 */
export async function locateInWpsDocument(text) {
  const needle = normalizeNewlines(String(text || '').trim())
  if (!needle) return { found: false }
  const w = globalThis.wps
  if (!w || typeof w.WpsApplication !== 'function') return { found: false }
  let doc
  try {
    doc = w.WpsApplication().ActiveDocument
  } catch (e) {
    return { found: false }
  }
  if (!doc) return { found: false }
  try {
    const body = bodyText(doc)
    const lower = body.toLowerCase()
    let probe = needle.toLowerCase()
    let i = lower.indexOf(probe)
    if (i < 0 && /\r/.test(needle)) {
      const fallback = pickAnchorFallback(needle, null)
      if (fallback) {
        probe = fallback.toLowerCase()
        i = lower.indexOf(probe)
      }
    }
    if (i < 0) return { found: false }
    const coords = makeDocCoords(body)
    doc.Range(coords.pos(i), coords.pos(i + probe.length)).Select()
    return { found: true, count: allIndexes(lower, probe).length }
  } catch (e) {
    return { found: false, error: (e && e.message) || String(e) }
  }
}

/* ==================== HANDLERS ==================== */

export const WPS_WORD_HANDLERS = {
  async get_text() {
    return truncate(bodyText(activeDoc()))
  },

  async get_selection() {
    activeDoc()
    const sel = app().Selection
    let text = ''
    try {
      // 光标（无选区）时 Selection.Text 按 VBA 惯例返回右侧一个字符——按空处理
      if (sel && sel.Type === wdSelectionIP) text = ''
      else text = String((sel && sel.Text) || '')
    } catch (e) {
      text = String((sel && sel.Text) || '')
    }
    return truncate(text)
  },

  async search(args) {
    const query = String(args.query || '')
    if (!query) throw new Error('查找文本不能为空')
    const doc = activeDoc()
    const body = bodyText(doc)
    // 不区分大小写；JS indexOf 无 255 上限、天然跨段（needle 归一成 \r 再搜）
    const needle = normalizeNewlines(query).toLowerCase()
    const lower = body.toLowerCase()
    const offsets = allIndexes(lower, needle)
    const shown = offsets.slice(0, MAX_SEARCH_HITS)
    const matches = shown.map((offset, i) => {
      // 命中上下文 = 命中所在段落的完整文本（\r 为段落符）
      const paraStart = lower.lastIndexOf('\r', offset - 1) + 1
      let paraEnd = lower.indexOf('\r', offset)
      if (paraEnd < 0) paraEnd = body.length
      return { index: i + 1, context: body.slice(paraStart, paraEnd).slice(0, 500) }
    })
    return { count: offsets.length, shown: shown.length, matches }
  },

  async replace_text(args) {
    const searchText = String(args.searchText || '')
    const replaceText = args.replaceText == null ? '' : String(args.replaceText)
    const replaceAll = !!args.replaceAll
    if (!searchText) throw new Error('查找文本不能为空')
    // 与 Office 面同口径的快速失败：跨段 searchText 不做静默降级——降级只会命中
    // 其中一段，却要把完整的多段 replaceText 塞进去，结果是内容重复/错位。
    if (/[\r\n]/.test(searchText)) {
      throw new Error('searchText 跨段落（含换行），请逐段替换：每次以单段内的文本为 searchText，并只提供该段的替换文本')
    }
    const doc = activeDoc()
    return withTracking(doc, () => {
      const body = bodyText(doc)
      const coords = makeDocCoords(body)
      const offsets = allIndexes(body, searchText)
      if (!offsets.length) {
        throw new Error('未找到目标文本，请确认 searchText 与文档内容精确一致（可先用 search 命令核对）')
      }
      const targets = replaceAll ? offsets : [offsets[0]]
      const newText = normalizeNewlines(replaceText)
      const multiline = /\r/.test(newText)
      // 偏移口径预检：任何一处命中的偏移文本对不上（文档含表格/域等占位符），
      // 整体切换到纯 Find 循环——不许偏移路径与 Find 兜底混跑。混跑的病灶：
      // Find.Execute 永远替换文档里最靠前的命中，与右到左循环当前处理的那处
      // 错位，replaceAll 下会提前吃掉左侧命中、最后一轮反过来报「未命中」。
      const allVerified = targets.every(
        (start) => rangeAtJsOffset(doc, coords, start, searchText) != null
      )
      if (!allVerified) {
        assertFindFallbackUsable(searchText, newText, targets.length)
        let replaced = 0
        for (let k = 0; k < targets.length; k++) {
          if (!findReplaceOnce(doc, searchText, newText)) break
          replaced++
        }
        if (!replaced) {
          throw new Error('替换失败：文档字符偏移与文本快照不一致，且查找替换兜底未命中，请先用 search 命令核对目标文本')
        }
        const result = { replaced, totalMatches: offsets.length, via: 'fullReplace', edits: 0, fallbacks: replaced }
        if (replaced < targets.length) {
          result.note = `按查找替换兜底完成 ${replaced}/${targets.length} 处（文档含占位内容，字符级最小修订不可用）`
        }
        return result
      }
      let minimal = 0
      let fallbacks = 0
      let editSegments = 0
      // 多个命中从右到左处理：前一处的写入不会推移后面命中的定位（与 Office 面同理由）
      for (let k = targets.length - 1; k >= 0; k--) {
        const applied = applyHit(doc, coords, targets[k], searchText, newText, multiline)
        if (applied == null || applied.via === 'full') {
          // 预检通过后 applyHit 不应再返回 null；防御性并入整替计数
          fallbacks++
        } else {
          minimal++
          editSegments += applied.edits
        }
      }
      const result = {
        replaced: targets.length,
        totalMatches: offsets.length,
        via: fallbacks === 0 ? 'minimalRedline' : (minimal === 0 ? 'fullReplace' : 'mixed'),
        edits: editSegments
      }
      if (fallbacks) result.fallbacks = fallbacks
      if (!editSegments && !fallbacks) result.note = '新旧文本一致，未产生修订'
      return result
    })
  },

  async insert_text(args) {
    const text = String(args.text || '')
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    if (!text) throw new Error('插入文本不能为空')
    const doc = activeDoc()
    const content = normalizeNewlines(text)
    return withTracking(doc, () => {
      if (anchorText) {
        const { range } = anchorRange(doc, anchorText, {
          position,
          message: '未找到锚点文本，请确认 anchorText 与文档内容精确一致'
        })
        if (position === 'before') range.InsertBefore(content)
        else range.InsertAfter(content)
      } else {
        // 无锚点：落在用户当前光标/选区处（选区被替换，与光标插入语义一致）。
        // VBA/WPS 语义：Selection.Text 赋值后**选区扩展覆盖新文本**——不折叠的话，
        // 连续两次无锚点插入时第二次会把第一次整段替换（修订态下第一段变红色删除线）。
        // 立刻把选区折叠到插入内容末尾（wdCollapseEnd=0），让连续插入成为追加。
        const sel = app().Selection
        sel.Text = content
        try { sel.Collapse(0) } catch (e) { /* 旧宿主缺 Collapse：保持原行为，单次插入不受影响 */ }
      }
      return { inserted: true, anchored: !!anchorText, position: anchorText ? position : 'selection' }
    })
  },

  async add_comment(args) {
    const anchorText = String(args.anchorText || '')
    const comment = String(args.comment || '')
    if (!anchorText) throw new Error('批注目标文本不能为空')
    if (!comment) throw new Error('批注内容不能为空')
    const doc = activeDoc()
    const { range } = anchorRange(doc, anchorText, {
      message: '未找到批注目标文本，请确认 anchorText 与文档内容精确一致'
    })
    doc.Comments.Add(range, comment)
    return { commented: true }
  },

  // ==================== 格式（走 WPS 原生修订） ====================

  async format_text(args) {
    const anchorText = String(args.anchorText || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    const plan = buildFontPlan(args)
    if (plan.empty) {
      throw new Error('未给出任何格式参数（fontName/fontSize/bold/italic/underline/strikeThrough/doubleStrikeThrough/color 至少给一个）')
    }
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { offsets, needle, coords } = locateAnchorAll(doc, anchorText)
      const targetOffsets = args.applyToAll ? offsets : [offsets[0]]
      // 全部命中先校验完再落笔：边校验边写的话，第 k 处校验失败时前 k-1 处已经改了，
      // 用户拿到的是「半篇改了格式 + 一条报错」，还留着一片修订。与 set_paragraph_format /
      // apply_style 的预取同口径，也与 replace_text 的「全有全无」门同理由。
      const ranges = targetOffsets.map((offset, k) => verifiedRange(doc, coords, offset, needle, k))
      for (const range of ranges) applyFontPlan(range.Font, plan)
      return { formatted: ranges.length, totalMatches: offsets.length, applied: plan.applied }
    })
  },

  async set_paragraph_format(args) {
    const anchorText = String(args.anchorText || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    const plan = buildParagraphPlan(args)
    const styleBuiltIn = args.styleBuiltIn == null
      ? null
      : toEnumValue(PARAGRAPH_STYLES, args.styleBuiltIn, 'styleBuiltIn')
    if (styleBuiltIn == null && plan.empty) {
      throw new Error('未给出任何格式参数（alignment/lineSpacing/spaceBefore/spaceAfter/firstLineIndent/leftIndent/rightIndent/styleBuiltIn 至少给一个）')
    }
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { offsets, needle, coords } = locateAnchorAll(doc, anchorText)
      const targetOffsets = args.applyToAll ? offsets : [offsets[0]]
      const paragraphs = targetOffsets.map((offset, k) => verifiedRange(doc, coords, offset, needle, k).Paragraphs.Item(1))
      // 套内置样式会把段落格式重置成样式自带的那套，必须先落样式再落其余参数
      if (styleBuiltIn != null) {
        for (const p of paragraphs) p.Range.Style = styleBuiltIn
      }
      for (const p of paragraphs) {
        const pf = p.Format
        for (const op of plan.ops) op(pf)
      }
      const applied = styleBuiltIn != null ? { ...plan.applied, styleBuiltIn: args.styleBuiltIn } : plan.applied
      const result = { formatted: paragraphs.length, totalMatches: offsets.length, applied }
      // WPS 原生支持「最小值」行距（Office 面只能 exact），向模型交底
      if (args.lineSpacing != null) result.lineSpacingMode = 'atLeast'
      return result
    })
  },

  async get_formatting(args) {
    const anchorText = String(args.anchorText || '')
    const doc = activeDoc()
    let range
    let source
    if (anchorText) {
      range = anchorRange(doc, anchorText).range
      source = 'anchor'
    } else {
      range = app().Selection.Range
      source = 'selection'
    }
    const paragraph = range.Paragraphs.Item(1)
    const font = range.Font
    const pf = paragraph.Format
    const num = (v) => (v == null || v === wdUndefined ? null : Number(v))
    let styleName = null
    try {
      // Style 属性的 JS 返回形态未文档化：可能是样式对象也可能是字符串
      const st = paragraph.Style
      if (st != null) {
        styleName = typeof st === 'object' ? String(st.NameLocal || st.Name || '') : String(st)
      }
    } catch (e) { /* 样式读不出不阻塞其余格式信息 */ }
    const styleShort = styleName ? STYLE_SHORT_BY_NAME[styleName.trim().toLowerCase()] : undefined
    return {
      source,
      text: String(range.Text || '').slice(0, 200),
      font: {
        name: font.Name === wdUndefined ? null : font.Name,
        size: num(font.Size),
        bold: triToBool(font.Bold),
        italic: triToBool(font.Italic),
        underline: font.Underline === wdUndefined ? null : fromEnumValue(UNDERLINE_TYPES, font.Underline),
        strikeThrough: triToBool(font.StrikeThrough),
        color: bgrToHex(font.Color)
      },
      paragraph: {
        alignment: fromEnumValue(ALIGNMENTS, pf.Alignment),
        lineSpacing: num(pf.LineSpacing),
        // 比 Office 面多回的字段：WPS 行距有 rule 维度（3=atLeast/4=exactly 等）
        lineSpacingRule: num(pf.LineSpacingRule),
        spaceBefore: num(pf.SpaceBefore),
        spaceAfter: num(pf.SpaceAfter),
        firstLineIndent: num(pf.FirstLineIndent),
        leftIndent: num(pf.LeftIndent),
        rightIndent: num(pf.RightIndent),
        style: styleName,
        styleBuiltIn: styleShort
      }
    }
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
    const doc = activeDoc()
    const needle = normalizeNewlines(anchorText)
    return withTracking(doc, () => {
      // 先在全文快照里判存在（1 次跨桥）。锚点根本不在文档里时，下面的逐段扫描
      // 要跑满 3×总段数次跨桥调用才发现——5000 段的文书上就是好几秒纯粹为了报错。
      // 段落文本都是全文的子串，所以「全文里没有」必然「哪一段里都没有」，无漏判。
      if (!bodyText(doc).includes(needle)) {
        throw new Error('未找到锚点段落，请确认 anchorText 与文档内容精确一致（可先用 search 命令核对）')
      }
      const paras = doc.Paragraphs
      const total = paras.Count
      let start = -1
      for (let k = 1; k <= total; k++) {
        if (String(paras.Item(k).Range.Text || '').includes(needle)) {
          start = k
          break
        }
      }
      if (start < 0) {
        throw new Error('未找到锚点段落，请确认 anchorText 与文档内容精确一致（可先用 search 命令核对）')
      }
      const last = Math.min(total, start + count - 1)
      const targetCount = last - start + 1
      const range = doc.Range(paras.Item(start).Range.Start, paras.Item(last).Range.End)
      const lf = range.ListFormat
      if (kind === 'none') {
        lf.RemoveNumbers()
        return { paragraphs: targetCount, kind, via: 'listApi' }
      }
      if (kind === 'bullet') {
        lf.ApplyBulletDefault()
        return { paragraphs: targetCount, kind, via: 'listApi' }
      }
      if (kind === 'decimal') {
        lf.ApplyNumberDefault()
        return { paragraphs: targetCount, kind, via: 'listApi' }
      }
      // chinese：原生 wdListNumberStyleSimpChinNum2（38，「一、二、」）走 ListTemplate
      // ——Office.js 做不到的能力升级；失败降级手写「一、」前缀
      try {
        const lt = app().ListGalleries.Item(wdNumberGallery).ListTemplates.Item(1)
        const lv = lt.ListLevels.Item(1)
        lv.NumberStyle = wdListNumberStyleSimpChinNum2
        lv.NumberFormat = '%1、'
        lf.ApplyListTemplateWithLevel(lt, false, wdListApplyToWholeList, wdWord9ListBehavior, 1)
        return { paragraphs: targetCount, kind, via: 'listTemplate' }
      } catch (e) {
        // 从后往前写前缀：前面段落的插入不会推移未写段落的定位。
        // 复用循环外已取的 paras（插入前缀不改变段落数，序号仍有效）——每次
        // doc.Paragraphs 都是一次跨进程往返，放循环里是白跑。
        for (let k = last; k >= start; k--) {
          paras.Item(k).Range.InsertBefore(chineseNumeral(k - start + 1) + '、')
        }
        return {
          paragraphs: targetCount,
          kind,
          via: 'literalText',
          note: '当前 WPS 版本套用中文编号列表模板失败，编号已作为文字写入各段段首'
        }
      }
    })
  },

  async format_table(args) {
    const borders = args.borders == null ? null : String(args.borders).trim().toLowerCase()
    if (borders && !['all', 'outside', 'inside', 'none'].includes(borders)) {
      throw new Error(`borders 值非法：${args.borders}（合法值：all/outside/inside/none）`)
    }
    const alignment = args.alignment == null ? null : toEnumValue(TABLE_ALIGNMENTS, args.alignment, 'alignment')
    const fontSize = args.fontSize == null ? null : Number(args.fontSize)
    if (!borders && alignment == null && args.headerBold == null && args.autoFit == null && fontSize == null) {
      throw new Error('未给出任何格式参数（borders/alignment/headerBold/autoFit/fontSize 至少给一个）')
    }
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { table, index, count } = getTable(doc, args.tableIndex)
      const applied = {}
      if (borders === 'none') {
        try {
          table.Borders.Enable = 0
        } catch (e) {
          // Enable 是 VBA 惯用法但 WPS 未给示例——备胎逐边关（1x1 表可能缺内框线，逐边容错）
          for (const side of [wdBorderTop, wdBorderLeft, wdBorderBottom, wdBorderRight, wdBorderHorizontal, wdBorderVertical]) {
            try {
              table.Borders.Item(side).LineStyle = wdLineStyleNone
            } catch (e2) { /* 该边不存在 */ }
          }
        }
        applied.borders = 'none'
      } else if (borders) {
        const colorHex = String(args.borderColor || '#000000')
        const colorBgr = hexToBgr(colorHex)
        const widthPt = Number(args.borderWidth) > 0 ? Number(args.borderWidth) : 1
        const lineWidth = toLineWidth(widthPt)
        const b = table.Borders
        if (borders === 'all' || borders === 'outside') {
          b.OutsideLineStyle = wdLineStyleSingle
          b.OutsideLineWidth = lineWidth
          b.OutsideColor = colorBgr
        }
        if (borders === 'all' || borders === 'inside') {
          b.InsideLineStyle = wdLineStyleSingle
          b.InsideLineWidth = lineWidth
          b.InsideColor = colorBgr
        }
        applied.borders = borders
        applied.borderColor = colorHex
        applied.borderWidth = widthPt
      }
      if (alignment != null) {
        table.Rows.Alignment = alignment
        applied.alignment = String(args.alignment).trim().toLowerCase()
      }
      if (args.headerBold != null) {
        table.Rows.Item(1).Range.Font.Bold = args.headerBold ? msoTrue : msoFalse
        applied.headerBold = !!args.headerBold
      }
      if (fontSize != null) {
        table.Range.Font.Size = fontSize
        applied.fontSize = fontSize
      }
      if (args.autoFit) {
        table.AutoFitBehavior(wdAutoFitWindow)
        applied.autoFit = true
      }
      return { tableIndex: index, tableCount: count, rowCount: table.Rows.Count, applied }
    })
  },

  async apply_standard_format(args) {
    const scope = String(args.scope || 'document').trim().toLowerCase() === 'selection' ? 'selection' : 'document'
    const doc = activeDoc()
    // 长循环：先关宿主重绘再进 withTracking（每段十余次跨桥写入，重绘开销可观）
    return withoutScreenUpdating(() => withTracking(doc, () => {
      const paras = scope === 'selection' ? app().Selection.Range.Paragraphs : doc.Paragraphs
      const total = paras.Count
      const truncated = total > MAX_STANDARD_FORMAT_PARAGRAPHS
      const limit = truncated ? MAX_STANDARD_FORMAT_PARAGRAPHS : total

      let firstNonEmptySeen = false
      let titles = 0
      let headings = 0
      let bodies = 0
      // 分类仍然逐段做（纯 JS 状态机，判定粒度不变），只把「写」按连续同类段落
      // 合并成 run 落笔。这样做对律师的好处不止是不卡：审阅窗格里的格式修订从
      // 「每段一条」变成「每个 run 一条」，一份数百段的文书从几百条噪音降到几十条，
      // 他真正要看的实质性红线不再被埋掉——格式修订本来就没人逐条接受/拒绝。
      let run = null // { kind, ranges: [] }
      let writeBatches = 0
      let degradedRuns = 0
      const flushRun = () => {
        if (!run || !run.ranges.length) return
        const ranges = run.ranges
        if (ranges.length === 1) {
          applyHouseFormat(ranges[0], run.kind)
          writeBatches++
        } else {
          const merged = doc.Range(ranges[0].Start, ranges[ranges.length - 1].End)
          // 安全阀：合并区间必须恰好覆盖这几段，多一段少一段都退回逐段落笔。
          // 字符偏移口径在真机上还没验（领域文档头号验证项），这一次校验把
          // 「静默把格式刷到别的段落上」变成「诚实降级、结果不变」。
          let exact = false
          try {
            exact = Number(merged.Paragraphs.Count) === ranges.length
          } catch (e) {
            exact = false
          }
          if (exact) {
            applyHouseFormat(merged, run.kind)
            writeBatches++
          } else {
            for (const r of ranges) applyHouseFormat(r, run.kind)
            writeBatches += ranges.length
            degradedRuns++
          }
        }
        run = null
      }

      for (let k = 1; k <= limit; k++) {
        const p = paras.Item(k)
        // Range 只取一次：同步桥下每个属性访问都是一次跨进程往返，重复取 p.Range
        // 在 500 段文档上就是 500 次白跑（format_table 缓存 table.Borders 同款手法）
        const pr = p.Range
        const raw = String(pr.Text || '')
        const text = raw.replace(/\r$/, '').trim()
        if (!text) {
          // 空段落断 run：现状是完全不碰空段，合并区间会把它一起格式化，
          // 段后 18 磅 + 最小值行距落到空行上是肉眼可见的版面变化
          flushRun()
          continue
        }
        let kind = 'body'
        if (!firstNonEmptySeen) {
          firstNonEmptySeen = true
          if (text.length <= HOUSE_TITLE_MAX_CHARS) kind = 'title'
        } else if (text.length <= HOUSE_HEADING_MAX_CHARS && HEADING_RE.test(text)) {
          kind = 'heading'
        }
        // 表格单元格里的段落（尾部带 \x07 结束符）一律单独落笔，不与正文合并：
        // 合并区间横跨表格边界的行为没有真机验证过，不值得为几次往返去赌
        const inTableCell = raw.indexOf('\x07') >= 0
        if (run && (run.kind !== kind || inTableCell)) flushRun()
        if (inTableCell) {
          applyHouseFormat(pr, kind)
          writeBatches++
        } else {
          if (!run) run = { kind, ranges: [] }
          run.ranges.push(pr)
        }
        if (kind === 'title') titles++
        else if (kind === 'heading') headings++
        else bodies++
      }
      flushRun()

      // 表格字号只在全文范围处理（选区里的表格不在 v1 范围，与 Office 面一致）
      let tableCount = 0
      if (scope === 'document') {
        const tables = doc.Tables
        tableCount = tables.Count
        for (let k = 1; k <= tableCount; k++) {
          tables.Item(k).Range.Font.Size = HOUSE.tablePt
        }
      }

      const result = {
        scope,
        paragraphs: titles + headings + bodies,
        titles,
        headings,
        tables: tableCount,
        fontSplit: true,
        // WPS 原生支持「行距最小值 16 磅」（Office 面被迫降成 exact）
        lineSpacingMode: 'atLeast',
        // 连续同类段落合并成一次写入：修订按 run 记而不是按段记（审阅窗格里
        // 是几十条而不是几百条），向模型交底实际落笔了多少批
        writeBatches
      }
      // note 可能有多条来源，逐条追加——别用赋值，会把前一条顶掉
      const notes = []
      if (degradedRuns) {
        result.degradedRuns = degradedRuns
        notes.push('部分段落的合并区间与段落边界对不上（文档可能含表格、域等占位内容），这些段落已逐段落笔，格式结果不受影响')
      }
      if (truncated) {
        result.truncated = true
        result.totalParagraphs = total
      }
      if (scope === 'selection') notes.push('选区范围不处理表格字号')
      if (notes.length) result.note = notes.join('；')
      return result
    }))
  },

  // ==================== 表格结构 ====================

  async insert_table(args) {
    const rows = args.rows
    if (!Array.isArray(rows) || !rows.length || !Array.isArray(rows[0]) || !rows[0].length) {
      throw new Error('rowsJson 不能为空表')
    }
    const rowCount = rows.length
    const colCount = rows[0].length
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    const doc = activeDoc()
    return withTracking(doc, () => {
      let at
      if (anchorText) {
        const { range } = anchorRange(doc, anchorText, {
          position,
          message: '未找到锚点文本，请确认 anchorText 与文档内容精确一致'
        })
        const pos = position === 'before' ? range.Start : range.End
        at = doc.Range(pos, pos) // Tables.Add 的 Range 未折叠会替换区域内容，必须折叠
      } else {
        const sr = app().Selection.Range
        at = doc.Range(sr.End, sr.End)
      }
      const table = doc.Tables.Add(at, rowCount, colCount)
      for (let r = 1; r <= rowCount; r++) {
        for (let c = 1; c <= colCount; c++) {
          const v = rows[r - 1][c - 1]
          table.Cell(r, c).Range.Text = v == null ? '' : String(v)
        }
      }
      if (args.headerBold) table.Rows.Item(1).Range.Font.Bold = msoTrue
      return { inserted: true, rows: rowCount, cols: colCount, anchored: !!anchorText }
    })
  },

  async table_read(args) {
    const doc = activeDoc()
    const { table, index, count } = getTable(doc, args.tableIndex)
    const out = []
    const rowCount = table.Rows.Count
    for (let r = 1; r <= rowCount; r++) {
      // 按行遍历 Cells（不是 Cell(r,c) 坐标）：合并单元格时坐标访问会出错，Cells 数量天然可变
      const cells = table.Rows.Item(r).Cells
      const row = []
      const cellCount = cells.Count
      for (let c = 1; c <= cellCount; c++) {
        row.push(cleanCellText(cells.Item(c).Range.Text).trim())
      }
      out.push(row)
    }
    return {
      tableIndex: index,
      tableCount: count,
      rowCount: out.length,
      colCount: table.Columns.Count,
      cells: out
    }
  },

  async table_set_cell(args) {
    const { row, col } = parseCellRef(args.cell)
    const text = args.text == null ? '' : String(args.text)
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { table } = getTable(doc, args.tableIndex)
      let cell
      try {
        cell = table.Cell(row + 1, col + 1)
      } catch (e) {
        cell = null
      }
      if (!cell) {
        throw new Error(`单元格 ${args.cell} 不存在（可能越界或落在合并单元格里），请先用 office_table_read 核对`)
      }
      const cr = cell.Range
      const cur = cleanCellText(cr.Text)
      const newText = normalizeNewlines(text)
      const multiline = /\r/.test(newText)
      // 最小修订：单元格 Range 的 Start 给出绝对偏移，差异段直接偏移落笔（右到左）
      let via = null
      let edits = 0
      if (!multiline && cur) {
        const cellStart = cr.Start
        const minimal = minimalEdits(cur, newText)
        if (!minimal.length) {
          via = 'unchanged'
        } else if (!(minimal.length === 1 && minimal[0].start === 0 && minimal[0].end === cur.length)) {
          let verified = true
          for (const e of minimal) {
            if (e.start === e.end) continue
            if (String(doc.Range(cellStart + e.start, cellStart + e.end).Text) !== e.oldText) {
              verified = false
              break
            }
          }
          if (verified) {
            for (let k = minimal.length - 1; k >= 0; k--) {
              const e = minimal[k]
              if (e.start === e.end) doc.Range(cellStart + e.start, cellStart + e.start).InsertBefore(e.newText)
              else doc.Range(cellStart + e.start, cellStart + e.end).Text = e.newText
            }
            via = 'minimalRedline'
            edits = minimal.length
          }
        }
      }
      if (via == null) {
        // 整格替换兜底：优先内容子 Range（避开 \r\x07 结束符），偏移不可信时退回
        // cell.Range.Text 整赋（VBA 惯用法，Word 会保住结束符——WPS 待真机验证）
        const content = doc.Range(cr.Start, cr.Start + cur.length)
        if (String(content.Text) === cur) content.Text = newText
        else cr.Text = newText
        via = 'fullReplace'
      }
      return { cell: args.cell, via, edits }
    })
  },

  async table_add_row(args) {
    let position = Math.floor(Number(args.position))
    if (!Number.isFinite(position)) position = -1
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { table, index } = getTable(doc, args.tableIndex)
      for (let k = 0; k < count; k++) {
        const rowCount = table.Rows.Count
        if (position < 0 || position >= rowCount) table.Rows.Add() // 省略参数 = 加到末尾
        else table.Rows.Add(table.Rows.Item(position + 1)) // 插在该行之前
      }
      return { tableIndex: index, added: count, rowCount: table.Rows.Count }
    })
  },

  // 表格结构删除不产生修订（tracked:false 契约与 Office 面一致）；WPS 下修订开着会把
  // 被删行留在表中导致连删错位，所以执行期临时关修订（见 withTrackingOff 注释）
  async table_delete_row(args) {
    const position = Math.floor(Number(args.position))
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    if (!Number.isFinite(position) || position < 0) throw new Error('缺少要删的行号（position，0 开始）')
    const doc = activeDoc()
    return withTrackingOff(doc, () => {
      const { table, index } = getTable(doc, args.tableIndex)
      const rowCount = table.Rows.Count
      if (position >= rowCount) {
        throw new Error(`rowIndex ${position} 越界：表格共 ${rowCount} 行（序号从 0 开始）`)
      }
      if (rowCount - count < 1) {
        throw new Error(`表格至少要留一行，当前 ${rowCount} 行删不了 ${count} 行`)
      }
      // 同一 index 连删 count 次：删掉一行后，后面的行顶上来
      for (let k = 0; k < count; k++) table.Rows.Item(position + 1).Delete()
      return { tableIndex: index, deleted: count, rowCount: table.Rows.Count, tracked: false }
    })
  },

  async table_add_col(args) {
    let position = Math.floor(Number(args.position))
    if (!Number.isFinite(position)) position = -1
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { table, index } = getTable(doc, args.tableIndex)
      const cols = table.Columns
      for (let k = 0; k < count; k++) {
        const colCount = cols.Count
        if (position < 0 || position >= colCount) cols.Add() // 省略参数 = 加到末尾
        else cols.Add(cols.Item(position + 1)) // 插在该列之前（中间插列在 WPS 无版本门槛）
      }
      return { tableIndex: index, added: count, colCount: cols.Count }
    })
  },

  async table_delete_col(args) {
    const position = Math.floor(Number(args.position))
    const count = Math.max(1, Math.floor(Number(args.count)) || 1)
    if (!Number.isFinite(position) || position < 0) throw new Error('缺少要删的列号（position，0 开始）')
    const doc = activeDoc()
    return withTrackingOff(doc, () => {
      const { table, index } = getTable(doc, args.tableIndex)
      const cols = table.Columns
      const colCount = cols.Count
      if (position >= colCount) {
        throw new Error(`colIndex ${position} 越界：表格共 ${colCount} 列（序号从 0 开始）`)
      }
      if (colCount - count < 1) {
        throw new Error(`表格至少要留一列，当前 ${colCount} 列删不了 ${count} 列`)
      }
      for (let k = 0; k < count; k++) cols.Item(position + 1).Delete()
      return { tableIndex: index, deleted: count, colCount: cols.Count, tracked: false }
    })
  },

  // ==================== 结构（分页符/超链接/页眉页脚） ====================

  async insert_break(args) {
    const type = args.breakType === 'sectionNext' ? wdSectionBreakNextPage : wdPageBreak
    const anchorText = String(args.anchorText || '')
    const position = args.position === 'before' ? 'before' : 'after'
    const doc = activeDoc()
    return withTracking(doc, () => {
      let pos
      if (anchorText) {
        const { range } = anchorRange(doc, anchorText, {
          position,
          message: '未找到锚点文本，请确认 anchorText 与文档内容精确一致'
        })
        pos = position === 'before' ? range.Start : range.End
      } else {
        const sr = app().Selection.Range
        pos = position === 'before' ? sr.Start : sr.End
      }
      // InsertBreak 的 Range 未折叠会替换区域内容（官方原文警告），先折叠到目标点
      doc.Range(pos, pos).InsertBreak(type)
      return { inserted: true, breakType: args.breakType || 'page', anchored: !!anchorText }
    })
  },

  async set_hyperlink(args) {
    const anchorText = String(args.anchorText || '')
    const url = String(args.url || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!url) throw new Error('url 不能为空')
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { range } = anchorRange(doc, anchorText)
      // 不传 TextToDisplay：本命令语义是「给现有文本加链」，传了会替换锚点文本
      doc.Hyperlinks.Add(range, url)
      return { linked: true, url }
    })
  },

  async edit_header_footer(args) {
    const part = args.part === 'footer' ? 'footer' : 'header'
    // **没给 text 就不许动文字**（dev-board#288）：旧写法无条件整替，
    // 模型只想改对齐方式（不传 text）时，text 兜底成空串，一调用就把用户的页眉清空，
    // 返回值还报成功。显式传空串仍然是「清空」这个合法意图，两者必须分开。
    const hasText = args.text != null
    const text = hasText ? String(args.text) : ''
    const alignment = args.alignment == null ? null : toEnumValue(ALIGNMENTS, args.alignment, 'alignment')
    if (!hasText && !alignment) {
      throw new Error('edit_header_footer 需要至少给 text（要写入的文字，传空串表示清空）或 alignment 之一')
    }
    const doc = activeDoc()
    return withTracking(doc, () => {
      const section = doc.Sections.Item(1)
      const hf = (part === 'footer' ? section.Footers : section.Headers).Item(wdHeaderFooterPrimary)
      // 页眉/页脚在独立 story，偏移与正文互不相干；整替与 Office 面 insertText(replace) 同语义
      if (hasText) hf.Range.Text = normalizeNewlines(text)
      if (alignment != null) {
        // 一把设全部段落（ParagraphFormat 作用于 Range 内所有段落）
        hf.Range.ParagraphFormat.Alignment = alignment
      }
      return {
        part,
        textUpdated: hasText,
        textLength: hasText ? text.length : null,
        alignment: args.alignment || null
      }
    })
  },

  // ==================== 批注（读取/回复/解决） ====================

  async get_comments() {
    const doc = activeDoc()
    const cs = doc.Comments
    const count = cs.Count
    const comments = []
    for (let i = 1; i <= count; i++) {
      const c = cs.Item(i)
      let anchorTextVal = ''
      try {
        anchorTextVal = String((c.Scope && c.Scope.Text) || '').slice(0, 200)
      } catch (e) { /* 锚定区取不到不阻塞列表 */ }
      let isReply = false
      try {
        isReply = !!c.Ancestor
      } catch (e) { /* 老版本没有 Ancestor */ }
      comments.push({
        index: i - 1,
        // WPS 批注无 GUID：id 就是 0 起的序号字符串（与 index 同口径），
        // 序号随删除/接受塌缩——后续操作前会重新读集合
        id: String(i - 1),
        author: String(c.Author || ''),
        content: String((c.Range && c.Range.Text) || '').replace(/\r$/, ''),
        createdAt: toIso(c.Date),
        resolved: !!c.Done,
        anchorText: anchorTextVal,
        isReply
      })
    }
    return { count, comments }
  },

  async reply_comment(args) {
    const reply = String(args.reply || '')
    if (!reply) throw new Error('回复内容不能为空')
    const doc = activeDoc()
    // 每次操作前重新读集合（序号随删除/解决塌缩重排）
    const cs = doc.Comments
    const idx = resolveCommentIndex(args, cs.Count)
    const c = cs.Item(idx + 1)
    try {
      // 主路：Comment.Replies（Comments 集合）上 Add——调研标注的最高风险类推，
      // 无官方示例，真机不通就走降级
      c.Replies.Add(c.Scope, reply)
      return { replied: true }
    } catch (e) {
      try {
        // 降级：把回复文本追加进原批注正文（不是线程回复，返回值交底）
        const r = c.Range
        const prev = String(r.Text || '').replace(/\r$/, '')
        r.Text = prev ? prev + '\r' + reply : reply
        return {
          replied: true,
          via: 'appendText',
          note: '当前 WPS 版本不支持批注线程回复，回复内容已追加到原批注正文末尾'
        }
      } catch (e2) {
        throw new Error(`回复批注失败：${(e2 && e2.message) || String(e2)}`)
      }
    }
  },

  async resolve_comment(args) {
    const resolved = args.resolved !== false
    const doc = activeDoc()
    const cs = doc.Comments
    const idx = resolveCommentIndex(args, cs.Count)
    cs.Item(idx + 1).Done = resolved // Done 可读写，可反向设 false 重开
    return { resolved }
  },

  // ==================== 修订读取/接受/拒绝 ====================

  async get_revisions() {
    const doc = activeDoc()
    const rs = doc.Revisions
    const count = rs.Count
    const revisions = []
    for (let i = 1; i <= count; i++) {
      const r = rs.Item(i)
      let text = ''
      try {
        text = String((r.Range && r.Range.Text) || '').slice(0, 200)
      } catch (e) { /* 结构修订可能无文本 */ }
      const typeRaw = Number(r.Type)
      revisions.push({
        index: i - 1,
        author: String(r.Author || ''),
        date: toIso(r.Date),
        type: REVISION_TYPE_NAMES[typeRaw] || String(r.Type),
        text
      })
    }
    return { count, revisions }
  },

  async accept_revision(args) {
    const doc = activeDoc()
    const rs = doc.Revisions
    if (args.all) {
      rs.AcceptAll()
      return { acceptedAll: true }
    }
    // 每次重新读集合：Accept 单条后集合立即塌缩、序号重排
    const index = Math.floor(Number(args.revisionIndex))
    if (!Number.isFinite(index) || index < 0 || index >= rs.Count) {
      throw new Error(`revisionIndex ${args.revisionIndex} 越界：文档共 ${rs.Count} 条修订（序号从 0 开始）`)
    }
    rs.Item(index + 1).Accept()
    return { accepted: true, revisionIndex: index }
  },

  async reject_revision(args) {
    const doc = activeDoc()
    const rs = doc.Revisions
    if (args.all) {
      rs.RejectAll()
      return { rejectedAll: true }
    }
    const index = Math.floor(Number(args.revisionIndex))
    if (!Number.isFinite(index) || index < 0 || index >= rs.Count) {
      throw new Error(`revisionIndex ${args.revisionIndex} 越界：文档共 ${rs.Count} 条修订（序号从 0 开始）`)
    }
    rs.Item(index + 1).Reject()
    return { rejected: true, revisionIndex: index }
  },

  // ==================== 脚注/尾注 ====================

  async insert_footnote(args) {
    const anchorText = String(args.anchorText || '')
    const text = String(args.text || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!text) throw new Error('脚注正文内容不能为空')
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { range } = anchorRange(doc, anchorText)
      // 脚注号打在锚点末尾（折叠到 End）；Reference 传 undefined 触发自动编号
      doc.Footnotes.Add(doc.Range(range.End, range.End), undefined, text)
      return { inserted: true }
    })
  },

  async insert_endnote(args) {
    const anchorText = String(args.anchorText || '')
    const text = String(args.text || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!text) throw new Error('尾注正文内容不能为空')
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { range } = anchorRange(doc, anchorText)
      doc.Endnotes.Add(doc.Range(range.End, range.End), undefined, text)
      return { inserted: true }
    })
  },

  // ==================== 图片插入（本版不支持） ====================

  async insert_image() {
    // WPS 的 InlineShapes.AddPicture 只吃本地文件路径，没有 base64 直插通路，
    // wps.FileSystem 也没有可用的二进制写接口——不能静默吞，明确告知模型
    throw new Error('WPS 宿主暂不支持插入图片：当前版本无法从 base64 数据直接插图（本地路径方案待后续版本支持），请勿重试该命令，可改为在文中说明图片位置')
  },

  // ==================== 样式应用 ====================

  async apply_style(args) {
    const anchorText = String(args.anchorText || '')
    const styleName = String(args.styleName || '')
    if (!anchorText) throw new Error('目标文本不能为空')
    if (!styleName) throw new Error('样式名不能为空')
    const doc = activeDoc()
    return withTracking(doc, () => {
      const { offsets, needle, coords } = locateAnchorAll(doc, anchorText)
      const targetOffsets = args.applyToAll ? offsets : [offsets[0]]
      const paragraphs = targetOffsets.map((offset, k) => verifiedRange(doc, coords, offset, needle, k).Paragraphs.Item(1))
      for (const p of paragraphs) {
        try {
          // Range.Style 接受本地化样式名/整数常量/样式对象（官方原文）
          p.Range.Style = styleName
        } catch (e) {
          // 样式名不存在（常见：模型给英文名而文档是中文版）——按内置样式常量表垫底
          const builtin = BUILTIN_STYLE_BY_NAME[styleName.trim().toLowerCase()]
          if (builtin == null) {
            throw new Error(`样式「${styleName}」不存在：请使用文档里已有的样式名（中文版内置样式如「标题 1」），或改用 set_paragraph_format 的 styleBuiltIn 参数`)
          }
          p.Range.Style = builtin
        }
      }
      return { applied: paragraphs.length, totalMatches: offsets.length, styleName }
    })
  },

  // ==================== 内容控件 ====================

  async manage_content_control(args) {
    const action = String(args.action || '')
    const tag = String(args.tag || '')
    if (!tag) throw new Error('tag 不能为空')
    const doc = activeDoc()

    if (action === 'insert') {
      const anchorText = String(args.anchorText || '')
      if (!anchorText) throw new Error('insert 需要 anchorText')
      return withTracking(doc, () => {
        const { range } = anchorRange(doc, anchorText, {
          message: '未找到锚点文本，请确认 anchorText 与文档内容精确一致'
        })
        // 包裹锚点所在段落（与 Office 面的 Paragraph.insertContentControl 同语义）
        const pr = range.Paragraphs.Item(1).Range
        const cc = doc.ContentControls.Add(wdContentControlRichText, pr)
        cc.Tag = tag
        if (args.title) cc.Title = String(args.title)
        return { inserted: true, tag }
      })
    }

    // 定位：优先 SelectContentControlsByTag，退回遍历集合过滤 Tag
    let cc = null
    try {
      if (typeof doc.SelectContentControlsByTag === 'function') {
        const ccs = doc.SelectContentControlsByTag(tag)
        if (ccs && ccs.Count > 0) cc = ccs.Item(1)
      }
    } catch (e) { /* 走遍历 */ }
    if (!cc) {
      try {
        const all = doc.ContentControls
        for (let k = 1; k <= all.Count; k++) {
          const item = all.Item(k)
          if (String(item.Tag || '') === tag) {
            cc = item
            break
          }
        }
      } catch (e) { /* 集合不可用按未找到处理 */ }
    }
    if (!cc) throw new Error(`未找到 tag 为 ${tag} 的内容控件`)

    if (action === 'read') {
      return { tag, title: String(cc.Title || ''), text: String((cc.Range && cc.Range.Text) || '').replace(/\r$/, '') }
    }
    if (action === 'set_text') {
      const text = args.text == null ? '' : String(args.text)
      return withTracking(doc, () => {
        cc.Range.Text = normalizeNewlines(text)
        return { updated: true, tag }
      })
    }
    if (action === 'delete') {
      const keepContent = !!args.keepContent
      return withTracking(doc, () => {
        // 参数语义与 Office 面相反：WPS/VBA 是 Delete(DeleteContents)，传 !keepContent
        cc.Delete(!keepContent)
        return { deleted: true, tag, keepContent }
      })
    }
    throw new Error(`action 值非法：${args.action}`)
  },

  // ==================== 文档属性 ====================

  async set_document_properties(args) {
    const doc = activeDoc()
    const props = doc.BuiltInDocumentProperties
    const applied = {}
    const setProp = (key, enumVal, raw) => {
      if (raw == null) return
      props.Item(enumVal).Value = String(raw)
      applied[key] = raw
    }
    // 文档属性无修订概念，不走 withTracking（与 Office 面一致）
    setProp('title', wdPropertyTitle, args.title)
    setProp('subject', wdPropertySubject, args.subject)
    setProp('author', wdPropertyAuthor, args.author)
    setProp('keywords', wdPropertyKeywords, args.keywords)
    setProp('comments', wdPropertyComments, args.comments)
    setProp('category', wdPropertyCategory, args.category)
    return { applied }
  }
}
