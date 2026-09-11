// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 尽调模块 P3 稳定性余项 #1（dev-board#100）：ReviewPanel 批量接受/拒绝一组修订时，
// 旧实现对组内每个条目都单独调一次 resolve_revision——worker 侧 redlineAt(index) 每次
// 都从头整棵重新枚举 getRedlines()，K 个条目 = K 次 O(N) 重扫，O(K·N)。大文档里一个
// 连续删除合并出的大卡片（backspace 连删几十上百字）点一次「接受」就能卡住。
//
// 修法：resolveGroup 一次性把组内全部 index 打包，发一条新的 resolve_revisions
// 批量命令（worker 侧一次建索引再批处理，O(N+K)），不再对每个条目单独调用
// resolve_revision。本用例断言的是"调用形状"——一组 K>1 的条目只应发一次批量命令，
// 不应是 K 次单条命令；旧实现下断言必然失败（K 次调用），新实现下只有 1 次。
//
// 复用 review-panel-double-tap.test.mjs 的抽取套路：<script> 剥壳当 this 调 methods。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { makeReviewVm } from '../_lib/review-panel-vm.mjs'


function makeVm(executor) {
  // 组件的纯函数层（utils/reviewGrouping.js）与子组件桩由共享底座喂进去。
  return makeReviewVm(executor)
}

// 假引擎：记录每次 executeCommand 的调用（action + params），供断言"发了几次、发的什么"。
// resolve_revisions 的语义与真 worker 一致：indices 降序逐条处置，返回 {results:[{index,success}]}。
function makeEngine(ids, contiguousIds) {
  const live = ids.slice()
  const resolved = []
  const calls = []
  return {
    live, resolved, calls,
    async executeCommand(action, params) {
      calls.push({ action, params })
      await Promise.resolve()
      if (action === 'list_revisions') {
        return {
          success: true,
          revisions: live.map((id, i) => ({
            index: i, id, text: id, type: 'Insert', author: 'u', date: 'd',
            contiguous: contiguousIds.includes(id),
          })),
        }
      }
      if (action === 'list_comments') return { success: true, comments: [] }
      if (action === 'resolve_revisions') {
        const idxs = (params.indices || []).slice().sort((a, b) => b - a)
        const results = idxs.map((idx) => {
          if (idx < 0 || idx >= live.length) return { index: idx, success: false }
          resolved.push(live[idx])
          live.splice(idx, 1)
          return { index: idx, success: true }
        })
        return { success: true, action: params.action, resolved: results.filter((r) => r.success).length, results }
      }
      // 旧实现会打到这里（逐条 resolve_revision）——不实现它，逼旧代码在断言里露馅
      return { success: true }
    },
  }
}

test('批量接受一组 K>1 的修订：只发一次批量命令，不逐条调用', async () => {
  // A B C D E：B(1)+C(2) 首尾相接，合并成一张覆盖两条的卡片
  const engine = makeEngine(['A', 'B', 'C', 'D', 'E'], ['C'])
  const vm = makeVm(engine)
  await vm.reload()
  const g = vm.revisionGroups.find((x) => x.items.length > 1)
  assert.deepEqual(g.items.map((r) => r.index), [1, 2], '前置条件：卡片覆盖索引 1 与 2')

  engine.calls.length = 0 // 只看这次处置发生的调用，不算 reload 那两条
  await vm.resolveGroup(g, 'accept')

  const resolveCalls = engine.calls.filter((c) => c.action === 'resolve_revision' || c.action === 'resolve_revisions')
  assert.equal(resolveCalls.length, 1,
    `批量处置 2 个条目应只发 1 次批量命令，实际发了 ${resolveCalls.length} 次: ` +
    JSON.stringify(resolveCalls.map((c) => c.action)))
  assert.equal(resolveCalls[0].action, 'resolve_revisions', '应调用批量原语，不是逐条的 resolve_revision')
  assert.deepEqual(resolveCalls[0].params.indices.slice().sort((a, b) => a - b), [1, 2],
    '批量命令要一次带上组内全部 index')
  assert.deepEqual(engine.resolved.slice().sort(), ['B', 'C'], '两条都要被正确处置')
  assert.deepEqual(engine.live, ['A', 'D', 'E'])
})

test('大组（10 个条目）同样只发一次批量命令——不随组大小线性增长请求数', async () => {
  // 十个连续删除合并成一张卡片：真实场景是长按 Backspace 连删一长串字符
  const ids = Array.from({ length: 10 }, (_, i) => 'x' + i).concat(['tail'])
  const contiguous = ids.slice(1, 10) // x1..x9 都标记 contiguous，与紧邻的前一条相接成一组
  const engine = makeEngine(ids, contiguous)
  const vm = makeVm(engine)
  await vm.reload()
  const g = vm.revisionGroups.find((x) => x.items.length === 10)
  assert.ok(g, '前置条件：应有一张覆盖 10 条的卡片')

  engine.calls.length = 0
  await vm.resolveGroup(g, 'reject')

  const resolveCalls = engine.calls.filter((c) => c.action === 'resolve_revision' || c.action === 'resolve_revisions')
  assert.equal(resolveCalls.length, 1, `10 个条目的大组也应只发 1 次批量命令，实际 ${resolveCalls.length} 次`)
  assert.equal(engine.live.length, 1, '10 条应全部被处置，只剩 tail')
  assert.deepEqual(engine.live, ['tail'])
})

test('组内部分条目处置失败：error 提示与既有口径一致（total/failed），成功的那条仍触发 changed', async () => {
  const engine = makeEngine(['A', 'B', 'C'], ['C'])
  // 篡改 resolve_revisions：让索引 2 处置失败，1 成功
  const origExec = engine.executeCommand.bind(engine)
  engine.executeCommand = async (action, params) => {
    if (action === 'resolve_revisions') {
      const idxs = params.indices.slice().sort((a, b) => b - a)
      const results = idxs.map((idx) => ({ index: idx, success: idx !== 2 }))
      return { success: true, results }
    }
    return origExec(action, params)
  }
  const vm = makeVm(engine)
  await vm.reload()
  const g = vm.revisionGroups.find((x) => x.items.length > 1)
  // reload() 内部还会再跑两次 run()（list_revisions/list_comments），成功时各自把
  // this.error 复位成 ''——与本用例要断言的"处置完那一刻的 error"是两回事，隔离掉。
  vm.reload = async () => {}
  let changed = 0
  vm.$emit = (evt) => { if (evt === 'changed') changed++ }

  await vm.resolveGroup(g, 'accept')

  assert.equal(vm.error, 'editor.review.groupPartialFail{"total":2,"failed":1}')
  assert.equal(changed, 1, '组里只要有条目成功就该触发一次 changed')
})

test('卡片处置携带当时的原生修订快照与文档代号，后续刷新不替换旧卡片的来源', async () => {
  const engine = makeEngine(['A', 'B', 'C'], ['C'])
  const execute = engine.executeCommand.bind(engine)
  engine.executeCommand = async (action, params) => {
    const result = await execute(action, params)
    if (action === 'list_revisions') {
      result.revision = 42
      result.documentSeq = 7
      result.revisions.forEach(r => { r.identifier = 'native-' + r.id; r.timestamp = '2026-09-10 08:00:00.0' })
    }
    return result
  }
  const vm = makeVm(engine)
  await vm.reload()
  const group = vm.revisionGroups.find(g => g.items.length === 2)
  vm.reviewRevision = 100
  vm.reviewDocumentSeq = 8
  await vm.resolveGroup(group, 'reject')
  const { params } = engine.calls.find(c => c.action === 'resolve_revisions')
  assert.equal(params.revision, 42)
  assert.equal(params.documentSeq, 7, '旧卡片不能借用新文档的代号绕过替换文档保护')
  assert.deepEqual(params.expectedRevisions.map(r => r.identifier), ['native-B', 'native-C'])
})

test('批注卡片保留原文档快照，定位和已解决操作均携带稳定编号', async () => {
  const calls = []
  const comment = { id: 'native-comment', index: 3, author: '审阅人', content: '待核对', timestamp: '2026-09-10 08:00:00.0', resolved: false, anchorText: '对应条款' }
  const vm = makeVm({ async executeCommand(action, params) {
    calls.push({ action, params })
    if (action === 'list_revisions') return { success: true, revisions: [], revision: 42, documentSeq: 7 }
    if (action === 'list_comments') return { success: true, comments: [comment], revision: 42, documentSeq: 7 }
    return { success: true }
  } })
  await vm.reload()
  const card = vm.commentRows[0]
  vm.comments = []
  vm.reviewRevision = 100
  vm.reviewDocumentSeq = 8
  vm.gotoComment(card)
  await vm.toggleResolved(card)
  const locate = calls.find(c => c.action === 'goto_comment').params
  assert.equal(locate.id, comment.id)
  assert.equal(locate.documentSeq, 7)
  const resolve = calls.find(c => c.action === 'set_comment_resolved').params
  assert.equal(resolve.documentSeq, 7)
  assert.equal(resolve.revision, 42)
  assert.equal(resolve.expectedComment.content, comment.content)
  assert.equal(resolve.expectedComment.id, comment.id)
  assert.equal(resolve.resolved, true)
})

test('概览与页边清单同为500条上限，达到上限后明确提示未加载更多内容', async () => {
  const calls = []
  const vm = makeVm({ async executeCommand(action, params) {
    calls.push({ action, params })
    return action === 'list_revisions'
      ? { success: true, revisions: Array.from({ length: 500 }, (_, index) => ({ index })) }
      : { success: true, comments: [{ index: 0 }] }
  } })
  await vm.reload()
  assert.deepEqual(calls.map(c => c.params.limit), [500, 500])
  assert.equal(vm.listLimitReached, true)
  vm.tab = 'cmt'
  assert.equal(vm.listLimitReached, false)
  vm.comments = Array.from({ length: 500 }, (_, index) => ({ index }))
  assert.equal(vm.listLimitReached, true)
  vm.tab = 'evd'
  assert.equal(vm.listLimitReached, false)
})
