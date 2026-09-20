// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 修订记录存储（dev-board#717）：别的窗格的 AI 改了本文档，痕迹要留在本文档自己的窗格里。
 *   node --test office-addin/taskpane/lib/revisionLog.test.js
 *
 * 钉住：新在前、按文档分开持久化、上限 200、未读计数、存储写满时的降级
 * （丢掉较早条目的改前值，而不是整本记录再也存不进去）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'

const mem = new Map()
const storage = {
  getItem: (k) => (mem.has(k) ? mem.get(k) : null),
  setItem: (k, v) => mem.set(k, String(v)),
  removeItem: (k) => mem.delete(k)
}
const log = await import('./revisionLog.js')

test('records newest first, persists per document, caps at 200', () => {
  log.bindDocument('docA', storage)
  for (let i = 0; i < 205; i++) log.record({ originDocName: 'A.docx', command: 'replace_text', summary: 's' + i })
  assert.equal(log.entries.length, 200)
  assert.equal(log.entries[0].summary, 's204')
  assert.equal(log.unread.value, 205)
  log.bindDocument('docB', storage)
  assert.equal(log.entries.length, 0)
  log.bindDocument('docA', storage)
  assert.equal(log.entries.length, 200)
  log.markAllRead()
  assert.equal(log.unread.value, 0)
})

test('record 给条目补上 id 与时间，并原样保留来源、改前值与撤销能力', () => {
  log.bindDocument('docMeta', storage)
  const e = log.record({
    originDocName: 'A.docx',
    originConversationId: 'c-a',
    command: 'excel_set_values',
    summary: '写入 B2',
    before: { target: { kind: 'excel', sheetName: 'S', address: 'B2' }, formulas: [[1]] },
    after: { kind: 'excel', sheetName: 'S', address: 'B2', formulas: [[2]] },
    undoable: true
  })
  assert.ok(typeof e.id === 'string' && e.id.length > 0)
  assert.ok(Number.isFinite(e.time) && e.time > 0)
  assert.equal(log.entries[0], e)
  assert.equal(e.originConversationId, 'c-a')
  assert.equal(e.undoable, true)
  // 持久化进了按文档分开的键，再绑一次能原样读回
  assert.ok(mem.has('awd_addin_revlog_docMeta'))
  log.bindDocument('docOther', storage)
  log.bindDocument('docMeta', storage)
  assert.equal(log.entries[0].id, e.id)
  assert.deepEqual(log.entries[0].before.formulas, [[1]])
  assert.equal(log.unread.value, 1, '未读计数也按文档持久化')
})

test('remove / markUndone / clear 都会持久化', () => {
  log.bindDocument('docOps', storage)
  const a = log.record({ command: 'replace_text', summary: 'a' })
  const b = log.record({ command: 'replace_text', summary: 'b', undoable: true })
  log.markUndone(b.id)
  assert.equal(log.entries.find((x) => x.id === b.id).undone, true)
  log.remove(a.id)
  assert.deepEqual(log.entries.map((x) => x.summary), ['b'])
  log.bindDocument('docElse', storage)
  log.bindDocument('docOps', storage)
  assert.deepEqual(log.entries.map((x) => x.summary), ['b'])
  assert.equal(log.entries[0].undone, true)
  log.clear()
  assert.equal(log.entries.length, 0)
  assert.equal(log.unread.value, 0)
  log.bindDocument('docElse', storage)
  log.bindDocument('docOps', storage)
  assert.equal(log.entries.length, 0)
})

test('没绑定文档时照样能记（只在内存里），不抛', () => {
  log.bindDocument('', storage)
  const e = log.record({ command: 'replace_text', summary: 'x' })
  assert.equal(log.entries[0].id, e.id)
  assert.ok(![...mem.keys()].some((k) => k === 'awd_addin_revlog_'), '不许落一个空文档键')
})

test('存储里是坏数据时当作空记录，不抛', () => {
  mem.set('awd_addin_revlog_docBad', '{not json')
  log.bindDocument('docBad', storage)
  assert.equal(log.entries.length, 0)
  assert.equal(log.unread.value, 0)
})

test('存储写满时丢掉较早条目的改前值再存，而不是整本存不进去', () => {
  // 只有「较早条目不带快照」时才写得进去的存储：模拟配额被大快照撑满
  const full = new Map()
  let failures = 0
  const tight = {
    getItem: (k) => (full.has(k) ? full.get(k) : null),
    setItem: (k, v) => {
      const parsed = JSON.parse(v)
      const heavy = parsed.entries.filter((x) => x.before).length
      if (heavy > log.KEEP_SNAPSHOTS_ON_COMPACT) { failures++; throw new Error('QuotaExceededError') }
      full.set(k, v)
    },
    removeItem: (k) => full.delete(k)
  }
  log.bindDocument('docTight', tight)
  for (let i = 0; i < 30; i++) {
    log.record({
      command: 'excel_set_values',
      summary: 'w' + i,
      before: { target: { kind: 'excel' }, formulas: [[i]] },
      after: { kind: 'excel', formulas: [[i + 1]] },
      undoable: true
    })
  }
  assert.ok(failures > 0, '用例前提：确实撞到过配额')
  const saved = JSON.parse(full.get('awd_addin_revlog_docTight'))
  assert.equal(saved.entries.length, 30, '条目本身一条不丢')
  // 最新的若干条仍可撤销，较早的改前值被丢弃且如实标注
  assert.equal(saved.entries[0].undoable, true)
  assert.ok(saved.entries[0].before)
  const old = saved.entries[29]
  assert.equal(old.undoable, false)
  assert.equal(old.before, undefined)
  assert.equal(old.snapshotDropped, true)
  // 内存里的状态与存进去的一致（不能界面显示可撤销、刷新后又不行）
  assert.equal(log.entries[29].undoable, false)
})
