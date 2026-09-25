// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机 BUG-18（C5-03）：审阅面板筛到「AI 12」后点「全部接受」，把「我 6」的
// 修订也一起接受了，而且没有任何确认。
//
// 病灶：resolveAll 无视 authorFilter，直接发 resolve_all_revisions（引擎的
// .uno:AcceptAllTrackedChanges，整份文档一把梭）。
// 修法：筛选不是「全部」时，「全部接受/拒绝」只处置当前筛选命中的修订，先弹确认框
// 说清楚会处置几条、另外几条不受影响；确认后按降序索引一次交给 resolve_revisions
// 批量原语（同 resolveGroup / resolveMergeSide）。筛选是「全部」时行为不变。
//
// 复核打回的第二轮病灶：筛选路径只带裸 indices、且在确认框弹出**之前**就采集，
// 确认框开着期间 AI 还在流式插修订，枚举序整体错位，「只接受 AI」接受了「我」的
// 修订；也没有像 resolveGroup 那样把关联的理由批注一并标为已解决。
// 修法：确认之后先 reload，只处置确认时列出的修订（按 identifier 认），载荷带
// revision / documentSeq / expectedRevisions 快照围栏；命中后调 resolveReasons。
import { test, beforeEach, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { makeReviewVm } from '../_lib/review-panel-vm.mjs'

const ME = '韩律师'
// 0,2,4 是 AI 的；1,3 是我的；5 是别人的。每条都在不同段落，不会被合并成一张卡。
const AUTHORS = ['AI WorkDeck', ME, 'AI WorkDeck', ME, 'AI WorkDeck', '律师乙']

function rev(r, i) {
  return {
    index: i, identifier: r.id, id: r.id, text: r.id, type: 'Insert', author: r.author,
    timestamp: '2026-09-20T10:00:00', date: '2026-09-20', paraKey: r.paraKey, start: 0, end: 2, contiguous: false,
  }
}

// 假引擎：resolve_revisions 带 expectedRevisions 时照 worker 的 matchRevisionSnapshot
// 按 identifier 重映射索引、内容对不上整批拒掉；不带围栏时按裸索引盲打（旧病灶的样子）。
function makeEngine(comments = []) {
  const live = AUTHORS.map((author, i) => ({ id: 'r' + i, author, paraKey: i * 10 }))
  const calls = []
  return {
    live, calls, comments,
    async executeCommand(action, params) {
      calls.push({ action, params })
      await Promise.resolve()
      if (action === 'list_revisions') return { success: true, revision: 7, documentSeq: 3, revisions: live.map(rev) }
      if (action === 'list_comments') return { success: true, revision: 7, documentSeq: 3, comments: comments.map((c) => ({ ...c })) }
      if (action === 'resolve_revisions') {
        let idxs = params.indices || []
        if (params.expectedRevisions) {
          const cur = live.map(rev)
          const matched = params.expectedRevisions.map((e) => cur.find((r) => r.identifier === e.identifier))
          if (!matched.every((r, i) => r && ['type', 'text', 'author', 'timestamp'].every((k) => r[k] === params.expectedRevisions[i][k]))) {
            return { success: false, message: '修订已变化，请刷新后重试' }
          }
          idxs = matched.map((r) => r.index)
        }
        const results = idxs.slice().sort((a, b) => b - a).map((idx) => { live.splice(idx, 1); return { index: idx, success: true } })
        return { success: true, resolved: results.length, results }
      }
      if (action === 'set_comment_resolved') {
        const c = comments.find((x) => x.id === params.id)
        if (c) c.resolved = true
        return { success: true }
      }
      if (action === 'resolve_all_revisions') { const n = live.length; live.length = 0; return { success: true, resolved: n } }
      return { success: true }
    },
  }
}

let modals
let answer
let whileOpen // 确认框开着期间要发生的事（模拟 AI 还在流式改文档）
beforeEach(() => {
  modals = []
  answer = true
  whileOpen = null
  globalThis.uni = {
    showModal(opts) {
      modals.push(opts)
      Promise.resolve().then(() => {
        if (whileOpen) whileOpen()
        opts.success && opts.success({ confirm: answer, cancel: !answer })
      })
    },
  }
})
afterEach(() => { delete globalThis.uni })

test('筛到「AI」后全部接受：只处置 AI 的修订，「我」和别人的原样留着', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine, { selfAuthor: ME })
  await vm.reload()
  vm.authorFilter = 'ai'
  engine.calls.length = 0

  await vm.resolveAll('accept')

  assert.equal(engine.calls.filter((c) => c.action === 'resolve_all_revisions').length, 0,
    '筛选下的「全部接受」不许再发整份文档的 resolve_all_revisions')
  const batch = engine.calls.filter((c) => c.action === 'resolve_revisions')
  assert.equal(batch.length, 1, '筛选命中的修订应一次交给批量原语')
  assert.deepEqual(batch[0].params.indices, [4, 2, 0], '只带 AI 的索引，且按降序')
  assert.equal(batch[0].params.action, 'accept')
  assert.deepEqual(engine.live.map((r) => r.author), [ME, ME, '律师乙'])
})

test('跨筛选的批量处置先弹确认框，说清楚影响几条、几条不受影响', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine, { selfAuthor: ME })
  await vm.reload()
  vm.authorFilter = 'me'
  await vm.resolveAll('reject')
  assert.equal(modals.length, 1, '没有确认框')
  const text = String(modals[0].title) + String(modals[0].content)
  assert.match(text, /"count":2/, '确认框要说出将处置的条数')
  assert.match(text, /"rest":4/, '确认框要说出不受影响的条数')
})

test('确认框点取消：一条都不处置', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine, { selfAuthor: ME })
  await vm.reload()
  vm.authorFilter = 'ai'
  answer = false
  engine.calls.length = 0
  await vm.resolveAll('accept')
  assert.equal(engine.calls.filter((c) => /^resolve_/.test(c.action)).length, 0)
  assert.equal(engine.live.length, 6)
})

test('筛选是「全部」时行为不变：直接整份文档处置，不弹框', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine, { selfAuthor: ME })
  await vm.reload()
  await vm.resolveAll('accept')
  assert.equal(modals.length, 0)
  assert.equal(engine.calls.filter((c) => c.action === 'resolve_all_revisions').length, 1)
})

test('筛选路径的 resolve_revisions 带快照围栏：revision / documentSeq / expectedRevisions', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine, { selfAuthor: ME })
  await vm.reload()
  vm.authorFilter = 'ai'
  await vm.resolveAll('accept')
  const [batch] = engine.calls.filter((c) => c.action === 'resolve_revisions')
  assert.equal(batch.params.revision, 7)
  assert.equal(batch.params.documentSeq, 3)
  assert.ok(Array.isArray(batch.params.expectedRevisions), '缺 expectedRevisions 围栏')
  assert.deepEqual(batch.params.expectedRevisions.map((r) => r.identifier).sort(), ['r0', 'r2', 'r4'])
  for (const r of batch.params.expectedRevisions) {
    assert.deepEqual(Object.keys(r).sort(), ['author', 'identifier', 'index', 'text', 'timestamp', 'type'],
      '围栏快照只拷 REVISION_FENCE_FIELDS（普通对象，过得了结构化克隆）')
  }
})

test('确认之后才采集：确认框开着期间有新修订插到前面，「我」的修订一条都不许被接受', async () => {
  const engine = makeEngine()
  const vm = makeReviewVm(engine, { selfAuthor: ME })
  await vm.reload()
  vm.authorFilter = 'ai'
  // AI 流式改稿：确认框开着时文档开头又插进三条修订，原来的枚举序整体后移 3 位。
  // 框前采的 [4,2,0] 这时指向 r1（我）和两条新修订。
  whileOpen = () => {
    engine.live.unshift({ id: 'n0', author: 'AI WorkDeck', paraKey: 1 }, { id: 'n1', author: ME, paraKey: 2 }, { id: 'n2', author: ME, paraKey: 3 })
  }
  engine.calls.length = 0
  await vm.resolveAll('accept')

  const listIdx = engine.calls.findIndex((c) => c.action === 'list_revisions')
  const batchIdx = engine.calls.findIndex((c) => c.action === 'resolve_revisions')
  assert.ok(listIdx >= 0 && listIdx < batchIdx, '确认后、下发前要先重读修订清单')
  assert.deepEqual(engine.live.map((r) => r.id), ['n0', 'n1', 'n2', 'r1', 'r3', 'r5'],
    '只处置确认时列出的 AI 修订 r0/r2/r4；我的修订和确认后才出现的新修订原样留着')
})

test('有理由批注时一并标为已解决；挂在没处置的修订上的理由不动', async () => {
  const engine = makeEngine([
    { id: 'c-ai', index: 0, author: 'AI WorkDeck', content: '为什么改', paraKey: 0, start: 0, end: 2, resolved: false },
    { id: 'c-me', index: 1, author: ME, content: '我的理由', paraKey: 10, start: 0, end: 2, resolved: false },
  ])
  const vm = makeReviewVm(engine, { selfAuthor: ME })
  await vm.reload()
  vm.authorFilter = 'ai'
  await vm.resolveAll('accept')
  const resolvedIds = engine.calls.filter((c) => c.action === 'set_comment_resolved').map((c) => c.params.id)
  assert.deepEqual(resolvedIds, ['c-ai'])
  const call = engine.calls.find((c) => c.action === 'set_comment_resolved')
  assert.equal(call.params.resolved, true)
  assert.equal(call.params.documentSeq, 3)
})
