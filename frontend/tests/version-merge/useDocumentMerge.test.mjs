// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 不重叠自动合并的编排（spec §5.2）。
 * 跑法：cd frontend && node --test tests/version-merge/*.test.mjs
 *
 * 这条链最贵的三个错误：
 *   ① 有同段冲突却按「全都合好了」收尾——律师根本没被问过，对方那处改动被静默丢掉；
 *   ② /status 每 120 秒一轮、面板刷新也会再来一次，不幂等就会同一份文件反复重放、
 *      甚至重复收尾；
 *   ③ 引擎报「对齐核对失败」还照样把导出的字节写回去——那份字节的段落是错位的。
 * 三条各有一个用例守着。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { useDocumentMerge } from '../../src/composables/useDocumentMerge.js'

const t = (key, params) => (params === undefined ? key : `${key}(${JSON.stringify(params)})`)

function makeHarness(over = {}) {
  const calls = {
    build: [], inputs: [], resolveFile: [], resolveStructured: [],
    adopt: [], cloud: [], sessionEnd: [], toasts: [], reloads: [], overview: 0,
    acquired: 0, released: 0, engineRuns: [],
  }
  const handle = {
    run: async (action, payload) => {
      calls.engineRuns.push(action)
      if (action === 'export_document') return { success: true, bytes: new Uint8Array([1, 2, 3]), name: payload && payload.name }
      return { success: true }
    },
  }
  const deps = {
    projectId: 7,
    isDesktop: true,
    t,
    toast: (msg) => calls.toasts.push(msg),
    getExecutorForHiddenInstance: async () => { calls.acquired += 1; return handle },
    releaseHiddenInstance: () => { calls.released += 1 },
    fetchMergeInputs: async (projectId, path, refs) => {
      calls.inputs.push({ projectId, path, refs })
      return { baseBytes: new Uint8Array([0]), mainBytes: new Uint8Array([1]), otherBytes: new Uint8Array([2]), analysis: { plan: {}, baseUnits: [] } }
    },
    buildMergeDraft: async (run, inputs, opts) => {
      calls.build.push(opts)
      return { success: true, mainCount: 3, otherCount: 4, conflicts: [], formatOnly: [] }
    },
    api: {
      postMergeResolveFile: async (projectId, body) => { calls.resolveFile.push(body); return { code: 0, data: { path: body.path, state: 'MERGED' } } },
      postMergeResolveStructured: async (projectId, body) => { calls.resolveStructured.push(body); return { code: 0, data: { path: body.path, state: 'MERGED' } } },
      resolveAdopt: async (projectId, draftId, resolutions) => { calls.adopt.push({ draftId, resolutions }); return { data: { affectedFileIds: [11] } } },
      resolveCloudMerge: async (projectId, resolutions) => { calls.cloud.push({ resolutions }); return { data: {} } },
      resolveSessionEnd: async (projectId, sessionId, resolutions) => { calls.sessionEnd.push({ sessionId, resolutions }); return { data: {} } },
    },
    reloadFiles: (ids) => calls.reloads.push(ids),
    openOverview: () => { calls.overview += 1 },
    ...over,
  }
  return { calls, deps, handle }
}

const docxRow = (over = {}) => ({
  path: '合同.docx', kind: 'DOCX', decision: 'AUTO', reason: 'CLEAN',
  mainChanges: 5, otherChanges: 4, overlapCount: 0, state: 'PENDING', ...over,
})

const conflictOf = (rows, over = {}) => ({
  draftId: 3,
  conflictingPaths: rows.map((r) => r.path),
  mergeBase: 'base1', mainlineTip: 'main1', draftTip: 'other1',
  sides: { main: { authorName: '张律师' }, other: { authorName: '李律师' } },
  documentMerges: rows,
  ...over,
})

test('全是 AUTO 且都成功 → 自动收尾，三份文件都报 MERGED，只收一次尾', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  const conflict = conflictOf([docxRow(), docxRow({ path: '附件.docx' })])
  await merge.onConflictStatus(conflict, 'adopt')

  assert.equal(calls.build.length, 2)
  assert.equal(calls.resolveFile.length, 2)
  assert.equal(calls.resolveFile[0].mode, 'auto')
  assert.equal(calls.adopt.length, 1)
  assert.deepEqual(calls.adopt[0].resolutions, { '合同.docx': 'MERGED', '附件.docx': 'MERGED' })
  assert.equal(calls.adopt[0].draftId, 3)
  assert.equal(calls.toasts.length, 1)
  assert.deepEqual(calls.reloads, [[11]])
  // 借出去的隐藏实例必须还回来，否则每次冲突都多留一个常驻引擎
  assert.equal(calls.acquired, calls.released)
})

test('幂等：/status 再来一轮同一个冲突，不重放、不重复收尾', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  const rows = [docxRow()]
  await merge.onConflictStatus(conflictOf(rows), 'adopt')
  await merge.onConflictStatus(conflictOf([docxRow()]), 'adopt')

  assert.equal(calls.build.length, 1)
  assert.equal(calls.resolveFile.length, 1)
  assert.equal(calls.adopt.length, 1)
})

test('只要有一份是 MANUAL 就不收尾——律师还没被问过', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(
    conflictOf([docxRow(), docxRow({ path: '章程.docx', decision: 'MANUAL', overlapCount: 1 })]),
    'adopt'
  )

  assert.equal(calls.build.length, 1)          // 只对 AUTO 那份跑
  assert.equal(calls.adopt.length, 0)          // 不收尾
  assert.equal(calls.overview, 1)              // 把人带到裁决总览
})

test('整份文件（pdf）在列 → 同样不收尾', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(
    conflictOf([docxRow(), { path: '证据.pdf', kind: 'WHOLE', decision: 'WHOLE', reason: 'BINARY', state: 'PENDING' }]),
    'adopt'
  )
  assert.equal(calls.adopt.length, 0)
  assert.equal(calls.overview, 1)
})

test('另一侧只改了格式 → 这份降成 MANUAL，字节一个都不写回去', async () => {
  const { calls, deps } = makeHarness({
    buildMergeDraft: async () => ({ success: true, mainCount: 3, otherCount: 2, conflicts: [], formatOnly: [{ paraKey: 'p7', preview: '第七条' }] }),
  })
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(conflictOf([docxRow()]), 'adopt')

  assert.equal(calls.resolveFile.length, 0)
  assert.equal(calls.adopt.length, 0)
  assert.equal(merge.state.rows[0].decision, 'MANUAL')
  assert.equal(merge.state.rows[0].formatOnlyCount, 1)
  assert.equal(merge.state.rows[0].state, 'PENDING')
})

test('对齐核对失败（stage:align）→ 退回整份三选一，绝不把错位的字节写回去', async () => {
  const { calls, deps } = makeHarness({
    buildMergeDraft: async () => ({ success: false, stage: 'align', message: 'norm mismatch at p12' }),
  })
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(conflictOf([docxRow()]), 'adopt')

  assert.equal(calls.resolveFile.length, 0)
  assert.equal(calls.adopt.length, 0)
  assert.equal(merge.state.rows[0].failed, true)
  assert.equal(merge.state.rows[0].failReason, 'align')
  assert.equal(calls.released, calls.acquired)
})

test('非桌面端：引擎压根不去取，docx 那份留给整份三选一', async () => {
  const { calls, deps } = makeHarness({ isDesktop: false })
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(conflictOf([docxRow()]), 'adopt')

  assert.equal(calls.acquired, 0)
  assert.equal(calls.build.length, 0)
  assert.equal(calls.adopt.length, 0)
})

test('xlsx/pptx 的自动合并在后端拼，不碰引擎', async () => {
  const { calls, deps } = makeHarness({ isDesktop: false })
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(
    conflictOf([docxRow({ path: '报价.xlsx', kind: 'XLSX' }), docxRow({ path: '汇报.pptx', kind: 'PPTX' })]),
    'cloud'
  )

  assert.equal(calls.acquired, 0)
  assert.equal(calls.resolveStructured.length, 2)
  assert.deepEqual(calls.resolveStructured[0].decisions, [])
  assert.equal(calls.cloud.length, 1)
  assert.deepEqual(calls.cloud[0].resolutions, { '报价.xlsx': 'MERGED', '汇报.pptx': 'MERGED' })
})

test('三语境各打各的收尾端点：结束工作那条要带工作段 id', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(
    conflictOf([docxRow()], { sessionId: 42, sessionTip: 'sess1', draftTip: null }),
    'session-end'
  )
  assert.equal(calls.sessionEnd.length, 1)
  assert.equal(calls.sessionEnd[0].sessionId, 42)
  assert.deepEqual(calls.sessionEnd[0].resolutions, { '合同.docx': 'MERGED' })
  assert.equal(calls.adopt.length, 0)
  assert.equal(calls.cloud.length, 0)
})

test('后端说这份已经合好（崩溃恢复）→ 不重跑引擎，直接按 MERGED 收尾', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(conflictOf([docxRow({ state: 'MERGED' })]), 'adopt')

  assert.equal(calls.build.length, 0)
  assert.equal(calls.adopt.length, 1)
})

test('没有冲突对象 → 清空、什么都不做', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(null, 'adopt')
  assert.deepEqual(merge.state.rows, [])
  assert.equal(merge.state.running, false)
  assert.equal(calls.adopt.length, 0)
  assert.equal(calls.overview, 0)
})

test('引擎借不到（备胎起不来）→ 记成 engine 失败，不收尾', async () => {
  const { calls, deps } = makeHarness({ getExecutorForHiddenInstance: async () => null })
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(conflictOf([docxRow()]), 'adopt')

  assert.equal(calls.build.length, 0)
  assert.equal(calls.adopt.length, 0)
  assert.equal(merge.state.rows[0].failed, true)
  assert.equal(merge.state.rows[0].failReason, 'engine')
})

test('导出前必须先把两边的修订全部接受，顺序不许颠倒', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  await merge.onConflictStatus(conflictOf([docxRow()]), 'adopt')

  const accept = calls.engineRuns.indexOf('resolve_all_revisions')
  const exportAt = calls.engineRuns.indexOf('export_document')
  assert.ok(accept >= 0 && exportAt >= 0, '两条命令都要发：' + JSON.stringify(calls.engineRuns))
  assert.ok(accept < exportAt, '接受修订必须在导出之前')
  assert.equal(calls.resolveFile[0].bytes.length, 3)
})

test('同一轮冲突被并发触发两次，仍然只合一遍、照样收尾（协作抽屉 conflict+changed 各调一次）', async () => {
  // app-e2e J14 实测抓到的真实故障：撞冲突时 CollabDialog.onUpdate 先 emit('conflict')
  // 再 emit('changed')，页面因此背靠背调两次 checkAdoptConflict。两次并发跑进来时，
  // 后一次把 state.rows 换成新数组，前一次那轮循环把「这份合好了」写在了被换掉的旧对象上，
  // finalize 读新数组只看到 PENDING——文件都已合好落盘，收尾却永远不发生，
  // 仓库停在 MERGING，律师面对一个说着「已合并」却关不掉的裁决窗。
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  const rows = [
    { path: '表.xlsx', kind: 'XLSX', decision: 'AUTO', reason: 'CLEAN', mainChanges: 1, otherChanges: 1, overlapCount: 0, state: 'PENDING' },
    { path: '演示.pptx', kind: 'PPTX', decision: 'AUTO', reason: 'CLEAN', mainChanges: 1, otherChanges: 1, overlapCount: 0, state: 'PENDING' },
  ]
  // 两次拿到的是同一份 /status 快照（第二次是在第一次还没合完时就进来的，所以仍是 PENDING）
  const a = merge.onConflictStatus(conflictOf(rows.map((r) => ({ ...r })), { cloudTip: 'cloud1' }), 'cloud')
  const b = merge.onConflictStatus(conflictOf(rows.map((r) => ({ ...r })), { cloudTip: 'cloud1' }), 'cloud')
  await Promise.all([a, b])
  assert.equal(calls.resolveStructured.length, 2, '两份文件各合一遍，不许因为并发合两遍')
  assert.equal(calls.cloud.length, 1, '收尾只发一次')
  assert.deepEqual(calls.cloud[0].resolutions, { '表.xlsx': 'MERGED', '演示.pptx': 'MERGED' })
})

test('更老的一份 /status 快照不许把本地已知的「已合好」抹回 PENDING', async () => {
  const { calls, deps } = makeHarness()
  const merge = useDocumentMerge(deps)
  const row = { path: '表.xlsx', kind: 'XLSX', decision: 'AUTO', reason: 'CLEAN', mainChanges: 1, otherChanges: 1, overlapCount: 0, state: 'PENDING' }
  await merge.onConflictStatus(conflictOf([{ ...row }], { cloudTip: 'cloud1' }), 'cloud')
  assert.equal(merge.state.rows[0].state, 'MERGED')
  // 后端那一轮 /status 还没看到落盘结果（缓存/时序），仍报 PENDING
  await merge.onConflictStatus(conflictOf([{ ...row }], { cloudTip: 'cloud1' }), 'cloud')
  assert.equal(merge.state.rows[0].state, 'MERGED', '行态倒退回 PENDING 会让界面转一个永远不来的圈')
  assert.equal(calls.resolveStructured.length, 1, '幂等：同一份文件不重合')
  assert.equal(calls.cloud.length, 1, '收尾也只发一次')
})
