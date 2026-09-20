// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * WPS 面跨文档写入的宿主侧（dev-board#717），与 officeCrossDoc.test.js 同一套不变式：
 *   node --test office-addin/taskpane/lib/wpsCrossDoc.test.js
 *
 * 1. 文字：带 __forceTracking 的命令在 TrackRevisions 不可用时拒绝执行（不降级直改）；
 *    可用时在修订开着的状态下落笔，之后恢复原值。
 * 2. 表格：改前值按实际落笔区域取公式，撤销写回；用户再改过就冲突不覆盖。
 * 3. 演示：替换文字与表格单元格可撤销，撤销走 Characters 只改差异段。
 *
 * mock 是 VBA 风的同步对象模型；真机上 Range.Formula 的读取形态（单格标量/多格二维）
 * 与 Value2 同口径的假设**未经真机验证**。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

function installWps(app) {
  const saved = globalThis.wps
  const factories = {}
  if (app.Documents) factories.WpsApplication = () => app
  if (app.Workbooks) factories.EtApplication = () => app
  if (app.Presentations) factories.WppApplication = () => app
  globalThis.wps = { Application: app, ...factories }
  return () => {
    if (saved === undefined) delete globalThis.wps
    else globalThis.wps = saved
  }
}

const { executeWpsCommand } = await import('./wpsExecutor.js')
const { runCrossDocWrite, undoEntry } = await import('./crossDocWrite.js')
const { crossDocTrackingOk } = await import('./hostBridge.js')

/* ==================== 文字 ==================== */

function wordApp({ broken }) {
  const state = { text: '', track: false, trackLog: [], writesWhileTracked: [] }
  const doc = {
    get TrackRevisions() {
      if (broken) throw new Error('mock：TrackRevisions 不可用')
      return state.track
    },
    set TrackRevisions(v) {
      if (broken) throw new Error('mock：TrackRevisions 不可用')
      state.track = !!v
      state.trackLog.push(!!v)
    }
  }
  const app = {
    Documents: {},
    ActiveDocument: doc,
    Selection: {
      set Text(v) { state.text += v; state.writesWhileTracked.push(state.track) },
      Collapse() {}
    }
  }
  return { app, state }
}

test('WPS 文字：强制修订 + TrackRevisions 不可用 → 拒绝执行', async () => {
  const { app, state } = wordApp({ broken: true })
  const restore = installWps(app)
  try {
    assert.equal(crossDocTrackingOk(), false, '门槛探测：修订开关读写不了就判不支持')
    const r = await executeWpsCommand('insert_text', { text: '新条款', __forceTracking: true })
    assert.equal(r.ok, false)
    assert.match(r.error, /无法标记修订/)
    assert.equal(state.text, '')
    // 不带强制标记时仍按原契约降级直改（本窗格自己的写入不受影响），且强制状态没有泄漏
    const plain = await executeWpsCommand('insert_text', { text: '本窗格' })
    assert.equal(plain.ok, true)
    assert.equal(plain.data.tracked, false)
  } finally { restore() }
})

test('WPS 文字：强制修订在修订开着时落笔，之后恢复原值', async () => {
  const { app, state } = wordApp({ broken: false })
  const restore = installWps(app)
  try {
    assert.equal(crossDocTrackingOk(), true)
    assert.equal(state.track, false, '探测不许改变用户的修订开关')
    const r = await executeWpsCommand('insert_text', { text: '新条款', __forceTracking: true })
    assert.equal(r.ok, true)
    assert.equal(r.data.tracked, true)
    assert.deepEqual(state.writesWhileTracked, [true])
    assert.equal(state.track, false)
  } finally { restore() }
})

/* ==================== 表格 ==================== */

function colIdx(letters) {
  let n = 0
  for (const ch of letters.toUpperCase()) n = n * 26 + (ch.charCodeAt(0) - 64)
  return n
}
function colName(n) {
  let s = ''
  while (n > 0) { const r = (n - 1) % 26; s = String.fromCharCode(65 + r) + s; n = Math.floor((n - 1) / 26) }
  return s
}
function parseCell(a) {
  const m = /^\$?([A-Za-z]+)\$?(\d+)$/.exec(a)
  return { r: Number(m[2]), c: colIdx(m[1]) }
}

function etApp(initial) {
  const grids = {}
  for (const [name, cells] of Object.entries(initial)) {
    grids[name] = new Map()
    for (const [addr, v] of Object.entries(cells)) {
      const { r, c } = parseCell(addr)
      grids[name].set(`${r},${c}`, v)
    }
  }
  function makeRange(name, r0, c0, r1, c1) {
    const g = grids[name]
    const read = () => {
      const out = []
      for (let r = r0; r <= r1; r++) {
        const row = []
        for (let c = c0; c <= c1; c++) row.push(g.has(`${r},${c}`) ? g.get(`${r},${c}`) : '')
        out.push(row)
      }
      // VBA 口径：单格回标量，多格回二维数组
      return r0 === r1 && c0 === c1 ? out[0][0] : out
    }
    const write = (v) => {
      const arr = Array.isArray(v) ? v : [[v]]
      for (let r = r0; r <= r1; r++) for (let c = c0; c <= c1; c++) g.set(`${r},${c}`, arr[r - r0][c - c0])
    }
    return {
      Rows: { Count: r1 - r0 + 1 },
      Columns: { Count: c1 - c0 + 1 },
      Resize(rows, cols) { return makeRange(name, r0, c0, r0 + rows - 1, c0 + cols - 1) },
      Address() {
        const a = `${colName(c0)}${r0}`
        const b = `${colName(c1)}${r1}`
        return a === b ? a : `${a}:${b}`
      },
      get Value2() { return read() },
      set Value2(v) { write(v) },
      get Formula() { return read() },
      set Formula(v) { write(v) }
    }
  }
  function makeSheet(name) {
    return {
      Name: name,
      Range(addr) {
        const [a, b] = String(addr).split(':')
        const p = parseCell(a)
        const q = b ? parseCell(b) : p
        return makeRange(name, p.r, p.c, q.r, q.c)
      }
    }
  }
  const names = Object.keys(grids)
  const wb = {
    ActiveSheet: makeSheet(names[0]),
    Worksheets: {
      Count: names.length,
      Item(key) {
        const name = typeof key === 'number' ? names[key - 1] : key
        if (!grids[name]) throw new Error('mock：没有这张表')
        return makeSheet(name)
      }
    }
  }
  const app = { Workbooks: {}, ActiveWorkbook: wb }
  const cell = (name, addr) => { const { r, c } = parseCell(addr); return grids[name].get(`${r},${c}`) }
  const setCell = (name, addr, v) => { const { r, c } = parseCell(addr); grids[name].set(`${r},${c}`, v) }
  return { app, cell, setCell }
}

test('WPS 表格：跨文档写入可撤销，公式按公式写回；用户再改过就冲突', async () => {
  const et = etApp({ 报价: { B2: 100, C2: '=B2*0.1' } })
  const restore = installWps(et.app)
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'excel_set_values',
      args: { sheetName: '报价', rangeAddress: 'B2', values: [[200, 20]] },
      origin: { docName: 'A.docx' }, host: 'excel', family: 'wps'
    })
    assert.equal(result.ok, true)
    assert.equal(entry.undoable, true)
    assert.deepEqual(entry.before.target, { kind: 'excel', sheetName: '报价', address: 'B2:C2' })
    assert.deepEqual(entry.before.formulas, [[100, '=B2*0.1']])
    assert.equal(et.cell('报价', 'C2'), 20)

    assert.deepEqual(await undoEntry(entry), { ok: true })
    assert.equal(et.cell('报价', 'B2'), 100)
    assert.equal(et.cell('报价', 'C2'), '=B2*0.1')

    const again = await runCrossDocWrite({
      command: 'excel_set_values',
      args: { sheetName: '报价', rangeAddress: 'B2', values: [[5]] },
      origin: { docName: 'A.docx' }, host: 'excel', family: 'wps'
    })
    assert.deepEqual(again.entry.before.formulas, [[100]], '单格读回标量也要归一成二维')
    et.setCell('报价', 'B2', 7)
    const c = await undoEntry(again.entry)
    assert.equal(c.conflict, true)
    assert.equal(et.cell('报价', 'B2'), 7)
  } finally { restore() }
})

/* ==================== 演示 ==================== */

function textRangeOf(holder) {
  return {
    get Text() { return holder.text },
    set Text(v) { holder.text = String(v); holder.wholeWrites++ },
    get Length() { return holder.text.length },
    Characters(start, len) {
      const s = start - 1
      return {
        get Text() { return holder.text.slice(s, s + len) },
        set Text(v) {
          holder.text = holder.text.slice(0, s) + String(v) + holder.text.slice(s + len)
          holder.subWrites++
        },
        Replace(find, repl) {
          const sub = holder.text.slice(s, s + len)
          const at = sub.indexOf(find)
          if (at < 0) return null
          const abs = s + at
          holder.text = holder.text.slice(0, abs) + repl + holder.text.slice(abs + find.length)
          return { Start: abs + 1 }
        }
      }
    }
  }
}

function wppApp(slides) {
  const model = slides.map((shapes) => shapes.map((s) => {
    if (typeof s === 'string') return { kind: 'text', holder: { text: s, wholeWrites: 0, subWrites: 0 } }
    return { kind: 'table', cells: s.table.map((row) => row.map((t) => ({ text: t, wholeWrites: 0, subWrites: 0 }))) }
  }))
  let id = 0
  const shapeObj = (sp) => {
    const myId = ++id
    if (sp.kind === 'text') {
      return {
        Id: myId, Type: 1, HasTable: false, HasTextFrame: -1,
        TextFrame: { get HasText() { return sp.holder.text.length ? -1 : 0 }, TextRange: textRangeOf(sp.holder) }
      }
    }
    return {
      Id: myId, Type: 19, HasTable: true, HasTextFrame: 0,
      Table: {
        Rows: { Count: sp.cells.length },
        Columns: { Count: sp.cells[0].length },
        Cell(r, c) {
          const cell = sp.cells[r - 1][c - 1]
          return {
            Shape: {
              HasTextFrame: -1,
              TextFrame: { get HasText() { return cell.text.length ? -1 : 0 }, TextRange: textRangeOf(cell) }
            }
          }
        }
      }
    }
  }
  const slideObjs = model.map((shapes) => {
    const objs = shapes.map(shapeObj)
    return { Shapes: { Count: objs.length, Item: (j) => objs[j - 1] } }
  })
  const pres = { Slides: { Count: slideObjs.length, Item: (i) => slideObjs[i - 1] } }
  return { app: { Presentations: {}, ActivePresentation: pres }, model }
}

test('WPS 演示：替换文字可撤销，撤销只改差异段', async () => {
  const w = wppApp([['本协议由甲方与乙方签订', '无关'], ['甲方盖章']])
  const restore = installWps(w.app)
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'ppt_replace_text', args: { searchText: '甲方', replaceText: '委托方' },
      origin: { docName: 'A.docx' }, host: 'powerpoint', family: 'wps'
    })
    assert.equal(result.ok, true)
    assert.equal(w.model[0][0].holder.text, '本协议由委托方与乙方签订')
    assert.equal(entry.undoable, true)
    assert.equal(entry.before.frames.length, 2)

    const hs = [w.model[0][0].holder, w.model[1][0].holder]
    hs.forEach((h) => { h.wholeWrites = 0 })
    assert.deepEqual(await undoEntry(entry), { ok: true })
    assert.equal(w.model[0][0].holder.text, '本协议由甲方与乙方签订')
    assert.equal(w.model[1][0].holder.text, '甲方盖章')
    for (const h of hs) assert.equal(h.wholeWrites, 0, '撤销不许整框回写')
  } finally { restore() }
})

test('WPS 演示：表格单元格可撤销，已被再改就冲突', async () => {
  const w = wppApp([[{ table: [['项目', '费率'], ['顾问费', '10%']] }]])
  const restore = installWps(w.app)
  try {
    const { result, entry } = await runCrossDocWrite({
      command: 'ppt_table_set_cell', args: { slideNumber: 1, row: 1, col: 1, text: '12%' },
      origin: { docName: 'A.docx' }, host: 'powerpoint', family: 'wps'
    })
    assert.equal(result.ok, true)
    assert.equal(entry.undoable, true)
    assert.deepEqual(await undoEntry(entry), { ok: true })
    assert.equal(w.model[0][0].cells[1][1].text, '10%')

    const again = await runCrossDocWrite({
      command: 'ppt_table_set_cell', args: { slideNumber: 1, row: 1, col: 1, text: '15%' },
      origin: { docName: 'A.docx' }, host: 'powerpoint', family: 'wps'
    })
    w.model[0][0].cells[1][1].text = '18%'
    const c = await undoEntry(again.entry)
    assert.equal(c.conflict, true)
    assert.equal(w.model[0][0].cells[1][1].text, '18%')
  } finally { restore() }
})
