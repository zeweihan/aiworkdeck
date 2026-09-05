// dev-board#460：Agent 写完内容后工具条上的「修订 / 批注」计数不刷新，切一次标签才对。
//
// 面板的清单只有一条来路——ReviewPanel.reload() 拿 list_revisions / list_comments。
// 这一路上有两个把刷新吃掉的缺口：
//   ① 读失败即清零：run() 在超时 / success:false / 抛错时返回 null，reload 却无条件
//      写 `(rv && rv.revisions) || []`，一次读失败就把 tab 打成「修订 0 / 批注 0」，
//      而文档里明明躺着 AI 刚做的几十条修订。律师据此以为 AI 什么都没改。
//   ② 无重入闸：AI 改稿期间每来一次 modified 就无条件再 reload 一轮，两条读命令
//      堆到单事件循环的 office 线程上，排在写命令后面互相拖慢——越忙越读不回来，
//      越读不回来越容易撞①。
//
// 本用例不做源码文本断言：用共享底座把 <script> 剥出来当普通对象跑，真调 reload()。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { makeReviewVm } from '../_lib/review-panel-vm.mjs'

const REVISIONS = [0, 1, 2, 3].map((i) => ({
  index: i, id: 'r' + i, text: '第' + i + '处', type: 'Insert',
  author: 'AI WorkDeck', date: '2026-09-05T10:0' + i + ':00', paraKey: i, start: 0, end: 2,
  contiguous: false,
}))
const COMMENTS = [0, 1, 2].map((i) => ({
  index: i, id: 'c' + i, text: '【修訂理由】第' + i + '条', author: 'AI WorkDeck',
  date: '2026-09-05T10:0' + i + ':00', paraKey: 90 + i, start: 0, end: 1, resolved: false,
}))

// 正常引擎：读得回 4 条修订 / 3 条批注
function okExecutor(calls) {
  return {
    async executeCommand(action) {
      if (calls) calls.push(action)
      await Promise.resolve()
      if (action === 'list_revisions') return { success: true, revisions: REVISIONS }
      if (action === 'list_comments') return { success: true, comments: COMMENTS }
      return { success: true }
    },
  }
}

test('读失败不许把计数打成 0：保留上一次清单，只把错误说出来', async () => {
  const vm = makeReviewVm(okExecutor())
  await vm.reload()
  assert.equal(vm.revisions.length, 4)
  assert.equal(vm.comments.length, 3)
  assert.equal(vm.allGroups.length, 4)

  // 引擎侧超时（relay 真实形态是 resolve 一个 {success:false}，不是抛）
  vm.executor = {
    async executeCommand(action) {
      await Promise.resolve()
      return { success: false, message: 'LibreOffice relay timeout: ' + action }
    },
  }
  await vm.reload()
  assert.equal(vm.revisions.length, 4, '读失败后清单被清零 → tab 显示「修订 0」')
  assert.equal(vm.comments.length, 3, '读失败后批注清单被清零')
  assert.equal(vm.allGroups.length, 4, 'tab 上的修订计数被打成 0')
  assert.ok(vm.error, '读失败必须如实置错——保留旧清单不等于假装成功')
})

test('抛错（传输层断了）同样不清零', async () => {
  const vm = makeReviewVm(okExecutor())
  await vm.reload()
  assert.equal(vm.revisions.length, 4)
  vm.executor = {
    async executeCommand(action) { throw new Error('LibreOffice command timeout: ' + action) },
  }
  await vm.reload()
  assert.equal(vm.revisions.length, 4)
  assert.equal(vm.comments.length, 3)
})

test('重入不并发：在飞时只记一笔 defer，收尾补跑一次，最后一次触发一定读到', async () => {
  const calls = []
  let gen = 0
  const slow = {
    async executeCommand(action) {
      calls.push(action)
      const myGen = gen
      await new Promise((r) => setTimeout(r, 10))
      if (action === 'list_revisions') {
        return { success: true, revisions: REVISIONS.slice(0, myGen + 1) }
      }
      if (action === 'list_comments') return { success: true, comments: [] }
      return { success: true }
    },
  }
  const vm = makeReviewVm(slow)
  const first = vm.reload()      // 第一轮在飞
  gen = 1
  vm.reload()                    // 应被 defer
  gen = 2
  vm.reload()                    // 同一笔 defer，不再多排一轮
  await first
  // 首轮 + defer 收尾轮 = 2 轮，每轮两条读命令
  assert.equal(calls.filter((a) => a === 'list_revisions').length, 2,
    '三次触发发了三轮读命令（无重入闸）——AI 改稿期间会把 office 线程读死')
  assert.equal(vm.revisions.length, 3, '收尾轮必须跑，且拿到的是最后一次触发时的状态')
})
