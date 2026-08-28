/**
 * WPS 表格宿主 HANDLERS：office_command 的 excel_* 全 26 命令在 WPS 加载项
 * JSAPI（jsaddon，taskpane 网页内同步对象模型）上的实现。
 *
 * 契约以 officeExecutor.js 为准绳：命令名、参数、返回值形状与 Office.js 版
 * 逐一对齐，行为差异只体现在返回值的 note/via 等说明字段里。
 *
 * WPS JSAPI 三条折算规则（全文件通用，来自调研 wps-et-api.md）：
 * 1. 集合索引一律 .Item(...) 函数调用（Worksheets.Item / Cells.Item / Borders.Item）；
 * 2. 带参属性按函数调用（Address(false,false)、Resize(r,c)、Offset(r,c)）；
 * 3. Value 在 JSAPI 里是只读方法——取值 rng.Value2，赋值只能 rng.Value2 = ...。
 *
 * 性能口径：跨进程桥约 0.2ms/次调用，大区域读写必须 Value2 二维数组一次进出，
 * 禁止逐格循环（get_range / set_values / search / get_overview / set_formulas 均按此写）。
 *
 * 枚举不依赖 wps.Enum（其键名规律官方未成文）：本文件自建数值常量表，
 * 数值与 VBA 完全一致（官方枚举分册抄录）。
 *
 * 真机验证要点（按优先级，钉死后可删注记）：
 * 1. Value2 多格读回的 JS 形状（二维/单行降维/空格编组）——read2D 已做防御性归一；
 * 2. 透视表全链路 PivotCaches().Create → CreatePivotTable → 字段编排（编组链最长，
 *    任何一环失败可能表现为 undefined 而非异常）；
 * 3. 公式错误值（#DIV/0! 等）经 Value2 的编组形态；
 * 4. Worksheet.Delete / Unprotect 的弹窗抑制效果；
 * 5. Range.Sort 的 Key1 传 Range 对象编组；Names.Add RefersTo 字符串路线；
 * 6. colorScale 的 ColorScaleCriteria 精调（失败已降级为 ET 默认三色刻度）；
 * 7. 空工作表的 UsedRange 行为（VBA 口径返回 A1 单格而非 null）。
 */

/* ==================== 入口与通用 helper ==================== */

/** 表格宿主 Application 入口（仅在 WPS 表格进程内有效） */
function app() {
  return wps.EtApplication()
}

/** 按名取工作表；名为空取活动工作表（与 officeExecutor.resolveSheet 同口径） */
function resolveSheet(sheetName) {
  const wb = app().ActiveWorkbook
  if (!wb) throw new Error('当前没有打开的工作簿')
  return sheetName ? wb.Worksheets.Item(sheetName) : wb.ActiveSheet
}

/** 0 起的行列号转 A1 地址（与 officeExecutor.cellAddress 同实现） */
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

/** 0 起的列号转字母（供行列范围拼 A1 引用，如 "C:E"） */
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

/** A1 单元格引用解析为 1 起行列号（容忍 $ 与表名前缀） */
function parseCellRef(addr) {
  const bare = String(addr).split('!').pop().replace(/\$/g, '')
  const m = /^([A-Za-z]+)(\d+)$/.exec(bare)
  if (!m) throw new Error(`单元格地址非法：${addr}`)
  let col = 0
  for (const ch of m[1].toUpperCase()) col = col * 26 + (ch.charCodeAt(0) - 64)
  return { row: Number(m[2]), col }
}

/**
 * '#RRGGBB' 十六进制 → WPS/VBA 的 BGR 长整型（r + g*256 + b*65536）。
 * 契约层颜色参数与 Office 版一致收 #RRGGBB，落 WPS 前必须转换。
 */
export function hexToBgr(hex) {
  const bare = String(hex).trim().replace(/^#/, '')
  if (!/^[0-9a-fA-F]{6}$/.test(bare)) {
    throw new Error(`颜色格式非法：${hex}（应为 #RRGGBB）`)
  }
  const n = parseInt(bare, 16)
  return ((n & 0xff) << 16) | (n & 0xff00) | ((n >> 16) & 0xff)
}

/** BGR 长整型 → '#RRGGBB'（读回方向的反向转换） */
export function bgrToHex(bgr) {
  const n = Number(bgr) >>> 0
  const r = n & 0xff
  const g = (n >> 8) & 0xff
  const b = (n >> 16) & 0xff
  return '#' + [r, g, b].map((x) => x.toString(16).padStart(2, '0')).join('').toUpperCase()
}

/**
 * 区域读为二维数组（Value2 一次性批量取回）。
 * 多格返回 JS 二维数组、单格返回标量为主形态；单行/单列是否降维官方未成文，
 * 这里做防御性归一，保证出参恒为 rows x cols 的二维数组。
 */
function read2D(rng) {
  const rows = rng.Rows.Count
  const cols = rng.Columns.Count
  const v = rng.Value2
  if (rows === 1 && cols === 1) return [[Array.isArray(v) ? (Array.isArray(v[0]) ? v[0][0] : v[0]) : v]]
  if (!Array.isArray(v)) return [[v]]
  if (!Array.isArray(v[0])) {
    // 一维形态：单行降成 [a,b,...]，单列降成 [a,b,...]，按区域形状还原
    if (rows === 1) return [v]
    if (cols === 1) return v.map((x) => [x])
  }
  return v
}

/** 破坏性操作（删表/合并丢值等）抑制确认弹窗，finally 恢复原状态 */
function withAlertsSuppressed(fn) {
  const a = app()
  let prev = true
  try { prev = a.DisplayAlerts !== false } catch (e) { /* 读不到按默认 true */ }
  a.DisplayAlerts = false
  try {
    return fn()
  } finally {
    a.DisplayAlerts = prev
  }
}

/** 空工作表判定：UsedRange 为空引用，或退化为 1x1 且无值（VBA 口径空表返回 A1 单格） */
function usedRangeIsEmpty(used) {
  if (!used) return true
  if (used.Rows.Count === 1 && used.Columns.Count === 1) {
    const v = used.Value2
    return v == null || v === ''
  }
  return false
}

/* ==================== 数值常量表（VBA 同值，不依赖 wps.Enum） ==================== */

/** excel_get_range 返回值的行数上限（与 officeExecutor.MAX_EXCEL_RESULT_ROWS 同值） */
const MAX_EXCEL_RESULT_ROWS = 500
/** excel_search 展示的命中上限（与 officeExecutor.MAX_SEARCH_HITS 同值） */
const MAX_SEARCH_HITS = 20

/** XlHAlign：horizontalAlignment 小写短名 → 数值 */
const ET_H_ALIGN = { left: -4131, center: -4108, right: -4152 }
/** XlVAlign：verticalAlignment 小写短名 → 数值（契约的 middle 对应 xlVAlignCenter） */
const ET_V_ALIGN = { top: -4160, middle: -4108, bottom: -4107 }

/** XlBordersIndex */
const ET_BORDER_INDEX = {
  EdgeTop: 8, EdgeBottom: 9, EdgeLeft: 7, EdgeRight: 10,
  InsideVertical: 11, InsideHorizontal: 12
}
/** borders → 参与的边集合（none 复用 all 的边去清空），键名与 Office 版同表 */
const ET_BORDER_LOCATIONS = {
  all: ['EdgeTop', 'EdgeBottom', 'EdgeLeft', 'EdgeRight', 'InsideHorizontal', 'InsideVertical'],
  outside: ['EdgeTop', 'EdgeBottom', 'EdgeLeft', 'EdgeRight'],
  inside: ['InsideHorizontal', 'InsideVertical']
}
/** XlBorderWeight：style 小写短名 → 数值 */
const ET_BORDER_WEIGHTS = { thin: 2, medium: -4138, thick: 4 }
/** XlLineStyle */
const xlContinuous = 1
const xlLineStyleNone = -4142

/** XlInsertShiftDirection / XlDeleteShiftDirection */
const xlShiftDown = -4121
const xlShiftUp = -4162
const xlShiftToRight = -4161
const xlShiftToLeft = -4159

/** XlSortOrder / XlYesNoGuess */
const xlAscending = 1
const xlDescending = 2
const xlYes = 1
const xlNo = 2

/** XlFormatConditionType / XlFormatConditionOperator（数据验证的 Operator 复用同一枚举） */
const xlCellValue = 1
const xlBetween = 1
const ET_CF_OPERATORS = { greaterthan: 5, lessthan: 6, between: xlBetween, equalto: 3 }

/** XlDVType / XlDVAlertStyle */
const ET_DV_TYPES = { wholenumber: 1, list: 3, date: 4 }
const xlValidAlertStop = 1

/** XlChartType：chartType 小写短名 → 数值（v1 起步四种，与 Office 版同集） */
const ET_CHART_TYPES = { column: 51 /* xlColumnClustered */, line: 4 /* xlLine */, pie: 5 /* xlPie */, bar: 57 /* xlBarClustered */ }

/** XlPivotTableSourceType / XlPivotFieldOrientation */
const xlDatabase = 1
const xlRowField = 1

/** 合法 action 清单（校验文案与 Office 版同腔调） */
const ET_EDIT_ROWS_COLS_ACTIONS = ['insert_rows', 'delete_rows', 'insert_cols', 'delete_cols', 'set_width', 'set_height']
const ET_MERGE_ACTIONS = ['merge', 'unmerge']
const ET_SHEET_ACTIONS = ['add', 'rename', 'delete', 'move', 'activate']
const ET_FREEZE_ACTIONS = ['freeze_rows', 'freeze_cols', 'freeze_at', 'unfreeze']
const ET_AUTOFILTER_ACTIONS = ['apply', 'clear', 'remove']

/**
 * 列宽单位换算：Office 版契约 size 单位是磅，WPS ColumnWidth 单位是标准字体
 * 字符宽（VBA 语义）。换算依据：Excel 默认列宽 8.43 字符 = 48 磅，
 * 即 1 字符 ≈ 48 / 8.43 ≈ 5.69 磅。行高两边都是磅，直落无需换算。
 */
const POINTS_PER_CHAR = 5.69

/** colorScale 默认三色刻度色（低到高：红-黄-绿），与 Office 版视觉口径一致 */
const ET_CF_COLOR_SCALE_HEX = ['#F8696B', '#FFEB84', '#63BE7B']

/* ==================== HANDLERS ==================== */

export const WPS_ET_HANDLERS = {

  // ==================== 读写/查找 ====================

  async excel_get_range(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const sheet = resolveSheet(sheetName)
    const name = String(sheet.Name)
    const rng = rangeAddress ? sheet.Range(rangeAddress) : sheet.UsedRange
    if (!rangeAddress && usedRangeIsEmpty(rng)) {
      return { sheet: name, address: '', rows: 0, cols: 0, values: [], note: '工作表为空' }
    }
    if (!rng) throw new Error(`无法解析区域：${rangeAddress}`)
    let values = read2D(rng)
    let truncated = false
    if (values.length > MAX_EXCEL_RESULT_ROWS) {
      values = values.slice(0, MAX_EXCEL_RESULT_ROWS)
      truncated = true
    }
    return {
      sheet: name,
      address: rng.Address(false, false),
      rows: rng.Rows.Count,
      cols: rng.Columns.Count,
      values,
      truncated
    }
  },

  async excel_set_values(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const values = args.values
    if (!rangeAddress) throw new Error('区域地址不能为空')
    if (!Array.isArray(values) || !values.length || !Array.isArray(values[0])) {
      throw new Error('values 必须是非空二维数组')
    }
    const sheet = resolveSheet(sheetName)
    let rng = sheet.Range(rangeAddress)
    const rows = values.length
    const cols = values[0].length
    const rngRows = rng.Rows.Count
    const rngCols = rng.Columns.Count
    if (rngRows === 1 && rngCols === 1 && (rows > 1 || cols > 1)) {
      // 单元格起点：按 values 尺寸向右下展开（Resize 带参属性按函数调用）
      rng = rng.Resize(rows, cols)
    } else if (rngRows !== rows || rngCols !== cols) {
      throw new Error(`区域尺寸（${rngRows}x${rngCols}）与 values 尺寸（${rows}x${cols}）不一致`)
    }
    // 赋值只能走 Value2（JSAPI 里 Value 是只读方法）；二维数组一次性写入
    rng.Value2 = values
    return { written: rows * cols, address: rng.Address(false, false) }
  },

  async excel_search(args) {
    const query = String(args.query || '')
    const sheetName = String(args.sheetName || '')
    if (!query) throw new Error('查找文本不能为空')
    const sheet = resolveSheet(sheetName)
    const name = String(sheet.Name)
    const used = sheet.UsedRange
    if (usedRangeIsEmpty(used)) return { sheet: name, count: 0, matches: [] }
    const values = read2D(used)
    // WPS 的 Row/Column 是 1 起，cellAddress 收 0 起——先归零再拼
    const baseRow = used.Row - 1
    const baseCol = used.Column - 1
    const needle = query.toLowerCase()
    const matches = []
    let count = 0
    for (let r = 0; r < values.length; r++) {
      const row = values[r] || []
      for (let c = 0; c < row.length; c++) {
        const cell = row[c]
        if (cell == null) continue
        const text = String(cell)
        if (text.toLowerCase().includes(needle)) {
          count++
          if (matches.length < MAX_SEARCH_HITS) {
            matches.push({ address: cellAddress(baseRow + r, baseCol + c), value: text.slice(0, 500) })
          }
        }
      }
    }
    return { sheet: name, count, shown: matches.length, matches }
  },

  // ==================== 格式/结构 ====================

  async excel_format_cells(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const sheet = resolveSheet(sheetName)
    const rng = sheet.Range(rangeAddress)
    const applied = {}
    if (args.fontName) { rng.Font.Name = String(args.fontName); applied.fontName = args.fontName }
    if (args.fontSize != null) { rng.Font.Size = Number(args.fontSize); applied.fontSize = args.fontSize }
    if (args.bold != null) { rng.Font.Bold = !!args.bold; applied.bold = !!args.bold }
    if (args.italic != null) { rng.Font.Italic = !!args.italic; applied.italic = !!args.italic }
    if (args.fontColor) { rng.Font.Color = hexToBgr(args.fontColor); applied.fontColor = args.fontColor }
    if (args.fillColor) { rng.Interior.Color = hexToBgr(args.fillColor); applied.fillColor = args.fillColor }
    if (args.horizontalAlignment) {
      const key = String(args.horizontalAlignment).trim().toLowerCase()
      const v = ET_H_ALIGN[key]
      if (!v) throw new Error(`horizontalAlignment 值非法：${args.horizontalAlignment}（合法值：${Object.keys(ET_H_ALIGN).join('/')}）`)
      rng.HorizontalAlignment = v
      applied.horizontalAlignment = key
    }
    if (args.verticalAlignment) {
      const key = String(args.verticalAlignment).trim().toLowerCase()
      const v = ET_V_ALIGN[key]
      if (!v) throw new Error(`verticalAlignment 值非法：${args.verticalAlignment}（合法值：${Object.keys(ET_V_ALIGN).join('/')}）`)
      rng.VerticalAlignment = v
      applied.verticalAlignment = key
    }
    if (args.wrapText != null) { rng.WrapText = !!args.wrapText; applied.wrapText = !!args.wrapText }
    if (args.numberFormat) {
      // WPS 的 NumberFormat 单字符串作用于整区域（无需像 Office.js 铺二维数组）；
      // 用英文格式代码（"General"/"0.00" 等），与 NumberFormatLocal 是两个口径
      const fmt = String(args.numberFormat)
      rng.NumberFormat = fmt
      applied.numberFormat = fmt
    }
    return { address: rng.Address(false, false), applied }
  },

  async excel_set_borders(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const borders = String(args.borders || '').trim().toLowerCase()
    if (borders !== 'none' && !ET_BORDER_LOCATIONS[borders]) {
      throw new Error(`borders 值非法：${args.borders}（合法值：all/outside/inside/none）`)
    }
    const style = String(args.style || 'thin').trim().toLowerCase()
    const weight = ET_BORDER_WEIGHTS[style] || ET_BORDER_WEIGHTS.thin
    const color = String(args.color || '#000000')
    const colorBgr = hexToBgr(color)
    const sheet = resolveSheet(sheetName)
    const rng = sheet.Range(rangeAddress)
    const ids = borders === 'none' ? ET_BORDER_LOCATIONS.all : ET_BORDER_LOCATIONS[borders]
    for (const id of ids) {
      const b = rng.Borders.Item(ET_BORDER_INDEX[id])
      if (borders === 'none') {
        b.LineStyle = xlLineStyleNone
      } else {
        b.LineStyle = xlContinuous
        b.Weight = weight
        b.Color = colorBgr
      }
    }
    const result = { address: rng.Address(false, false), borders }
    if (borders !== 'none') {
      result.style = ET_BORDER_WEIGHTS[style] ? style : 'thin'
      result.color = color
    }
    return result
  },

  async excel_edit_rows_cols(args) {
    const action = String(args.action || '').trim().toLowerCase()
    if (!ET_EDIT_ROWS_COLS_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：${ET_EDIT_ROWS_COLS_ACTIONS.join('/')}）`)
    }
    const index = Math.floor(Number(args.index))
    if (!Number.isFinite(index) || index < 0) throw new Error('index 不能为负')
    let count = args.count == null ? 1 : Math.floor(Number(args.count))
    if (!Number.isFinite(count) || count < 1) count = 1
    const needsSize = action === 'set_width' || action === 'set_height'
    let size = null
    if (needsSize) {
      size = Number(args.size)
      if (!Number.isFinite(size) || size <= 0) throw new Error('size 须为正数')
    }
    const sheet = resolveSheet(String(args.sheetName || ''))
    const isRowAction = action === 'insert_rows' || action === 'delete_rows' || action === 'set_height'
    const range = isRowAction
      ? sheet.Rows.Item(`${index + 1}:${index + count}`)
      : sheet.Columns.Item(`${columnLetter(index)}:${columnLetter(index + count - 1)}`)
    const result = { action, index, count }
    if (action === 'insert_rows') range.Insert(xlShiftDown)
    else if (action === 'delete_rows') range.Delete(xlShiftUp)
    else if (action === 'insert_cols') range.Insert(xlShiftToRight)
    else if (action === 'delete_cols') range.Delete(xlShiftToLeft)
    else if (action === 'set_width') {
      // 契约 size 单位是磅（对齐 Office 版），WPS ColumnWidth 是字符宽——按 1 字符≈5.69 磅折算
      range.ColumnWidth = size / POINTS_PER_CHAR
      result.size = size
      result.note = `列宽已按 1 字符宽≈${POINTS_PER_CHAR} 磅折算写入（${size} 磅 ≈ ${(size / POINTS_PER_CHAR).toFixed(2)} 字符宽）`
    } else if (action === 'set_height') {
      // 行高两边单位都是磅，直落
      range.RowHeight = size
      result.size = size
    }
    return result
  },

  async excel_merge_cells(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const action = String(args.action || '').trim().toLowerCase()
    if (!ET_MERGE_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：merge/unmerge）`)
    }
    const sheet = resolveSheet(sheetName)
    const rng = sheet.Range(rangeAddress)
    // merge 丢弃非左上角值时 ET 可能弹提示，统一抑制
    withAlertsSuppressed(() => {
      if (action === 'merge') rng.Merge()
      else rng.UnMerge()
    })
    return { address: rng.Address(false, false), action }
  },

  async excel_sort_range(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const keyColumn = Math.floor(Number(args.keyColumn))
    if (!Number.isFinite(keyColumn) || keyColumn < 0) throw new Error('keyColumn 不能为负')
    const ascending = args.ascending !== false
    const hasHeader = !!args.hasHeader
    const sheet = resolveSheet(sheetName)
    const rng = sheet.Range(rangeAddress)
    // Key1 收 Range 对象（不收数字下标）：区域内相对列 0 起 → Columns.Item 1 起
    const keyRng = rng.Columns.Item(keyColumn + 1)
    // Range.Sort(Key1, Order1, Key2, Type, Order2, Key3, Order3, Header, ...)，可选参数 null 占位
    rng.Sort(keyRng, ascending ? xlAscending : xlDescending,
      null, null, null, null, null,
      hasHeader ? xlYes : xlNo)
    return { address: rng.Address(false, false), keyColumn, ascending, hasHeader }
  },

  async excel_manage_sheets(args) {
    const action = String(args.action || '').trim().toLowerCase()
    if (!ET_SHEET_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：${ET_SHEET_ACTIONS.join('/')}）`)
    }
    const sheetName = String(args.sheetName || '')
    if (action !== 'add' && !sheetName) throw new Error('sheetName 不能为空')
    const wb = app().ActiveWorkbook
    if (!wb) throw new Error('当前没有打开的工作簿')
    const sheets = wb.Worksheets
    const result = { action }
    let target
    if (action === 'add') {
      // WPS 的 Sheets.Add 无参默认插在活动表之前，显式 After 末表对齐 Office.js 的末尾追加
      target = sheets.Add(null, sheets.Item(sheets.Count))
      if (sheetName) target.Name = sheetName
    } else if (action === 'delete') {
      if (sheets.Count <= 1) {
        throw new Error('无法删除：工作簿至少要保留一张工作表')
      }
      target = sheets.Item(sheetName)
      // Worksheet.Delete 会弹确认对话框，必须抑制并 finally 恢复
      withAlertsSuppressed(() => { target.Delete() })
      return result
    } else {
      target = sheets.Item(sheetName)
      if (action === 'rename') {
        const newName = String(args.newName || '')
        if (!newName) throw new Error('newName 不能为空')
        target.Name = newName
      } else if (action === 'move') {
        const position = Math.floor(Number(args.position))
        if (!Number.isFinite(position) || position < 0) throw new Error('position 不能为负')
        // Move 必须给 Before/After 之一（都不给会移到新工作簿）；position 0 起
        if (position === 0) target.Move(sheets.Item(1))
        else target.Move(null, sheets.Item(Math.min(position, sheets.Count)))
      } else if (action === 'activate') {
        target.Activate()
      }
    }
    // WPS 的 Index 是 1 起，出参减 1 对齐 Office.js 的 position 0 起
    result.name = String(target.Name)
    result.position = target.Index - 1
    return result
  },

  async excel_freeze_panes(args) {
    const action = String(args.action || '').trim().toLowerCase()
    if (!ET_FREEZE_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：${ET_FREEZE_ACTIONS.join('/')}）`)
    }
    const sheetName = String(args.sheetName || '')
    const sheet = resolveSheet(sheetName)
    // 冻结是 Window 级状态，作用于活动表——指定表时先激活（与 Office.js 表级 API 的行为差异）
    if (sheetName) sheet.Activate()
    const win = app().ActiveWindow
    if (action === 'unfreeze') {
      win.FreezePanes = false
      win.Split = false
      return { action }
    }
    // 重设前先解除既有冻结，保证 SplitRow/SplitColumn 生效
    win.FreezePanes = false
    if (action === 'freeze_rows' || action === 'freeze_cols') {
      let count = Math.floor(Number(args.count))
      if (!Number.isFinite(count) || count < 1) count = 1
      if (action === 'freeze_rows') { win.SplitRow = count; win.SplitColumn = 0 }
      else { win.SplitColumn = count; win.SplitRow = 0 }
      win.FreezePanes = true
      return { action, count }
    }
    // freeze_at：冻结指定单元格左上方区域，折算成 SplitRow/SplitColumn（免依赖选区语义）
    const cellAddr = String(args.cellAddress || '')
    if (!cellAddr) throw new Error('cellAddress 不能为空')
    const { row, col } = parseCellRef(cellAddr)
    win.SplitRow = row - 1
    win.SplitColumn = col - 1
    win.FreezePanes = true
    return { action, cellAddress: cellAddr }
  },

  async excel_set_formulas(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const formulas = args.formulas
    if (!rangeAddress) throw new Error('区域地址不能为空')
    if (!Array.isArray(formulas) || !formulas.length || !Array.isArray(formulas[0])) {
      throw new Error('formulas 必须是非空二维数组')
    }
    const sheet = resolveSheet(sheetName)
    let rng = sheet.Range(rangeAddress)
    const rows = formulas.length
    const cols = formulas[0].length
    const rngRows = rng.Rows.Count
    const rngCols = rng.Columns.Count
    if (rngRows === 1 && rngCols === 1 && (rows > 1 || cols > 1)) {
      rng = rng.Resize(rows, cols)
    } else if (rngRows !== rows || rngCols !== cols) {
      throw new Error(`区域尺寸（${rngRows}x${rngCols}）与 formulas 尺寸（${rows}x${cols}）不一致`)
    }
    // Formula 属性走 en-US 文法（逗号分隔、英文函数名、跨表 'Sheet name'!A1），与 Office 版直通
    rng.Formula = formulas
    // 写入后读回 Value2 批量扫描 # 开头的错误值（#DIV/0! 等），契约同 officeExecutor
    const values = read2D(rng)
    const baseRow = rng.Row - 1
    const baseCol = rng.Column - 1
    const formulaErrors = []
    for (let r = 0; r < values.length; r++) {
      const row = values[r] || []
      for (let c = 0; c < row.length; c++) {
        const v = row[c]
        if (typeof v === 'string' && v.startsWith('#')) {
          formulaErrors.push({ address: cellAddress(baseRow + r, baseCol + c), value: v })
        }
      }
    }
    const result = { written: rows * cols, address: rng.Address(false, false) }
    if (formulaErrors.length) result.formulaErrors = formulaErrors
    return result
  },

  async excel_get_overview() {
    const wb = app().ActiveWorkbook
    if (!wb) throw new Error('当前没有打开的工作簿')
    const activeName = String(wb.ActiveSheet.Name)
    const n = wb.Worksheets.Count
    const sheets = []
    for (let i = 1; i <= n; i++) {
      const s = wb.Worksheets.Item(i)
      const u = s.UsedRange
      sheets.push({
        name: String(s.Name),
        active: String(s.Name) === activeName,
        usedRange: usedRangeIsEmpty(u)
          ? null
          : { address: u.Address(false, false), rows: u.Rows.Count, cols: u.Columns.Count }
      })
    }
    return { sheetCount: n, sheets }
  },

  async excel_select_range(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const sheet = resolveSheet(sheetName)
    // 跨表 Select 必须先激活目标表，否则报错
    if (sheetName) sheet.Activate()
    const rng = sheet.Range(rangeAddress)
    rng.Select()
    return { address: rng.Address(false, false) }
  },

  async excel_set_autofilter(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const action = String(args.action || '').trim().toLowerCase()
    if (!ET_AUTOFILTER_ACTIONS.includes(action)) {
      throw new Error(`action 值非法：${args.action}（合法值：apply/clear/remove）`)
    }
    if (action === 'apply' && !rangeAddress) throw new Error('apply 需要 rangeAddress')
    const sheet = resolveSheet(sheetName)
    if (action === 'apply') {
      // 无参 AutoFilter() 是「切换」语义——先撤掉既有箭头再套，保证幂等为「开」
      sheet.AutoFilterMode = false
      sheet.Range(rangeAddress).AutoFilter()
    } else if (action === 'clear') {
      // ShowAllData 在无筛选条件时报错，先查 FilterMode（与 AutoFilterMode 互相独立）
      if (sheet.FilterMode) sheet.ShowAllData()
    } else {
      sheet.AutoFilterMode = false
    }
    const result = { action }
    if (action === 'apply') result.rangeAddress = rangeAddress
    return result
  },

  async excel_conditional_format(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const action = String(args.action || 'apply').trim().toLowerCase()
    const sheet = resolveSheet(sheetName)
    const fcs = sheet.Range(rangeAddress).FormatConditions
    if (action === 'clearall') {
      fcs.Delete()
      return { action: 'clearAll' }
    }
    // apply：每次先清空该区域现有规则再套用新规则，不叠加（与 Office 版同口径）
    fcs.Delete()
    const ruleType = String(args.ruleType || '').trim().toLowerCase()
    if (ruleType === 'cellvalue') {
      const operator = ET_CF_OPERATORS[String(args.operator || '').trim().toLowerCase()]
      if (!operator) throw new Error(`operator 值非法：${args.operator}（合法值：greaterThan/lessThan/between/equalTo）`)
      const fc = operator === xlBetween
        ? fcs.Add(xlCellValue, operator, String(args.value1), String(args.value2))
        : fcs.Add(xlCellValue, operator, String(args.value1))
      fc.Interior.Color = hexToBgr(String(args.fillColor || '#FFC7CE'))
    } else if (ruleType === 'colorscale') {
      const cs = fcs.AddColorScale(3)
      // ColorScaleCriteria 精调是类推用法，真机若不通降级为 ET 默认三色刻度
      try {
        for (let i = 0; i < 3; i++) {
          cs.ColorScaleCriteria.Item(i + 1).FormatColor.Color = hexToBgr(ET_CF_COLOR_SCALE_HEX[i])
        }
      } catch (e) {
        // 保持 ET 默认三色刻度，视觉与桌面端略有偏差
      }
    } else {
      throw new Error(`ruleType 值非法：${args.ruleType}（合法值：cellValue/colorScale）`)
    }
    return { action: 'apply', ruleType }
  },

  // ==================== 批注（WPS 只有老式单条 Comment，无线程/回复/解决语义） ====================

  async excel_add_comment(args) {
    const sheetName = String(args.sheetName || '')
    const cellAddr = String(args.cellAddress || '')
    const comment = String(args.comment || '')
    if (!cellAddr) throw new Error('cellAddress 不能为空')
    if (!comment) throw new Error('批注内容不能为空')
    const sheet = resolveSheet(sheetName)
    const rng = sheet.Range(cellAddr)
    // 已有批注时 AddComment 会报错，先删旧的（覆盖语义）
    const existing = rng.Comment
    if (existing) existing.Delete()
    rng.AddComment(comment)
    return { cellAddress: cellAddr, added: true }
  },

  async excel_get_comments(args) {
    const scope = args.scope === 'workbook' ? 'workbook' : 'sheet'
    const wb = app().ActiveWorkbook
    if (!wb) throw new Error('当前没有打开的工作簿')
    const targets = []
    if (scope === 'workbook') {
      const n = wb.Worksheets.Count
      for (let i = 1; i <= n; i++) targets.push(wb.Worksheets.Item(i))
    } else {
      targets.push(resolveSheet(String(args.sheetName || '')))
    }
    const comments = []
    for (const sheet of targets) {
      const cms = sheet.Comments
      const count = cms ? cms.Count : 0
      for (let i = 1; i <= count; i++) {
        const c = cms.Item(i)
        const entry = {
          cellAddress: c.Parent.Address(false, false),
          content: String(c.Text() || ''),
          author: String(c.Author || ''),
          // WPS 老式批注没有创建时间/解决状态/回复模型，按契约字段降级为恒定值
          createdAt: null,
          resolved: false,
          replies: []
        }
        if (scope === 'workbook') entry.sheet = String(sheet.Name)
        comments.push(entry)
      }
    }
    return { count: comments.length, comments }
  },

  async excel_reply_comment(args) {
    const sheetName = String(args.sheetName || '')
    const cellAddr = String(args.cellAddress || '')
    const reply = String(args.reply || '')
    if (!cellAddr) throw new Error('cellAddress 不能为空')
    if (!reply) throw new Error('回复内容不能为空')
    const sheet = resolveSheet(sheetName)
    const c = sheet.Range(cellAddr).Comment
    if (!c) throw new Error('该单元格没有批注')
    // 降级实现：WPS 老式批注无回复模型，把回复追加为批注正文的一行
    const existing = String(c.Text() || '')
    c.Text(existing + '\n回复：' + reply)
    return { cellAddress: cellAddr, replied: true, via: 'appendText' }
  },

  async excel_resolve_comment() {
    // WPS 老式批注对象没有 resolved 语义（无线程批注模型），明确报不支持
    throw new Error('WPS 表格批注不支持标记解决')
  },

  async excel_delete_comment(args) {
    const sheetName = String(args.sheetName || '')
    const cellAddr = String(args.cellAddress || '')
    if (!cellAddr) throw new Error('cellAddress 不能为空')
    const sheet = resolveSheet(sheetName)
    const c = sheet.Range(cellAddr).Comment
    if (!c) throw new Error('该单元格没有批注')
    c.Delete()
    return { cellAddress: cellAddr, deleted: true }
  },

  // ==================== 数据验证 ====================

  async excel_set_data_validation(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const action = String(args.action || 'apply').trim().toLowerCase()
    if (!rangeAddress) throw new Error('区域地址不能为空')
    const sheet = resolveSheet(sheetName)
    const rng = sheet.Range(rangeAddress)
    const v = rng.Validation
    if (action === 'clear') {
      v.Delete()
      return { action: 'clear' }
    }
    const type = String(args.type || '').trim().toLowerCase()
    // 已有规则时 Add 会报错，先删旧规则
    v.Delete()
    if (type === 'list') {
      const source = String(args.listSource || '')
      if (!source) throw new Error('type=list 时 listSource 不能为空')
      // Formula1 = 逗号分隔值列表 或 "=$A$1:$A$5" 工作表引用；Operator 对 list 无实义，按官方示例传 xlBetween 占位
      v.Add(ET_DV_TYPES.list, xlValidAlertStop, xlBetween, source)
      v.InCellDropdown = true
    } else if (type === 'wholenumber' || type === 'date') {
      const operator = ET_CF_OPERATORS[String(args.operator || '').trim().toLowerCase()]
      if (!operator) throw new Error(`operator 值非法：${args.operator}`)
      if (operator === ET_CF_OPERATORS.between) {
        v.Add(ET_DV_TYPES[type], xlValidAlertStop, operator, String(args.value1), String(args.value2))
      } else {
        v.Add(ET_DV_TYPES[type], xlValidAlertStop, operator, String(args.value1))
      }
    } else {
      throw new Error(`type 值非法：${args.type}（合法值：wholeNumber/list/date）`)
    }
    return { action: 'apply', type, address: rng.Address(false, false) }
  },

  // ==================== 图表 ====================

  async excel_add_chart(args) {
    const sheetName = String(args.sheetName || '')
    const dataRangeAddress = String(args.dataRangeAddress || '')
    if (!dataRangeAddress) throw new Error('数据源区域不能为空')
    const typeKey = String(args.chartType || '').trim().toLowerCase()
    const chartType = ET_CHART_TYPES[typeKey]
    if (!chartType) throw new Error(`chartType 值非法：${args.chartType}（合法值：column/line/pie/bar）`)
    const sheet = resolveSheet(sheetName)
    // ChartObjects 是方法（带括号）；Add(Left, Top, Width, Height) 四参必选，单位磅
    const co = sheet.ChartObjects().Add(50, 40, 360, 220)
    const chart = co.Chart
    chart.SetSourceData(sheet.Range(dataRangeAddress))
    chart.ChartType = chartType
    if (args.title) {
      // HasTitle 前置是硬要求（不置 true 直接设 ChartTitle 报错）
      chart.HasTitle = true
      chart.ChartTitle.Text = String(args.title)
    }
    return { added: true, name: String(co.Name), chartType: typeKey }
  },

  // ==================== 命名区域 ====================

  async excel_define_name(args) {
    const sheetName = String(args.sheetName || '')
    const action = String(args.action || '').trim().toLowerCase()
    const name = String(args.name || '')
    if (!name) throw new Error('name 不能为空')
    if (action !== 'add' && action !== 'remove') throw new Error(`action 值非法：${args.action}（合法值：add/remove）`)
    const wb = app().ActiveWorkbook
    if (!wb) throw new Error('当前没有打开的工作簿')
    if (action === 'add') {
      const rangeAddress = String(args.rangeAddress || '')
      if (!rangeAddress) throw new Error('add 需要 rangeAddress')
      const sheet = resolveSheet(sheetName)
      const rng = sheet.Range(rangeAddress)
      // RefersTo 走 A1 字符串路线（表名加引号防空格），Range 对象编组待真机验证
      wb.Names.Add(name, "='" + String(sheet.Name) + "'!" + rng.Address())
      return { action: 'add', name }
    }
    let nm
    try {
      nm = wb.Names.Item(name)
    } catch (e) {
      nm = null
    }
    if (!nm) throw new Error(`未找到命名区域：${name}`)
    nm.Delete()
    return { action: 'remove', name }
  },

  // ==================== 工作表保护 ====================

  async excel_protect_sheet(args) {
    const sheetName = String(args.sheetName || '')
    const action = String(args.action || '').trim().toLowerCase()
    const password = args.password ? String(args.password) : undefined
    if (action !== 'protect' && action !== 'unprotect') throw new Error(`action 值非法：${args.action}（合法值：protect/unprotect）`)
    const sheet = resolveSheet(sheetName)
    if (action === 'protect') {
      sheet.Protect(password)
    } else {
      // 有密码保护且不传密码时 ET 会弹输入框，密码错误抛异常照透传
      sheet.Unprotect(password)
    }
    return { action }
  },

  // ==================== 行列分组 ====================

  async excel_group_rows_cols(args) {
    const sheetName = String(args.sheetName || '')
    const rangeAddress = String(args.rangeAddress || '')
    const action = String(args.action || '').trim().toLowerCase()
    const by = String(args.by || '').trim().toLowerCase()
    if (!rangeAddress) throw new Error('rangeAddress 不能为空')
    if (action !== 'group' && action !== 'ungroup') throw new Error(`action 值非法：${args.action}（合法值：group/ungroup）`)
    if (by !== 'rows' && by !== 'cols') throw new Error(`by 值非法：${args.by}（合法值：rows/cols）`)
    const sheet = resolveSheet(sheetName)
    // 大纲分组要求整行/整列区域，EntireRow/EntireColumn 归一化后 "A1:C5"/"2:5"/"C:E" 皆合法
    const target = by === 'rows'
      ? sheet.Range(rangeAddress).EntireRow
      : sheet.Range(rangeAddress).EntireColumn
    if (action === 'group') target.Group()
    else target.Ungroup()
    return { action, by }
  },

  // ==================== 透视表 ====================

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
    const wb = app().ActiveWorkbook
    if (!wb) throw new Error('当前没有打开的工作簿')
    const sheet = resolveSheet(sheetName)
    // 真机验证清单第二位：PivotCaches().Create → CreatePivotTable → 字段编排编组链最长，
    // 任何一环编组失败可能表现为 undefined 而非异常，写入后要读回校验
    const src = "'" + String(sheet.Name) + "'!" + sourceRangeAddress
    // PivotCaches 是方法不是属性（带括号）；Version 省略 = xlPivotTableVersion12
    const cache = wb.PivotCaches().Create(xlDatabase, src)
    const name = args.pivotName ? String(args.pivotName) : `PivotTable_${Date.now()}`
    const pivot = cache.CreatePivotTable(sheet.Range(destinationCellAddress), name)
    if (!pivot) throw new Error('透视表创建失败：CreatePivotTable 未返回对象')
    for (const field of rowFields) {
      const pf = pivotFieldOrThrow(pivot, field)
      pf.Orientation = xlRowField
    }
    for (const field of valueFields) {
      pivot.AddDataField(pivotFieldOrThrow(pivot, field))
    }
    return { added: true, name: String(pivot.Name || name), rowFields, valueFields }
  }
}

/** 取透视表字段，取不到时给出与 Office 版同款的排障提示 */
function pivotFieldOrThrow(pivot, field) {
  let pf
  try {
    pf = pivot.PivotFields(String(field))
  } catch (e) {
    pf = null
  }
  if (!pf) {
    throw new Error(`未找到透视表字段：${field}（字段名须与源区域首行列标题完全一致，可先用 office_excel_get_range 核对）`)
  }
  return pf
}
