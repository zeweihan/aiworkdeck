/**
 * Office/Excel 面读取路径的过桥量（dev-board#288）：
 *   node --test office-addin/taskpane/lib/officeExcelRead.test.js
 *
 * 病灶：`excel_get_range` 与随消息附带的表格正文都是**先把整片已用区域的 values
 * 编组过桥、再切前 N 行**。几万行的台账上，这一趟能让任务窗格无响应几十秒——
 * 而切完之后真正用到的只有前 500 / 2000 行。WPS 面（wpsDoc.readEtSheet）早就是
 * 「先 Resize 再取 Value2」，Office 面一直没跟。
 *
 * 不变式：**截断必须发生在过桥之前**——`load('values')` 只许落在截断后的区间上，
 * 整片已用区域只许被问尺寸（rowCount/columnCount/address）。
 * 本用例直接盯这条不变式，而不是盯耗时（耗时在 mock 里没有意义）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

/** 记录每个 Range 上 load 过哪些属性，便于断言「谁被要了 values」 */
function makeRange(spec) {
  const r = {
    _tag: spec.tag,
    _loaded: [],
    rowIndex: spec.rowIndex || 0,
    columnIndex: spec.columnIndex || 0,
    rowCount: spec.rowCount,
    columnCount: spec.columnCount,
    address: spec.address || 'A1',
    isNullObject: !!spec.isNullObject,
    values: spec.values,
    load(props) { this._loaded.push(String(props)) }
  }
  return r
}

/**
 * 装一个假的 Excel 命名空间。
 * used = 整片已用区域（大）；byIndexes = getRangeByIndexes 切出来的小区间。
 */
function installExcel({ totalRows, cols, sliceFactory }) {
  const saved = globalThis.Excel
  const used = makeRange({ tag: 'used', rowCount: totalRows, columnCount: cols, address: `A1:C${totalRows}` })
  const slices = []
  const sheet = {
    name: '测算表',
    load() {},
    getUsedRangeOrNullObject() { return used },
    getRangeByIndexes(r, c, nr, nc) {
      const s = sliceFactory(nr, nc)
      s._tag = 'slice'
      s._req = { r, c, nr, nc }
      slices.push(s)
      return s
    },
    getRange() { return used }
  }
  globalThis.Excel = {
    run: async (cb) => cb({
      workbook: {
        worksheets: {
          getActiveWorksheet: () => sheet,
          getItem: () => sheet
        }
      },
      sync: async () => {}
    })
  }
  return {
    used,
    slices,
    restore() {
      if (saved === undefined) delete globalThis.Excel
      else globalThis.Excel = saved
    }
  }
}

function rowsOf(n, cols) {
  return Array.from({ length: n }, (_, i) => Array.from({ length: cols }, (_, c) => `r${i}c${c}`))
}

// officeAvailable() 看的是 Office.context；detectHost() 优先 Office.context.host。
// 两者都要有，读取路径才走得到 Excel 分支。
globalThis.Office = {
  HostType: { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' },
  context: { host: 'Excel', document: { url: 'C:/x/测算表.xlsx' } }
}

const { readActiveDocument } = await import('./wordDoc.js')

test('随消息附带的表格正文：整片已用区域只许被问尺寸，values 只许落在截断后的区间上', async () => {
  // 10 万行的台账，只展示前 2000 行
  const env = installExcel({
    totalRows: 100000,
    cols: 3,
    sliceFactory: (nr, nc) => makeRange({ tag: 'slice', rowCount: nr, columnCount: nc, values: rowsOf(nr, nc) })
  })
  // detectHost 走 Excel 分支
  const savedWord = globalThis.Word
  const savedPpt = globalThis.PowerPoint
  delete globalThis.Word
  delete globalThis.PowerPoint
  try {
    const doc = await readActiveDocument()
    assert.ok(doc, '应读到内容')
    // 不变式一：整片已用区域**不许**被要 values
    const usedLoads = env.used._loaded.join(' ')
    assert.ok(!/values/.test(usedLoads),
      `整片已用区域不该被 load values（会把 10 万行编组过桥），实际 load 了：${usedLoads}`)
    assert.ok(/rowCount/.test(usedLoads), '应当只问尺寸')
    // 不变式二：确实切了 2000 行的小区间，values 落在它身上
    assert.equal(env.slices.length, 1, '应当切一次小区间')
    assert.equal(env.slices[0]._req.nr, 2000, `只该取 2000 行，实际 ${env.slices[0]._req.nr}`)
    assert.ok(env.slices[0]._loaded.join(' ').includes('values'), '小区间才是被要 values 的那个')
    // 内容与「共多少行」的交代都要对
    assert.ok(doc.inlineContent.includes('共 100000 行，仅附前 2000 行'), doc.inlineContent.slice(-80))
  } finally {
    env.restore()
    if (savedWord !== undefined) globalThis.Word = savedWord
    if (savedPpt !== undefined) globalThis.PowerPoint = savedPpt
  }
})

test('小表照旧一次取回，不多切一刀', async () => {
  const env = installExcel({
    totalRows: 12,
    cols: 3,
    sliceFactory: (nr, nc) => makeRange({ tag: 'slice', rowCount: nr, columnCount: nc, values: rowsOf(nr, nc) })
  })
  env.used.values = rowsOf(12, 3)
  const savedWord = globalThis.Word
  const savedPpt = globalThis.PowerPoint
  delete globalThis.Word
  delete globalThis.PowerPoint
  try {
    const doc = await readActiveDocument()
    assert.equal(env.slices.length, 0, '行数没超上限时不该再切区间')
    assert.ok(env.used._loaded.join(' ').includes('values'), '小表直接在已用区域上取值')
    assert.ok(!doc.inlineContent.includes('仅附前'), '不该出现截断说明')
  } finally {
    env.restore()
    if (savedWord !== undefined) globalThis.Word = savedWord
    if (savedPpt !== undefined) globalThis.PowerPoint = savedPpt
  }
})
