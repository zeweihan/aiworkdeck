// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 裁决总览每一行的行态与文案（spec §5.3 那张表）。
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 *
 * 行态判错的后果不是「字不好看」：把 MANUAL 判成 AUTO 会让律师以为同段冲突已经
 * 自动合过了；把 merged 判成 whole 会让他在已经合好的文件上再做一次整份三选一
 * （整份字节覆盖，对方的改动全丢）。所以八种行态逐一钉死。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { mergeRowState, mergeRowText } from '../../src/utils/mergeRows.js'

// 假 $t：把键与参数原样拼出来，断言的是「挑了哪个键、喂了什么参数」
const t = (key, params) => (params === undefined ? key : `${key}(${JSON.stringify(params)})`)

const docx = (over = {}) => ({
  path: '合同.docx', kind: 'DOCX', decision: 'AUTO', reason: 'CLEAN',
  mainChanges: 5, otherChanges: 4, overlapCount: 0, state: 'PENDING', ...over,
})

test('AUTO 的 docx 在桌面端 = 正在合并', () => {
  assert.equal(mergeRowState(docx(), { isDesktop: true }), 'auto-running')
})

test('AUTO 的 docx 不在桌面端 = 退回整份三选一（没有引擎，合不了）', () => {
  assert.equal(mergeRowState(docx(), { isDesktop: false }), 'whole-nondesktop')
  // MANUAL 的 docx 同理：逐处裁决也要引擎
  assert.equal(mergeRowState(docx({ decision: 'MANUAL', overlapCount: 1 }), { isDesktop: false }), 'whole-nondesktop')
})

test('xlsx/pptx 不需要引擎，非桌面端照样自动合', () => {
  assert.equal(mergeRowState(docx({ kind: 'XLSX' }), { isDesktop: false }), 'auto-running')
  assert.equal(mergeRowState(docx({ kind: 'PPTX' }), { isDesktop: false }), 'auto-running')
})

test('已经合好的行优先于一切：不许再显示成待裁决', () => {
  assert.equal(mergeRowState(docx({ state: 'MERGED' }), { isDesktop: true }), 'merged')
  assert.equal(mergeRowState(docx({ state: 'MERGED', decision: 'MANUAL' }), { isDesktop: false }), 'merged')
})

test('MANUAL 按文件类型分三种逐处裁决界面', () => {
  assert.equal(mergeRowState(docx({ decision: 'MANUAL', overlapCount: 2 }), { isDesktop: true }), 'manual-docx')
  assert.equal(mergeRowState(docx({ kind: 'XLSX', decision: 'MANUAL', overlapCount: 3 }), { isDesktop: true }), 'manual-xlsx')
  assert.equal(mergeRowState(docx({ kind: 'PPTX', decision: 'MANUAL', overlapCount: 1 }), { isDesktop: true }), 'manual-pptx')
})

test('pdf/图片等整份文件 = whole，与是不是桌面端无关', () => {
  const pdf = { path: '证据.pdf', kind: 'WHOLE', decision: 'WHOLE', reason: 'BINARY', state: 'PENDING' }
  assert.equal(mergeRowState(pdf, { isDesktop: true }), 'whole')
  assert.equal(mergeRowState(pdf, { isDesktop: false }), 'whole')
})

test('自动合并失败 = 单独一种行态（要给原因、要能重试），不许混进 whole', () => {
  assert.equal(mergeRowState(docx({ failed: true, failReason: 'align' }), { isDesktop: true }), 'auto-failed')
})

test('缺省当桌面端：opts 不传时不许把 docx 行判成「只能在桌面端合」', () => {
  assert.equal(mergeRowState(docx()), 'auto-running')
})

test('文案：正在合并 / 已合并带两边处数', () => {
  assert.equal(mergeRowText(t, docx(), {}), 'version.mergeAutoRunning')
  const sides = { main: { authorName: '韩泽伟', self: true }, other: { authorName: '李律师' } }
  assert.equal(
    mergeRowText(t, docx({ state: 'MERGED', mainCount: 3, otherCount: 4 }), sides),
    'version.mergeRowMerged({"main":"version.actorYou","mainCount":3,"other":"李律师","otherCount":4})'
  )
})

test('文案：已合并没拿到实际处数时退回分析给的改动数，不许显示 0 处', () => {
  const sides = { main: { authorName: '张律师' }, other: { authorName: '李律师' } }
  assert.equal(
    mergeRowText(t, docx({ state: 'MERGED' }), sides),
    'version.mergeRowMerged({"main":"张律师","mainCount":5,"other":"李律师","otherCount":4})'
  )
})

test('文案：两侧信息缺席时用「同事」兜底，绝不显示用户名', () => {
  assert.equal(
    mergeRowText(t, docx({ state: 'MERGED', mainCount: 1, otherCount: 2 }), { other: { username: 'u_8823' } }),
    'version.mergeRowMerged({"main":"version.unnamedColleague","mainCount":1,"other":"version.unnamedColleague","otherCount":2})'
  )
})

test('文案：同一段两边都改了带处数；表格按格、演示按页各一句', () => {
  assert.equal(
    mergeRowText(t, docx({ decision: 'MANUAL', overlapCount: 2 }), {}),
    'version.mergeRowManualDocx({"count":2})'
  )
  assert.equal(
    mergeRowText(t, docx({ kind: 'XLSX', decision: 'MANUAL', overlapCount: 3 }), {}),
    'version.mergeRowManualXlsx({"count":3})'
  )
  assert.equal(
    mergeRowText(t, docx({ kind: 'PPTX', decision: 'MANUAL', overlapCount: 1 }), {}),
    'version.mergeRowManualPptx({"count":1})'
  )
})

test('文案：整份文件按原因给不同的解释句', () => {
  const whole = (reason) => ({ path: 'a.pdf', kind: 'WHOLE', decision: 'WHOLE', reason, state: 'PENDING' })
  assert.equal(mergeRowText(t, whole('BINARY'), {}), 'version.mergeRowWholeBinary')
  assert.equal(mergeRowText(t, whole('TOO_LARGE'), {}), 'version.mergeRowWholeTooLarge')
  assert.equal(mergeRowText(t, whole('NO_BASE'), {}), 'version.mergeRowWholeNoBase')
  assert.equal(mergeRowText(t, whole('PARSE_FAILED'), {}), 'version.mergeRowWholeUnsupported')
  assert.equal(mergeRowText(t, whole('UNSUPPORTED'), {}), 'version.mergeRowWholeUnsupported')
})

test('文案：非桌面端那句说清「在桌面端可逐处合并」，不说成不支持', () => {
  assert.equal(mergeRowText(t, docx(), {}, { isDesktop: false }), 'version.mergeRowDesktopOnly')
})

test('文案：自动合并失败要带得出原因，对齐核对失败与引擎不可用各一句', () => {
  assert.equal(
    mergeRowText(t, docx({ failed: true, failReason: 'align' }), {}),
    'version.mergeRowAutoFailed({"reason":"version.mergeFailReasonAlign"})'
  )
  assert.equal(
    mergeRowText(t, docx({ failed: true, failReason: 'engine' }), {}),
    'version.mergeRowAutoFailed({"reason":"version.mergeFailReasonEngine"})'
  )
  assert.equal(
    mergeRowText(t, docx({ failed: true, failReason: 'compare-main' }), {}),
    'version.mergeRowAutoFailed({"reason":"version.mergeFailReasonGeneric"})'
  )
})
