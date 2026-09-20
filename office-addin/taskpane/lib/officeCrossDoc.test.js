// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * Office 面跨文档写入的宿主侧（dev-board#717）：
 *   node --test office-addin/taskpane/lib/officeCrossDoc.test.js
 *
 * 1. Word：带 __forceTracking 的命令在标不了修订的宿主上必须**拒绝执行**（不许像本窗格
 *    自己的写入那样降级成直改）；标得了就在 TrackAll 下落笔、之后恢复用户原来的开关；
 *    __forceTracking 不许漏进 handler 的参数，也不许把强制状态泄漏给之后的命令。
 * 2. Excel：改前值按「实际落笔的区域」取（单元格起点按 values 尺寸展开），撤销写回原值；
 *    撤销前比对当前值，用户已再改过就报冲突不覆盖。
 * 3. PPT：替换文字与表格单元格可撤销；撤销只改差异段（不整框回写抹掉格式）。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

const HostType = { Word: 'Word', Excel: 'Excel', PowerPoint: 'PowerPoint' }

function setGlobals(g) {
  const saved = {}
  for (const k of ['Office', 'Word', 'Excel', 'PowerPoint']) {
    saved[k] = globalThis[k]
    if (k in g) globalThis[k] = g[k]
    else delete globalThis[k]
  }
  return () => {
    for (const k of Object.keys(saved)) {
      if (saved[k] === undefined) delete globalThis[k]
      else globalThis[k] = saved[k]
    }
  }
}

/* ==================== Word ==================== */

function installWord({ tracking }) {
  const state = { mode: 'Off', modeWrites: [], breaks: [] }
  const document = {
    load() {},
    get changeTrackingMode() { return state.mode },
    set changeTrackingMode(v) { state.mode = v; state.modeWrites.push(v) },
    getSelection: () => ({
      insertBreak: (type, loc) => state.breaks.push({ type, loc, modeAtWrite: state.mode })
    })
  }
  const restore = setGlobals({
    Office: {
      HostType,
      context: {
        host: 'Word',
        requirements: { isSetSupported: (set, v) => (set === 'WordApi' && v === '1.4' ? tracking : true) }
      }
    },
    Word: {
      run: async (cb) => cb({ document, sync: async () => {} }),
      BreakType: { page: 'Page', sectionNext: 'SectionNext' },
      InsertLocation: { before: 'Before', after: 'After' },
      ChangeTrackingMode: { trackAll: 'TrackAll' }
    }
  })
  return { state, restore }
}

const { executeOfficeCommand } = await import('./officeExecutor.js')
const { runCrossDocWrite, undoEntry } = await import('./crossDocWrite.js')

test('Word：强制修订 + 宿主标不了修订 → 拒绝执行，一个字都不落', async () => {
  const w = installWord({ tracking: false })
  try {
    const r = await executeOfficeCommand('insert_break', { __forceTracking: true })
    assert.equal(r.ok, false)
    assert.match(r.error, /无法标记修订/)
    assert.equal(w.state.breaks.length, 0)
  } finally { w.restore() }
})

test('Word：强制修订在 TrackAll 下落笔，之后恢复原开关；强制状态不泄漏给下一条命令', async () => {
  const w = installWord({ tracking: true })
  try {
    const r = await executeOfficeCommand('insert_break', { __forceTracking: true })
    assert.equal(r.ok, true)
    assert.equal(r.data.tracked, true)
    assert.equal(w.state.breaks[0].modeAtWrite, 'TrackAll')
    assert.equal(w.state.mode, 'Off', '用户原来的修订开关要恢复')
  } finally { w.restore() }
  // 同一个模块实例上，下一条不带强制标记的命令在标不了修订的宿主上照旧降级直改
  const w2 = installWord({ tracking: false })
  try {
    const r = await executeOfficeCommand('insert_break', {})
    assert.equal(r.ok, true)
    assert.equal(r.data.tracked, false)
  } finally { w2.restore() }
})

test('Word：runCrossDocWrite 用默认依赖跑通（门槛探测走宿主桥）', async () => {
  const w = installWord({ tracking: false })
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'insert_break', args: {}, origin: { docName: 'A.docx' }, host: 'word', family: 'office'
    })
    assert.equal(result.ok, false)
    assert.match(result.error, /无法标记修订/)
    assert.equal(entry, null)
    assert.equal(w.state.breaks.length, 0)
  } finally { w.restore() }
  const w2 = installWord({ tracking: true })
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'insert_break', args: {}, origin: { docName: 'A.docx' }, host: 'word', family: 'office'
    })
    assert.equal(result.ok, true)
    assert.equal(entry.undoable, false)
    assert.equal(w2.state.breaks[0].modeAtWrite, 'TrackAll')
  } finally { w2.restore() }
})

/* ==================== Excel（网格 mock） ==================== */

function colIdx(letters) {
  let n = 0
  for (const ch of letters.toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64)
  return n - 1
}
function colName(i) {
  let s = ''
  let n = i + 1
  while (n > 0) { const r = (n - 1) % 26; s = String.fromCharCode(65 + r) + s; n = Math.floor((n - 1) / 26) }
  return s
}
function parseCell(a) {
  const m = /^\$?([A-Za-z]+)\$?(\d+)$/.exec(a)
  return { r: Number(m[2]) - 1, c: colIdx(m[1]) }
}

function installExcel(initial) {
  // initial: { sheetName: { 'B2': 1, ... } }
  const sheets = {}
  for (const [name, cells] of Object.entries(initial)) {
    sheets[name] = new Map()
    for (const [addr, v] of Object.entries(cells)) {
      const { r, c } = parseCell(addr)
      sheets[name].set(`${r},${c}`, v)
    }
  }
  const counters = { formulaLoads: 0 }
  function makeRange(name, r0, c0, r1, c1) {
    const grid = sheets[name]
    const read = () => {
      const out = []
      for (let r = r0; r <= r1; r++) {
        const row = []
        for (let c = c0; c <= c1; c++) row.push(grid.has(`${r},${c}`) ? grid.get(`${r},${c}`) : '')
        out.push(row)
      }
      return out
    }
    const write = (v) => {
      for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) grid.set(`${r},${c}`, v[r - r0][c - c0])
    }
    return {
      rowIndex: r0,
      columnIndex: c0,
      get rowCount() { return r1 - r0 + 1 },
      get columnCount() { return c1 - c0 + 1 },
      get address() {
        const a = `${colName(c0)}${r0 + 1}`
        const b = `${colName(c1)}${r1 + 1}`
        return `${name}!${a === b ? a : `${a}:${b}`}`
      },
      load(p) { if (String(p).includes('formulas')) counters.formulaLoads++ },
      getResizedRange(dr, dc) { return makeRange(name, r0, c0, r1 + dr, c1 + dc) },
      get values() { return read() },
      set values(v) { write(v) },
      get formulas() { return read() },
      set formulas(v) { write(v) },
      sort: { apply() { /* 排序在本 mock 里不重排，只验证改前值 */ } }
    }
  }
  function makeSheet(name) {
    if (!sheets[name]) {
      const e = new Error('ItemNotFound')
      e.code = 'ItemNotFound'
      throw e
    }
    return {
      name,
      load() {},
      getRange(addr) {
        const [a, b] = String(addr).split(':')
        const p = parseCell(a)
        const q = b ? parseCell(b) : p
        return makeRange(name, p.r, p.c, q.r, q.c)
      }
    }
  }
  const active = Object.keys(sheets)[0]
  const restore = setGlobals({
    Office: { HostType, context: { host: 'Excel', requirements: { isSetSupported: () => true } } },
    Excel: {
      run: async (cb) => cb({
        workbook: { worksheets: { getItem: (n) => makeSheet(n), getActiveWorksheet: () => makeSheet(active) } },
        sync: async () => {}
      })
    }
  })
  const cell = (name, addr) => {
    const { r, c } = parseCell(addr)
    return sheets[name].get(`${r},${c}`)
  }
  const setCell = (name, addr, v) => {
    const { r, c } = parseCell(addr)
    sheets[name].set(`${r},${c}`, v)
  }
  return { cell, setCell, counters, restore }
}

const { captureOfficeState, readOfficeState, writeOfficeState } = await import('./officeExecutor.js')
const LIMITS = { maxCells: 2000, maxChars: 20000 }

test('Excel：单元格起点的写入按 values 尺寸展开取改前值（与 handler 的落笔区域一致）', async () => {
  const x = installExcel({ 报价: { B2: 1, C2: 2, B3: '=B2*2', C3: 'x' } })
  try {
    const snap = await captureOfficeState('excel_set_values',
      { sheetName: '报价', rangeAddress: 'B2', values: [[9, 9], [9, 9]] }, LIMITS)
    assert.deepEqual(snap.target, { kind: 'excel', sheetName: '报价', address: 'B2:C3' })
    assert.deepEqual(snap.before.formulas, [[1, 2], ['=B2*2', 'x']])
    assert.equal(snap.before.kind, 'excel')
  } finally { x.restore() }
})

test('Excel：超过体积上限、尺寸对不上、不可撤销的命令都不取改前值', async () => {
  const x = installExcel({ S: { A1: 1 } })
  try {
    assert.equal(await captureOfficeState('excel_set_values',
      { rangeAddress: 'A1', values: Array.from({ length: 50 }, () => Array(50).fill(0)) }, { maxCells: 100, maxChars: 1 }), null)
    assert.equal(await captureOfficeState('excel_set_values',
      { rangeAddress: 'A1:B2', values: [[1, 2, 3]] }, LIMITS), null)
    assert.equal(await captureOfficeState('excel_format_cells', { rangeAddress: 'A1' }, LIMITS), null)
    assert.equal(await captureOfficeState('excel_manage_sheets', { action: 'delete', sheetName: 'S' }, LIMITS), null)
  } finally { x.restore() }
})

test('Excel：跨文档写入 → 撤销写回原值；用户再改过就冲突不覆盖', async () => {
  const x = installExcel({ 报价: { B2: 100, C2: '=B2*0.1' } })
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'excel_set_values',
      args: { sheetName: '报价', rangeAddress: 'B2:C2', values: [[200, 20]] },
      origin: { docName: 'A.docx', conversationId: 'c-a' },
      host: 'excel',
      family: 'office'
    })
    assert.equal(result.ok, true)
    assert.equal(x.cell('报价', 'B2'), 200)
    assert.equal(entry.undoable, true)
    assert.deepEqual(entry.after.formulas, [[200, 20]])

    // 撤销：当前值仍是改后值 → 写回
    const r = await undoEntry(entry)
    assert.deepEqual(r, { ok: true })
    assert.equal(x.cell('报价', 'B2'), 100)
    assert.equal(x.cell('报价', 'C2'), '=B2*0.1', '公式要按公式写回，而不是写回算出来的值')

    // 再来一次，这回用户在撤销前手动改了一格
    const again = await runCrossDocWrite({
      command: 'excel_set_values',
      args: { sheetName: '报价', rangeAddress: 'B2:C2', values: [[300, 30]] },
      origin: { docName: 'A.docx' }, host: 'excel', family: 'office'
    })
    x.setCell('报价', 'C2', 99)
    const c = await undoEntry(again.entry)
    assert.equal(c.ok, false)
    assert.equal(c.conflict, true)
    assert.equal(x.cell('报价', 'B2'), 300, '冲突时一格都不许动')
    assert.equal(x.cell('报价', 'C2'), 99)
  } finally { x.restore() }
})

test('Excel：排序也按区域记改前值', async () => {
  const x = installExcel({ S: { A1: 3, A2: 1, A3: 2 } })
  try {
    const snap = await captureOfficeState('excel_sort_range', { rangeAddress: 'A1:A3', keyColumn: 0 }, LIMITS)
    assert.deepEqual(snap.before.formulas, [[3], [1], [2]])
    assert.deepEqual(snap.target, { kind: 'excel', sheetName: 'S', address: 'A1:A3' })
  } finally { x.restore() }
})

/* ==================== PowerPoint ==================== */

function makeTextRange(holder) {
  return {
    load() {},
    get text() { return holder.text },
    set text(v) { holder.text = String(v); holder.wholeWrites++ },
    getSubstring(start, len) {
      return {
        set text(v) {
          holder.text = holder.text.slice(0, start) + String(v) + holder.text.slice(start + len)
          holder.subWrites++
        }
      }
    }
  }
}

function installPpt(slides) {
  // slides: [[ 'text' | { table: [['a','b'],...] } ]]
  const model = slides.map((shapes) => shapes.map((s) => {
    if (typeof s === 'string') return { kind: 'text', holder: { text: s, wholeWrites: 0, subWrites: 0 } }
    return { kind: 'table', cells: s.table.map((row) => row.map((t) => ({ text: t }))) }
  }))
  let idSeq = 0
  const slideObjs = model.map((shapes) => ({
    shapes: {
      items: shapes.map((sp) => ({
        id: String(++idSeq),
        type: sp.kind === 'table' ? 'Table' : 'GeometricShape',
        getTextFrameOrNullObject() {
          if (sp.kind !== 'text') return { isNullObject: true, hasText: false, load() {}, textRange: { load() {} } }
          return {
            isNullObject: false,
            get hasText() { return sp.holder.text.length > 0 },
            load() {},
            textRange: makeTextRange(sp.holder)
          }
        },
        getTable() {
          return {
            getCellOrNullObject(r, c) {
              const cell = sp.cells[r] && sp.cells[r][c]
              if (!cell) return { isNullObject: true, load() {} }
              return {
                isNullObject: false,
                load() {},
                get text() { return cell.text },
                set text(v) { cell.text = String(v) }
              }
            }
          }
        }
      })),
      load() {}
    }
  }))
  const restore = setGlobals({
    Office: { HostType, context: { host: 'PowerPoint', requirements: { isSetSupported: () => true } } },
    PowerPoint: {
      run: async (cb) => cb({ presentation: { slides: { items: slideObjs, load() {} } }, sync: async () => {} })
    }
  })
  return { model, restore }
}

test('PPT：替换文字可撤销，撤销只改差异段、不整框回写', async () => {
  const p = installPpt([['本协议由甲方与乙方签订', '无关文字'], ['甲方盖章']])
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'ppt_replace_text',
      args: { searchText: '甲方', replaceText: '委托方' },
      origin: { docName: 'A.docx' }, host: 'powerpoint', family: 'office'
    })
    assert.equal(result.ok, true)
    assert.equal(p.model[0][0].holder.text, '本协议由委托方与乙方签订')
    assert.equal(entry.undoable, true)
    assert.equal(entry.before.frames.length, 2, '只记被命中的文本框')

    const holders = [p.model[0][0].holder, p.model[1][0].holder]
    holders.forEach((h) => { h.wholeWrites = 0 })
    const r = await undoEntry(entry)
    assert.deepEqual(r, { ok: true })
    assert.equal(p.model[0][0].holder.text, '本协议由甲方与乙方签订')
    assert.equal(p.model[1][0].holder.text, '甲方盖章')
    assert.equal(p.model[0][1].holder.text, '无关文字')
    for (const h of holders) assert.equal(h.wholeWrites, 0, '撤销不许整框回写（会抹掉框内格式与超链接）')
  } finally { p.restore() }
})

test('PPT：撤销前文本框已被再改 → 冲突不覆盖', async () => {
  const p = installPpt([['甲方']])
  try {
    const { entry } = await runCrossDocWrite({
      command: 'ppt_replace_text', args: { searchText: '甲方', replaceText: '乙方' },
      origin: { docName: 'A.docx' }, host: 'powerpoint', family: 'office'
    })
    p.model[0][0].holder.text = '乙方（已手改）'
    const r = await undoEntry(entry)
    assert.equal(r.conflict, true)
    assert.equal(p.model[0][0].holder.text, '乙方（已手改）')
  } finally { p.restore() }
})

test('PPT：表格单元格可撤销', async () => {
  const p = installPpt([['标题', { table: [['项目', '费率'], ['顾问费', '10%']] }]])
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'ppt_table_set_cell', args: { slideNumber: 1, row: 1, col: 1, text: '12%' },
      origin: { docName: 'A.docx' }, host: 'powerpoint', family: 'office'
    })
    assert.equal(result.ok, true)
    assert.equal(p.model[0][1].cells[1][1].text, '12%')
    assert.equal(entry.undoable, true)
    const r = await undoEntry(entry)
    assert.deepEqual(r, { ok: true })
    assert.equal(p.model[0][1].cells[1][1].text, '10%')
  } finally { p.restore() }
})

test('PPT：结构性命令（加页、删形状）不记改前值', async () => {
  const p = installPpt([['甲']])
  try {
    assert.equal(await captureOfficeState('ppt_add_slide', {}, LIMITS), null)
    assert.equal(await captureOfficeState('ppt_delete_shape', { slideNumber: 1, shapeId: '1' }, LIMITS), null)
    assert.equal(await captureOfficeState('ppt_format_text', { searchText: '甲', bold: true }, LIMITS), null)
  } finally { p.restore() }
})

test('读当前值：目标已不存在时回 null（撤销按冲突处理）', async () => {
  const x = installExcel({ S: { A1: 1 } })
  try {
    assert.equal(await readOfficeState({ kind: 'excel', sheetName: '已删的表', address: 'A1' }), null)
    assert.equal(await readOfficeState({ kind: 'nope' }), null)
  } finally { x.restore() }
})

test('写回未知形态的快照要报错，不许静默当成功', async () => {
  const x = installExcel({ S: { A1: 1 } })
  try {
    await assert.rejects(() => writeOfficeState({ kind: 'nope' }))
  } finally { x.restore() }
})
