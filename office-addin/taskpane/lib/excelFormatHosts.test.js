// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「追加行沿用上一行格式 / get_range 带回格式」在两个宿主上的执行路径（dev-board#844）：
 *   node --test office-addin/taskpane/lib/excelFormatHosts.test.js
 *
 * 手法：Office.js 与 WPS JSAPI 各造一张假工作表（值 + 逐格格式），走真实的 handler：
 * - Office：excel_set_values 在 ExcelApi 1.9 上走 copyFrom(formats)，1.9 以下逐格抄；
 * - WPS：excel_set_values 逐属性抄格式，**不许碰剪贴板**（Copy/PasteSpecial 一调就抛）；
 * - 两边 excel_get_range(withFormat) 回同一套字段。
 * 判定本身（哪几行该沿用）在 excelFormat.test.js。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { WPS_ET_HANDLERS } from './wpsEtHandlers.js'

/* ==================== Office.js 假工作表 ==================== */

let officeMaxMinor = 9

globalThis.Office = {
  HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
  context: {
    host: 'Excel',
    document: { url: 'C:/x/重大合同清单.xlsx' },
    requirements: {
      isSetSupported: (name, version) => name === 'ExcelApi' && Number(String(version).split('.')[1]) <= officeMaxMinor
    }
  }
}

const { executeOfficeCommand } = await import('./officeExecutor.js')

function colIndexOf(letters) {
  let n = 0
  for (const ch of letters.toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64)
  return n - 1
}
function parseA1(addr) {
  const [a, b] = String(addr).split(':')
  const p = (s) => {
    const m = /^([A-Za-z]+)(\d+)$/.exec(s)
    return { r: Number(m[2]) - 1, c: colIndexOf(m[1]) }
  }
  const s = p(a)
  const e = b ? p(b) : s
  return { r: s.r, c: s.c, nr: e.r - s.r + 1, nc: e.c - s.c + 1 }
}
function letter(c) {
  let s = ''
  let n = c + 1
  while (n > 0) { const rem = (n - 1) % 26; s = String.fromCharCode(65 + rem) + s; n = Math.floor((n - 1) / 26) }
  return s
}

/**
 * 造 Office 版假工作表。values：0 起行的二维值；cellFmt(r,c)：该格预置格式（缺省默认外观）。
 * 返回 { log, cell(r,c) } —— log.copies 记 copyFrom，log.heights 记行高写入。
 */
function installOfficeSheet({ values, cellFmt = () => ({}), rowHeights = {} }) {
  const grid = values.map((row) => row.slice())
  const log = { copies: [], heights: [], loads: [] }
  const proxies = new Map()
  function cell(r, c) {
    const key = `${r},${c}`
    if (proxies.has(key)) return proxies.get(key)
    const f = cellFmt(r, c) || {}
    const edges = {}
    for (const id of ['EdgeTop', 'EdgeBottom', 'EdgeLeft', 'EdgeRight']) {
      const preset = (f.borders && f.borders[id]) || null
      edges[id] = {
        load() {},
        style: preset ? 'Continuous' : 'None',
        weight: preset || 'Thin',
        color: '#000000',
        _written: false
      }
    }
    const obj = {
      numberFormat: [[f.nf || 'General']],
      format: {
        load() {},
        horizontalAlignment: f.h || 'General',
        verticalAlignment: 'Bottom',
        wrapText: false,
        font: { load() {}, name: f.font || 'Calibri', size: f.size || 11, bold: !!f.bold, italic: false, color: f.color || '#000000' },
        fill: { load() {}, color: f.fill || '#FFFFFF', cleared: false, clear() { this.cleared = true } },
        borders: { getItem(id) { return edges[id] } }
      },
      _edges: edges
    }
    // 边框写入留痕：只画有线的边这条纪律要能被断言
    for (const id of Object.keys(edges)) {
      const e = edges[id]
      let style = e.style
      Object.defineProperty(e, 'style', {
        get() { return style },
        set(v) { style = v; e._written = true }
      })
    }
    proxies.set(key, obj)
    return obj
  }
  function region(r, c, nr, nc) {
    return {
      rowIndex: r,
      columnIndex: c,
      rowCount: nr,
      columnCount: nc,
      isNullObject: false,
      get address() { return `Sheet1!${letter(c)}${r + 1}:${letter(c + nc - 1)}${r + nr}` },
      load(p) { log.loads.push({ r, nr, p: String(p) }) },
      get values() {
        return Array.from({ length: nr }, (_, i) => Array.from({ length: nc }, (_, j) => {
          const row = grid[r + i]
          return row && row[c + j] != null ? row[c + j] : ''
        }))
      },
      set values(v) {
        v.forEach((line, i) => line.forEach((x, j) => {
          while (grid.length <= r + i) grid.push([])
          grid[r + i][c + j] = x
        }))
      },
      get numberFormat() {
        return Array.from({ length: nr }, (_, i) => Array.from({ length: nc }, (_, j) => cell(r + i, c + j).numberFormat[0][0]))
      },
      getResizedRange(dr, dc) { return region(r, c, nr + dr, nc + dc) },
      getCell(i, j) { return cell(r + i, c + j) },
      copyFrom(src, type) { log.copies.push({ dstRow: r + 1, srcRow: src.rowIndex + 1, type, cols: nc }) },
      getCellProperties() {
        return {
          value: Array.from({ length: nr }, (_, i) => Array.from({ length: nc }, (_, j) => {
            const x = cell(r + i, c + j)
            const b = {}
            for (const [id, k] of [['EdgeTop', 'top'], ['EdgeBottom', 'bottom'], ['EdgeLeft', 'left'], ['EdgeRight', 'right']]) {
              const e = x._edges[id]
              b[k] = { style: e.style, weight: e.weight, color: e.color }
            }
            return {
              format: {
                font: { ...x.format.font },
                fill: { color: x.format.fill.color, pattern: x.format.fill.color === '#FFFFFF' ? 'None' : 'Solid' },
                horizontalAlignment: x.format.horizontalAlignment,
                verticalAlignment: x.format.verticalAlignment,
                wrapText: x.format.wrapText,
                borders: b
              }
            }
          }))
        }
      },
      format: {
        load() {},
        wrapText: false,
        get rowHeight() { return rowHeights[r] || 15 },
        set rowHeight(v) { log.heights.push({ row: r + 1, v }) }
      }
    }
  }
  const sheet = {
    name: 'Sheet1',
    load() {},
    getRange(addr) { const p = parseA1(addr); return region(p.r, p.c, p.nr, p.nc) },
    getRangeByIndexes: region,
    getUsedRangeOrNullObject() { return region(0, 0, grid.length, Math.max(...grid.map((x) => x.length))) }
  }
  const saved = globalThis.Excel
  globalThis.Excel = {
    RangeCopyType: { formats: 'Formats' },
    run: async (cb) => cb({
      workbook: { worksheets: { getActiveWorksheet: () => sheet, getItem: () => sheet } },
      sync: async () => {}
    })
  }
  return {
    log,
    grid,
    cell,
    restore() {
      if (saved === undefined) delete globalThis.Excel
      else globalThis.Excel = saved
    }
  }
}

async function runOffice(command, args) {
  const res = await executeOfficeCommand(command, args)
  if (!res.ok) throw new Error(res.error)
  return res.data
}

/** 「重大合同清单」：表头 + 三行数据，数据行四边细线、宋体、居中 */
const CONTRACTS = [
  ['序号', '合同名称', '金额'],
  ['1', '采购合同', 100],
  ['2', '租赁合同', 200],
  ['3', '借款合同', 300]
]
const contractFmt = (r) => r === 0
  ? { bold: true, fill: '#D9E1F2', h: 'Center', borders: { EdgeTop: 'Thin', EdgeBottom: 'Thin', EdgeLeft: 'Thin', EdgeRight: 'Thin' } }
  : r <= 3
    ? { font: '宋体', h: 'Center', borders: { EdgeTop: 'Thin', EdgeBottom: 'Thin', EdgeLeft: 'Thin', EdgeRight: 'Thin' } }
    : {}

test('Office（ExcelApi 1.9）：末尾追加一行 → copyFrom(formats) 抄上一行，行高对齐，再写值', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({ values: CONTRACTS, cellFmt: contractFmt, rowHeights: { 3: 20 } })
  try {
    const out = await runOffice('excel_set_values', { rangeAddress: 'A5', values: [['4', '担保合同', 400]] })
    assert.deepEqual(out.formatInherited, [{ row: 5, from: 4 }])
    assert.deepEqual(env.log.copies, [{ dstRow: 5, srcRow: 4, type: 'Formats', cols: 3 }])
    assert.deepEqual(env.log.heights, [{ row: 5, v: 20 }])
    assert.deepEqual(env.grid[4], ['4', '担保合同', 400])
    assert.equal(out.formatNote, undefined)
  } finally { env.restore() }
})

test('Office：一次追加三行逐行级联，都从最后一行既有数据抄', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({ values: CONTRACTS, cellFmt: contractFmt })
  try {
    const out = await runOffice('excel_set_values', { rangeAddress: 'A5', values: [['4', 'a', 1], ['5', 'b', 2], ['6', 'c', 3]] })
    assert.deepEqual(out.formatInherited, [{ row: 5, from: 4 }, { row: 6, from: 5 }, { row: 7, from: 6 }])
    assert.deepEqual(env.log.copies.map((x) => [x.dstRow, x.srcRow]), [[5, 4], [6, 4], [7, 4]])
  } finally { env.restore() }
})

test('Office：表头下第一行不沿用（上方只有表头）', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({ values: [CONTRACTS[0]], cellFmt: contractFmt })
  try {
    const out = await runOffice('excel_set_values', { rangeAddress: 'A2', values: [['1', '采购合同', 100]] })
    assert.deepEqual(out.formatInherited, [])
    assert.equal(env.log.copies.length, 0)
  } finally { env.restore() }
})

/** 「标题行紧贴表头」：第 1 行合并居中的标题，第 2 行表头，第 3 行写第一条数据 */
const TITLE_HEADER = [
  ['重大合同清单', '', ''],
  ['序号', '合同名称', '金额']
]

test('Office：标题行紧贴表头 → 不沿用（上方两行都有内容，但格式签名对不上）', async () => {
  for (const minor of [9, 8]) {
    officeMaxMinor = minor
    const env = installOfficeSheet({
      values: TITLE_HEADER,
      cellFmt: (r) => (r === 0
        ? { bold: true, size: 16, h: 'Center' }
        : r === 1 ? contractFmt(0) : {})
    })
    try {
      const out = await runOffice('excel_set_values', { rangeAddress: 'A3', values: [['1', '采购合同', 100]] })
      assert.deepEqual(out.formatInherited, [], `ExcelApi 1.${minor}`)
      assert.equal(env.log.copies.length, 0)
      assert.equal(env.cell(2, 0).format.font.bold, false, '表头的加粗不能进数据行')
    } finally { env.restore() }
  }
})

test('Office：数据行只有字色/底色不同（状态标红、隔行底纹）→ 仍沿用', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({
    values: CONTRACTS,
    cellFmt: (r, c) => (r === 2 ? { ...contractFmt(r), fill: '#F2F2F2' } : r === 3 && c === 2 ? { ...contractFmt(r), color: '#FF0000' } : contractFmt(r))
  })
  try {
    const out = await runOffice('excel_set_values', { rangeAddress: 'A5', values: [['4', '担保合同', 400]] })
    assert.deepEqual(out.formatInherited, [{ row: 5, from: 4 }])
  } finally { env.restore() }
})

test('Office：改既有数据行时一格格式都不读（没有候选行就不加开销）', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({ values: CONTRACTS, cellFmt: contractFmt })
  try {
    await runOffice('excel_set_values', { rangeAddress: 'A4:C4', values: [['3', '改', 1]] })
    assert.ok(!env.log.loads.some((x) => /numberFormat/.test(x.p)), '不该为格式比较读任何格式')
  } finally { env.restore() }
})

test('Office：inheritFormat=false 不沿用', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({ values: CONTRACTS, cellFmt: contractFmt })
  try {
    const out = await runOffice('excel_set_values', { rangeAddress: 'A5', values: [['4', 'x', 1]], inheritFormat: false })
    assert.deepEqual(out.formatInherited, [])
    assert.equal(env.log.copies.length, 0)
  } finally { env.restore() }
})

test('Office：改写既有数据行不动格式', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({ values: CONTRACTS, cellFmt: contractFmt })
  try {
    const out = await runOffice('excel_set_values', { rangeAddress: 'A4:C4', values: [['3', '借款合同（修订）', 350]] })
    assert.deepEqual(out.formatInherited, [])
    assert.equal(env.log.copies.length, 0)
    assert.equal(env.grid[3][1], '借款合同（修订）')
  } finally { env.restore() }
})

test('Office（ExcelApi 1.8，无 copyFrom）：逐格抄字体/对齐/边框；无填充清掉而不是涂白；只画有线的边', async () => {
  officeMaxMinor = 8
  const env = installOfficeSheet({
    values: CONTRACTS,
    cellFmt: (r, c) => (r >= 1 && r <= 3
      ? { font: '宋体', h: 'Center', nf: c === 2 ? '#,##0' : 'General', borders: { EdgeBottom: 'Thin', EdgeLeft: 'Thin', EdgeRight: 'Thin' } }
      : contractFmt(r))
  })
  try {
    const out = await runOffice('excel_set_values', { rangeAddress: 'A5', values: [['4', '担保合同', 400]] })
    assert.deepEqual(out.formatInherited, [{ row: 5, from: 4 }])
    assert.equal(env.log.copies.length, 0, '1.9 以下不能调 copyFrom')
    for (let c = 0; c < 3; c++) {
      const dst = env.cell(4, c)
      assert.equal(dst.format.font.name, '宋体')
      assert.equal(dst.format.horizontalAlignment, 'Center')
      assert.equal(dst.format.fill.cleared, true)
      assert.equal(dst._edges.EdgeBottom.style, 'Continuous')
      assert.equal(dst._edges.EdgeTop._written, false, '源格上边无线：不许给目标写 None（会擦掉上一行的下边框）')
    }
    assert.deepEqual(env.cell(4, 2).numberFormat, [['#,##0']])
  } finally { env.restore() }
})

test('Office get_range(withFormat)：数据区压成列级摘要，表头是例外格', async () => {
  officeMaxMinor = 9
  const env = installOfficeSheet({ values: CONTRACTS, cellFmt: contractFmt })
  try {
    const out = await runOffice('excel_get_range', { rangeAddress: 'A1:C4', withFormat: true })
    assert.equal(out.format.scanned, 'A1:C4')
    assert.deepEqual(out.format.columns.A, { hAlign: 'center', borders: 'all' })
    assert.deepEqual(out.format.cells.A1, { fontName: 'Calibri', bold: true, background: '#D9E1F2', hAlign: 'center', borders: 'all' })
    // 不带 withFormat 就不回格式
    const plain = await runOffice('excel_get_range', { rangeAddress: 'A1:C4' })
    assert.equal(plain.format, undefined)
  } finally { env.restore() }
})

test('Office get_range(withFormat)：ExcelApi 1.8 走逐格 load，结果同形', async () => {
  officeMaxMinor = 8
  const env = installOfficeSheet({ values: CONTRACTS, cellFmt: contractFmt })
  try {
    const out = await runOffice('excel_get_range', { rangeAddress: 'A1:C4', withFormat: true })
    assert.deepEqual(out.format.columns.B, { hAlign: 'center', borders: 'all' })
    assert.equal(out.format.cells.B1.bold, true)
  } finally { env.restore() }
})

/* ==================== WPS 假工作表 ==================== */

const NONE = -4142

/** WPS 一格：preset 覆写 Font/Interior/对齐/边框；Copy/PasteSpecial 一调就抛（剪贴板红线） */
function makeEtCell(preset = {}) {
  const borders = {}
  for (const i of [7, 8, 9, 10]) {
    borders[i] = { LineStyle: NONE, Weight: 2, Color: 0, _written: false }
    const store = borders[i]
    let ls = (preset.borders && preset.borders[i]) ? 1 : NONE
    Object.defineProperty(store, 'LineStyle', { get() { return ls }, set(v) { ls = v; store._written = true } })
  }
  return {
    Font: { Name: 'Calibri', Size: 11, Bold: false, Italic: false, Underline: NONE, Color: 0, ...(preset.font || {}) },
    Interior: { ColorIndex: NONE, Color: 0xffffff, ...(preset.interior || {}) },
    HorizontalAlignment: preset.h != null ? preset.h : 1,
    VerticalAlignment: -4107,
    NumberFormat: preset.nf || 'G/通用格式',
    WrapText: !!preset.wrap,
    RowHeight: preset.rowHeight || 14.25,
    Borders: { Item(i) { return borders[i] } },
    Copy() { throw new Error('不许走剪贴板') },
    PasteSpecial() { throw new Error('不许走剪贴板') },
    _borders: borders
  }
}

function installEtSheet({ values, cellPreset = () => ({}) }) {
  const grid = values.map((row) => row.slice())
  const cells = new Map()
  function cell(r, c) { // 1 起
    const key = `${r},${c}`
    if (!cells.has(key)) cells.set(key, makeEtCell(cellPreset(r, c)))
    const obj = cells.get(key)
    obj.Resize = (nr, nc) => region(r, c, nr, nc)
    return obj
  }
  function region(r, c, nr, nc) {
    return {
      Row: r,
      Column: c,
      Rows: { Count: nr },
      Columns: { Count: nc },
      Address() { return nr === 1 && nc === 1 ? `${letter(c - 1)}${r}` : `${letter(c - 1)}${r}:${letter(c + nc - 2)}${r + nr - 1}` },
      Resize(a, b) { return region(r, c, a, b) },
      get Value2() {
        const out = Array.from({ length: nr }, (_, i) => Array.from({ length: nc }, (_, j) => {
          const row = grid[r - 1 + i]
          return row && row[c - 1 + j] != null ? row[c - 1 + j] : undefined
        }))
        return nr === 1 && nc === 1 ? out[0][0] : out
      },
      set Value2(v) {
        v.forEach((line, i) => line.forEach((x, j) => {
          while (grid.length < r + i) grid.push([])
          grid[r - 1 + i][c - 1 + j] = x
        }))
      }
    }
  }
  const sheet = {
    Name: 'Sheet1',
    Range(addr) { const p = parseA1(addr); return region(p.r + 1, p.c + 1, p.nr, p.nc) },
    Cells: { Item: cell },
    get UsedRange() { return region(1, 1, grid.length, Math.max(...grid.map((x) => x.length))) }
  }
  const workbook = { ActiveSheet: sheet, Worksheets: { Count: 1, Item: () => sheet } }
  const original = globalThis.wps
  globalThis.wps = { EtApplication: () => ({ ActiveWorkbook: workbook }) }
  return {
    grid,
    cell,
    restore() {
      if (original === undefined) delete globalThis.wps
      else globalThis.wps = original
    }
  }
}

const etContractPreset = (r) => r === 1
  ? { font: { Bold: true }, interior: { ColorIndex: 6, Color: 0xf2e1d9 }, h: -4108, borders: { 7: 1, 8: 1, 9: 1, 10: 1 } }
  : r <= 4
    ? { font: { Name: '宋体' }, h: -4108, borders: { 8: 1, 9: 1, 7: 1, 10: 1 }, rowHeight: 20 }
    : {}

test('WPS：末尾追加一行 → 逐属性抄上一行（字体/对齐/边框/行高），不碰剪贴板，再写值', async () => {
  const env = installEtSheet({ values: CONTRACTS, cellPreset: etContractPreset })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A5', values: [['4', '担保合同', 400]] })
    assert.deepEqual(out.formatInherited, [{ row: 5, from: 4 }])
    assert.equal(out.formatNote, undefined)
    for (let c = 1; c <= 3; c++) {
      const dst = env.cell(5, c)
      assert.equal(dst.Font.Name, '宋体')
      assert.equal(dst.HorizontalAlignment, -4108)
      assert.equal(dst.Interior.ColorIndex, NONE)
      assert.equal(dst._borders[9].LineStyle, 1, '下边框应沿用')
      assert.equal(dst._borders[8].LineStyle, 1, '上边框应沿用')
    }
    assert.equal(env.cell(5, 1).RowHeight, 20)
    assert.deepEqual(env.grid[4], ['4', '担保合同', 400])
  } finally { env.restore() }
})

test('WPS：源格某边无线时不给目标写 xlNone（相邻格共用一条边，会擦掉上一行的下边框）', async () => {
  const env = installEtSheet({
    values: CONTRACTS,
    cellPreset: (r) => (r >= 2 && r <= 4 ? { borders: { 9: 1 } } : {})
  })
  try {
    await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A5', values: [['4', 'x', 1]] })
    const dst = env.cell(5, 1)
    assert.equal(dst._borders[9].LineStyle, 1)
    assert.equal(dst._borders[8]._written, false)
  } finally { env.restore() }
})

test('WPS：多行追加级联 / 表头下第一行不沿用 / inheritFormat=false / 非空行不动', async () => {
  let env = installEtSheet({ values: CONTRACTS, cellPreset: etContractPreset })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A5', values: [['4', 'a', 1], ['5', 'b', 2]] })
    assert.deepEqual(out.formatInherited, [{ row: 5, from: 4 }, { row: 6, from: 5 }])
    assert.equal(env.cell(6, 2).Font.Name, '宋体')
  } finally { env.restore() }

  env = installEtSheet({ values: [CONTRACTS[0]], cellPreset: etContractPreset })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A2', values: [['1', 'a', 1]] })
    assert.deepEqual(out.formatInherited, [])
  } finally { env.restore() }

  env = installEtSheet({ values: CONTRACTS, cellPreset: etContractPreset })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A5', values: [['4', 'a', 1]], inheritFormat: false })
    assert.deepEqual(out.formatInherited, [])
    assert.equal(env.cell(5, 1).Font.Name, 'Calibri')
  } finally { env.restore() }

  env = installEtSheet({ values: CONTRACTS, cellPreset: etContractPreset })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A4:C4', values: [['3', '改', 1]] })
    assert.deepEqual(out.formatInherited, [])
  } finally { env.restore() }
})

test('WPS：标题行紧贴表头 → 不沿用（与 Office、桌面端同一判定）', async () => {
  const env = installEtSheet({
    values: TITLE_HEADER,
    cellPreset: (r) => (r === 1 ? { font: { Bold: true, Size: 16 }, h: -4108 } : r === 2 ? etContractPreset(1) : {})
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A3', values: [['1', '采购合同', 100]] })
    assert.deepEqual(out.formatInherited, [])
    assert.equal(env.cell(3, 1).Font.Bold, false, '表头的加粗不能进数据行')
    assert.equal(env.cell(3, 1).Interior.ColorIndex, NONE)
  } finally { env.restore() }
})

test('WPS：数据行只有字色/底色不同 → 仍沿用', async () => {
  const env = installEtSheet({
    values: CONTRACTS,
    cellPreset: (r, c) => (r === 3
      ? { ...etContractPreset(r), interior: { ColorIndex: 15, Color: 0xf2f2f2 } }
      : r === 4 && c === 3 ? { ...etContractPreset(r), font: { Name: '宋体', Color: 0x0000ff } } : etContractPreset(r))
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_set_values({ rangeAddress: 'A5', values: [['4', '担保合同', 400]] })
    assert.deepEqual(out.formatInherited, [{ row: 5, from: 4 }])
  } finally { env.restore() }
})

test('WPS get_range(withFormat)：与 Office 同一套字段，表头例外、数据区列级摘要', async () => {
  const env = installEtSheet({ values: CONTRACTS, cellPreset: etContractPreset })
  try {
    const out = await WPS_ET_HANDLERS.excel_get_range({ rangeAddress: 'A1:C4', withFormat: true })
    assert.equal(out.format.scanned, 'A1:C4')
    assert.deepEqual(out.format.columns.A, { hAlign: 'center', borders: 'all' })
    assert.deepEqual(out.format.cells.A1, { fontName: 'Calibri', bold: true, background: '#D9E1F2', hAlign: 'center', borders: 'all' })
    const plain = await WPS_ET_HANDLERS.excel_get_range({ rangeAddress: 'A1:C4' })
    assert.equal(plain.format, undefined)
  } finally { env.restore() }
})
