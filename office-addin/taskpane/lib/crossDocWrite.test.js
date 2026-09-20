// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 跨文档写入的痕迹（dev-board#717）：别的窗格的 AI 改本文档时，
 *   - Word：必须带修订，宿主标不了修订就拒绝执行（无痕迹的跨文档写入不允许发生）；
 *   - Excel / PPT：没有修订机制，执行前记下改前值、执行后读回改后值，可一键撤销，
 *     撤销前比对当前值，已被再改过就报冲突不覆盖。
 *   node --test office-addin/taskpane/lib/crossDocWrite.test.js
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  runCrossDocWrite, undoEntry, summarize, entrySummary, mergeCrossDocBanner,
  UNDO_LIMITS, WORD_UNTRACKABLE_COMMANDS
} from './crossDocWrite.js'
import { t } from './i18n.js'
import { commandDisplayName } from './hostBridge.js'
import { isReadOnlyCommand } from './docSnapshot.js'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))

const origin = { docName: 'A.docx', conversationId: 'c-a', paneId: 'A' }

test('word refuses without tracking support', async () => {
  let ran = false
  const { result, entry } = await runCrossDocWrite({
    command: 'replace_text', args: {}, origin, host: 'word', family: 'office',
    exec: async () => { ran = true; return { ok: true } }, capture: async () => null, trackingOk: () => false
  })
  assert.equal(result.ok, false)
  assert.match(result.error, /无法标记修订/)
  assert.equal(ran, false)
  assert.equal(entry, null)
})

test('word forces tracking and logs non-undoable entry', async () => {
  let seen
  const args = { searchText: '甲', replaceText: '乙' }
  const { result, entry } = await runCrossDocWrite({
    command: 'replace_text', args, origin, host: 'word', family: 'office',
    exec: async (c, a) => { seen = a; return { ok: true, data: {} } },
    capture: async () => null, trackingOk: () => true
  })
  assert.equal(result.ok, true)
  assert.equal(seen.__forceTracking, true)
  assert.equal(args.__forceTracking, undefined, '不许改写调用方传进来的 args')
  assert.equal(entry.undoable, false)
  assert.equal(entry.originDocName, 'A.docx')
  assert.equal(entry.originConversationId, 'c-a')
  assert.equal(entry.command, 'replace_text')
  assert.equal(entry.locateText, '乙', 'Word 条目要带上定位用的文字（改后的那段）')
})

test('WPS 文字同样强制修订', async () => {
  let seen
  const { result } = await runCrossDocWrite({
    command: 'insert_text', args: { text: '新增条款' }, origin, host: 'word', family: 'wps',
    exec: async (c, a) => { seen = a; return { ok: true, data: {} } },
    capture: async () => null, trackingOk: () => true
  })
  assert.equal(result.ok, true)
  assert.equal(seen.__forceTracking, true)
})

test('Word 里标不出修订的命令（删表格行列、接受/拒绝修订、改文档属性）跨文档一律拒绝', async () => {
  for (const command of ['table_delete_row', 'table_delete_col', 'accept_revision', 'reject_revision', 'set_document_properties']) {
    assert.ok(WORD_UNTRACKABLE_COMMANDS.has(command), command)
    let ran = false
    const { result, entry } = await runCrossDocWrite({
      command, args: {}, origin, host: 'word', family: 'office',
      exec: async () => { ran = true; return { ok: true, data: {} } },
      capture: async () => null, trackingOk: () => true
    })
    assert.equal(result.ok, false, command)
    assert.match(result.error, /修订/)
    assert.equal(ran, false, command)
    assert.equal(entry, null)
  }
})

/**
 * 批注三件在两个家族的执行器里都不经 withTracking（批注不是修订，开着修订也不给它留痕），
 * 所以 __forceTracking 传进去会被原样丢掉：执行照做、文档里一处修订都没有，而修订记录面板
 * 会按 undoable:false + 没有 noBefore 显示「已标为修订，撤销请在修订中拒绝」——那是假的，
 * 用户照着去修订里找，什么也找不到，也没有任何撤销通路。
 */
test('批注类命令（新增/回复/标记已处理）跨文档一律拒绝，不留假的「已标为修订」条目', async () => {
  for (const command of ['add_comment', 'reply_comment', 'resolve_comment']) {
    assert.ok(WORD_UNTRACKABLE_COMMANDS.has(command), command)
    for (const family of ['office', 'wps']) {
      let ran = false
      const { result, entry } = await runCrossDocWrite({
        command, args: { commentIndex: 0, text: '已处理' }, origin, host: 'word', family,
        exec: async () => { ran = true; return { ok: true, data: {} } },
        capture: async () => null, trackingOk: () => true
      })
      assert.equal(result.ok, false, `${command}/${family}`)
      assert.match(result.error, /修订/)
      assert.equal(ran, false, `${command}/${family} 不许执行`)
      assert.equal(entry, null, `${command}/${family} 不许留条目`)
    }
  }
})

/**
 * 名单与执行器对拍：Word 宿主的每一条写入命令，要么它的处理函数经 withTracking /
 * withForcedTracking 执行（留得下修订），要么它必须在 WORD_UNTRACKABLE_COMMANDS 里。
 * 漏一条就是一次「没有痕迹、却被记成已标修订」的跨文档写入——这条用例就是为它守着的。
 * 以 Office 家族的执行器为准（WPS 实现同一批命令名）。
 */
test('WORD_UNTRACKABLE_COMMANDS 与执行器对拍：没有第三种 Word 写入命令', () => {
  const src = fs.readFileSync(path.join(here, 'officeExecutor.js'), 'utf8')
  const hostsBlock = /const COMMAND_HOSTS = \{([\s\S]*?)\n\}/.exec(src)[1]
  const wordCommands = [...hostsBlock.matchAll(/(\w+):\s*'(\w+)'/g)]
    .filter(([, , host]) => host === 'word').map(([, command]) => command)
  assert.ok(wordCommands.length > 20, '没解析到 COMMAND_HOSTS，用例本身失效了')

  // 处理函数体：从 "\n  async 名字(" 到下一个 "\n  async "
  const bodies = new Map()
  const heads = [...src.matchAll(/\n {2}async (\w+)\(/g)]
  heads.forEach((m, i) => {
    const end = i + 1 < heads.length ? heads[i + 1].index : src.length
    bodies.set(m[1], src.slice(m.index, end))
  })

  const unguarded = wordCommands.filter((command) => {
    if (isReadOnlyCommand(command)) return false
    if (WORD_UNTRACKABLE_COMMANDS.has(command)) return false
    const body = bodies.get(command) || ''
    return !/withTracking|withForcedTracking/.test(body)
  })
  assert.deepEqual(unguarded, [],
    '这些 Word 写入命令既不带修订执行、也不在 WORD_UNTRACKABLE_COMMANDS 里：'
    + '跨文档执行会留下没有痕迹的修改，却被记成「已标为修订」')
})

test('执行失败不产生条目，结果原样回传', async () => {
  const { result, entry } = await runCrossDocWrite({
    command: 'replace_text', args: { searchText: 'x', replaceText: 'y' }, origin, host: 'word', family: 'office',
    exec: async () => ({ ok: false, error: '未找到' }), capture: async () => null, trackingOk: () => true
  })
  assert.deepEqual(result, { ok: false, error: '未找到' })
  assert.equal(entry, null)
})

test('exec 抛异常也要变成失败结果，不许把异常抛给调用方', async () => {
  const { result, entry } = await runCrossDocWrite({
    command: 'excel_set_values', args: { rangeAddress: 'B2', values: [[1]] }, origin, host: 'excel', family: 'office',
    exec: async () => { throw new Error('宿主崩了') },
    capture: async () => null, readCurrent: async () => null, trackingOk: () => true
  })
  assert.equal(result.ok, false)
  assert.match(result.error, /宿主崩了/)
  assert.equal(entry, null)
})

test('excel captures before value and is undoable', async () => {
  const order = []
  const { result, entry } = await runCrossDocWrite({
    command: 'excel_set_values', args: { rangeAddress: 'B2', values: [[2]] }, origin,
    host: 'excel', family: 'office',
    exec: async () => { order.push('exec'); return { ok: true, data: {} } },
    capture: async () => { order.push('capture'); return { target: { range: 'B2' }, before: { range: 'B2', values: [[1]] } } },
    readCurrent: async (target) => { order.push('read'); assert.deepEqual(target, { range: 'B2' }); return { range: 'B2', values: [[2]] } },
    trackingOk: () => true
  })
  assert.equal(result.ok, true)
  assert.deepEqual(order, ['capture', 'exec', 'read'], '改前值必须在执行之前取，改后值在执行之后读回')
  assert.equal(entry.undoable, true)
  assert.deepEqual(entry.before.values, [[1]])
  assert.deepEqual(entry.before.target, { range: 'B2' })
  assert.deepEqual(entry.after, { range: 'B2', values: [[2]] })
})

test('capture 拿到的是体积上限（超大区域不记改前值）', async () => {
  let limits
  await runCrossDocWrite({
    command: 'excel_set_values', args: {}, origin, host: 'excel', family: 'office',
    exec: async () => ({ ok: true, data: {} }),
    capture: async (c, a, l) => { limits = l; return null },
    readCurrent: async () => null, trackingOk: () => true
  })
  assert.deepEqual(limits, UNDO_LIMITS)
  assert.ok(UNDO_LIMITS.maxCells > 0 && UNDO_LIMITS.maxChars > 0)
})

test('取不到改前值：照常执行，条目标不可撤销、noBefore', async () => {
  let ran = false
  const { result, entry } = await runCrossDocWrite({
    command: 'excel_format_cells', args: { rangeAddress: 'A1' }, origin, host: 'excel', family: 'office',
    exec: async () => { ran = true; return { ok: true, data: {} } },
    capture: async () => null, readCurrent: async () => null, trackingOk: () => true
  })
  assert.equal(ran, true)
  assert.equal(result.ok, true)
  assert.equal(entry.undoable, false)
  assert.equal(entry.noBefore, true)
  assert.equal(entry.before, undefined)
})

test('capture 抛异常当作取不到，不拦执行', async () => {
  const { result, entry } = await runCrossDocWrite({
    command: 'ppt_replace_text', args: { searchText: 'a', replaceText: 'b' }, origin, host: 'powerpoint', family: 'wps',
    exec: async () => ({ ok: true, data: {} }),
    capture: async () => { throw new Error('读不到') }, readCurrent: async () => null, trackingOk: () => true
  })
  assert.equal(result.ok, true)
  assert.equal(entry.undoable, false)
  assert.equal(entry.noBefore, true)
})

test('改后值读不回来：没法判冲突，就不许标可撤销', async () => {
  const { entry } = await runCrossDocWrite({
    command: 'excel_set_values', args: { rangeAddress: 'B2', values: [[2]] }, origin, host: 'excel', family: 'office',
    exec: async () => ({ ok: true, data: {} }),
    capture: async () => ({ target: { range: 'B2' }, before: { values: [[1]] } }),
    readCurrent: async () => { throw new Error('读回失败') },
    trackingOk: () => true
  })
  assert.equal(entry.undoable, false)
  assert.equal(entry.before, undefined)
})

test('read-only commands produce no entry', async () => {
  let seen
  const { result, entry } = await runCrossDocWrite({
    command: 'get_text', args: {}, origin, host: 'word', family: 'office',
    exec: async (c, a) => { seen = a; return { ok: true, data: {} } }, capture: async () => null, trackingOk: () => false
  })
  assert.equal(result.ok, true, '只读命令不受修订门槛约束')
  assert.equal(seen.__forceTracking, undefined)
  assert.equal(entry, null)
})

test('read_for_reference 与选中区域都不留条目', async () => {
  for (const command of ['read_for_reference', 'excel_select_range']) {
    const { entry } = await runCrossDocWrite({
      command, args: {}, origin, host: 'excel', family: 'office',
      exec: async () => ({ ok: true, data: {} }), capture: async () => null, trackingOk: () => true
    })
    assert.equal(entry, null, command)
  }
})

test('undo detects conflict', async () => {
  const entry = { undoable: true, before: { target: { range: 'B2' }, values: [[1]] }, after: { range: 'B2', values: [[2]] } }
  let wrote = false
  const r = await undoEntry(entry, { readCurrent: async () => ({ range: 'B2', values: [[9]] }), writeBack: async () => { wrote = true } })
  assert.equal(r.ok, false)
  assert.equal(r.conflict, true)
  assert.equal(wrote, false)
})

test('undo 在当前值等于改后值时写回改前值', async () => {
  const entry = { undoable: true, before: { target: { range: 'B2' }, values: [[1]] }, after: { range: 'B2', values: [[2]] } }
  let written
  const r = await undoEntry(entry, {
    readCurrent: async (target) => { assert.deepEqual(target, { range: 'B2' }); return { values: [[2]], range: 'B2' } },
    writeBack: async (before) => { written = before }
  })
  assert.deepEqual(r, { ok: true })
  assert.equal(written, entry.before)
})

test('undo：不可撤销、已撤销、读不到当前值、写回失败都如实返回', async () => {
  assert.equal((await undoEntry({ undoable: false })).ok, false)
  const done = { undoable: true, undone: true, before: { target: {} }, after: {} }
  assert.equal((await undoEntry(done, { readCurrent: async () => ({}), writeBack: async () => {} })).ok, false)
  const e = { undoable: true, before: { target: { a: 1 }, v: 1 }, after: { v: 2 } }
  const missing = await undoEntry(e, { readCurrent: async () => null, writeBack: async () => {} })
  assert.equal(missing.conflict, true, '目标已不存在（表被删、页被删）也按冲突处理')
  const failed = await undoEntry(e, {
    readCurrent: async () => ({ v: 2 }),
    writeBack: async () => { throw new Error('写不进去') }
  })
  assert.equal(failed.ok, false)
  assert.match(failed.error, /写不进去/)
})

test('summarize：人话摘要，按当前界面语言出', () => {
  assert.equal(summarize('replace_text', { searchText: '甲方', replaceText: '乙方' }),
    t('revSumReplace', { from: '甲方', to: '乙方' }))
  assert.equal(summarize('ppt_replace_text', { searchText: 'a', replaceText: 'b' }),
    t('revSumReplace', { from: 'a', to: 'b' }))
  assert.equal(summarize('replace_batch', { items: [{}, {}, {}] }), t('revSumReplaceBatch', { count: 3 }))
  assert.equal(summarize('insert_text', { text: '第九条' }), t('revSumInsert', { text: '第九条' }))
  assert.equal(summarize('excel_set_values', { sheetName: '报价', rangeAddress: 'B2:C3' }),
    t('revSumExcelWrite', { range: '报价!B2:C3' }))
  assert.equal(summarize('excel_set_formulas', { rangeAddress: 'D1' }), t('revSumExcelWrite', { range: 'D1' }))
  assert.equal(summarize('excel_sort_range', { rangeAddress: 'A1:C9' }), t('revSumExcelSort', { range: 'A1:C9' }))
  assert.equal(summarize('ppt_table_set_cell', { slideNumber: 2, row: 0, col: 1, text: '10%' }),
    t('revSumPptCell', { slide: 2, row: 1, col: 2, text: '10%' }))
  // 未知命令回退为命令显示名
  assert.equal(summarize('excel_add_chart', {}), commandDisplayName('excel_add_chart'))
})

test('summarize：长文字截断，别把整段条款塞进一行记录', () => {
  const long = '甲'.repeat(200)
  const s = summarize('insert_text', { text: long })
  assert.ok(s.length < 100, s)
  assert.ok(s.includes('…'))
})

test('entrySummary 按条目里的 key 现查字典（切语言后跟着换），没有 key 回退 summary', () => {
  const entry = { command: 'replace_text', summaryKey: 'revSumReplace', summaryParams: { from: 'x', to: 'y' }, summary: '旧文案' }
  assert.equal(entrySummary(entry), t('revSumReplace', { from: 'x', to: 'y' }))
  assert.equal(entrySummary({ command: 'excel_add_chart', summary: '手写摘要' }), '手写摘要')
  assert.equal(entrySummary({ command: 'excel_add_chart' }), commandDisplayName('excel_add_chart'))
})

test('runCrossDocWrite 的条目带摘要与摘要 key', async () => {
  const { entry } = await runCrossDocWrite({
    command: 'replace_text', args: { searchText: '甲', replaceText: '乙' }, origin, host: 'word', family: 'office',
    exec: async () => ({ ok: true, data: {} }), capture: async () => null, trackingOk: () => true
  })
  assert.equal(entry.summary, t('revSumReplace', { from: '甲', to: '乙' }))
  assert.equal(entry.summaryKey, 'revSumReplace')
  assert.deepEqual(entry.summaryParams, { from: '甲', to: '乙' })
})

test('横幅：同一来源 10 秒内合并计数，换来源或过了窗口就重新计', () => {
  let b = mergeCrossDocBanner(null, 'A.docx', 1000)
  assert.deepEqual({ name: b.originDocName, count: b.count }, { name: 'A.docx', count: 1 })
  b = mergeCrossDocBanner(b, 'A.docx', 5000)
  assert.equal(b.count, 2)
  b = mergeCrossDocBanner(b, 'A.docx', 14000)
  assert.equal(b.count, 3, '窗口按最近一次更新滑动')
  b = mergeCrossDocBanner(b, 'B.xlsx', 15000)
  assert.deepEqual({ name: b.originDocName, count: b.count }, { name: 'B.xlsx', count: 1 })
  b = mergeCrossDocBanner(b, 'B.xlsx', 26000)
  assert.equal(b.count, 1)
})
