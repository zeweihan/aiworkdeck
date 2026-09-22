// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// chatTurns 的增量维护契约（dev-board#811 K31，审查 C-05）。
//
// 病灶：`chatTurns` 是挂在深响应式 `bubbles` 上的 computed，流式回答每个 token 都会让它
// 全量重跑，而且每次都新建一整套 turn 对象——模板那条 `v-memo="[turn, …]"` 以 turn 身份
// 为第一依赖，身份每帧都变就等于没有 memo，200 轮历史的子树要跟着重建 3000 遍。
//
// 所以这里钉两件事：
//   ① 内容没变的轮次，跨次调用必须返回**同一个对象**（v-memo 的前提）；
//   ② 内容真的变了必须换新对象（复用过头 = 界面不动，而且一声不吭）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { buildChatTurns } from '../../src/components/AgentMessage/chatTurns.mjs'

const CHAT_INTERFACE = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')

const user = (id, content) => ({ id, role: 'USER', content })
const assistant = (id, extra = {}) => ({ id, role: 'ASSISTANT', content: '回答', processes: [], ...extra })

function conversation(turns) {
  const bubbles = []
  for (let i = 1; i <= turns; i += 1) {
    bubbles.push(user(`u${i}`, `第 ${i} 问`))
    bubbles.push(assistant(`a${i}`))
  }
  return bubbles
}

test('内容没变的轮次跨次调用返回同一个对象（v-memo 的前提）', () => {
  const bubbles = conversation(5)
  const cache = {}
  const first = buildChatTurns(bubbles, { cache })
  const second = buildChatTurns(bubbles, { cache })
  assert.equal(first.length, 5)
  for (let i = 0; i < first.length; i += 1) {
    assert.equal(second[i], first[i], `第 ${i + 1} 轮的 turn 身份必须稳定`)
  }
})

test('流式：只有正在长的那一轮换对象，前面的历史一个都不动', () => {
  const bubbles = conversation(5)
  const live = assistant('live', { content: '', isStreaming: true })
  bubbles.push(user('u6', '第 6 问'), live)
  const cache = {}
  const before = buildChatTurns(bubbles, { cache, isStreaming: true })
  live.content = '正在回答……'
  const after = buildChatTurns(bubbles, { cache, isStreaming: true })
  for (let i = 0; i < 5; i += 1) {
    assert.equal(after[i], before[i], `历史第 ${i + 1} 轮不该因为别人在流式而重建`)
  }
  // 最后一轮的 answerIndex 从 -1 变成了有值，所以它必须是新对象
  assert.notEqual(after[5], before[5], '正在长的那一轮必须换新对象，否则界面不动')
})

test('不给 cache 就是原来的纯函数行为（每次都新建）', () => {
  const bubbles = conversation(3)
  const a = buildChatTurns(bubbles)
  const b = buildChatTurns(bubbles)
  assert.notEqual(a[0], b[0])
  assert.deepEqual(a.map(t => t.key), b.map(t => t.key))
})

test('轮次真的变了必须换对象：提问改写 / 新增助手消息 / 新增工具过程', () => {
  const bubbles = conversation(3)
  const cache = {}
  const base = buildChatTurns(bubbles, { cache })

  bubbles[0].content = '第 1 问（改过了）'
  const afterEdit = buildChatTurns(bubbles, { cache })
  assert.notEqual(afterEdit[0], base[0], '提问正文改了必须换对象')
  assert.equal(afterEdit[0].label, '第 1 问（改过了）')
  assert.equal(afterEdit[1], base[1], '不相干的轮次不受影响')

  const settled = buildChatTurns(bubbles, { cache })
  bubbles[3].processes = [{ id: 'p1', items: [] }]
  const afterProcess = buildChatTurns(bubbles, { cache })
  assert.notEqual(afterProcess[1], settled[1], '多了一个工具过程必须换对象')

  const settled2 = buildChatTurns(bubbles, { cache })
  bubbles.push(assistant('a3b'))
  const afterAppend = buildChatTurns(bubbles, { cache })
  assert.notEqual(afterAppend[2], settled2[2], '这一轮多了一条助手消息必须换对象')
})

test('第一轮也能复用：没有清单的轮次共用同一个空数组，不是每次现造一个', () => {
  const bubbles = conversation(2)
  const cache = {}
  const a = buildChatTurns(bubbles, { cache })
  const b = buildChatTurns(bubbles, { cache })
  assert.equal(b[0], a[0], 'todos 若每次现造 []，第一轮会永远命中不了复用')
})

test('提问摘要按气泡缓存，正文没变就不再跑一遍正则', () => {
  const bubble = user('u1', '<b>请核对</b>   付款条款')
  const turns = buildChatTurns([bubble, assistant('a1')])
  assert.equal(turns[0].label, '请核对 付款条款', '仍然剥标签、压空白')
  // 改了正文就要重算（缓存按 source 比对，不是按气泡一次定终身）
  bubble.content = '换个问题'
  assert.equal(buildChatTurns([bubble, assistant('a1')])[0].label, '换个问题')
})

test('复用不影响待处理定位条：attentionIndex 变了照样换对象', () => {
  const answered = { question: { answered: false } }
  const bubbles = [user('u1', '问'), assistant('a1', answered)]
  const cache = {}
  const idle = buildChatTurns(bubbles, { cache, isStreaming: true })
  assert.equal(idle[0].attentionIndex, -1, '流式期间不该有可操作的问题卡')
  const waiting = buildChatTurns(bubbles, { cache, isStreaming: false })
  assert.notEqual(waiting[0], idle[0])
  assert.equal(waiting[0].attentionIndex, 1)
  assert.equal(waiting[0].attentionKind, 'question')
})

// ---- 接线：turn 复用只有配上模板那条 v-memo 才真的省下什么 ----
// 两边任何一边被摘掉，性能都会悄悄退回改造前（14.5ms/token），而界面一切正常、
// 没有任何测试会因此变红——所以在源码层钉住这两处。

test('chatTurns 的 computed 必须带 cache，否则 turn 身份每帧都变', () => {
  assert.match(CHAT_INTERFACE, /buildChatTurns\(bubbles\.value,\s*\{[^}]*cache:\s*turnCache/,
    'ChatInterface 的 chatTurns computed 必须把 turnCache 传进去')
})

test('轮次的 v-for 元素上必须有 v-memo，且第一依赖是 turn 本身', () => {
  const memo = CHAT_INTERFACE.match(/v-for="turn in chatTurns"[\s\S]{0,200}?v-memo="\[([^\]]*)/)
  assert.ok(memo, 'v-memo 必须挂在 v-for="turn in chatTurns" 那个元素上——' +
    '挂到 v-for 里层的元素上时，200 个迭代共用同一个缓存槽，等于没有 memo')
  assert.match(memo[1].trim(), /^turn\b/, '第一依赖必须是 turn 对象本身')
  for (const dep of ['isStreaming', 'bubbles.length', 'receiptState', 'contextNotices', 'dbMessageId']) {
    assert.ok(memo[1].includes(dep), `v-memo 依赖里漏了 ${dep}：漏一个就是「内容变了界面不动」且一声不吭`)
  }
})
