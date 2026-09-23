// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Excel 两族（Office.js / WPS 表格）的**格式判定纯函数层**（dev-board#844）：
 *
 * 1. `planFormatInheritance`：excel_set_values 往表格下方追加行时，哪几行要先沿用上一行的格式。
 * 2. `normalizeOfficeCellProps` / `normalizeEtCell` + `summarizeCellFormats`：
 *    excel_get_range(withFormat) 把每格格式归一成同一套字段，再压成「列级摘要 + 例外单元格」。
 *
 * 病灶：用户让 AI 在「重大合同清单」末尾加一行，写值原语只写值，空单元格落的是默认格式——
 * 新行没边框、字体不同、对齐不同，一眼就是后加的。读取原语又只回值，模型看不到格式，
 * 就算想对齐也无从下手。
 *
 * 与 excelReplace.js 同一类角色：两族共享的判定放独立模块，同一条指令在 Excel 和 WPS 表格上
 * 必须给出一样的判定；宿主相关的读写（copyFrom / 逐属性复制）各留在各自的执行器里。
 *
 * 字段名与桌面端 sheet_read_range 对齐：fontName/fontSize/bold/italic/color/background/
 * hAlign/vAlign/numberFormat/borders/wrap。
 */

/** withFormat 一次最多扫多少格（每格一组格式项，再多就撑爆工具输出，也拖慢同步桥） */
export const MAX_FORMAT_CELLS = 400

/** 空白判定：null/undefined/空串/纯空白都算空（与用户肉眼看到的「这格是空的」一致） */
export function isBlankCell(v) {
  if (v == null) return true
  if (typeof v === 'string') return v.trim() === ''
  return false
}

/** 一行在写入列跨度内是否有内容 */
export function rowHasContent(row) {
  return Array.isArray(row) && row.some((v) => !isBlankCell(v))
}

/**
 * 算出本次写入里哪几行要先沿用上一行的格式。
 *
 * 规则（逐行、自上而下，允许级联）：目标行满足以下全部条件才沿用——
 * - inheritFormat 没有显式关掉；
 * - 写入前该行在写入列跨度内**全空**（非空行一律不动格式：那是用户已有的数据行）；
 * - 本次要写进去的值不是全空（清空一行不该顺手给它套格式）；
 * - 紧邻上方一行满足其一：
 *   a. 它本身就是本次沿用了格式的行（级联：一次追加多行，逐行沿用下去）；
 *   b. 它写入前就有内容，**再上一行**写入前也有内容，并且这两行的格式签名在写入列中
 *      至少一半的列一致（rowsFormatConsistent）——两行连续、长得一样的既有数据，才说明
 *      上方是一张表的数据区。只有一行时它很可能是表头；两行都有内容但格式对不上，
 *      多半是「标题行（合并居中）紧贴表头」——两种情形把表头的加粗/底色扩给第一条
 *      数据恰恰是错的。
 *
 * 「写入前就有内容」只认写入前的状态：表头下第一行是本次新写的，它不能反过来给
 * 下一行当格式来源——它自己的格式只是空白行的默认格式，复制它等于用空格式
 * 覆盖掉下一行可能已有的模板格式。
 *
 * 与桌面端（LOWA sheet_write_cells）同一条规则：同一条指令在两族上必须给出同样的判定。
 *
 * 执行器的调用顺序：先 formatCheckRows 看要不要读格式（没有候选行就一格都不读，
 * 不给每次写入都加开销），读了再把 rowFormats 交给本函数。
 *
 * @param {object} p
 * @param {number} p.startRow  第一行写入行的工作表行号（1 起）
 * @param {Array<Array>} p.aboveRows  紧邻上方的既有行（自上而下，0~2 行，写入列跨度内的值）
 * @param {Array<Array>} p.targetRows 写入前目标区域各行的值（与 values 同形）
 * @param {Array<Array>} p.values     本次要写的值
 * @param {boolean} [p.inheritFormat] 缺省 true
 * @param {Map<number, Array<object>>} [p.rowFormats] 工作表行号 → 该行写入列的统一格式
 *   （normalize 后的对象）。条件 b 要比的两行缺格式时按「不一致」处理——宁可不沿用，
 *   也不能凭猜把表头格式抄进数据行。
 * @returns {Array<{row:number, from:number, root:number}>}
 *   row = 目标行号；from = 紧邻上方的行号（报给模型）；root = 格式真正的出处
 *   （级联时是链条最上端那行既有数据行，执行器从它复制，效果与逐行复制相同）
 */
export function planFormatInheritance(p) {
  const formats = p && p.rowFormats instanceof Map ? p.rowFormats : new Map()
  return walkInheritance(p, (upper, lower) => rowsFormatConsistent(formats.get(upper), formats.get(lower)))
}

/**
 * 条件 b 要比格式的行号（升序去重）。为空时执行器不必读任何格式。
 * 条件 b 的判定与级联互斥（级联的上一行写入前是空的，进不了条件 b），
 * 所以「假设都一致」走一遍收集到的就是全部要比的行对。
 */
export function formatCheckRows(p) {
  const rows = new Set()
  walkInheritance(p, (upper, lower) => { rows.add(upper); rows.add(lower); return true })
  return [...rows].sort((a, b) => a - b)
}

function walkInheritance({ startRow, aboveRows, targetRows, values, inheritFormat } = {}, consistent) {
  if (inheritFormat === false) return []
  const above = Array.isArray(aboveRows) ? aboveRows : []
  const targets = Array.isArray(targetRows) ? targetRows : []
  const vals = Array.isArray(values) ? values : []
  const k = above.length
  // 合并成一条行序列：前 k 行是上方既有行，后面是目标行；pre[j] = 写入前是否有内容
  const pre = [...above.map(rowHasContent), ...vals.map((_, i) => rowHasContent(targets[i]))]
  const sheetRow = (j) => startRow - k + j
  const rootOf = new Map() // 合并序列下标 → 沿用链的 root 行号
  const out = []
  for (let i = 0; i < vals.length; i++) {
    const j = k + i
    if (pre[j]) continue
    if (!rowHasContent(vals[i])) continue
    if (j - 1 < 0) continue
    let root = null
    if (rootOf.has(j - 1)) {
      root = rootOf.get(j - 1) // 级联：不再比格式
    } else if (pre[j - 1] && j - 2 >= 0 && pre[j - 2] && consistent(sheetRow(j - 2), sheetRow(j - 1))) {
      root = sheetRow(j - 1)
    }
    if (root == null) continue
    rootOf.set(j, root)
    out.push({ row: sheetRow(j), from: sheetRow(j - 1), root })
  }
  return out
}

/**
 * 一格的格式签名：字体名 / 字号 / 加粗 / 水平对齐 / 数字格式 / 四边有无边框。
 * **刻意不含字色和底色**：状态列标红、隔行底纹在数据行里很常见，算进去会把正常的
 * 数据区判成「格式不一致」。
 */
export function formatSignature(f) {
  if (!f) return ''
  const b = f.borders || {}
  const nf = f.numberFormat == null ? '' : String(f.numberFormat).trim()
  return JSON.stringify([
    f.fontName == null ? '' : String(f.fontName),
    f.fontSize == null ? '' : Number(f.fontSize),
    !!f.bold,
    f.hAlign || 'general',
    GENERAL_FORMATS.has(nf.toLowerCase()) ? '' : nf,
    !!b.top, !!b.bottom, !!b.left, !!b.right
  ])
}

/**
 * 上下两行（写入列跨度内的统一格式数组）是否「长得一样」：签名一致的列至少占一半。
 * 任一行缺格式按不一致处理。
 */
export function rowsFormatConsistent(upper, lower) {
  if (!Array.isArray(upper) || !Array.isArray(lower) || !upper.length) return false
  const n = Math.min(upper.length, lower.length)
  let same = 0
  for (let c = 0; c < n; c++) {
    if (formatSignature(upper[c]) === formatSignature(lower[c])) same++
  }
  return same * 2 >= upper.length
}

/* ==================== 格式归一 ==================== */

/** Office.js HorizontalAlignment / VerticalAlignment → 与 format_cells 同一套小写词 */
function normAlign(raw, vertical) {
  if (raw == null || raw === '') return null
  const s = String(raw).trim().toLowerCase()
  if (vertical && s === 'center') return 'middle'
  return s
}

/** 颜色统一成大写 #RRGGBB；读不出的回 null */
function normHex(raw) {
  if (raw == null) return null
  const s = String(raw).trim()
  if (/^#[0-9a-fA-F]{6}$/.test(s)) return s.toUpperCase()
  return s ? s : null
}

/** 线宽统一小写词：hairline/thin/medium/thick */
function normWeight(raw) {
  if (raw == null || raw === '') return null
  return String(raw).trim().toLowerCase()
}

/**
 * Office.js 一格的格式 → 统一字段。入参是 getCellProperties 的单格结果形状
 * （{format:{font, fill, horizontalAlignment, verticalAlignment, wrapText, borders:{top,...}}}），
 * ExcelApi 1.9 以下逐格 load 出来的值也拼成同一形状再进来。
 */
export function normalizeOfficeCellProps(cp, numberFormat) {
  const f = (cp && cp.format) || {}
  const font = f.font || {}
  const fill = f.fill || {}
  const b = f.borders || {}
  const edge = (e) => {
    if (!e || e.style == null || String(e.style).toLowerCase() === 'none') return null
    return { weight: normWeight(e.weight) || 'thin', color: normHex(e.color) }
  }
  const noFill = fill.pattern != null && String(fill.pattern).toLowerCase() === 'none'
  return {
    fontName: font.name == null ? null : String(font.name),
    fontSize: font.size == null ? null : Number(font.size),
    bold: !!font.bold,
    italic: !!font.italic,
    color: normHex(font.color),
    background: noFill ? null : normHex(fill.color),
    hAlign: normAlign(f.horizontalAlignment, false),
    vAlign: normAlign(f.verticalAlignment, true),
    numberFormat: numberFormat == null ? null : String(numberFormat),
    wrap: !!f.wrapText,
    borders: { top: edge(b.top), bottom: edge(b.bottom), left: edge(b.left), right: edge(b.right) }
  }
}

/** WPS/VBA XlHAlign / XlVAlign 数值 → 小写词 */
const ET_H_ALIGN_NAMES = { 1: 'general', '-4131': 'left', '-4108': 'center', '-4152': 'right', 5: 'fill', '-4130': 'justify', 7: 'centeracrossselection', '-4117': 'distributed' }
const ET_V_ALIGN_NAMES = { '-4160': 'top', '-4108': 'middle', '-4107': 'bottom', '-4130': 'justify', '-4117': 'distributed' }
/** XlBorderWeight 数值 → 小写词 */
const ET_WEIGHT_NAMES = { 1: 'hairline', 2: 'thin', '-4138': 'medium', 4: 'thick' }
/** xlLineStyleNone / xlColorIndexNone */
const ET_NONE = -4142

/**
 * WPS 一格的原始读数 → 统一字段。入参是执行器逐属性读出来的原值
 * （颜色是 BGR 数值，对齐与线宽是 VBA 枚举数值），bgrToHex 由执行器注入以免本模块依赖执行器。
 *
 * raw = {fontName, fontSize, bold, italic, fontColor, interiorColorIndex, interiorColor,
 *        hAlign, vAlign, numberFormat, wrap, borders:{top:{lineStyle, weight, color}, ...}}
 */
export function normalizeEtCell(raw, bgrToHex) {
  const r = raw || {}
  const hex = (v) => (v == null || v === '' ? null : bgrToHex(v))
  const edge = (e) => {
    if (!e || e.lineStyle == null || Number(e.lineStyle) === ET_NONE) return null
    return { weight: ET_WEIGHT_NAMES[String(e.weight)] || 'thin', color: hex(e.color) }
  }
  const b = r.borders || {}
  return {
    fontName: r.fontName == null ? null : String(r.fontName),
    fontSize: r.fontSize == null ? null : Number(r.fontSize),
    bold: !!r.bold,
    italic: !!r.italic,
    color: hex(r.fontColor),
    background: Number(r.interiorColorIndex) === ET_NONE ? null : hex(r.interiorColor),
    hAlign: r.hAlign == null ? null : (ET_H_ALIGN_NAMES[String(r.hAlign)] || String(r.hAlign)),
    vAlign: r.vAlign == null ? null : (ET_V_ALIGN_NAMES[String(r.vAlign)] || String(r.vAlign)),
    numberFormat: r.numberFormat == null ? null : String(r.numberFormat),
    wrap: !!r.wrap,
    borders: { top: edge(b.top), bottom: edge(b.bottom), left: edge(b.left), right: edge(b.right) }
  }
}

/* ==================== 格式摘要 ==================== */

/** 「常规」数字格式的几种写法（WPS 中文界面回的是 G/通用格式） */
const GENERAL_FORMATS = new Set(['general', 'g/通用格式', 'g/標準', ''])

/**
 * 边框概括：四边都有 → 'all'；一边没有 → 省略；否则列出有线的边，如 'top,bottom'。
 * 线宽不全是 thin 时追加括号说明，如 'all(medium)'。颜色不在概括里（绝大多数表格是黑线）。
 */
export function summarizeBorders(borders) {
  const b = borders || {}
  const sides = ['top', 'bottom', 'left', 'right'].filter((s) => b[s])
  if (!sides.length) return null
  const label = sides.length === 4 ? 'all' : sides.join(',')
  const weights = [...new Set(sides.map((s) => b[s].weight || 'thin'))]
  if (weights.length === 1 && weights[0] === 'thin') return label
  return `${label}(${weights.join('/')})`
}

/** 统计最常见的值（平手取先出现的） */
function modeOf(list) {
  const counts = new Map()
  let best = null
  let bestN = 0
  for (const v of list) {
    const n = (counts.get(v) || 0) + 1
    counts.set(v, n)
    if (n > bestN) { best = v; bestN = n }
  }
  return { value: best, count: bestN }
}

/** 一格相对「默认」的差异项；base = 区域内最常见的字体名/字号 */
export function formatDiff(fmt, base) {
  const d = {}
  if (!fmt) return d
  if (fmt.fontName != null && fmt.fontName !== base.fontName) d.fontName = fmt.fontName
  if (fmt.fontSize != null && fmt.fontSize !== base.fontSize) d.fontSize = fmt.fontSize
  if (fmt.bold) d.bold = true
  if (fmt.italic) d.italic = true
  if (fmt.color && fmt.color !== '#000000') d.color = fmt.color
  if (fmt.background && fmt.background !== '#FFFFFF') d.background = fmt.background
  if (fmt.hAlign && fmt.hAlign !== 'general') d.hAlign = fmt.hAlign
  if (fmt.vAlign && fmt.vAlign !== 'bottom') d.vAlign = fmt.vAlign
  if (fmt.numberFormat != null && !GENERAL_FORMATS.has(String(fmt.numberFormat).trim().toLowerCase())) {
    d.numberFormat = fmt.numberFormat
  }
  const borders = summarizeBorders(fmt.borders)
  if (borders) d.borders = borders
  if (fmt.wrap) d.wrap = true
  return d
}

/** 字段顺序固定的序列化，作为「两格格式是否相同」的判等键 */
function diffKey(d) {
  return JSON.stringify(Object.keys(d).sort().map((k) => [k, d[k]]))
}

/** 0 起列号 → 列字母 */
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

/**
 * withFormat 该扫多大一块：总格数不超过 MAX_FORMAT_CELLS，先保列（一行的格式要看全）再按行截。
 */
export function formatScanShape(rows, cols, cap = MAX_FORMAT_CELLS) {
  const scanCols = Math.max(0, Math.min(cols, cap))
  const scanRows = scanCols ? Math.max(0, Math.min(rows, Math.max(1, Math.floor(cap / scanCols)))) : 0
  return { rows: scanRows, cols: scanCols, truncated: scanRows < rows || scanCols < cols }
}

/**
 * 把逐格的统一格式压成给模型看的摘要：
 * - baseFont：区域内最常见的字体名/字号（各格只在与它不同时才报 fontName/fontSize）；
 * - columns：每列最常见的格式差异项（至少两格一致才算列级格式；整列都是默认则不出现）；
 * - cells：与所在列摘要不同的单元格（值为该格完整的差异项，{} 表示这格是默认格式）。
 * 典型表格下，表头行作为例外出现在 cells 里，数据区的边框/对齐落在 columns 里——
 * 模型据此就知道新行该长什么样。
 *
 * @param {Array<Array<object>>} formats 统一字段的二维数组（行 x 列）
 * @param {number} startRow 0 起行号（第一行扫描行）
 * @param {number} startCol 0 起列号
 */
export function summarizeCellFormats(formats, startRow, startCol) {
  const grid = Array.isArray(formats) ? formats : []
  const all = grid.flat().filter(Boolean)
  const base = {
    fontName: modeOf(all.map((f) => f.fontName).filter((v) => v != null)).value,
    fontSize: modeOf(all.map((f) => f.fontSize).filter((v) => v != null)).value
  }
  const columns = {}
  const cells = {}
  const cols = grid.length ? Math.max(...grid.map((r) => (r ? r.length : 0))) : 0
  for (let c = 0; c < cols; c++) {
    const diffs = grid.map((row) => formatDiff(row && row[c], base))
    const keys = diffs.map(diffKey)
    const mode = modeOf(keys)
    let colKey = diffKey({})
    if (mode.count >= 2) {
      colKey = mode.value
      const colDiff = diffs[keys.indexOf(mode.value)]
      if (Object.keys(colDiff).length) columns[columnLetter(startCol + c)] = colDiff
    }
    for (let r = 0; r < diffs.length; r++) {
      if (keys[r] === colKey) continue
      cells[columnLetter(startCol + c) + (startRow + r + 1)] = diffs[r]
    }
  }
  const out = { baseFont: base }
  if (Object.keys(columns).length) out.columns = columns
  if (Object.keys(cells).length) out.cells = cells
  return out
}
