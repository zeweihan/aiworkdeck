// 审计（dev-board#74）：ReviewPanel 的修订卡片「接受/拒绝」没有重入闸，连点两次会
// 并发跑两轮 resolveGroup。两轮手里是同一份索引，第一轮处置掉一条后引擎里比它大的
// 索引全部前移，第二轮那份索引已经过期，会打到文档里别的修订上——用户没看过的改动
// 被悄悄接受/拒绝，引擎还返回 success:true。
//
// 用例不做源码文本断言，直接把组件的 <script> 抽出来跑：export default 换成 return，
// 再用普通对象当 this 调 methods（同 personal-settings-reentrancy.test.mjs 的路子）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/ReviewPanel.vue', import.meta.url), 'utf8')

function makeVm(executor) {
  // 组件带 @/ 别名的 import（证据页子组件）进不来：剥掉 import 行，子组件用形参喂桩。
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
  // eslint-disable-next-line no-new-func
  const component = new Function('EvidencePanel', script.replace('export default', 'return'))({})
  const base = { $t: (k) => k, $emit: () => {}, executor }
  return Object.assign(base, component.data.call(base), component.methods, {
    revisionGroups: component.computed.revisionGroups,
  })
}

// 假引擎：live 是当前修订列表，index 就是数组下标；处置一条后其余前移——与真引擎一致。
function makeEngine(ids) {
  const live = ids.slice()
  const resolved = []
  return {
    live,
    resolved,
    async executeCommand(action, params) {
      await Promise.resolve()
      if (action === 'list_revisions') {
        return {
          success: true,
          revisions: live.map((id, i) => ({
            index: i, id, text: id, type: 'Insert', author: 'u', date: 'd',
            // 只有 C 与前一条 B 首尾相接，于是 B+C 并成一张卡片
            contiguous: id === 'C',
          })),
        }
      }
      if (action === 'list_comments') return { success: true, comments: [] }
      if (action === 'resolve_revision') {
        if (params.index < 0 || params.index >= live.length) return { success: false, message: 'out of range' }
        resolved.push(live[params.index])
        live.splice(params.index, 1)
        return { success: true }
      }
      // resolveGroup 批量处置（尽调 P3#1）：indices 降序逐条处置，语义与上面的单条
      // resolve_revision 一致，只是一次带上整组。
      if (action === 'resolve_revisions') {
        const idxs = (params.indices || []).slice().sort((a, b) => b - a)
        const results = idxs.map((idx) => {
          if (idx < 0 || idx >= live.length) return { index: idx, success: false }
          resolved.push(live[idx])
          live.splice(idx, 1)
          return { index: idx, success: true }
        })
        return { success: true, results }
      }
      return { success: true }
    },
  }
}

test('修订卡片连点两次接受，只处置本组的修订，不误伤别的', async () => {
  // A B C D E：B(1)+C(2) 是一组，A/D/E 与用户点的这张卡片无关
  const engine = makeEngine(['A', 'B', 'C', 'D', 'E'])
  const vm = makeVm(engine)
  await vm.reload()
  const groups = vm.revisionGroups.call(vm)
  const g = groups.find((x) => x.items.length > 1)
  assert.deepEqual(g.items.map((r) => r.index), [1, 2], '前置条件：卡片覆盖索引 1 与 2')

  await Promise.all([vm.resolveGroup(g, 'accept'), vm.resolveGroup(g, 'accept')])

  assert.deepEqual(engine.resolved.slice().sort(), ['B', 'C'], '只许处置本组的 B 与 C')
  assert.deepEqual(engine.live, ['A', 'D', 'E'], 'A/D/E 是用户没看过的修订，不许被动过')
})

test('处置完成后闸要放开，允许接着处置下一张卡片', async () => {
  const engine = makeEngine(['A', 'B', 'C', 'D', 'E'])
  const vm = makeVm(engine)
  await vm.reload()
  const g1 = vm.revisionGroups.call(vm).find((x) => x.items.length > 1)
  await vm.resolveGroup(g1, 'accept')
  const g2 = vm.revisionGroups.call(vm)[0]
  await vm.resolveGroup(g2, 'reject')
  assert.deepEqual(engine.live, ['D', 'E'], '闸没放开的话第二张卡片就点不动了')
})
