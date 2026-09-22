// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * excel_replace（表格内查找替换，dev-board#804 / 审查 A16·B-04）：
 *   node --test office-addin/taskpane/lib/excelReplace.test.js
 *
 * 三层：
 *   1. 纯函数层（excelReplace.js）——两族共用的判定；
 *   2. Office.js 版 HANDLERS.excel_replace——盯「只重写命中的格」这条不变式；
 *   3. WPS JSAPI 版 WPS_ET_HANDLERS.excel_replace——同一条不变式在另一个对象模型上。
 *
 * 核心不变式（两族都必须满足，反过来就是这张卡要修的病）：
 *   **未命中的格一次都不许被写**。审查 B-04 的病灶正是「模型只能整块 set_values 回写，
 *   把区域内不该动的格子一起覆盖掉」——所以用例直接记录每一次写入落在哪个坐标。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  MAX_EXCEL_REPLACEMENTS,
  replaceAllLiteral,
  countLiteral,
  planCellReplacement,
  normalizeReplaceArgs,
  buildReplaceResult
} from './excelReplace.js'
import { WPS_ET_HANDLERS } from './wpsEtHandlers.js'

/* ==================== 纯函数层 ==================== */

test('replaceAllLiteral：字面量替换，正则元字符按文字处理', () => {
  assert.equal(replaceAllLiteral('甲方与甲方代表', '甲方', '买受人', true), '买受人与买受人代表')
  // 合同里真实出现的串：编成 RegExp 会被当成分组语法
  assert.equal(replaceAllLiteral('见（甲方）备注', '（甲方）', '（买受人）', true), '见（买受人）备注')
  assert.equal(replaceAllLiteral('a.b.c', '.', '-', true), 'a-b-c')
  assert.equal(replaceAllLiteral('*备注*', '*', '#', true), '#备注#')
})

test('replaceAllLiteral：不区分大小写时，未命中部分保留原本的大小写', () => {
  assert.equal(replaceAllLiteral('Party A and PARTY B', 'party', '乙方', false), '乙方 A and 乙方 B')
  assert.equal(replaceAllLiteral('Party A', 'party', '乙方', true), 'Party A', '区分大小写时不该命中')
})

test('countLiteral：一格里多处命中各算一次', () => {
  assert.equal(countLiteral('甲方、甲方、甲方', '甲方', true), 3)
  assert.equal(countLiteral('aaaa', 'aa', true), 2, '不重叠计数')
  assert.equal(countLiteral('甲方', '乙方', true), 0)
})

test('planCellReplacement：公式格与数值格命中也不改（改了会毁公式/把数字变成文本）', () => {
  const base = { find: '50', replace: 'X', matchCase: true, wholeCell: false }
  assert.deepEqual(planCellReplacement({ value: '甲方50', formula: '甲方50', ...base }),
    { next: '甲方X', occurrences: 1 })
  assert.deepEqual(planCellReplacement({ value: 12500.5, formula: '=SUM(B2:B3)', ...base }),
    { skip: 'formula' }, '公式格：写字符串会把公式本身毁掉')
  assert.deepEqual(planCellReplacement({ value: 2500.5, formula: '2500.5', ...base }),
    { skip: 'numeric' }, '数值格："2500.5" 里含 "50" 是最常见的误伤')
  assert.equal(planCellReplacement({ value: '', formula: '', ...base }), null)
  assert.equal(planCellReplacement({ value: '甲方', formula: '甲方', ...base }), null, '没命中就不动')
})

test('planCellReplacement：wholeCell 要求整格相等', () => {
  const base = { find: '甲方', replace: '买受人', matchCase: false, wholeCell: true }
  assert.deepEqual(planCellReplacement({ value: '甲方', formula: '甲方', ...base }),
    { next: '买受人', occurrences: 1 })
  assert.equal(planCellReplacement({ value: '甲方代表', formula: '甲方代表', ...base }), null,
    'wholeCell 下「甲方代表」不算命中')
})

test('normalizeReplaceArgs：省略 replace 报错，空串合法（= 删掉命中文本）', () => {
  assert.throws(() => normalizeReplaceArgs({ find: '', replace: 'x' }), /查找文本不能为空/)
  assert.throws(() => normalizeReplaceArgs({ find: '甲方' }), /缺少替换文本/)
  assert.throws(() => normalizeReplaceArgs({ find: '甲方', replace: '甲方' }), /相同/)
  assert.throws(() => normalizeReplaceArgs({ find: 'a', replace: 'b', maxReplacements: 0 }), /不小于 1/)
  assert.equal(normalizeReplaceArgs({ find: '（草稿）', replace: '' }).replace, '')
  assert.equal(normalizeReplaceArgs({ find: 'a', replace: 'b' }).cap, MAX_EXCEL_REPLACEMENTS)
  assert.equal(normalizeReplaceArgs({ find: 'a', replace: 'b', maxReplacements: 999999 }).cap,
    MAX_EXCEL_REPLACEMENTS, '超硬顶夹到硬顶')
})

test('buildReplaceResult：跳过的格数与截断都要如实交代，不能静默', () => {
  const out = buildReplaceResult({
    sheet: 'Sheet1', address: 'A1:D10', find: '甲方', replace: '买受人',
    cells: ['A1'], replaced: 1, occurrences: 2,
    skippedFormula: 2, skippedNumeric: 1, truncated: true, cap: 1
  })
  assert.equal(out.skippedFormulaCells, 2)
  assert.equal(out.skippedNumericCells, 1)
  assert.match(out.note, /上限（1 格）/)
  assert.match(out.note, /公式/)
  assert.match(out.note, /数值/)

  const none = buildReplaceResult({
    sheet: 'Sheet1', address: 'A1', find: 'x', replace: 'y', cells: [],
    replaced: 0, occurrences: 0, skippedFormula: 0, skippedNumeric: 0, truncated: false, cap: 2000
  })
  assert.equal(none.note, '区域内没有命中')
  assert.equal(none.cellsTruncated, false)
})

/* ==================== Office.js 版 ==================== */

/**
 * 假 Excel 命名空间：记录每一次 `getRangeByIndexes(...).values = ...` 落在哪个坐标，
 * 用来证明「未命中的格一次都不许被写」。
 */
function installExcel({ values, formulas, rowIndex = 0, columnIndex = 0, address = 'A1:B4', isNullObject = false }) {
  const saved = globalThis.Excel
  const writes = []
  const used = {
    values, formulas, rowIndex, columnIndex,
    rowCount: values.length, columnCount: (values[0] || []).length,
    address, isNullObject,
    load() {}
  }
  const sheet = {
    name: '台账',
    load() {},
    getUsedRangeOrNullObject() { return used },
    getRange() { return used },
    getRangeByIndexes(r, c, nr, nc) {
      return {
        set values(v) { writes.push({ r, c, nr, nc, value: v[0][0] }) },
        load() {}
      }
    }
  }
  globalThis.Excel = {
    run: async (cb) => cb({
      workbook: { worksheets: { getActiveWorksheet: () => sheet, getItem: () => sheet } },
      sync: async () => {}
    })
  }
  return {
    writes,
    restore() {
      if (saved === undefined) delete globalThis.Excel
      else globalThis.Excel = saved
    }
  }
}

// officeAvailable() 看 Office.context；detectHost() 优先 Office.context.host。
// 两者都要有，executeOfficeCommand 才走得到 excel 分支（COMMAND_HOSTS 要求 excel）。
globalThis.Office = {
  HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
  context: { host: 'Excel', document: { url: 'C:/x/台账.xlsx' } }
}

const { executeOfficeCommand } = await import('./officeExecutor.js')

/** 走公开分发入口（HANDLERS 不导出），成功时取 data、失败时把 error 抛出来 */
async function runExcel(args) {
  const res = await executeOfficeCommand('excel_replace', args)
  if (!res.ok) throw new Error(res.error)
  return res.data
}

test('Office excel_replace：只重写命中的格，其他格一次都不写（审查 B-04 的病灶）', async () => {
  const env = installExcel({
    values: [
      ['项目', '甲方'],
      ['咨询费', 10000],
      ['甲方代表', '张三'],
      ['备注', '甲方与甲方']
    ],
    formulas: [
      ['项目', '甲方'],
      ['咨询费', '10000'],
      ['甲方代表', '张三'],
      ['备注', '甲方与甲方']
    ]
  })
  try {
    const out = await runExcel({ find: '甲方', replace: '买受人' })
    // B1 / A3 / B4 三格命中；A1、A2、B2（数值）、B3 都不该被碰
    assert.deepEqual(env.writes.map((w) => `${w.r},${w.c}=${w.value}`),
      ['0,1=买受人', '2,0=买受人代表', '3,1=买受人与买受人'])
    assert.equal(out.replaced, 3)
    assert.equal(out.occurrences, 4, 'B4 一格里有两处')
    assert.deepEqual(out.cells, ['B1', 'A3', 'B4'])
    assert.equal(out.truncated, false)
    assert.equal(out.sheet, '台账')
  } finally { env.restore() }
})

test('Office excel_replace：公式算出来的文本命中也不改，并如实报数', async () => {
  // B 行是公式格，它**算出来的**文本里就含「甲方」——不做类型判定的实现会把
  // `=A1&"小计"` 直接写成字符串，公式当场报废。
  const env = installExcel({
    values: [['甲方合计'], ['甲方小计'], [12500.5]],
    formulas: [['甲方合计'], ['=A1&"小计"'], ['=SUM(A1:A2)']]
  })
  try {
    const out = await runExcel({ find: '甲方', replace: '买受人' })
    assert.deepEqual(env.writes.map((w) => `${w.r},${w.c}=${w.value}`), ['0,0=买受人合计'],
      '只该写那个纯文本格')
    assert.equal(out.replaced, 1)
    assert.equal(out.skippedFormulaCells, 1, '公式格被跳过必须报出来，不能静默')
    assert.match(out.note, /公式/)
  } finally { env.restore() }
})

test('Office excel_replace：maxReplacements 命中即停并标 truncated', async () => {
  const env = installExcel({
    values: [['甲方'], ['甲方'], ['甲方']],
    formulas: [['甲方'], ['甲方'], ['甲方']]
  })
  try {
    const out = await runExcel({ find: '甲方', replace: '买受人', maxReplacements: 2 })
    assert.equal(env.writes.length, 2)
    assert.equal(out.replaced, 2)
    assert.equal(out.truncated, true)
    assert.match(out.note, /上限（2 格）/)
  } finally { env.restore() }
})

test('Office excel_replace：空表与非法参数都不落笔', async () => {
  const env = installExcel({ values: [[]], formulas: [[]], isNullObject: true })
  try {
    const out = await runExcel({ find: '甲方', replace: '买受人' })
    assert.equal(out.replaced, 0)
    assert.equal(env.writes.length, 0)
    await assert.rejects(runExcel({ find: '甲方' }), /缺少替换文本/)
    await assert.rejects(runExcel({ find: '', replace: 'x' }), /查找文本不能为空/)
    assert.equal(env.writes.length, 0)
  } finally { env.restore() }
})

/* ==================== WPS JSAPI 版 ==================== */

function installWpsEnv({ values, formulas, rowsCount, colsCount, address = 'A1:B4' }) {
  const saved = globalThis.wps
  const writes = []
  const rng = {
    Rows: { Count: rowsCount != null ? rowsCount : values.length },
    Columns: { Count: colsCount != null ? colsCount : (values[0] || []).length },
    Row: 1,
    Column: 1,
    Value2: values,
    Formula: formulas,
    Address() { return address },
    Resize() { return rng }
  }
  const sheet = {
    Name: 'Sheet1',
    UsedRange: rng,
    Range: () => rng,
    Cells: {
      Item(row, col) {
        return { set Value2(v) { writes.push({ row, col, value: v }) } }
      }
    }
  }
  const workbook = {
    ActiveSheet: sheet,
    Worksheets: { Count: 1, Item: () => sheet }
  }
  globalThis.wps = { EtApplication: () => ({ ActiveWorkbook: workbook, DisplayAlerts: true }) }
  return {
    writes,
    restore() {
      if (saved === undefined) delete globalThis.wps
      else globalThis.wps = saved
    }
  }
}

test('WPS excel_replace：与 Office 版同一条不变式——未命中的格一次都不写', async () => {
  const env = installWpsEnv({
    values: [
      ['项目', '甲方'],
      ['咨询费', 10000],
      ['甲方代表', '张三'],
      ['备注', '甲方与甲方']
    ],
    formulas: [
      ['项目', '甲方'],
      ['咨询费', '10000'],
      ['甲方代表', '张三'],
      ['备注', '甲方与甲方']
    ]
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_replace({ find: '甲方', replace: '买受人' })
    // WPS 的 Cells.Item 是 1 起行列
    assert.deepEqual(env.writes.map((w) => `${w.row},${w.col}=${w.value}`),
      ['1,2=买受人', '3,1=买受人代表', '4,2=买受人与买受人'])
    assert.equal(out.replaced, 3)
    assert.equal(out.occurrences, 4)
    assert.deepEqual(out.cells, ['B1', 'A3', 'B4'])
    assert.equal(out.sheet, 'Sheet1')
  } finally { env.restore() }
})

test('WPS excel_replace：数值格命中显示文本也跳过，计数如实报出', async () => {
  const env = installWpsEnv({
    values: [['编号50'], [2500.5]],
    formulas: [['编号50'], ['2500.5']]
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_replace({ find: '50', replace: '60' })
    assert.equal(env.writes.length, 1)
    assert.equal(out.replaced, 1)
    assert.equal(out.skippedNumericCells, 1)
    assert.match(out.note, /数值/)
  } finally { env.restore() }
})

test('WPS excel_replace：扫描行数封顶时如实交代只扫到哪儿', async () => {
  const env = installWpsEnv({
    values: [['甲方']],
    formulas: [['甲方']],
    rowsCount: 20000
  })
  try {
    const out = await WPS_ET_HANDLERS.excel_replace({ find: '甲方', replace: '买受人' })
    assert.equal(out.truncated, true)
    assert.equal(out.totalRows, 20000)
    assert.equal(out.scannedRows, 5000)
    assert.match(out.note, /只扫描了前 5000 行/)
  } finally { env.restore() }
})

test('WPS excel_replace：非法参数不落笔', async () => {
  const env = installWpsEnv({ values: [['甲方']], formulas: [['甲方']] })
  try {
    await assert.rejects(WPS_ET_HANDLERS.excel_replace({ find: '甲方' }), /缺少替换文本/)
    await assert.rejects(WPS_ET_HANDLERS.excel_replace({ find: '', replace: 'x' }), /查找文本不能为空/)
    assert.equal(env.writes.length, 0)
  } finally { env.restore() }
})

/* ==================== 双边三端的登记（漏登记 = 30 秒超时死路径） ==================== */

test('excel_replace 在 Office 执行器里登记齐全：HANDLERS / 宿主 / 显示名 / 可撤销', async () => {
  const { readFileSync } = await import('node:fs')
  const src = readFileSync(new URL('./officeExecutor.js', import.meta.url), 'utf8')
  assert.match(src, /async excel_replace\(args\)/, '没有实现 = 后端空等 30 秒')
  assert.match(src, /excel_replace: 'excel'/, 'COMMAND_HOSTS 漏登记会漏进 Word 会话')
  assert.match(src, /excel_replace: 'cmdExcelReplace'/, '没有显示名 chip 会显示 snake_case')
  assert.match(src, /EXCEL_UNDOABLE_COMMANDS[^\n]*excel_replace/, '批量替换必须可回退')
  assert.ok(typeof WPS_ET_HANDLERS.excel_replace === 'function', 'WPS 侧缺实现 = 双边三端不齐')

  const i18n = readFileSync(new URL('./i18n.js', import.meta.url), 'utf8')
  assert.match(i18n, /cmdExcelReplace: '替换单元格文本'/)
  assert.match(i18n, /cmdExcelReplace: 'Replace in cells'/)
})
