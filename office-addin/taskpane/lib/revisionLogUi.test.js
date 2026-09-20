// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 修订记录面板背后的纯逻辑（dev-board#717，Task 13）：
 *   node --test office-addin/taskpane/lib/revisionLogUi.test.js
 *
 * 钉住：
 *   - 条目的「定位」信息（Word 靠文字、Excel/PPT 靠目标位置）独立于改前值存放——
 *     存储写满时改前值会被丢掉，定位不能跟着丢；
 *   - 面板上每条的撤销状态（可撤销 / 已撤销 / 请在修订里拒绝 / 无改前值）怎么判；
 *   - 撤销的三种结局（撤销成功要落盘标记、冲突不覆盖、失败如实报）；
 *   - 面板开关与未读清零、文档标识、时间显示。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

const mem = new Map()
const storage = {
  getItem: (k) => (mem.has(k) ? mem.get(k) : null),
  setItem: (k, v) => mem.set(k, String(v)),
  removeItem: (k) => mem.delete(k)
}

const {
  runCrossDocWrite, locateEntry, canLocate, entryUndoState, undoRevisionEntry
} = await import('./crossDocWrite.js')
const log = await import('./revisionLog.js')

const origin = { docName: 'A.docx', conversationId: 'c-a', paneId: 'A' }
const ok = async () => ({ ok: true, data: {} })

/* ==================== 条目带定位信息 ==================== */

test('Excel 可撤销条目：定位目标取自改前值的 target，且单独存一份', async () => {
  const target = { kind: 'excel', sheetName: '报价', address: 'B2:C3' }
  const { entry } = await runCrossDocWrite({
    command: 'excel_set_values', args: { sheetName: '报价', rangeAddress: 'B2', values: [[1, 2], [3, 4]] },
    origin, host: 'excel', family: 'office', exec: ok,
    capture: async () => ({ target, before: { ...target, formulas: [[0, 0], [0, 0]] } }),
    readCurrent: async () => ({ ...target, formulas: [[1, 2], [3, 4]] }),
    trackingOk: () => true
  })
  assert.equal(entry.undoable, true)
  assert.deepEqual(entry.locateTarget, target)
})

test('Excel 取不到改前值的命令（格式类）：按参数推出定位目标', async () => {
  const { entry } = await runCrossDocWrite({
    command: 'excel_format_cells', args: { sheetName: '报价', rangeAddress: 'A1:D1', bold: true },
    origin, host: 'excel', family: 'office', exec: ok, capture: async () => null, trackingOk: () => true
  })
  assert.equal(entry.noBefore, true)
  assert.deepEqual(entry.locateTarget, { kind: 'excel', sheetName: '报价', address: 'A1:D1' })
})

test('Excel 区域地址自带表名（报价!A1）时拆开；没有表名就不给定位（不猜活动表）', async () => {
  const qualified = await runCrossDocWrite({
    command: 'excel_format_cells', args: { rangeAddress: "'报价 2026'!A1:B2" },
    origin, host: 'excel', family: 'office', exec: ok, capture: async () => null, trackingOk: () => true
  })
  assert.deepEqual(qualified.entry.locateTarget, { kind: 'excel', sheetName: '报价 2026', address: 'A1:B2' })

  const bare = await runCrossDocWrite({
    command: 'excel_format_cells', args: { rangeAddress: 'A1' },
    origin, host: 'excel', family: 'office', exec: ok, capture: async () => null, trackingOk: () => true
  })
  assert.equal(bare.entry.locateTarget, undefined)
  assert.equal(canLocate(bare.entry), false)
})

test('PPT 命令：按页码推出定位目标；替换文字的定位落在第一个被改的文本框所在页', async () => {
  const shape = await runCrossDocWrite({
    command: 'ppt_add_text_box', args: { slideNumber: 3, text: '新框' },
    origin, host: 'powerpoint', family: 'office', exec: ok, capture: async () => null, trackingOk: () => true
  })
  assert.deepEqual(shape.entry.locateTarget, { kind: 'pptSlide', slideNumber: 3 })

  const frames = { kind: 'pptFrames', frames: [{ slide: 2, frame: 0 }, { slide: 5, frame: 1 }] }
  const replaced = await runCrossDocWrite({
    command: 'ppt_replace_text', args: { searchText: '甲方', replaceText: '乙方' },
    origin, host: 'powerpoint', family: 'office', exec: ok,
    capture: async () => ({ target: frames, before: { kind: 'pptFrames', frames: [] } }),
    readCurrent: async () => ({ kind: 'pptFrames', frames: [] }),
    trackingOk: () => true
  })
  assert.deepEqual(replaced.entry.locateTarget, frames)
})

test('Word 条目靠文字定位，不带位置目标', async () => {
  const { entry } = await runCrossDocWrite({
    command: 'replace_text', args: { searchText: '甲', replaceText: '乙' },
    origin, host: 'word', family: 'office', exec: ok, capture: async () => null, trackingOk: () => true
  })
  assert.equal(entry.locateText, '乙')
  assert.equal(entry.locateTarget, undefined)
  assert.equal(canLocate(entry), true)
})

test('存储写满丢掉改前值时，定位目标保留下来', () => {
  const full = new Map()
  const tight = {
    getItem: (k) => (full.has(k) ? full.get(k) : null),
    setItem: (k, v) => {
      const parsed = JSON.parse(v)
      if (parsed.entries.filter((x) => x.before).length > log.KEEP_SNAPSHOTS_ON_COMPACT) throw new Error('Quota')
      full.set(k, v)
    },
    removeItem: (k) => full.delete(k)
  }
  log.bindDocument('docLocateTight', tight)
  for (let i = 0; i < log.KEEP_SNAPSHOTS_ON_COMPACT + 5; i++) {
    const target = { kind: 'excel', sheetName: 'S', address: `A${i + 1}` }
    log.record({
      command: 'excel_set_values', summary: 'w' + i, undoable: true, locateTarget: target,
      before: { ...target, formulas: [[i]] }, after: { ...target, formulas: [[i + 1]] }
    })
  }
  const oldest = log.entries[log.entries.length - 1]
  assert.equal(oldest.before, undefined, '用例前提：较早条目的改前值已被丢弃')
  assert.equal(canLocate(oldest), true)
  assert.deepEqual(oldest.locateTarget, { kind: 'excel', sheetName: 'S', address: 'A1' })
})

/* ==================== 定位 ==================== */

test('locateEntry：Word 条目按文字定位，Excel/PPT 按目标定位', async () => {
  const calls = []
  const deps = {
    locateText: async (t) => { calls.push(['text', t]); return { found: true } },
    locateTarget: async (t) => { calls.push(['target', t]); return { found: true } }
  }
  assert.deepEqual(await locateEntry({ locateText: '乙方' }, deps), { found: true })
  const target = { kind: 'excel', sheetName: 'S', address: 'A1' }
  assert.deepEqual(await locateEntry({ locateTarget: target }, deps), { found: true })
  // 老条目（本任务之前记的）没有 locateTarget，但改前值里有 target
  await locateEntry({ before: { target, formulas: [[1]] } }, deps)
  assert.deepEqual(calls, [['text', '乙方'], ['target', target], ['target', target]])
})

test('locateEntry：没有可定位的信息、宿主抛错、宿主找不到，一律回 found:false 且不抛', async () => {
  let called = false
  const deps = {
    locateText: async () => { called = true; throw new Error('boom') },
    locateTarget: async () => { called = true; return { found: false } }
  }
  assert.deepEqual(await locateEntry({ command: 'excel_manage_sheets' }, deps), { found: false })
  assert.equal(called, false, '没有定位信息就不去碰宿主')
  assert.deepEqual(await locateEntry({ locateText: 'x' }, deps), { found: false })
  assert.deepEqual(await locateEntry({ locateTarget: { kind: 'pptSlide', slideNumber: 9 } }, deps), { found: false })
  assert.deepEqual(await locateEntry(null, deps), { found: false })
})

/* ==================== 撤销状态与撤销 ==================== */

test('entryUndoState：可撤销 / 已撤销 / 请在修订里拒绝 / 无改前值', () => {
  assert.equal(entryUndoState({ undoable: true, before: { formulas: [[1]] } }), 'undoable')
  assert.equal(entryUndoState({ undoable: true, before: { formulas: [[1]] }, undone: true }), 'undone')
  assert.equal(entryUndoState({ undoable: false, locateText: '乙' }), 'reject')
  assert.equal(entryUndoState({ undoable: false, noBefore: true }), 'noBefore')
  assert.equal(entryUndoState({ undoable: false, snapshotDropped: true }), 'noBefore')
  // 标了可撤销却没有改前值（坏数据）：不能给撤销按钮
  assert.equal(entryUndoState({ undoable: true }), 'noBefore')
})

test('undoRevisionEntry：成功时回调标记已撤销（落盘），冲突与失败不标记', async () => {
  const marked = []
  const onUndone = (id) => marked.push(id)
  const entry = { id: 'rev-1', undoable: true, before: { target: {} }, after: {} }

  assert.deepEqual(await undoRevisionEntry(entry, { undo: async () => ({ ok: true }), onUndone }), { status: 'undone' })
  assert.deepEqual(marked, ['rev-1'])

  assert.deepEqual(
    await undoRevisionEntry(entry, { undo: async () => ({ ok: false, conflict: true }), onUndone }),
    { status: 'conflict' })
  assert.deepEqual(
    await undoRevisionEntry(entry, { undo: async () => ({ ok: false, error: '目标单元格已不存在' }), onUndone }),
    { status: 'failed', error: '目标单元格已不存在' })
  assert.deepEqual(
    await undoRevisionEntry(entry, { undo: async () => { throw new Error('宿主崩了') }, onUndone }),
    { status: 'failed', error: '宿主崩了' })
  assert.deepEqual(marked, ['rev-1'], '只有真撤销成功的那一次标记')
})

/* ==================== 面板开关、文档标识、时间 ==================== */

test('打开面板即清零未读，关闭再清一次（面板开着时进来的新条目用户也看见了）', () => {
  log.bindDocument('docPanel', storage)
  log.record({ command: 'replace_text', summary: 'a' })
  log.record({ command: 'replace_text', summary: 'b' })
  assert.equal(log.unread.value, 2)
  assert.equal(log.revisionLogOpen.value, false)
  log.openRevisionLog()
  assert.equal(log.revisionLogOpen.value, true)
  assert.equal(log.unread.value, 0)
  log.record({ command: 'replace_text', summary: 'c' })
  log.closeRevisionLog()
  assert.equal(log.revisionLogOpen.value, false)
  assert.equal(log.unread.value, 0)
  assert.equal(JSON.parse(mem.get('awd_addin_revlog_docPanel')).unread, 0, '清零要落盘，刷新后角标不能复活')
})

test('docKeyOf：有文件路径用路径，否则用「宿主:文档名」，连宿主都没有就不绑定', () => {
  assert.equal(log.docKeyOf({ path: 'https://x/y/合同.docx', host: 'word', docName: '合同.docx' }), 'https://x/y/合同.docx')
  assert.equal(log.docKeyOf({ path: '', host: 'excel', docName: '工作簿1' }), 'excel:工作簿1')
  assert.equal(log.docKeyOf({ path: '', host: '', docName: '' }), '')
  assert.equal(log.docKeyOf({}), '')
})

test('formatEntryTime：当天只显示时分，其他日子带月日', () => {
  const now = new Date(2026, 8, 18, 15, 0).getTime()
  assert.equal(log.formatEntryTime(new Date(2026, 8, 18, 9, 5).getTime(), now), '09:05')
  assert.equal(log.formatEntryTime(new Date(2026, 8, 17, 23, 59).getTime(), now), '09-17 23:59')
  assert.equal(log.formatEntryTime(new Date(2025, 8, 18, 9, 5).getTime(), now), '2025-09-18 09:05')
  assert.equal(log.formatEntryTime(undefined, now), '')
})
