// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 审阅概览（ReviewPanel）发给引擎的参数必须能过结构化克隆。
//
// 命令从宿主到编辑器要走 Electron webview.send（IPC）或 iframe postMessage，两条路
// 都做结构化克隆。旧实现把 g.items（取自响应式 this.revisions，每一项都是 Vue
// Proxy）原样塞进 expectedRevisions：iframe 下 postMessage 同步抛 DataCloneError，
// webview 下 send() 的 Promise 被拒、无人接，relay 干等满 resolve_revisions 的 120s
// 预算，其间面板按钮全被 resolving 锁死——概览里的「接受/拒绝」永远到不了引擎。
//
// 共享底座（tests/_lib/review-panel-vm.mjs）的假 this 是普通对象，照原样跑测不出
// 这个病：这里把 revisions / comments 两份清单接到真 reactive 上，还原宿主里
// 「this.revisions 读出来是 Proxy」的形态。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { reactive } from 'vue'
import { makeReviewVm } from '../_lib/review-panel-vm.mjs'

// worker 围栏实际比对的字段（office_thread.js 的 matchRevisionSnapshot /
// matchCommentSnapshot）。index 是 resolve_revisions 核对 indices 用的。
const REVISION_FENCE = ['index', 'identifier', 'type', 'text', 'author', 'timestamp']
const COMMENT_FENCE = ['id', 'author', 'content', 'timestamp', 'anchorText', 'resolved']

function makeReactiveVm(executor) {
  const vm = makeReviewVm(executor)
  const state = reactive({ revisions: [], comments: [] })
  for (const k of ['revisions', 'comments']) {
    Object.defineProperty(vm, k, {
      get: () => state[k], set: (v) => { state[k] = v }, configurable: true, enumerable: true,
    })
  }
  return vm
}

const REVISIONS = [
  { index: 0, identifier: 'rv-1', type: 'Insert', author: 'AI WorkDeck', text: '甲', date: '2026-09-10 08:00',
    timestamp: '2026-09-10 08:00:01.0', description: '', paragraph: '甲乙丙', paraKey: 1, start: 0, end: 1, contiguous: false },
  { index: 1, identifier: 'rv-2', type: 'Insert', author: 'AI WorkDeck', text: '乙', date: '2026-09-10 08:00',
    timestamp: '2026-09-10 08:00:02.0', description: '', paragraph: '甲乙丙', paraKey: 1, start: 1, end: 2, contiguous: true },
]
const COMMENTS = [
  { index: 0, id: 'cm-1', author: 'AI WorkDeck', content: '修订理由', date: '2026-09-10 08:00',
    timestamp: '2026-09-10 08:00:03.0', resolved: false, anchorText: '甲乙', paragraph: '甲乙丙', paraKey: 1, start: 0, end: 2 },
]

// 假引擎：每条命令的参数都先过一次 structuredClone（等价于 webview.send / postMessage
// 那一跳），克隆失败就如实回失败，不让它被 run() 的 catch 静默吞掉。
function makeEngine() {
  const calls = []
  return {
    calls,
    async executeCommand(action, params) {
      let cloneError = null
      try { structuredClone(params) } catch (e) { cloneError = e }
      calls.push({ action, params, cloneError })
      if (cloneError) return { success: false, message: cloneError.message }
      if (action === 'list_revisions') return { success: true, revisions: REVISIONS.map((r) => ({ ...r })), revision: 42, documentSeq: 7 }
      if (action === 'list_comments') return { success: true, comments: COMMENTS.map((c) => ({ ...c })), revision: 42, documentSeq: 7 }
      if (action === 'resolve_revisions') {
        return { success: true, results: params.indices.map((index) => ({ index, success: true })) }
      }
      return { success: true }
    },
  }
}

test('前置条件：卡片条目取自响应式清单，本身过不了结构化克隆', async () => {
  const vm = makeReactiveVm(makeEngine())
  await vm.reload()
  const g = vm.revisionGroups[0]
  assert.equal(g.items.length, 2, '两条首尾相接的插入合成一张卡片')
  assert.throws(() => structuredClone(g.items[0]), { name: 'DataCloneError' },
    '底座若没还原出 Proxy，下面的断言就是空转')
})

test('整组接受：发给引擎的参数能过结构化克隆，且只带围栏比对的字段', async () => {
  const engine = makeEngine()
  const vm = makeReactiveVm(engine)
  await vm.reload()
  const g = vm.revisionGroups[0]
  engine.calls.length = 0
  await vm.resolveGroup(g, 'accept')

  const bad = engine.calls.filter((c) => c.cloneError)
  assert.deepEqual(bad.map((c) => c.action), [], '任何一条命令的参数都不许带 Proxy')
  const call = engine.calls.find((c) => c.action === 'resolve_revisions')
  assert.ok(call, '处置命令要真的发出去')
  const { params } = call
  assert.deepEqual(params.indices, [1, 0])
  assert.equal(params.revision, 42)
  assert.equal(params.documentSeq, 7)
  assert.deepEqual(params.expectedRevisions, REVISIONS.map((r) => Object.fromEntries(REVISION_FENCE.map((k) => [k, r[k]]))))
  for (const r of params.expectedRevisions) assert.deepEqual(Object.keys(r), REVISION_FENCE)
  assert.equal(vm.error, '', '处置成功不该留红条')
  assert.equal(vm.resolving, false, '处置结束后按钮要解锁')
  // 理由批注随处置标记为已解决——这条也得真的发到引擎
  assert.ok(engine.calls.some((c) => c.action === 'set_comment_resolved' && c.params.id === 'cm-1'))
})

test('批注已解决：expectedComment 能过结构化克隆，且只带围栏比对的字段', async () => {
  const engine = makeEngine()
  const vm = makeReactiveVm(engine)
  await vm.reload()
  const card = vm.commentRows[0]
  engine.calls.length = 0
  vm.gotoComment(card)
  vm.goto(vm.revisionGroups[0])
  await vm.toggleResolved(card)

  assert.deepEqual(engine.calls.filter((c) => c.cloneError).map((c) => c.action), [])
  const { params } = engine.calls.find((c) => c.action === 'set_comment_resolved')
  assert.equal(params.id, 'cm-1')
  assert.equal(params.resolved, true)
  assert.equal(params.documentSeq, 7)
  assert.equal(params.revision, 42)
  assert.deepEqual(params.expectedComment, Object.fromEntries(COMMENT_FENCE.map((k) => [k, COMMENTS[0][k]])))
  assert.deepEqual(Object.keys(params.expectedComment), COMMENT_FENCE)
})
