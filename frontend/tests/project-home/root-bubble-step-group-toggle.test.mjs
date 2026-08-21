// 审计（dev-board#74）MEDIUM：步骤分组的展开状态只按 stepIndex 记，导致两组联动。
//
// 病灶：processGroups 按 processes 数组里连续同 stepIndex 的项收成一组，key 形如
// 's0'/'s1'。模型回退重做某一步时（比如 step 1 失败又重跑一次 step 0），会产生
// 两个非相邻、但 stepIndex 相同因此 key 相同的分组。isGroupExpanded/toggleGroup
// 以前按 key 记展开状态（groupToggles.value[key]），两个同 key 的分组因此共用
// 一个槽位——点开/收起其中一个连带把另一个也翻了状态。
//
// 修法：改按分组下标 gi 记（processes 只增不减，已渲染分组的 gi 不会变，天然
// 互不冲突）。
//
// RootBubble.vue 是 <script setup>，但脚本主体只依赖 computed/ref（真实 'vue' export，
// 在非组件上下文也能正常工作——已用一次性 smoke test 验证过）与 defineProps/
// defineEmits（编译宏，运行时不存在，用极简桩替换）。用真实的 Vue 响应式系统跑，
// 而不是纯源码文本断言，能验证 gi 分组是否真的互不干扰这个"行为"本身。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { computed, ref, reactive } from 'vue'

const SRC = readFileSync(
  new URL('../../src/components/AgentMessage/RootBubble.vue', import.meta.url), 'utf8')

function mountRootBubble(bubble) {
  const body = SRC.match(/<script setup>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
  const props = reactive({ bubble, isLatest: false })
  const factory = new Function(
    'computed', 'ref', 't', 'defineProps', 'defineEmits',
    body + '\nreturn { processGroups, isGroupExpanded, toggleGroup, groupToggles }')
  return factory(
    computed, ref,
    (key, vars) => (vars ? key + JSON.stringify(vars) : key), // t()：桩，不关心具体文案
    () => props,                                              // defineProps(schema) → 真实 props
    () => (() => {}))                                         // defineEmits(names) → 空 emit
}

test('两个 stepIndex 相同但不相邻的分组（重跑早前步骤），先各自成组，不合并', () => {
  const bubble = reactive({
    isStreaming: false,
    processes: [
      { id: 'a', stepIndex: 0, stepTitle: 'Step A', items: [] },
      { id: 'b', stepIndex: 1, stepTitle: 'Step B', items: [] },
      { id: 'c', stepIndex: 0, stepTitle: 'Step A retry', items: [] },
    ],
  })
  const { processGroups } = mountRootBubble(bubble)
  const groups = processGroups.value
  assert.equal(groups.length, 3, '三段应该分成三组（s0, s1, s0），不能因为 key 相同就合并非相邻的组')
  assert.equal(groups[0].key, 's0')
  assert.equal(groups[1].key, 's1')
  assert.equal(groups[2].key, 's0')
})

test('CRITICAL 行为：展开其中一个 stepIndex=0 的分组，不联动展开/收起另一个同 key 的分组', () => {
  const bubble = reactive({
    isStreaming: false,
    processes: [
      { id: 'a', stepIndex: 0, stepTitle: 'Step A', items: [] },
      { id: 'b', stepIndex: 1, stepTitle: 'Step B', items: [] },
      { id: 'c', stepIndex: 0, stepTitle: 'Step A retry', items: [] },
    ],
  })
  const { processGroups, isGroupExpanded, toggleGroup } = mountRootBubble(bubble)
  const groups = processGroups.value
  const [g0, , g2] = groups // g0.key === g2.key === 's0'，但 gi 分别是 0 和 2

  // 初始都收起（isStreaming=false 时默认全收）
  assert.equal(isGroupExpanded(g0.key, 0), false)
  assert.equal(isGroupExpanded(g2.key, 2), false)

  toggleGroup(g2.key, 2) // 用户点开第三组（proc c 所在、更晚出现的那组）
  assert.equal(isGroupExpanded(g2.key, 2), true, '刚点开的这组应该展开')
  assert.equal(isGroupExpanded(g0.key, 0), false,
    '这是本条缺陷的核心：第一组（同样 key=s0）绝不能被联动展开')

  toggleGroup(g0.key, 0) // 再点开第一组
  assert.equal(isGroupExpanded(g0.key, 0), true)
  assert.equal(isGroupExpanded(g2.key, 2), true, '两组现在都展开，是各自独立点开的结果，不是联动')

  toggleGroup(g2.key, 2) // 收起第三组
  assert.equal(isGroupExpanded(g2.key, 2), false)
  assert.equal(isGroupExpanded(g0.key, 0), true, '收起第三组不该连带收起第一组')
})

test('流式进行中默认展开最后一组，其余收起（回归保护，未受 gi 改动影响）', () => {
  const bubble = reactive({
    isStreaming: true,
    processes: [
      { id: 'a', stepIndex: 0, stepTitle: 'Step A', items: [] },
      { id: 'b', stepIndex: 1, stepTitle: 'Step B', items: [] },
    ],
  })
  const { processGroups, isGroupExpanded } = mountRootBubble(bubble)
  const groups = processGroups.value
  assert.equal(isGroupExpanded(groups[0].key, 0), false)
  assert.equal(isGroupExpanded(groups[1].key, 1), true, '流式进行中默认展开最新一组')
})

test('用户手动收起过"当前最新组"后，即使它还是最新组也保持用户选择（回归保护）', () => {
  const bubble = reactive({
    isStreaming: true,
    processes: [{ id: 'a', stepIndex: 0, stepTitle: 'Step A', items: [] }],
  })
  const { processGroups, isGroupExpanded, toggleGroup } = mountRootBubble(bubble)
  const g0 = processGroups.value[0]
  assert.equal(isGroupExpanded(g0.key, 0), true, '流式中默认展开')
  toggleGroup(g0.key, 0)
  assert.equal(isGroupExpanded(g0.key, 0), false, '用户手动收起后，以用户选择为准')
})

test('单一无归属分组（无 stepIndex，含历史消息）能正常展开/收起', () => {
  const bubble = reactive({
    isStreaming: false,
    processes: [{ id: 'a', items: [] }, { id: 'b', items: [] }],
  })
  const { processGroups, isGroupExpanded, toggleGroup } = mountRootBubble(bubble)
  const groups = processGroups.value
  assert.equal(groups.length, 1, '无 stepIndex 的项应该收进同一个"执行过程"组')
  assert.equal(groups[0].key, '')
  toggleGroup(groups[0].key, 0)
  assert.equal(isGroupExpanded(groups[0].key, 0), true)
})
