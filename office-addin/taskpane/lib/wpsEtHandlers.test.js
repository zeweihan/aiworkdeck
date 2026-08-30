/**
 * wpsEtHandlers.js 的单元测试（node 自带 test runner，零依赖）：
 *   node --test office-addin/taskpane/lib/wpsEtHandlers.test.js
 *
 * 手法：mock globalThis.wps（EtApplication 返回假对象模型），逐命令断言
 * JSAPI 调用形态与返回值契约。重点盯三折算规则（.Item 函数调用/带参属性
 * 函数化/赋值走 Value2）、颜色 BGR 转换、DisplayAlerts 抑制与恢复、
 * 批注降级路径、先清后套等 WPS 特有行为。真机行为（Value2 编组形状等）
 * 无法在此覆盖，见 wpsEtHandlers.js 文件头的真机验证要点。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { WPS_ET_HANDLERS, hexToBgr, bgrToHex } from './wpsEtHandlers.js'

/* ==================== mock 工具 ==================== */

/** 造一个假 Application：DisplayAlerts 带 setter 日志，供抑制/恢复断言 */
function makeApp({ workbook, window: win } = {}) {
  const alertsLog = []
  let alerts = true
  const appObj = { ActiveWorkbook: workbook, ActiveWindow: win, _alertsLog: alertsLog }
  Object.defineProperty(appObj, 'DisplayAlerts', {
    get() { return alerts },
    set(v) { alerts = v; alertsLog.push(v) }
  })
  return appObj
}

/** 安装 globalThis.wps，返回恢复函数 */
function installWps(appObj) {
  const original = globalThis.wps
  globalThis.wps = { EtApplication: () => appObj }
  return () => {
    if (original === undefined) delete globalThis.wps
    else globalThis.wps = original
  }
}

/** 造一个假 Range（属性按需覆写） */
function makeRange(overrides = {}) {
  const rng = {
    Rows: { Count: 1 },
    Columns: { Count: 1, Item() { return makeRange() } },
    Row: 1,
    Column: 1,
    Value2: undefined,
    Address() { return 'A1' },
    Font: {},
    Interior: {},
    ...overrides
  }
  return rng
}

/** 造一个假工作表 */
function makeSheet(name, overrides = {}) {
  return { Name: name, ...overrides }
}

/** 造 Worksheets 集合（按 1 起下标或名字取） */
function makeSheetsCollection(sheetList) {
  return {
    get Count() { return sheetList.length },
    Item(key) {
      if (typeof key === 'number') return sheetList[key - 1]
      const found = sheetList.find((s) => s.Name === key)
      if (!found) throw new Error(`未找到工作表：${key}`)
      return found
    }
  }
}

/** 一条龙：单表工作簿环境，返回 {appObj, sheet, restore} */
function makeSingleSheetEnv(sheetOverrides = {}, appOverrides = {}) {
  const sheet = makeSheet('Sheet1', sheetOverrides)
  const workbook = {
    ActiveSheet: sheet,
    Worksheets: makeSheetsCollection([sheet])
  }
  const appObj = makeApp({ workbook, ...appOverrides })
  const restore = installWps(appObj)
  return { appObj, sheet, workbook, restore }
}

/* ==================== 颜色转换纯函数 ==================== */

test('hexToBgr：#RRGGBB 转 BGR 长整型（VBA RGB 字节序）', () => {
  assert.equal(hexToBgr('#FF0000'), 0x0000ff)   // 纯红 = 255
  assert.equal(hexToBgr('#00FF00'), 0x00ff00)   // 纯绿
  assert.equal(hexToBgr('#0000FF'), 0xff0000)   // 纯蓝
  assert.equal(hexToBgr('#336699'), 0x996633)
  assert.equal(hexToBgr('FFFFFF'), 0xffffff)    // 容忍无 # 前缀
})

test('bgrToHex：反向转换与 hexToBgr 互逆', () => {
  assert.equal(bgrToHex(hexToBgr('#F8696B')), '#F8696B')
  assert.equal(bgrToHex(0x0000ff), '#FF0000')
  assert.equal(bgrToHex(0), '#000000')
})

test('hexToBgr：非法输入抛中文错误', () => {
  assert.throws(() => hexToBgr('#FFF'), /颜色格式非法/)
  assert.throws(() => hexToBgr('red'), /颜色格式非法/)
})

/* ==================== excel_get_range ==================== */

test('excel_get_range：二维值批量读回与地址口径', async () => {
  const rng = makeRange({
    Rows: { Count: 2 },
    Columns: { Count: 2 },
    Value2: [[1, 'x'], [null, 2]],
    Address(rowAbs, colAbs) {
      assert.equal(rowAbs, false)
      assert.equal(colAbs, false)
      return 'A1:B2'
    }
  })
  const { restore } = makeSingleSheetEnv({ Range: (addr) => { assert.equal(addr, 'A1:B2'); return rng } })
  try {
    const out = await WPS_ET_HANDLERS.excel_get_range({ rangeAddress: 'A1:B2' })
    assert.deepEqual(out, {
      sheet: 'Sheet1', address: 'A1:B2', rows: 2, cols: 2,
      values: [[1, 'x'], [null, 2]], truncated: false
    })
  } finally { restore() }
})

test('excel_get_range：空工作表返回 note（UsedRange 退化为无值单格）', async () => {
  const used = makeRange({ Value2: null })
  const { restore } = makeSingleSheetEnv({ UsedRange: used })
  try {
    const out = await WPS_ET_HANDLERS.excel_get_range({})
    assert.equal(out.note, '工作表为空')
    assert.equal(out.rows, 0)
    assert.deepEqual(out.values, [])
  } finally { restore() }
})

test('excel_get_range：单行一维返回被归一为二维', async () => {
  const rng = makeRange({
    Rows: { Count: 1 },
    Columns: { Count: 3 },
    Value2: [1, 2, 3],
    Address() { return 'A1:C1' }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_get_range({ rangeAddress: 'A1:C1' })
    assert.deepEqual(out.values, [[1, 2, 3]])
  } finally { restore() }
})

/* ==================== excel_set_values ==================== */

test('excel_set_values：二维数组整体经 Value2 写入', async () => {
  const rng = makeRange({
    Rows: { Count: 2 },
    Columns: { Count: 2 },
    Address() { return 'A1:B2' }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A1:B2', values: [[1, 2], [3, 4]] })
    assert.deepEqual(rng.Value2, [[1, 2], [3, 4]])
    assert.deepEqual(out, { written: 4, address: 'A1:B2' })
  } finally { restore() }
})

test('excel_set_values：单元格起点按 values 尺寸 Resize 展开', async () => {
  const resized = makeRange({
    Rows: { Count: 2 },
    Columns: { Count: 2 },
    Address() { return 'B2:C3' }
  })
  let resizeArgs = null
  const rng = makeRange({
    Rows: { Count: 1 },
    Columns: { Count: 1 },
    Resize(r, c) { resizeArgs = [r, c]; return resized }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'B2', values: [['a', 'b'], ['c', 'd']] })
    assert.deepEqual(resizeArgs, [2, 2])
    assert.deepEqual(resized.Value2, [['a', 'b'], ['c', 'd']])
    assert.equal(out.address, 'B2:C3')
  } finally { restore() }
})

test('excel_set_values：尺寸不一致与空参数的错误路径', async () => {
  const rng = makeRange({ Rows: { Count: 3 }, Columns: { Count: 1 } })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A1:A3', values: [[1, 2]] }),
      /区域尺寸（3x1）与 values 尺寸（1x2）不一致/
    )
    await assert.rejects(WPS_ET_HANDLERS.excel_set_values({ values: [[1]] }), /区域地址不能为空/)
    await assert.rejects(WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A1', values: 'x' }), /values 必须是非空二维数组/)
  } finally { restore() }
})

/* ==================== excel_search ==================== */

test('excel_search：JS 层全表扫描，基址按 1 起 Row/Column 折算', async () => {
  const used = makeRange({
    Rows: { Count: 2 },
    Columns: { Count: 2 },
    Row: 2,
    Column: 2,
    Value2: [['hello', 'world'], ['HELLOX', 3]]
  })
  const { restore } = makeSingleSheetEnv({ UsedRange: used })
  try {
    const out = await WPS_ET_HANDLERS.excel_search({ query: 'hello' })
    assert.equal(out.count, 2)
    assert.equal(out.shown, 2)
    assert.deepEqual(out.matches.map((m) => m.address), ['B2', 'B3'])
    assert.equal(out.sheet, 'Sheet1')
  } finally { restore() }
})

test('excel_search：空查找串报错', async () => {
  const { restore } = makeSingleSheetEnv({})
  try {
    await assert.rejects(WPS_ET_HANDLERS.excel_search({ query: '' }), /查找文本不能为空/)
  } finally { restore() }
})

/* ==================== excel_format_cells ==================== */

test('excel_format_cells：颜色转 BGR、对齐转数值枚举', async () => {
  const rng = makeRange({ Address() { return 'A1:B2' } })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_format_cells({
      rangeAddress: 'A1:B2',
      fontColor: '#FF0000',
      fillColor: '#0000FF',
      bold: true,
      horizontalAlignment: 'center',
      verticalAlignment: 'middle',
      numberFormat: '0.00'
    })
    assert.equal(rng.Font.Color, 0x0000ff)        // 红 → BGR 255
    assert.equal(rng.Interior.Color, 0xff0000)    // 蓝 → BGR 16711680
    assert.equal(rng.Font.Bold, true)
    assert.equal(rng.HorizontalAlignment, -4108)  // xlHAlignCenter
    assert.equal(rng.VerticalAlignment, -4108)    // xlVAlignCenter
    assert.equal(rng.NumberFormat, '0.00')
    assert.equal(out.applied.fontColor, '#FF0000')
    assert.equal(out.applied.horizontalAlignment, 'center')
  } finally { restore() }
})

test('excel_format_cells：非法对齐值报错', async () => {
  const rng = makeRange({})
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_format_cells({ rangeAddress: 'A1', horizontalAlignment: 'justify' }),
      /horizontalAlignment 值非法/
    )
  } finally { restore() }
})

/* ==================== excel_set_borders ==================== */

test('excel_set_borders：outside 四边挂线，Borders.Item 收数值枚举', async () => {
  const borderCalls = {}
  const rng = makeRange({
    Borders: {
      Item(idx) {
        borderCalls[idx] = borderCalls[idx] || {}
        return borderCalls[idx]
      }
    },
    Address() { return 'A1:C3' }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_borders({
      rangeAddress: 'A1:C3', borders: 'outside', style: 'thick', color: '#00FF00'
    })
    // xlEdgeTop=8 / xlEdgeBottom=9 / xlEdgeLeft=7 / xlEdgeRight=10
    assert.deepEqual(Object.keys(borderCalls).map(Number).sort((a, b) => a - b), [7, 8, 9, 10])
    for (const b of Object.values(borderCalls)) {
      assert.equal(b.LineStyle, 1)      // xlContinuous
      assert.equal(b.Weight, 4)         // xlThick
      assert.equal(b.Color, 0x00ff00)
    }
    assert.equal(out.style, 'thick')
  } finally { restore() }
})

test('excel_set_borders：none 走六边清空；非法值报错', async () => {
  const borderCalls = {}
  const rng = makeRange({
    Borders: { Item(idx) { borderCalls[idx] = borderCalls[idx] || {}; return borderCalls[idx] } },
    Address() { return 'A1' }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    await WPS_ET_HANDLERS.excel_set_borders({ rangeAddress: 'A1', borders: 'none' })
    assert.equal(Object.keys(borderCalls).length, 6)
    for (const b of Object.values(borderCalls)) assert.equal(b.LineStyle, -4142)  // xlLineStyleNone
    await assert.rejects(
      WPS_ET_HANDLERS.excel_set_borders({ rangeAddress: 'A1', borders: 'diagonal' }),
      /borders 值非法/
    )
  } finally { restore() }
})

/* ==================== excel_edit_rows_cols ==================== */

test('excel_edit_rows_cols：列宽按实测仿射式折算（磅 = 5.625×字符 + 3.35）', async () => {
  // 真机实测（WPS 12.1.0.28043）：字符宽 5→31.5 磅、10→59.6、20→115.85、40→228.35，
  // 是仿射关系不是正比。旧口径按 48/5.69 折算，窄列偏差可达 10%。
  const colRange = {}
  let colKey = null
  const { restore } = makeSingleSheetEnv({
    Columns: { Item(key) { colKey = key; return colRange } }
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_edit_rows_cols({ action: 'set_width', index: 2, size: 48 })
    assert.equal(colKey, 'C:C')
    assert.ok(Math.abs(colRange.ColumnWidth - (48 - 3.35) / 5.625) < 1e-9)
    assert.equal(out.size, 48)
    assert.match(out.note, /字符宽/)
  } finally { restore() }
})

test('excel_edit_rows_cols：列宽落笔后读回实际磅数并就地校正一次', async () => {
  // 斜率随工作簿标准字体会变，所以落笔后读回 Width、解出真实内边距再回代。
  // 这个 mock 的内边距是 10 磅（不是常量里的 3.35），校正后必须命中目标。
  const PAD = 10
  const colRange = {
    _cw: 0,
    get ColumnWidth() { return this._cw },
    set ColumnWidth(v) { this._cw = v },
    get Width() { return 5.625 * this._cw + PAD }
  }
  const { restore } = makeSingleSheetEnv({
    Columns: { Item() { return colRange } }
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_edit_rows_cols({ action: 'set_width', index: 0, size: 60 })
    assert.ok(Math.abs(colRange.Width - 60) < 0.01, `校正后应命中 60 磅，实际 ${colRange.Width}`)
    assert.match(out.note, /实际 60\.0 磅/)
  } finally { restore() }
})

test('excel_edit_rows_cols：一次设多列时不许把列宽算成 0（Range.Width 是总宽，不是单列宽）', async () => {
  // dev-board#288：`Range.Width` 在多列区间上返回的是**这几列的总和**。旧实现拿它当
  // 单列宽去解内边距，算出的 padding 大得离谱，回代后的 chars 变成负数被夹成 0——
  // `ColumnWidth = 0` 在 Excel/WPS 语义里就是**把这几列藏起来**，返回值还照报成功。
  // 用户的表格凭空少三列，最难查的那一类。
  const PAD = 10
  const COUNT = 3
  const state = { cw: 0 }
  const singleCol = { get Width() { return 5.625 * state.cw + PAD } }
  const colRange = {
    get ColumnWidth() { return state.cw },
    set ColumnWidth(v) { state.cw = v },
    // 多列区间的 Width = 各列之和
    get Width() { return COUNT * (5.625 * state.cw + PAD) },
    Columns: { Item(i) { return i === 1 ? singleCol : singleCol } }
  }
  const { restore } = makeSingleSheetEnv({
    Columns: { Item() { return colRange } }
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_edit_rows_cols({
      action: 'set_width', index: 0, count: COUNT, size: 60
    })
    assert.ok(state.cw > 0, `列宽绝不能被写成 0（会把列藏起来），实际 ${state.cw}`)
    assert.ok(Math.abs(singleCol.Width - 60) < 0.01,
      `按单列校正后应命中 60 磅，实际 ${singleCol.Width}`)
    assert.equal(out.size, 60)
  } finally { restore() }
})

test('excel_edit_rows_cols：插行走 Rows.Item + Insert(xlShiftDown)', async () => {
  let insertArg = null
  let rowKey = null
  const rowRange = { Insert(shift) { insertArg = shift } }
  const { restore } = makeSingleSheetEnv({
    Rows: { Item(key) { rowKey = key; return rowRange } }
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_edit_rows_cols({ action: 'insert_rows', index: 1, count: 2 })
    assert.equal(rowKey, '2:3')
    assert.equal(insertArg, -4121)  // xlShiftDown
    assert.deepEqual(out, { action: 'insert_rows', index: 1, count: 2 })
  } finally { restore() }
})

/* ==================== excel_merge_cells ==================== */

test('excel_merge_cells：Merge 前抑制弹窗、结束后恢复', async () => {
  let merged = false
  const rng = makeRange({ Merge() { merged = true }, Address() { return 'A1:B2' } })
  const { appObj, restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_merge_cells({ rangeAddress: 'A1:B2', action: 'merge' })
    assert.equal(merged, true)
    assert.deepEqual(appObj._alertsLog, [false, true])   // 先抑制、后恢复
    assert.equal(appObj.DisplayAlerts, true)
    assert.deepEqual(out, { address: 'A1:B2', action: 'merge' })
  } finally { restore() }
})

/* ==================== excel_sort_range ==================== */

test('excel_sort_range：Key1 传区域内相对列 Range，且把持久排序设置钉死', async () => {
  // Header/Order/Orientation/SortMethod 是被保存复用的持久设置（MS 官方明文）。
  // 不显式传就继承用户上一次手动排序的选择——律师手动做过一次「按行排序（从左到右）」
  // 之后，AI 的每次排序都会把整张表横着重排。与 Word 面 Find 宽松度是同一类地雷。
  let sortArgs = null
  const keyRng = { _tag: 'keyRange' }
  const rng = makeRange({
    Columns: { Count: 3, Item(i) { assert.equal(i, 2); return keyRng } },
    Sort(...args) { sortArgs = args },
    Address() { return 'A1:C5' }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_sort_range({
      rangeAddress: 'A1:C5', keyColumn: 1, ascending: false, hasHeader: true
    })
    assert.equal(sortArgs[0], keyRng)
    assert.equal(sortArgs[1], 2)     // xlDescending
    assert.deepEqual(sortArgs.slice(2, 7), [null, null, null, null, null])
    assert.equal(sortArgs[7], 1)     // xlYes
    assert.equal(sortArgs[9], false) // MatchCase：不区分大小写
    assert.equal(sortArgs[10], 1)    // xlSortColumns：数据行上下重排（不是列左右重排）
    assert.equal(sortArgs[11], 1)    // xlPinYin：中文按拼音
    assert.deepEqual(out, { address: 'A1:C5', keyColumn: 1, ascending: false, hasHeader: true })
  } finally { restore() }
})


test('excel_manage_sheets：最后一张表拒删（可读中文错误）', async () => {
  const { restore } = makeSingleSheetEnv({})
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_manage_sheets({ action: 'delete', sheetName: 'Sheet1' }),
      /无法删除：工作簿至少要保留一张工作表/
    )
  } finally { restore() }
})

test('excel_manage_sheets：删除时抑制确认弹窗，出错也 finally 恢复', async () => {
  let deleted = false
  const s1 = makeSheet('Sheet1', { Delete() { deleted = true } })
  const s2 = makeSheet('Sheet2', { Delete() { throw new Error('删除被拒绝') } })
  const workbook = { ActiveSheet: s1, Worksheets: makeSheetsCollection([s1, s2]) }
  const appObj = makeApp({ workbook })
  const restore = installWps(appObj)
  try {
    const out = await WPS_ET_HANDLERS.excel_manage_sheets({ action: 'delete', sheetName: 'Sheet1' })
    assert.equal(deleted, true)
    assert.deepEqual(appObj._alertsLog, [false, true])
    assert.deepEqual(out, { action: 'delete' })
    // Delete 抛异常时 DisplayAlerts 也必须恢复
    appObj._alertsLog.length = 0
    await assert.rejects(
      WPS_ET_HANDLERS.excel_manage_sheets({ action: 'delete', sheetName: 'Sheet2' }),
      /删除被拒绝/
    )
    assert.equal(appObj.DisplayAlerts, true)
    assert.deepEqual(appObj._alertsLog, [false, true])
  } finally { restore() }
})

test('excel_manage_sheets：add 显式 After 末表追加，位置出参 0 起', async () => {
  let addArgs = null
  const s1 = makeSheet('Sheet1', { Index: 1 })
  const added = makeSheet('', { Index: 2 })
  const sheetList = [s1]
  const sheets = {
    get Count() { return sheetList.length },
    Item(key) { return typeof key === 'number' ? sheetList[key - 1] : sheetList.find((s) => s.Name === key) },
    Add(before, after) { addArgs = [before, after]; sheetList.push(added); return added }
  }
  const workbook = { ActiveSheet: s1, Worksheets: sheets }
  const restore = installWps(makeApp({ workbook }))
  try {
    const out = await WPS_ET_HANDLERS.excel_manage_sheets({ action: 'add', sheetName: '汇总' })
    assert.deepEqual(addArgs, [null, s1])       // After = 原末表
    assert.equal(added.Name, '汇总')
    assert.deepEqual(out, { action: 'add', name: '汇总', position: 1 })
  } finally { restore() }
})

/* ==================== excel_freeze_panes ==================== */

test('excel_freeze_panes：freeze_rows / freeze_at / unfreeze 三态', async () => {
  const win = {}
  const { restore } = makeSingleSheetEnv({}, { window: win })
  try {
    let out = await WPS_ET_HANDLERS.excel_freeze_panes({ action: 'freeze_rows', count: 2 })
    assert.equal(win.SplitRow, 2)
    assert.equal(win.SplitColumn, 0)
    assert.equal(win.FreezePanes, true)
    assert.deepEqual(out, { action: 'freeze_rows', count: 2 })

    out = await WPS_ET_HANDLERS.excel_freeze_panes({ action: 'freeze_at', cellAddress: 'C3' })
    assert.equal(win.SplitRow, 2)      // 冻结 C3 左上 = 前 2 行
    assert.equal(win.SplitColumn, 2)   // 前 2 列
    assert.equal(win.FreezePanes, true)

    out = await WPS_ET_HANDLERS.excel_freeze_panes({ action: 'unfreeze' })
    assert.equal(win.FreezePanes, false)
    assert.equal(win.Split, false)
    assert.deepEqual(out, { action: 'unfreeze' })
  } finally { restore() }
})

/* ==================== excel_set_formulas ==================== */

test('excel_set_formulas：用 SpecialCells 找错误单元格，显示文本取 Range.Text', async () => {
  // 真机实测：错误值经 Value2 回来的是 CVErr 数值码（#DIV/0! 是 -2146826281），
  // **不是** '#DIV/0!' 字符串——旧写法扫 Value2 的 '#' 开头，在 WPS 上永远判不出错误。
  const errCell = { Text: '#DIV/0!', Address() { return 'A1' } }
  let specialArgs = null
  const rng = makeRange({
    Rows: { Count: 1 },
    Columns: { Count: 2 },
    Address() { return 'A1:B1' },
    SpecialCells(type, value) {
      specialArgs = [type, value]
      return {
        Areas: { Count: 1, Item() { return { Rows: { Count: 1 }, Columns: { Count: 1 }, Cells: { Item: () => errCell } } } }
      }
    }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_formulas({
      rangeAddress: 'A1:B1', formulas: [['=1/0', '=2*3']]
    })
    assert.deepEqual(rng.Formula, [['=1/0', '=2*3']])
    assert.equal(out.written, 2)
    assert.deepEqual(specialArgs, [-4123, 16]) // xlCellTypeFormulas / xlErrors
    assert.deepEqual(out.formulaErrors, [{ address: 'A1', value: '#DIV/0!' }])
  } finally { restore() }
})

test('excel_set_formulas：区域内没有错误单元格时 SpecialCells 抛异常属正常路径', async () => {
  const rng = makeRange({
    Rows: { Count: 1 },
    Columns: { Count: 2 },
    Address() { return 'A1:B1' },
    SpecialCells() { throw new Error('未找到单元格') }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_formulas({
      rangeAddress: 'A1:B1', formulas: [['=1+1', '=2*3']]
    })
    assert.equal(out.written, 2)
    assert.equal(out.formulaErrors, undefined)
  } finally { restore() }
})

test('excel_set_formulas：单格区间不许走 SpecialCells（它会改为搜索整张表）', async () => {
  // VBA 语义实测确认：对单格调用 SpecialCells 会搜索整个已用区域，
  // 会把别处的旧错误算到本次写入头上。单格必须直接读 Text 判。
  let specialCalled = false
  const rng = makeRange({
    Rows: { Count: 1 },
    Columns: { Count: 1 },
    Text: '#NAME?',
    Address() { return 'A1' },
    SpecialCells() { specialCalled = true; throw new Error('不该被调用') }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_formulas({
      rangeAddress: 'A1', formulas: [['=NOSUCHFN(1)']]
    })
    assert.equal(specialCalled, false)
    assert.deepEqual(out.formulaErrors, [{ address: 'A1', value: '#NAME?' }])
  } finally { restore() }
})

/* ==================== excel_get_overview ==================== */

test('excel_get_overview：逐表聚合，空表 usedRange 为 null', async () => {
  const s1 = makeSheet('Sheet1', {
    UsedRange: makeRange({ Rows: { Count: 3 }, Columns: { Count: 2 }, Value2: [[1]], Address() { return 'A1:B3' } })
  })
  const s2 = makeSheet('Sheet2', { UsedRange: makeRange({ Value2: null }) })
  const workbook = { ActiveSheet: s1, Worksheets: makeSheetsCollection([s1, s2]) }
  const restore = installWps(makeApp({ workbook }))
  try {
    const out = await WPS_ET_HANDLERS.excel_get_overview()
    assert.equal(out.sheetCount, 2)
    assert.deepEqual(out.sheets[0], { name: 'Sheet1', active: true, usedRange: { address: 'A1:B3', rows: 3, cols: 2 } })
    assert.deepEqual(out.sheets[1], { name: 'Sheet2', active: false, usedRange: null })
  } finally { restore() }
})

/* ==================== excel_set_autofilter ==================== */

test('excel_set_autofilter：apply 先撤旧箭头再套（幂等为开）', async () => {
  const calls = []
  let filterCalled = false
  const rng = makeRange({ AutoFilter() { filterCalled = true; calls.push('autofilter') } })
  const sheet = makeSheet('Sheet1', { Range: () => rng })
  Object.defineProperty(sheet, 'AutoFilterMode', {
    set(v) { calls.push(['mode', v]) },
    get() { return false }
  })
  const workbook = { ActiveSheet: sheet, Worksheets: makeSheetsCollection([sheet]) }
  const restore = installWps(makeApp({ workbook }))
  try {
    const out = await WPS_ET_HANDLERS.excel_set_autofilter({ action: 'apply', rangeAddress: 'A1:C10' })
    assert.deepEqual(calls, [['mode', false], 'autofilter'])
    assert.equal(filterCalled, true)
    assert.deepEqual(out, { action: 'apply', rangeAddress: 'A1:C10' })
  } finally { restore() }
})

/* ==================== excel_conditional_format ==================== */

test('excel_conditional_format：apply 先清后套，不叠加', async () => {
  const calls = []
  const fc = { Interior: {} }
  const fcs = {
    Delete() { calls.push('delete') },
    Add(type, op, f1, f2) { calls.push(['add', type, op, f1, f2]); return fc }
  }
  const rng = makeRange({ FormatConditions: fcs })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_conditional_format({
      rangeAddress: 'A1:A10', ruleType: 'cellValue', operator: 'greaterThan', value1: 10, fillColor: '#FF0000'
    })
    assert.deepEqual(calls, ['delete', ['add', 1, 5, '10', undefined]])  // 先 Delete 再 Add(xlCellValue, xlGreater)
    assert.equal(fc.Interior.Color, 0x0000ff)
    assert.deepEqual(out, { action: 'apply', ruleType: 'cellvalue' })
  } finally { restore() }
})

test('excel_conditional_format：clearAll 只清不套', async () => {
  const calls = []
  const fcs = { Delete() { calls.push('delete') }, Add() { calls.push('add') } }
  const rng = makeRange({ FormatConditions: fcs })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_conditional_format({ rangeAddress: 'A1:A10', action: 'clearAll' })
    assert.deepEqual(calls, ['delete'])
    assert.deepEqual(out, { action: 'clearAll' })
  } finally { restore() }
})

/* ==================== 批注（降级路径） ==================== */

test('excel_add_comment：已有批注先删再加（覆盖语义）', async () => {
  let oldDeleted = false
  let addedText = null
  const rng = makeRange({
    Comment: { Delete() { oldDeleted = true } },
    AddComment(text) { addedText = text }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_add_comment({ cellAddress: 'B2', comment: '待核对' })
    assert.equal(oldDeleted, true)
    assert.equal(addedText, '待核对')
    assert.deepEqual(out, { cellAddress: 'B2', added: true })
  } finally { restore() }
})

test('excel_get_comments：老式批注降级字段（createdAt/resolved/replies 恒定）', async () => {
  const comment = {
    Parent: { Address() { return 'B2' } },
    Text() { return '存疑' },
    Author: '张三'
  }
  const { restore } = makeSingleSheetEnv({
    Comments: { Count: 1, Item(i) { assert.equal(i, 1); return comment } }
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_get_comments({})
    assert.equal(out.count, 1)
    assert.deepEqual(out.comments[0], {
      cellAddress: 'B2', content: '存疑', author: '张三',
      createdAt: null, resolved: false, replies: []
    })
  } finally { restore() }
})

test('excel_reply_comment：降级为批注正文追加「回复：」行并交底 via', async () => {
  let newText = null
  const comment = {
    Text(text) {
      if (text === undefined) return '原批注'
      newText = text
    }
  }
  const rng = makeRange({ Comment: comment })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_reply_comment({ cellAddress: 'B2', reply: '已确认' })
    assert.equal(newText, '原批注\n回复：已确认')
    assert.deepEqual(out, { cellAddress: 'B2', replied: true, via: 'appendText' })
  } finally { restore() }
})

test('excel_resolve_comment：WPS 无解决语义，明确报不支持', async () => {
  const { restore } = makeSingleSheetEnv({})
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_resolve_comment({ cellAddress: 'B2' }),
      /WPS 表格批注不支持标记解决/
    )
  } finally { restore() }
})

test('excel_delete_comment：无批注单元格报可读错误', async () => {
  const rng = makeRange({ Comment: null })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_delete_comment({ cellAddress: 'B2' }),
      /该单元格没有批注/
    )
  } finally { restore() }
})

/* ==================== excel_set_data_validation ==================== */

test('excel_set_data_validation：list 先删旧规则再 Add 并开下拉', async () => {
  const calls = []
  const validation = {
    Delete() { calls.push('delete') },
    Add(...args) { calls.push(['add', ...args]) }
  }
  const rng = makeRange({ Validation: validation, Address() { return 'A1:A10' } })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_data_validation({
      rangeAddress: 'A1:A10', type: 'list', listSource: '是,否'
    })
    assert.deepEqual(calls, ['delete', ['add', 3, 1, 1, '是,否']])  // xlValidateList, xlValidAlertStop, xlBetween 占位
    assert.equal(validation.InCellDropdown, true)
    assert.deepEqual(out, { action: 'apply', type: 'list', address: 'A1:A10' })
  } finally { restore() }
})

/* ==================== excel_add_chart ==================== */

test('excel_add_chart：ChartObjects() 函数调用建图，HasTitle 前置', async () => {
  let addArgs = null
  let sourceRange = null
  const chart = {
    SetSourceData(rng) { sourceRange = rng },
    ChartTitle: {}
  }
  const co = { Chart: chart, Name: '图表 1' }
  const dataRng = { _tag: 'data' }
  const { restore } = makeSingleSheetEnv({
    ChartObjects() { return { Add(...args) { addArgs = args; return co } } },
    Range: () => dataRng
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_add_chart({
      dataRangeAddress: 'A1:B5', chartType: 'column', title: '销量'
    })
    assert.deepEqual(addArgs, [50, 40, 360, 220])
    assert.equal(sourceRange, dataRng)
    assert.equal(chart.ChartType, 51)       // xlColumnClustered
    assert.equal(chart.HasTitle, true)
    assert.equal(chart.ChartTitle.Text, '销量')
    assert.deepEqual(out, { added: true, name: '图表 1', chartType: 'column' })
  } finally { restore() }
})

/* ==================== excel_define_name ==================== */

test('excel_define_name：add 拼带引号表名的 RefersTo；remove 未找到报错', async () => {
  let addArgs = null
  const names = {
    Add(name, refersTo) { addArgs = [name, refersTo] },
    Item(name) { throw new Error(`名称不存在：${name}`) }
  }
  const rng = makeRange({ Address() { return '$A$1:$B$2' } })
  const sheet = makeSheet('数据表', { Range: () => rng })
  const workbook = { ActiveSheet: sheet, Worksheets: makeSheetsCollection([sheet]), Names: names }
  const restore = installWps(makeApp({ workbook }))
  try {
    const out = await WPS_ET_HANDLERS.excel_define_name({ action: 'add', name: '结算区', rangeAddress: 'A1:B2' })
    assert.deepEqual(addArgs, ['结算区', "='数据表'!$A$1:$B$2"])
    assert.deepEqual(out, { action: 'add', name: '结算区' })
    await assert.rejects(
      WPS_ET_HANDLERS.excel_define_name({ action: 'remove', name: '不存在的名字' }),
      /未找到命名区域：不存在的名字/
    )
  } finally { restore() }
})

/* ==================== excel_group_rows_cols / excel_protect_sheet ==================== */

test('excel_group_rows_cols：按 EntireRow/EntireColumn 归一化后分组', async () => {
  let grouped = false
  let ungrouped = false
  const rng = makeRange({
    EntireRow: { Group() { grouped = true } },
    EntireColumn: { Ungroup() { ungrouped = true } }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => rng })
  try {
    let out = await WPS_ET_HANDLERS.excel_group_rows_cols({ rangeAddress: '2:5', action: 'group', by: 'rows' })
    assert.equal(grouped, true)
    assert.deepEqual(out, { action: 'group', by: 'rows' })
    out = await WPS_ET_HANDLERS.excel_group_rows_cols({ rangeAddress: 'C:E', action: 'ungroup', by: 'cols' })
    assert.equal(ungrouped, true)
    assert.deepEqual(out, { action: 'ungroup', by: 'cols' })
  } finally { restore() }
})

test('excel_protect_sheet：protect/unprotect 透传密码；非法 action 报错', async () => {
  const calls = []
  const { restore } = makeSingleSheetEnv({
    Protect(pwd) { calls.push(['protect', pwd]) },
    Unprotect(pwd) { calls.push(['unprotect', pwd]) }
  })
  try {
    await WPS_ET_HANDLERS.excel_protect_sheet({ action: 'protect', password: 'abc' })
    await WPS_ET_HANDLERS.excel_protect_sheet({ action: 'unprotect', password: 'abc' })
    assert.deepEqual(calls, [['protect', 'abc'], ['unprotect', 'abc']])
    await assert.rejects(WPS_ET_HANDLERS.excel_protect_sheet({ action: 'lock' }), /action 值非法/)
  } finally { restore() }
})

/* ==================== excel_add_pivot_table ==================== */

test('excel_add_pivot_table：PivotCaches().Create 全签名链路与字段编排', async () => {
  let createArgs = null
  let createPivotArgs = null
  const fields = {
    地区: { Orientation: null },
    销量: { _tag: 'valueField' }
  }
  const dataFieldCalls = []
  const pivot = {
    Name: '透视1',
    PivotFields(name) {
      if (!fields[name]) throw new Error('字段不存在')
      return fields[name]
    },
    AddDataField(pf) { dataFieldCalls.push(pf) }
  }
  const cache = { CreatePivotTable(dest, name) { createPivotArgs = [dest, name]; return pivot } }
  const destRng = { _tag: 'dest' }
  const sheet = makeSheet('Sheet1', { Range: () => destRng })
  const workbook = {
    ActiveSheet: sheet,
    Worksheets: makeSheetsCollection([sheet]),
    PivotCaches() { return { Create(type, src) { createArgs = [type, src]; return cache } } }
  }
  const restore = installWps(makeApp({ workbook }))
  try {
    const out = await WPS_ET_HANDLERS.excel_add_pivot_table({
      sourceRangeAddress: 'A1:C10', destinationCellAddress: 'E1',
      rowFields: ['地区'], valueFields: ['销量'], pivotName: '透视1'
    })
    assert.deepEqual(createArgs, [1, "'Sheet1'!A1:C10"])   // xlDatabase + 带表名源地址
    assert.deepEqual(createPivotArgs, [destRng, '透视1'])
    assert.equal(fields['地区'].Orientation, 1)             // xlRowField
    assert.deepEqual(dataFieldCalls, [fields['销量']])
    assert.deepEqual(out, { added: true, name: '透视1', rowFields: ['地区'], valueFields: ['销量'] })
    // 字段名对不上时给排障提示
    await assert.rejects(
      WPS_ET_HANDLERS.excel_add_pivot_table({
        sourceRangeAddress: 'A1:C10', destinationCellAddress: 'E1',
        rowFields: ['不存在的列'], valueFields: ['销量']
      }),
      /未找到透视表字段：不存在的列/
    )
  } finally { restore() }
})

/* ==================== excel_select_range ==================== */

test('excel_select_range：指定表先 Activate 再 Select', async () => {
  const calls = []
  const rng = makeRange({ Select() { calls.push('select') }, Address() { return 'B2:C3' } })
  const sheet = makeSheet('明细', { Activate() { calls.push('activate') }, Range: () => rng })
  const workbook = { ActiveSheet: makeSheet('Sheet1'), Worksheets: makeSheetsCollection([sheet]) }
  const restore = installWps(makeApp({ workbook }))
  try {
    const out = await WPS_ET_HANDLERS.excel_select_range({ sheetName: '明细', rangeAddress: 'B2:C3' })
    assert.deepEqual(calls, ['activate', 'select'])
    assert.deepEqual(out, { address: 'B2:C3' })
  } finally { restore() }
})

/* ============ 本轮主动排查补的用例（真机测量 + 审计确认的问题）============ */

test('excel_manage_sheets(move)：往后挪要补偿「自己先被摘掉」这一格', async () => {
  // 契约的 position 是最终落点的 0 起下标（Office.js 语义：先摘掉自己再插入）。
  // WPS 只有 Move(Before, After)，参照物取自移动前的集合——[A,B,C] 把 A 挪到
  // position=2，不补偿会得到 [B,A,C] 而不是 [B,C,A]。
  const order = ['A', 'B', 'C']
  const sheets = order.map((n) => ({ Name: n }))
  let moveCall = null
  const target = sheets[0]
  target.Index = 1
  target.Move = (before, after) => { moveCall = { before: before ? before.Name : null, after: after ? after.Name : null } }
  const collection = {
    get Count() { return sheets.length },
    Item(k) { return typeof k === 'number' ? sheets[k - 1] : sheets.find((x) => x.Name === k) }
  }
  const workbook = { ActiveSheet: sheets[0], Worksheets: collection }
  const restore = installWps(makeApp({ workbook }))
  try {
    await WPS_ET_HANDLERS.excel_manage_sheets({ action: 'move', sheetName: 'A', position: 2 })
    // 目标位次 3（0 起的 2）；A 在第 1 位，往后挪 → After = 第 3 张（C）
    assert.deepEqual(moveCall, { before: null, after: 'C' })
  } finally { restore() }
})

test('excel_manage_sheets(move)：挪到原位不发指令', async () => {
  const sheets = [{ Name: 'A' }, { Name: 'B' }]
  let moved = false
  sheets[1].Index = 2
  sheets[1].Move = () => { moved = true }
  const collection = {
    get Count() { return sheets.length },
    Item(k) { return typeof k === 'number' ? sheets[k - 1] : sheets.find((x) => x.Name === k) }
  }
  const restore = installWps(makeApp({ workbook: { ActiveSheet: sheets[0], Worksheets: collection } }))
  try {
    await WPS_ET_HANDLERS.excel_manage_sheets({ action: 'move', sheetName: 'B', position: 1 })
    assert.equal(moved, false)
  } finally { restore() }
})

test('excel_conditional_format：入参非法时不许删掉用户已有的条件格式', async () => {
  // 律师那套辛苦配好的规则不能因为模型把 operator 拼错一次就没了
  let deleted = false
  const fcs = { Delete() { deleted = true }, Add() { throw new Error('不该走到这里') } }
  const { restore } = makeSingleSheetEnv({ Range: () => makeRange({ FormatConditions: fcs }) })
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_conditional_format({ rangeAddress: 'A1:C9', ruleType: 'cellValue', operator: 'greater_than', value1: 1 }),
      /operator 值非法/
    )
    assert.equal(deleted, false, '校验失败时一条既有规则都不许删')
  } finally { restore() }
})

test('excel_conditional_format：缺 value1 也在删之前拦住', async () => {
  let deleted = false
  const fcs = { Delete() { deleted = true }, Add() { throw new Error('不该走到这里') } }
  const { restore } = makeSingleSheetEnv({ Range: () => makeRange({ FormatConditions: fcs }) })
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_conditional_format({ rangeAddress: 'A1:C9', ruleType: 'cellValue', operator: 'greaterThan' }),
      /需要 value1/
    )
    assert.equal(deleted, false)
  } finally { restore() }
})

test('excel_set_data_validation：type 非法时不许删掉用户已有的下拉列表', async () => {
  let deleted = false
  const v = { Delete() { deleted = true }, Add() { throw new Error('不该走到这里') } }
  const { restore } = makeSingleSheetEnv({ Range: () => makeRange({ Validation: v }) })
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_set_data_validation({ rangeAddress: 'D2:D99', type: 'whole_number', operator: 'greaterThan', value1: 0 }),
      /type 值非法/
    )
    assert.equal(deleted, false)
  } finally { restore() }
})

test('excel_set_data_validation：action 拼错不许掉进 apply 分支去删规则', async () => {
  let deleted = false
  const v = { Delete() { deleted = true }, Add() {} }
  const { restore } = makeSingleSheetEnv({ Range: () => makeRange({ Validation: v }) })
  try {
    await assert.rejects(
      WPS_ET_HANDLERS.excel_set_data_validation({ rangeAddress: 'D2:D99', action: 'reset', type: 'list', listSource: 'a,b' }),
      /action 值非法/
    )
    assert.equal(deleted, false)
  } finally { restore() }
})

test('excel_get_range：超出返回上限时先 Resize 再过桥，不整片搬运', async () => {
  // 只回 500 行却把几万行整片编组过同步桥，是「一问就把 WPS 卡死」的根因
  let resizedTo = null
  const big = makeRange({
    Rows: { Count: 30000 },
    Columns: { Count: 4 },
    Value2: [['a', 'b', 'c', 'd']],
    Address() { return 'A1:D30000' },
    Resize(r, c) {
      resizedTo = [r, c]
      return makeRange({ Rows: { Count: r }, Columns: { Count: c }, Value2: [['a', 'b', 'c', 'd']], Address() { return 'A1:D500' } })
    }
  })
  const { restore } = makeSingleSheetEnv({ Range: () => big })
  try {
    const out = await WPS_ET_HANDLERS.excel_get_range({ rangeAddress: 'A1:D30000' })
    assert.deepEqual(resizedTo, [500, 4])
    assert.equal(out.truncated, true)
    assert.equal(out.rows, 30000) // 报的仍是真实尺寸
  } finally { restore() }
})

test('excel_search：超大表只扫前若干行并如实交代', async () => {
  let resizedTo = null
  const used = makeRange({
    Rows: { Count: 40000 },
    Columns: { Count: 2 },
    Row: 1,
    Column: 1,
    Value2: [['张三', '原告']],
    Address() { return 'A1:B40000' },
    Resize(r, c) {
      resizedTo = [r, c]
      return makeRange({ Rows: { Count: r }, Columns: { Count: c }, Row: 1, Column: 1, Value2: [['张三', '原告']] })
    }
  })
  const { restore } = makeSingleSheetEnv({ UsedRange: used })
  try {
    const out = await WPS_ET_HANDLERS.excel_search({ query: '张三' })
    assert.deepEqual(resizedTo, [5000, 2])
    assert.equal(out.truncated, true)
    assert.equal(out.scannedRows, 5000)
    assert.equal(out.totalRows, 40000)
    assert.match(out.note, /只扫描了前 5000 行/)
  } finally { restore() }
})
