// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1060：进行中的对话切走再切回、或刷新后看历史，思考记录不能没了。
//
// 三条契约，都跑真实 useAgentStream（agent-stream-factory 的整段注入法）：
//  1. state_recovery 带 events（新后端）时按序重放：思考进 ThinkingCard、工具过程进过程卡，
//     思考计时按后端的 startedAt 续算，不从 0 重来；
//  2. 历史消息带 reasoning 记录时，每块按 anchor 挂回原位（没过程卡 → 顶层，有 → 那个过程卡），
//     折叠态显示落库的时长，多轮不合成一坨；
//  3. 老消息 / 老后端没有这些字段时行为不变。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { visibleChatTimeline } from '../../src/components/AgentMessage/chatTimeline.mjs'
import { buildAgentStreamFactory } from '../_lib/agent-stream-factory.mjs'

function stream() {
  return buildAgentStreamFactory({ expose: 'handleEvent, currentAssistantBubble, createAssistantBubble' })
}

const TOOL = '<process name="读取文件"><tool_code>read_file({"path":"a.txt"})</tool_code><tool_output status="SUCCESS">OK</tool_output></process>\n'

test('state_recovery 带 events：思考、过程卡、正文按原顺序重建，计时按后端 startedAt 续算', () => {
  const s = stream()
  const serverNow = 1_000_000
  const payload = {
    content: '<final>结论',
    startedAt: serverNow - 70_000,
    serverNow,
    events: [
      { type: 'reasoning', content: '先想想要读哪份文件。', at: serverNow - 65_000, lastAt: serverNow - 40_000 },
      { type: 'text', content: TOOL, at: serverNow - 39_000 },
      { type: 'reasoning', content: '读完了，开始组织答案。', at: serverNow - 30_000, lastAt: serverNow - 5_000 },
    ],
  }
  const before = Date.now()
  s.handleEvent('state_recovery', JSON.stringify(payload))
  const bubble = s.bubbles.value[s.bubbles.value.length - 1]
  assert.equal(bubble.role, 'ASSISTANT')
  assert.equal(bubble.isStreaming, true)
  // 顶层思考卡：内容回来了，并且已经收尾（后面紧跟着工具过程），时长 ≈ 首个工具出现时刻 - 本段开始
  assert.equal(bubble.thinking.content, '先想想要读哪份文件。')
  assert.equal(bubble.thinking.status, 'done')
  assert.ok(Math.abs(bubble.thinking.duration - 31) < 1.5, `顶层思考时长应≈31s，实际 ${bubble.thinking.duration}`)
  // 过程卡回来了，第二轮思考挂在它上面，且还在进行中，起点换算成本机时钟
  assert.equal(bubble.processes.length, 1)
  const items = bubble.processes[0].items
  const think = items[items.length - 1]
  assert.equal(think.type, 'thinking')
  assert.equal(think.content, '读完了，开始组织答案。')
  assert.equal(think.status, 'thinking')
  const expectedStart = before - 30_000
  assert.ok(Math.abs(think.startTime - expectedStart) < 1000, '第二轮思考起点按后端时间换算')
  assert.equal(bubble.content, '', '思考不进正文')
  // 后续增量照常追加
  s.handleEvent('reasoning_delta', JSON.stringify({ content: '再补一句。' }))
  assert.equal(think.content, '读完了，开始组织答案。再补一句。')
  s.handleEvent('text_delta', JSON.stringify({ content: '<final>完成</final>' }))
  assert.equal(bubble.content, '完成')
  assert.equal(think.status, 'done')
  s.resetSSE()
})

test('state_recovery 只有 startedAt 没有任何事件（首 token 未到）：思考卡从发送时刻读秒', () => {
  const s = stream()
  const serverNow = 5_000_000
  s.handleEvent('state_recovery', JSON.stringify({ content: '', startedAt: serverNow - 42_000, serverNow, events: [] }))
  const bubble = s.bubbles.value[s.bubbles.value.length - 1]
  assert.equal(bubble.thinking.status, 'thinking')
  const waited = (Date.now() - bubble.thinking.startTime) / 1000
  assert.ok(Math.abs(waited - 42) < 1.5, `应从 42 秒续算，实际 ${waited}`)
  s.resetSSE()
})

test('老后端 state_recovery 只有 content：行为不变', () => {
  const s = stream()
  s.handleEvent('state_recovery', JSON.stringify({ content: '<final>旧快照' }))
  const bubble = s.bubbles.value[s.bubbles.value.length - 1]
  assert.equal(bubble.content, '旧快照')
  assert.equal(bubble.thinking.content, '')
  s.resetSSE()
})

test('历史消息带 reasoning：每块挂回原位，折叠态显示落库时长，不合成一坨', () => {
  const s = stream()
  const record = JSON.stringify([
    { text: '第一轮思考', startedAt: 1000, endedAt: 13_000, anchor: 0 },
    { text: '第二轮思考', startedAt: 20_000, endedAt: 25_000, anchor: 1 },
    { text: '第三轮思考', startedAt: 30_000, endedAt: 32_000, anchor: 2 },
  ])
  const content = TOOL + TOOL + '<final>这是结论</final>'
  const bubble = s.parseAssistantHistory(content, record)
  assert.equal(bubble.thinking.content, '第一轮思考')
  assert.equal(bubble.thinking.status, 'done')
  assert.equal(bubble.thinking.duration, 12)
  assert.equal(bubble.processes.length, 2)
  const t1 = bubble.processes[0].items.filter(i => i.type === 'thinking')
  const t2 = bubble.processes[1].items.filter(i => i.type === 'thinking')
  assert.deepEqual(t1.map(i => [i.content, i.status, i.duration]), [['第二轮思考', 'done', 5]])
  assert.deepEqual(t2.map(i => [i.content, i.status, i.duration]), [['第三轮思考', 'done', 2]])
  assert.equal(bubble.content, '这是结论', '思考不进正文')
  // 时间线：顶层思考在最前，过程组在中间，正文最后
  const types = visibleChatTimeline(bubble).map(e => e.type)
  assert.equal(types[0], 'thinking')
  assert.equal(types[types.length - 1], 'text')
})

test('历史消息没有 reasoning 字段 / 坏值：与改造前一致', () => {
  const s = stream()
  const content = TOOL + '<final>这是结论</final>'
  const plain = s.parseAssistantHistory(content)
  const broken = s.parseAssistantHistory(content, '{not json')
  for (const bubble of [plain, broken]) {
    assert.equal(bubble.thinking.content, '')
    assert.equal(bubble.processes.length, 1)
    assert.equal(bubble.processes[0].items.filter(i => i.type === 'thinking').length, 0)
    assert.equal(bubble.content, '这是结论')
  }
})

test('ChatInterface 回灌历史时把 msg.reasoning 交给解析器', async () => {
  const { readFileSync } = await import('node:fs')
  const ui = readFileSync(new URL('../../src/components/ChatInterface.vue', import.meta.url), 'utf8')
  assert.match(ui, /parseAssistantHistory\(msg\.content \|\| '', msg\.reasoning\)/)
})
