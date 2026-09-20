// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#723/#724：即时审校的清单搬进审阅面板的第五个标签「审校」。
//
// 这份用例盯三件事：
//   1. 标签的计数与列表同源（忽略了的不算），没有审校时标签整个不出现；
//   2. 定位/采用只走 goto_review_range / apply_review_edit 两条带围栏的命令，
//      绝不碰 find_text_locations（那个会把书签写进 docx）；
//   3. 结果过期时保留清单但禁用动作——旧坐标落在改过的正文上会改错地方。
import test from 'node:test'
import assert from 'node:assert/strict'
import { makeReviewVm } from '../_lib/review-panel-vm.mjs'
import { makeInlineReviewVm } from '../_lib/inline-review-panel-vm.mjs'

const PARA = '甲方应于30日内付款，共计三笔。'
const READY = (patch = {}) => ({
  session: 's1', ai: true, hidden: false, writable: true, status: 'ready', deepStatus: 'idle',
  revision: 7, truncated: false, message: '',
  findings: [
    { id: 'f1', kind: 'PLACEHOLDER', title: '存在待定内容', message: '补齐', paragraphIndex: 0, start: 4, end: 7, quote: '30日', expectedParagraph: PARA, replacement: '15日' },
    { id: 'f2', kind: 'COUNT_MISMATCH', title: '数量不一致', message: '前后对不上', paragraphIndex: 0, start: 9, end: 12, quote: '三笔', expectedParagraph: PARA },
    { id: 'f3', kind: 'LOGIC_REVIEW', title: '待人工确认', message: '逻辑存疑' },
  ],
  ...patch,
})

function recorder(result = { success: true }) {
  const calls = []
  return { calls, executor: { executeCommand: async (action, params) => { calls.push({ action, params }); return result } } }
}

test('审阅面板：没有审校时「AI 审校」标签不出现，有审校时可由宿主直接切过去', () => {
  const plain = makeReviewVm(null, {})
  plain.openTab('chk')
  assert.equal(plain.tab, 'rev', '没有 inlineReview 时切不过去（标签也不渲染）')
  const withCheck = makeReviewVm(null, { inlineReview: READY() })
  withCheck.openTab('chk')
  assert.equal(withCheck.tab, 'chk')
  withCheck.openTab('rev')
  assert.equal(withCheck.tab, 'rev')
  const merge = makeReviewVm(null, { mode: 'merge', inlineReview: READY() })
  merge.openTab('chk')
  assert.equal(merge.tab, 'rev', '合并比对稿没有标签栏')
})

test('标签计数来自面板 @count，忽略掉的条目同时从列表与计数里消失', () => {
  const vm = makeInlineReviewVm(recorder().executor, { state: READY() })
  assert.equal(vm.all.length, 3)
  assert.deepEqual(vm.counts, { all: 3, supplement: 1, consistency: 1, format: 0, ai: 1 })
  assert.deepEqual(vm.emitted.at(-1), ['count', 3])
  vm.ignore(vm.all[0])
  assert.equal(vm.all.length, 2)
  assert.equal(vm.counts.supplement, 0)
  vm.bucket = 'ai'
  assert.deepEqual(vm.rows.map((f) => f.id), ['f3'])
  assert.equal(vm.counts.all, 2, '切分类不改计数')
})

test('定位与采用只发两条带围栏的命令，不碰 find_text_locations', async () => {
  const r = recorder()
  const vm = makeInlineReviewVm(r.executor, { state: READY() })
  await vm.locate(vm.all[1])
  assert.equal(r.calls[0].action, 'goto_review_range')
  assert.deepEqual(r.calls[0].params, {
    revision: 7, paragraphIndex: 0, start: 9, end: 12, expectedParagraph: PARA, quote: '三笔',
  })
  await vm.apply(vm.all[0])
  assert.equal(r.calls[1].action, 'apply_review_edit')
  assert.equal(r.calls[1].params.replacement, '15日')
  assert.equal(r.calls[1].params.revision, 7)
  assert.equal(r.calls[1].params.expectedParagraph, PARA)
  assert.ok(vm.emitted.some(([name]) => name === 'changed'), '采用之后要让宿主重拉审阅清单')
  assert.equal(r.calls.some((c) => c.action === 'find_text_locations'), false)
})

test('结果过期时清单还在、标注等待重新检查，但定位与采用一律拒绝', async () => {
  const r = recorder()
  const vm = makeInlineReviewVm(r.executor, { state: READY() })
  assert.equal(vm.fresh, true)
  // 正文一改，宿主就把 findings 清空（旧坐标不可信）——面板留着上一轮的清单，只是禁用
  vm.setState(READY({ status: 'stale', revision: null, findings: [] }))
  assert.equal(vm.fresh, false)
  assert.equal(vm.all.length, 3, '不清零：问题没有自己消失')
  assert.deepEqual(vm.emitted.at(-1), ['count', 3])
  await vm.locate(vm.all[1])
  await vm.apply(vm.all[0])
  assert.deepEqual(r.calls, [], '过期条目一条命令都不许发')
})

test('只读成员不给采用；重复引文被摘掉 replacement 后同样不给采用', () => {
  const ro = makeInlineReviewVm(recorder().executor, { state: READY({ writable: false }) })
  assert.equal(ro.applicable(ro.all[0]), false)
  assert.equal(ro.locatable(ro.all[0]), true, '只读成员仍然可以定位原文')
  const vm = makeInlineReviewVm(recorder().executor, { state: READY() })
  assert.equal(vm.applicable(vm.all[1]), false, 'f2 没有 replacement')
  assert.equal(vm.applicable(vm.all[2]), false, 'f3 没有坐标')
})

test('AI 开关/浮球/重查/立即 AI 审校都只上抛给宿主，面板自己不发请求', () => {
  const r = recorder()
  const vm = makeInlineReviewVm(r.executor, { state: READY() })
  vm.toggleAi(); vm.toggleBall(); vm.refresh(); vm.runDeep()
  const actions = vm.emitted.filter(([name]) => name === 'action').map(([, p]) => p)
  assert.deepEqual(actions, [
    { action: 'ai', value: false },
    { action: 'hidden', value: true },
    { action: 'refresh' },
    { action: 'deep' },
  ])
  assert.deepEqual(r.calls, [])
  // 正在跑的时候不许重复点（深入审校是要花钱的那一条）
  const busy = makeInlineReviewVm(r.executor, { state: READY({ deepStatus: 'checking', status: 'checking' }) })
  busy.runDeep(); busy.refresh()
  assert.equal(busy.emitted.filter(([name]) => name === 'action').length, 0)
})

test('深入审校未完成时说清原因，未知码不把码本身露给用户', () => {
  const vm = makeInlineReviewVm(recorder().executor, {
    state: READY({ message: 'REVIEW_DEEP_INCOMPLETE', deepReason: 'DEEP_TIMEOUT', deepRetried: true }),
  })
  assert.ok(vm.noteText.includes('editor.inlineReview.err.REVIEW_DEEP_INCOMPLETE'))
  assert.ok(vm.noteText.includes('editor.inlineReview.deepReason.DEEP_TIMEOUT'))
  assert.ok(vm.noteText.includes('editor.inlineReview.deepRetried'))
  vm.setState(READY({ message: 'REVIEW_DEEP_INCOMPLETE', deepReason: 'DEEP_WHATEVER' }))
  assert.equal(vm.noteText.includes('DEEP_WHATEVER'), false)
  vm.setState(READY({ message: 'SOMETHING_NEW' }))
  assert.equal(vm.noteText, 'editor.inlineReview.error', '认不出的错误码只说「检查暂不可用」')
})

test('换文档时忽略清单、分类与上一轮结果都不跟过去', () => {
  const vm = makeInlineReviewVm(recorder().executor, { state: READY() })
  vm.ignore(vm.all[0]); vm.bucket = 'ai'
  vm.setState(READY({ session: 's2', findings: [] }))
  assert.deepEqual(vm.ignored, [])
  assert.equal(vm.bucket, 'all')
  assert.equal(vm.all.length, 0)
})

test('AI 关掉之后规则检查那一半照常显示，只有「AI 审校」那一桶说清是为什么空', () => {
  const vm = makeInlineReviewVm(recorder().executor, { state: READY({ ai: false }) })
  assert.equal(vm.aiEnabled, false)
  assert.equal(vm.all.length, 3, '规则结果不因 AI 关掉消失')
  assert.deepEqual(vm.emitted.at(-1), ['count', 3])
  vm.bucket = 'ai'
  assert.deepEqual(vm.rows.map((f) => f.id), ['f3'], 'AI 桶里已有的结论不清掉')
})

test('自动 AI 被额度一类的原因停掉时，顶栏说清原因并指向手动重试', () => {
  const vm = makeInlineReviewVm(recorder().executor, { state: READY({ autoBlocked: 'DEEP_QUOTA' }) })
  assert.ok(vm.noteText.includes('editor.inlineReview.autoPaused'))
  assert.ok(vm.noteText.includes('editor.inlineReview.deepReason.DEEP_QUOTA'))
  vm.setState(READY({ autoBlocked: 'NOT_A_CODE' }))
  assert.equal(vm.noteText.includes('NOT_A_CODE'), false, '认不出的码不露给用户')
})
