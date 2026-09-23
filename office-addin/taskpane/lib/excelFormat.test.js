// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * excelFormat.js（表格格式判定层，dev-board#844）的单元测试：
 *   node --test office-addin/taskpane/lib/excelFormat.test.js
 *
 * 病灶：往「重大合同清单」末尾加一行，新行无边框、字体不同、对齐不同。这里钉两件事：
 * 1. planFormatInheritance：哪几行该沿用上一行格式（末尾追加沿用、表头下第一行不沿用、
 *    关掉不沿用、非空行不动、多行级联）；
 * 2. summarizeCellFormats：逐格格式压成「列级摘要 + 例外格」，模型据此知道新行该长什么样。
 * 两宿主的执行路径见 excelFormatHosts.test.js。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  planFormatInheritance,
  summarizeCellFormats,
  summarizeBorders,
  normalizeOfficeCellProps,
  normalizeEtCell,
  formatScanShape,
  isBlankCell,
  formatCheckRows,
  rowsFormatConsistent,
  MAX_FORMAT_CELLS
} from './excelFormat.js'

/** 给若干行造一样的数据行格式（条件 b 要求上方两行格式签名一致） */
function sameFormats(rows, cols = 2) {
  return new Map(rows.map((r) => [r, Array.from({ length: cols }, () => fmt({ borders: ALL_THIN }))]))
}

/* ==================== planFormatInheritance ==================== */

test('末尾追加：上方两行连续数据 → 新行沿用紧邻上一行', () => {
  const plan = planFormatInheritance({
    startRow: 5,
    aboveRows: [['2', '采购合同'], ['3', '租赁合同']],
    targetRows: [[null, '']],
    values: [['4', '借款合同']],
    rowFormats: sameFormats([3, 4])
  })
  assert.deepEqual(plan, [{ row: 5, from: 4, root: 4 }])
})

test('表头下第一行不沿用：上方只有一行（表头），不能把表头的加粗/底色扩给第一条数据', () => {
  assert.deepEqual(planFormatInheritance({
    startRow: 2,
    aboveRows: [['序号', '合同名称']],
    targetRows: [[null, null]],
    values: [['1', '采购合同']]
  }), [])
  // 表头上面还有一行空行（标题与表头之间留白）也一样
  assert.deepEqual(planFormatInheritance({
    startRow: 3,
    aboveRows: [['', ''], ['序号', '合同名称']],
    targetRows: [[null, null]],
    values: [['1', '采购合同']]
  }), [])
})

test('表头下一次写多行：新写的第一行不能反过来给下一行当格式来源', () => {
  assert.deepEqual(planFormatInheritance({
    startRow: 2,
    aboveRows: [['序号', '合同名称']],
    targetRows: [[null, null], [null, null], [null, null]],
    values: [['1', 'a'], ['2', 'b'], ['3', 'c']]
  }), [])
})

test('inheritFormat=false 一律不沿用', () => {
  assert.deepEqual(planFormatInheritance({
    startRow: 5,
    aboveRows: [['2', 'a'], ['3', 'b']],
    targetRows: [[null, null]],
    values: [['4', 'c']],
    inheritFormat: false
  }), [])
})

test('非空行不动格式：写入前已有内容的行是用户的数据行', () => {
  assert.deepEqual(planFormatInheritance({
    startRow: 5,
    aboveRows: [['2', 'a'], ['3', 'b']],
    targetRows: [['4', '旧值']],
    values: [['4', '新值']]
  }), [])
  // 只要跨度内有一格非空就算非空行
  assert.deepEqual(planFormatInheritance({
    startRow: 5,
    aboveRows: [['2', 'a'], ['3', 'b']],
    targetRows: [[null, '备注']],
    values: [['4', 'x']]
  }), [])
})

test('多行追加逐行级联：每行 from 是紧邻上一行，root 都是最后一行既有数据', () => {
  const plan = planFormatInheritance({
    startRow: 10,
    aboveRows: [['7', 'a'], ['8', 'b']],
    targetRows: [[null, null], [null, null], [null, null]],
    values: [['9', 'c'], ['10', 'd'], ['11', 'e']],
    rowFormats: sameFormats([8, 9])
  })
  assert.deepEqual(plan, [
    { row: 10, from: 9, root: 9 },
    { row: 11, from: 10, root: 9 },
    { row: 12, from: 11, root: 9 }
  ])
})

test('先改一行既有数据再往下追加：追加行从被改的那行沿用', () => {
  const plan = planFormatInheritance({
    startRow: 5,
    aboveRows: [['2', 'a'], ['3', 'b']],
    targetRows: [['4', '旧'], [null, null]],
    values: [['4', '新'], ['5', '追加']],
    rowFormats: sameFormats([4, 5])
  })
  assert.deepEqual(plan, [{ row: 6, from: 5, root: 5 }])
})

test('写入的值全空（清空一行）不套格式，并且断开级联', () => {
  const plan = planFormatInheritance({
    startRow: 5,
    aboveRows: [['2', 'a'], ['3', 'b']],
    targetRows: [[null, null], [null, null]],
    values: [['', ''], ['5', 'x']]
  })
  assert.deepEqual(plan, [])
})

test('第一行 / 第二行起写入：上方不够两行数据，不沿用', () => {
  assert.deepEqual(planFormatInheritance({ startRow: 1, aboveRows: [], targetRows: [[null]], values: [['x']] }), [])
  // 从第 1 行起写三行：前两行写入前已有内容，第三行空 → 第三行沿用第二行
  assert.deepEqual(planFormatInheritance({
    startRow: 1,
    aboveRows: [],
    targetRows: [['h'], ['d'], [null]],
    values: [['h'], ['d'], ['e']],
    rowFormats: sameFormats([1, 2], 1)
  }), [{ row: 3, from: 2, root: 2 }])
})

test('标题行紧贴表头：上方两行都有内容但格式对不上 → 不沿用（不把表头加粗/底色抄进第一条数据）', () => {
  // 第 1 行：合并居中的标题（大字号、加粗、无边框）；第 2 行：表头（加粗、底色、四边框）
  const title = fmt({ fontSize: 16, bold: true, hAlign: 'center' })
  const header = fmt({ bold: true, background: '#D9E1F2', hAlign: 'center', borders: ALL_THIN })
  const input = {
    startRow: 3,
    aboveRows: [['重大合同清单', null, null], ['序号', '合同名称', '金额']],
    targetRows: [[null, null, null]],
    values: [['1', '采购合同', 100]]
  }
  assert.deepEqual(formatCheckRows(input), [1, 2], '条件 b 要比第 1、2 行')
  assert.deepEqual(planFormatInheritance({
    ...input,
    rowFormats: new Map([[1, [title, title, title]], [2, [header, header, header]]])
  }), [])
})

test('两行数据格式一致、只是字色/底色不同（状态列标红、隔行底纹）→ 仍沿用', () => {
  const plain = fmt({ borders: ALL_THIN, hAlign: 'center' })
  const red = fmt({ borders: ALL_THIN, hAlign: 'center', color: '#FF0000' })
  const zebra = fmt({ borders: ALL_THIN, hAlign: 'center', background: '#F2F2F2' })
  const plan = planFormatInheritance({
    startRow: 5,
    aboveRows: [['2', 'a', '逾期'], ['3', 'b', '正常']],
    targetRows: [[null, null, null]],
    values: [['4', 'c', '正常']],
    rowFormats: new Map([[3, [plain, plain, red]], [4, [zebra, zebra, plain]]])
  })
  assert.deepEqual(plan, [{ row: 5, from: 4, root: 4 }])
})

test('rowsFormatConsistent：签名一致的列至少一半才算一致；缺格式按不一致', () => {
  const a = fmt({ borders: ALL_THIN })
  const bold = fmt({ borders: ALL_THIN, bold: true })
  assert.equal(rowsFormatConsistent([a, a, a, a], [a, a, bold, bold]), true, '2/4 正好一半')
  assert.equal(rowsFormatConsistent([a, a, a, a], [a, bold, bold, bold]), false, '1/4 不到一半')
  assert.equal(rowsFormatConsistent(undefined, [a]), false)
  // 没给 rowFormats：条件 b 一律不成立（宁可不沿用也不凭猜）
  assert.deepEqual(planFormatInheritance({
    startRow: 5, aboveRows: [['2', 'a'], ['3', 'b']], targetRows: [[null, null]], values: [['4', 'c']]
  }), [])
})

test('formatCheckRows：没有候选行就不必读格式（改既有数据 / 表头下第一行 / 级联）', () => {
  assert.deepEqual(formatCheckRows({ startRow: 5, aboveRows: [['2'], ['3']], targetRows: [['旧']], values: [['新']] }), [])
  assert.deepEqual(formatCheckRows({ startRow: 2, aboveRows: [['序号']], targetRows: [[null]], values: [['1']] }), [])
  assert.deepEqual(formatCheckRows({ startRow: 5, aboveRows: [['2'], ['3']], targetRows: [[null]], values: [['4']], inheritFormat: false }), [])
  // 级联的第二、三行不再比格式：只比第一行上方那一对
  assert.deepEqual(formatCheckRows({
    startRow: 10, aboveRows: [['7'], ['8']], targetRows: [[null], [null], [null]], values: [['9'], ['10'], ['11']]
  }), [8, 9])
})

test('纯空白串算空', () => {
  assert.equal(isBlankCell('  '), true)
  assert.equal(isBlankCell(0), false)
  assert.equal(isBlankCell(false), false)
})

/* ==================== 格式归一与摘要 ==================== */

/** 造一格统一格式（缺省 = 默认外观） */
function fmt(over = {}) {
  return {
    fontName: '宋体',
    fontSize: 11,
    bold: false,
    italic: false,
    color: '#000000',
    background: null,
    hAlign: 'general',
    vAlign: 'bottom',
    numberFormat: 'General',
    wrap: false,
    borders: { top: null, bottom: null, left: null, right: null },
    ...over
  }
}
const ALL_THIN = {
  top: { weight: 'thin', color: '#000000' },
  bottom: { weight: 'thin', color: '#000000' },
  left: { weight: 'thin', color: '#000000' },
  right: { weight: 'thin', color: '#000000' }
}

test('summarizeCellFormats：表头作为例外格，数据区格式压成列级摘要', () => {
  const header = fmt({ bold: true, background: '#D9E1F2', hAlign: 'center', borders: ALL_THIN })
  const dataA = fmt({ hAlign: 'center', borders: ALL_THIN })
  const dataB = fmt({ borders: ALL_THIN, numberFormat: '#,##0.00' })
  const formats = [
    [header, header],
    [dataA, dataB],
    [dataA, dataB],
    [dataA, dataB]
  ]
  const out = summarizeCellFormats(formats, 0, 0)
  assert.deepEqual(out.baseFont, { fontName: '宋体', fontSize: 11 })
  assert.deepEqual(out.columns, {
    A: { hAlign: 'center', borders: 'all' },
    B: { numberFormat: '#,##0.00', borders: 'all' }
  })
  assert.deepEqual(out.cells, {
    A1: { bold: true, background: '#D9E1F2', hAlign: 'center', borders: 'all' },
    B1: { bold: true, background: '#D9E1F2', hAlign: 'center', borders: 'all' }
  })
})

test('summarizeCellFormats：与基准字体不同才报 fontName/fontSize；默认格式的列不出现', () => {
  const formats = [
    [fmt(), fmt({ fontName: '黑体', fontSize: 14 })],
    [fmt(), fmt()],
    [fmt(), fmt()]
  ]
  const out = summarizeCellFormats(formats, 4, 2)
  assert.equal(out.columns, undefined)
  assert.deepEqual(out.cells, { D5: { fontName: '黑体', fontSize: 14 } })
})

test('summarizeBorders：四边 all、部分边列出、非细线标线宽', () => {
  assert.equal(summarizeBorders(ALL_THIN), 'all')
  assert.equal(summarizeBorders({ top: { weight: 'thin' }, bottom: { weight: 'thin' } }), 'top,bottom')
  assert.equal(summarizeBorders({ bottom: { weight: 'medium' } }), 'bottom(medium)')
  assert.equal(summarizeBorders({}), null)
})

test('normalizeOfficeCellProps：getCellProperties 形状 → 统一字段（Center 垂直对齐记 middle，无填充记 null）', () => {
  const n = normalizeOfficeCellProps({
    format: {
      font: { name: 'Calibri', size: 11, bold: true, italic: false, color: '#ff0000' },
      fill: { color: '#FFFFFF', pattern: 'None' },
      horizontalAlignment: 'Center',
      verticalAlignment: 'Center',
      wrapText: true,
      borders: {
        top: { style: 'Continuous', weight: 'Thin', color: '#000000' },
        bottom: { style: 'None', weight: 'Thin', color: '#000000' },
        left: { style: 'Continuous', weight: 'Medium', color: '#000000' },
        right: null
      }
    }
  }, '0.00')
  assert.equal(n.color, '#FF0000')
  assert.equal(n.background, null)
  assert.equal(n.hAlign, 'center')
  assert.equal(n.vAlign, 'middle')
  assert.equal(n.wrap, true)
  assert.equal(n.numberFormat, '0.00')
  assert.deepEqual(n.borders.top, { weight: 'thin', color: '#000000' })
  assert.equal(n.borders.bottom, null)
  assert.equal(n.borders.left.weight, 'medium')
})

test('normalizeEtCell：VBA 枚举数值与 BGR 颜色 → 与 Office 同一套字段', () => {
  const bgrToHex = (bgr) => '#' + [bgr & 0xff, (bgr >> 8) & 0xff, (bgr >> 16) & 0xff]
    .map((x) => x.toString(16).padStart(2, '0')).join('').toUpperCase()
  const n = normalizeEtCell({
    fontName: '宋体', fontSize: 10.5, bold: -1, italic: 0, fontColor: 0x0000ff,
    interiorColorIndex: 6, interiorColor: 0xf2e1d9,
    hAlign: -4108, vAlign: -4108, numberFormat: 'G/通用格式', wrap: false,
    borders: { top: { lineStyle: 1, weight: 2, color: 0 }, bottom: { lineStyle: -4142 }, left: { lineStyle: 1, weight: -4138, color: 0 } }
  }, bgrToHex)
  assert.equal(n.bold, true)
  assert.equal(n.color, '#FF0000')
  assert.equal(n.background, '#D9E1F2')
  assert.equal(n.hAlign, 'center')
  assert.equal(n.vAlign, 'middle')
  assert.deepEqual(n.borders.top, { weight: 'thin', color: '#000000' })
  assert.equal(n.borders.bottom, null)
  assert.equal(n.borders.left.weight, 'medium')
  // WPS 中文界面的「常规」格式不算差异
  const out = summarizeCellFormats([[n]], 0, 0)
  assert.equal(out.cells.A1.numberFormat, undefined)
  // 无填充
  assert.equal(normalizeEtCell({ interiorColorIndex: -4142, interiorColor: 0xffffff }, bgrToHex).background, null)
})

test('formatScanShape：总格数不超过上限，先保列再截行', () => {
  assert.deepEqual(formatScanShape(10, 4), { rows: 10, cols: 4, truncated: false })
  assert.deepEqual(formatScanShape(500, 8), { rows: MAX_FORMAT_CELLS / 8, cols: 8, truncated: true })
  assert.deepEqual(formatScanShape(3, 1000), { rows: 1, cols: MAX_FORMAT_CELLS, truncated: true })
})
