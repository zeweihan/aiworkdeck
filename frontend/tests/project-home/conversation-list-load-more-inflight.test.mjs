// 审计（dev-board#74）：ConversationList 的「加载更多」直接 @tap="$emit('load-more')"，
// 没有重入闸。父组件的翻页游标（nextBefore/nextBeforeId）要等响应回来才更新，
// 所以在第一次请求落地前连点两下，第二次带的是同一份游标，取回同一页拼进列表——
// 同一个 conversationId 在数组里出现两次，v-for 的 :key 撞车，卡片重复渲染并触发
// Vue 的 duplicate key 告警。
//
// 用例不做纯文本断言，按本目录既有路子把 <script> 抽出来跑（同
// review-panel-double-tap.test.mjs）：组件的 import 走 @/ 别名，node 直接进不来，
// 这里用不到那些纯函数，剥掉 import 行即可。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/project-home/ConversationList.vue', import.meta.url), 'utf8')

function makeVm(props) {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s+'[^']*'\s*$/gm, '')
  // eslint-disable-next-line no-new-func
  const component = new Function(script.replace('export default', 'return'))()
  const emitted = []
  const vm = Object.assign(
    { $t: (k) => k, $emit: (...a) => emitted.push(a) },
    props,
    component.methods)
  return { vm, emitted }
}

test('第一次请求还没落地时再点，不再派发 load-more', () => {
  const { vm, emitted } = makeVm({ loading: false, hasMore: true })
  vm.onLoadMore()
  // 父组件在 loadConversations 开头同步置 conversationsLoading = true，prop 随之下来
  vm.loading = true
  vm.onLoadMore()
  vm.onLoadMore()
  assert.deepEqual(emitted.map((e) => e[0]), ['load-more'], '同一份游标只许发一次')
})

test('这一轮落地后闸放开，还能接着翻下一页', () => {
  const { vm, emitted } = makeVm({ loading: true, hasMore: true })
  vm.onLoadMore()
  assert.equal(emitted.length, 0)
  vm.loading = false
  vm.onLoadMore()
  assert.deepEqual(emitted.map((e) => e[0]), ['load-more'], '闸没放开就再也翻不了页')
})

test('加载更多行走 onLoadMore，不再裸 emit', () => {
  const row = SRC.split('\n').find((l) => l.includes('class="conv-more"'))
  assert.ok(row, '找不到加载更多行')
  assert.match(row, /@tap="onLoadMore"/, '裸 $emit 绕开了重入闸')
})
