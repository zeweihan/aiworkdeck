#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 表格新增行与既有行格式一致（dev-board#844）——真引擎无头回归。
//
// 病灶：用户让 AI 往「重大合同清单」（A3:E8 带边框、中文字体、序号/状态居中、金额千分位）
// 末尾加一行，sheet_write_cells 往空格写值落的是引擎默认格式：无边框、默认字体、
// 序号右对齐、状态左对齐——一眼就是后加的。本组盯住：
//   (1) 追加到表格下方：新行逐属性沿用上一行（边框/水平对齐/数字格式/中文字体/字号/行高），
//       返回值带 formatInherited；多行追加逐行级联；撤销一次连值带格式一起退掉；
//   (2) 表头下的第一条数据**不**沿用表头格式（上方只有一行内容）；
//   (3) 标题行紧贴表头（上方两行格式不一致）也不沿用；
//   (4) inheritFormat=false 不沿用；已有内容的行被覆写时格式不动；
//   (5) sheet_read_range(withFormat) 回出格式摘要，默认不回；超 400 格截断并标注；
//   (6) sheet_edit_rows_cols insert_rows 插入的新行是否从上方行继承格式（真机结论打印出来）。
// 判据一律经注入的探针直读 UNO 属性，不信原语自己的回报。
//
// Run:  node tests/lowa-e2e/sheet-format-inherit.mjs     (from frontend/)
// Env:  同 _boot.mjs（LOWA_ENGINE_DIR / PUPPETEER_EXECUTABLE_PATH / LOWA_E2E_PORT）
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

// 最小 xlsx：一张 Sheet1，A1 是内联字符串（与 undo-redo-kinds.mjs 同一套路，走真实 load_document）。
async function minimalXlsx(a1) {
  const NS = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
  const REL = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
  const zip = new JSZip()
  zip.file('[Content_Types].xml',
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>'
    + '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    + '<Default Extension="xml" ContentType="application/xml"/>'
    + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
    + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
    + '</Types>')
  zip.file('_rels/.rels',
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    + '<Relationship Id="rId1" Type="' + REL + '/officeDocument" Target="xl/workbook.xml"/></Relationships>')
  zip.file('xl/workbook.xml',
    '<workbook xmlns="' + NS + '" xmlns:r="' + REL + '">'
    + '<sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>')
  zip.file('xl/_rels/workbook.xml.rels',
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    + '<Relationship Id="rId1" Type="' + REL + '/worksheet" Target="worksheets/sheet1.xml"/></Relationships>')
  zip.file('xl/worksheets/sheet1.xml',
    '<worksheet xmlns="' + NS + '"><sheetData><row r="1">'
    + '<c r="A1" t="inlineStr"><is><t>' + a1 + '</t></is></c>'
    + '</row></sheetData></worksheet>')
  return Array.from(await zip.generateAsync({ type: 'uint8array' }))
}

// 探针：直读活动工作表上一格的格式属性（worker 侧做完枚举归一，返回纯数据）。
const PROBE = `
  debug_sheet_fmt_raw(p) {
    try {
      const sheet = ctrl.getActiveSheet();
      const cell = sheet.getCellRangeByName(String(p.cell)).getCellByPosition(0, 0);
      const g = function (n) { try { return cell.getPropertyValue(n); } catch (e) { return null; } };
      const bw = function (n) { const v = g(n); return v ? v.LineWidth : null; };
      const out = { success: true, text: cell.getString(), type: unoEnumVal(cell.getType()),
        fontName: g('CharFontName'), fontAsian: g('CharFontNameAsian'), fontComplex: g('CharFontNameComplex'),
        size: g('CharHeight'), sizeAsian: g('CharHeightAsian'), weight: g('CharWeight'),
        posture: unoEnumVal(g('CharPosture')), color: g('CharColor'),
        hori: unoEnumVal(g('HoriJustify')), vert: unoEnumVal(g('VertJustify')), bg: g('CellBackColor'),
        top: bw('TopBorder2'), bottom: bw('BottomBorder2'), left: bw('LeftBorder2'), right: bw('RightBorder2') };
      try { out.numberFormat = xModel.getNumberFormats().getByKey(g('NumberFormat')).getPropertyValue('FormatString'); } catch (e) {}
      try {
        const row = sheet.getRows().getByIndex(cell.getCellAddress().Row);
        out.optimalHeight = row.getPropertyValue('OptimalHeight'); out.height = row.getPropertyValue('Height');
      } catch (e) {}
      return out;
    } catch (e) { return { success: false, message: errStr(e) }; }
  },
`
function patchServed(urlPath, content) {
  if (urlPath === '/office_thread.js') {
    const s = content.toString('utf8')
    if (s.split('const EXEC = {').length !== 2) throw new Error('office_thread.js: EXEC anchor not unique')
    return Buffer.from(s.replace('const EXEC = {', 'const EXEC = {\n' + PROBE), 'utf8')
  }
  if (/^\/assets\/editor-.*\.js$/.test(urlPath)) {
    const s = content.toString('utf8')
    if (!s.includes('"get_hyperlink_at_cursor"')) throw new Error('editor bundle: whitelist anchor missing')
    return Buffer.from(s.replace('"get_hyperlink_at_cursor"', '"get_hyperlink_at_cursor","debug_sheet_fmt_raw"'), 'utf8')
  }
  return content
}

preflight()
const server = await startServer({ patchServed })
const browser = await launchBrowser(await loadPuppeteer())
let passed = 0, failed = 0
function check(label, cond, detail) {
  if (cond) { passed++; console.log('  PASS ' + label) }
  else { failed++; console.log('  FAIL ' + label + (detail ? '  [' + detail + ']' : '')) }
}
// 与格式有关的字段（比较「新行是否与上一行一致」用；text/type 不算）
const FMT_KEYS = ['fontName', 'fontAsian', 'fontComplex', 'size', 'sizeAsian', 'weight', 'posture', 'color', 'hori', 'vert', 'bg', 'top', 'bottom', 'left', 'right', 'numberFormat', 'optimalHeight', 'height']
const fmtOf = (o) => JSON.stringify(FMT_KEYS.map((k) => [k, o[k]]))
const COLS = ['A', 'B', 'C', 'D', 'E']

try {
  const page = await openEditor(browser, { clipboard: false })
  page.on('pageerror', (e) => console.error('PAGE ERROR:', e.message))
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  const raw = (cell) => exec('debug_sheet_fmt_raw', { cell })
  const rowSame = async (row, from) => {
    const diffs = []
    for (const c of COLS) {
      const a = await raw(c + row), b = await raw(c + from)
      if (fmtOf(a) !== fmtOf(b)) diffs.push(c + row + ' ' + fmtOf(a) + ' vs ' + c + from + ' ' + fmtOf(b))
    }
    return diffs
  }

  // ---------- 夹具：仿「重大合同清单」 ----------
  console.log('\n[夹具] 标题行 + 空行 + 表头 + 5 行带格式数据（A3:E8）')
  const ld = await exec('load_document', { bytes: await minimalXlsx('重大合同清单'), name: 'contracts.xlsx', authorName: '测试用户' })
  check('load_document 打开 xlsx 且 kind=calc', ld.success === true && ld.kind === 'calc', JSON.stringify(ld))
  const setup = [
    ['sheet_write_cells', { startCell: 'A3', rows: [
      ['序号', '合同名称', '相对方', '金额', '状态'],
      [1, '采购合同', '甲公司', 1250000, '履行中'],
      [2, '租赁合同', '乙公司', 360000.5, '已完成'],
      [3, '借款合同', '丙银行', 5000000, '履行中'],
      [4, '服务合同', '丁公司', 98000, '待履行'],
      [5, '技术合同', '戊公司', 720000, '履行中'],
    ] }],
    ['sheet_format_cells', { range: 'A1', bold: true, fontSize: 16 }],
    ['sheet_format_cells', { range: 'A3:E8', fontName: '宋体', fontSize: 10.5, vAlign: 'center' }],
    ['sheet_format_cells', { range: 'A3:E3', bold: true, hAlign: 'center', background: '#D9D9D9' }],
    ['sheet_format_cells', { range: 'A4:A8', hAlign: 'center' }],
    ['sheet_format_cells', { range: 'E4:E8', hAlign: 'center' }],
    // 相对方列斜体 + 字色 + 底色：覆盖枚举（FontSlant）与颜色类属性的抄写路径
    ['sheet_format_cells', { range: 'C4:C8', italic: true, color: '#C00000', background: '#FFF2CC' }],
    ['sheet_format_cells', { range: 'D4:D8', numberFormat: '#,##0.00', hAlign: 'right' }],
    ['sheet_set_borders', { range: 'A3:E8', preset: 'all', widthPt: 0.75 }],
    ['sheet_set_row_col', { range: 'A4:A8', rowHeightPt: 20 }],
  ]
  for (const [a, p] of setup) {
    const r = await exec(a, p)
    check('夹具 ' + a + ' ' + (p.range || p.startCell), r.success === true, JSON.stringify(r))
  }
  const a8 = await raw('A8'), d8 = await raw('D8')
  // 防空断言：夹具的格式确实与引擎默认不同，否则「新行与上一行一致」恒真。
  check('夹具就位：A8 居中、有边框、宋体、非自动行高', a8.hori === 2 && a8.top > 0 && a8.fontAsian === '宋体' && a8.optimalHeight === false, JSON.stringify(a8))
  check('夹具就位：D8 千分位', d8.numberFormat === '#,##0.00', JSON.stringify(d8))
  const e20 = await raw('A20')
  check('对照：远处空格是默认格式（无边框、非宋体）', !(e20.top > 0) && e20.fontAsian !== '宋体', JSON.stringify(e20))

  // ---------- (1) 末尾追加一行 ----------
  console.log('\n[1] 末尾追加一行：沿用上一行格式')
  const t9 = Date.now()
  const w9 = await exec('sheet_write_cells', { startCell: 'A9', rows: [[6, '保密协议', '己公司', 880000, '待履行']] })
  console.log('  INFO 追加一行（含抄格式）耗时 ' + (Date.now() - t9) + 'ms')
  check('写入成功并回报 formatInherited=[{row:9,from:8}]',
    w9.success === true && JSON.stringify(w9.formatInherited) === JSON.stringify([{ row: 9, from: 8 }]), JSON.stringify(w9))
  let diffs = await rowSame(9, 8)
  check('第 9 行五格格式与第 8 行逐属性一致（边框/对齐/数字格式/中文字体/字号/行高）', diffs.length === 0, diffs.join(' ; '))
  const a9 = await raw('A9'), d9 = await raw('D9'), e9 = await raw('E9')
  check('A9 序号居中、四边有框、中文字体宋体', a9.hori === 2 && a9.top > 0 && a9.bottom > 0 && a9.left > 0 && a9.right > 0 && a9.fontAsian === '宋体', JSON.stringify(a9))
  check('D9 金额千分位、值仍是数值', d9.numberFormat === '#,##0.00' && d9.type === 1 /* VALUE */, JSON.stringify(d9))
  check('E9 状态居中', e9.hori === 2 && e9.text === '待履行', JSON.stringify(e9))
  const c9 = await raw('C9')
  check('C9 相对方斜体、字色 #C00000、底色 #FFF2CC', c9.posture === 2 && c9.color === 0xC00000 && c9.bg === 0xFFF2CC, JSON.stringify(c9))

  // 撤销一次：值和格式一起退掉（不留一行空着但带边框的格子）
  const un = await exec('undo')
  const a9u = await raw('A9')
  check('undo 一次：A9 值与边框一起退回', un.success === true && a9u.type === 0 && !(a9u.top > 0) && a9u.hori !== 2, JSON.stringify(a9u))
  const w9b = await exec('sheet_write_cells', { startCell: 'A9', rows: [[6, '保密协议', '己公司', 880000, '待履行']] })
  check('重写第 9 行再次沿用', JSON.stringify(w9b.formatInherited) === JSON.stringify([{ row: 9, from: 8 }]), JSON.stringify(w9b))

  // 多行追加：逐行级联
  const w10 = await exec('sheet_write_cells', { startCell: 'A10', rows: [[7, '担保合同', '庚公司', 150000, '履行中'], [8, '委托合同', '辛公司', 60000, '已完成']] })
  check('两行追加：formatInherited 逐行级联 10←9、11←10',
    JSON.stringify(w10.formatInherited) === JSON.stringify([{ row: 10, from: 9 }, { row: 11, from: 10 }]), JSON.stringify(w10.formatInherited))
  diffs = (await rowSame(10, 8)).concat(await rowSame(11, 8))
  check('第 10、11 行格式与第 8 行一致', diffs.length === 0, diffs.join(' ; '))

  // ---------- (4) inheritFormat=false / 覆写已有行 ----------
  console.log('\n[4] inheritFormat=false 不沿用；覆写已有内容的行格式不动')
  const w12 = await exec('sheet_write_cells', { startCell: 'A12', rows: [[9, '不沿用', '壬公司', 1000, '履行中']], inheritFormat: false })
  const a12 = await raw('A12')
  check('inheritFormat=false：无 formatInherited、A12 无边框非宋体', w12.success === true && w12.formatInherited === undefined
    && !(a12.top > 0) && a12.fontAsian !== '宋体', JSON.stringify(w12) + ' ' + JSON.stringify(a12))
  const w5 = await exec('sheet_write_cells', { startCell: 'B5', rows: [['租赁合同（续）']] })
  const b5 = await raw('B5')
  check('覆写已有内容的格：无 formatInherited、格式原样', w5.formatInherited === undefined && b5.fontAsian === '宋体' && b5.top > 0, JSON.stringify(w5) + ' ' + JSON.stringify(b5))

  // ---------- (5) sheet_read_range withFormat ----------
  console.log('\n[5] sheet_read_range withFormat')
  const plain = await exec('sheet_read_range', { range: 'A3:E9' })
  check('缺省不回格式', plain.success === true && plain.format === undefined, JSON.stringify(Object.keys(plain)))
  const wf = await exec('sheet_read_range', { range: 'A3:E9', withFormat: true })
  console.log('  INFO withFormat 摘要 ' + JSON.stringify(wf.format))
  const g0 = (wf.format || [])[0] || {}
  const gData = (wf.format || []).find((g) => /^4-/.test(g.rows)) || {}
  check('表头一组：rows=3、A 列加粗/底色/边框 all/宋体',
    g0.rows === '3' && g0.cols && g0.cols.A && g0.cols.A.bold === true && g0.cols.A.background === '#D9D9D9'
    && g0.cols.A.borders === 'all' && g0.cols.A.fontName === '宋体', JSON.stringify(g0))
  check('数据行压成一组（含追加的第 9 行）：rows=4-9、D 列千分位、A/E 居中、行高 20 磅',
    gData.rows === '4-9' && gData.cols.D.numberFormat === '#,##0.00' && gData.cols.A.hAlign === 'center'
    && gData.cols.E.hAlign === 'center' && Math.abs((gData.rowHeightPt || 0) - 20) < 0.2 && !('bold' in gData.cols.A),
    JSON.stringify(wf.format))
  check('数据行 B5 被覆写过的值在 rows 里', wf.rows[2][1] === '租赁合同（续）', JSON.stringify(wf.rows[2]))
  check('带 formatNote、不截断', typeof wf.formatNote === 'string' && wf.formatTruncated === undefined, JSON.stringify(wf.formatNote))
  const big = await exec('sheet_read_range', { range: 'A1:T60', withFormat: true })
  check('超 400 格：formatTruncated 且说明只读了前 20 行', big.success === true && big.formatTruncated === true && /前 20 行/.test(big.formatNote || ''), JSON.stringify({ t: big.formatTruncated, n: big.formatNote }))

  // ---------- (6) insert_rows 的继承行为（结论打印出来） ----------
  console.log('\n[6] sheet_edit_rows_cols insert_rows：新行是否继承上方格式')
  const ins = await exec('sheet_edit_rows_cols', { op: 'insert_rows', start: '6', count: 1 })
  check('insert_rows 成功', ins.success === true, JSON.stringify(ins))
  diffs = await rowSame(6, 5)
  console.log('  INFO insert_rows 后第 6 行（空）与第 5 行格式' + (diffs.length ? '不一致：' + diffs.join(' ; ') : '逐属性一致（引擎自带继承）'))
  check('insert_rows 插入的空行已继承上一行格式（引擎行为）', diffs.length === 0, diffs.join(' ; '))
  const w6 = await exec('sheet_write_cells', { startCell: 'A6', rows: [[2.5, '补充协议', '癸公司', 12000, '履行中']] })
  console.log('  INFO 往插入行写值的 formatInherited=' + JSON.stringify(w6.formatInherited))
  diffs = await rowSame(6, 5)
  check('往插入的空行写值后格式与第 5 行一致', w6.success === true && diffs.length === 0, diffs.join(' ; '))

  // ---------- (2) 表头下的第一条数据 ----------
  console.log('\n[2] 表头下第一条数据：不沿用表头格式')
  await exec('sheet_manage_sheets', { op: 'add', name: '表2' })
  await exec('sheet_write_cells', { startCell: 'A1', rows: [['标题']], sheet: '表2' })
  await exec('sheet_write_cells', { startCell: 'A3', rows: [['序号', '名称', '金额']], sheet: '表2' })
  await exec('sheet_format_cells', { range: 'A3:C3', bold: true, hAlign: 'center', background: '#D9D9D9', fontName: '宋体', sheet: '表2' })
  await exec('sheet_set_borders', { range: 'A3:C3', preset: 'all', sheet: '表2' })
  const h3 = await raw('A3')
  check('表 2 表头就位（加粗、有框）', h3.weight > 100 && h3.top > 0, JSON.stringify(h3))
  const w4 = await exec('sheet_write_cells', { startCell: 'A4', rows: [[1, '第一条', 100]], sheet: '表2' })
  const a4 = await raw('A4')
  check('表头下第一条：无 formatInherited、不加粗、无边框、无底色',
    w4.success === true && w4.formatInherited === undefined && !(a4.weight > 100) && !(a4.top > 0) && a4.bg !== 0xD9D9D9,
    JSON.stringify(w4) + ' ' + JSON.stringify(a4))

  // ---------- (3) 标题紧贴表头 ----------
  console.log('\n[3] 标题行紧贴表头（上方两行格式不一致）：不沿用')
  await exec('sheet_manage_sheets', { op: 'add', name: '表3' })
  await exec('sheet_write_cells', { startCell: 'A1', rows: [['标题'], ['序号', '名称', '金额']], sheet: '表3' })
  await exec('sheet_format_cells', { range: 'A1', bold: true, fontSize: 16, sheet: '表3' })
  await exec('sheet_format_cells', { range: 'A2:C2', bold: true, hAlign: 'center', background: '#D9D9D9', fontName: '宋体', sheet: '表3' })
  await exec('sheet_set_borders', { range: 'A2:C2', preset: 'all', sheet: '表3' })
  const w3 = await exec('sheet_write_cells', { startCell: 'A3', rows: [[1, '第一条', 100]], sheet: '表3' })
  const a3 = await raw('A3')
  check('标题紧贴表头：无 formatInherited、第一条数据不带表头格式',
    w3.success === true && w3.formatInherited === undefined && !(a3.weight > 100) && !(a3.top > 0),
    JSON.stringify(w3) + ' ' + JSON.stringify(a3))

  console.log('\n结果 / result: ' + passed + ' passed, ' + failed + ' failed')
} finally {
  await browser.close()
  server.close()
}
process.exit(failed ? 1 : 0)
