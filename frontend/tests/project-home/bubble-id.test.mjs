// 聊天气泡 ID 必须唯一——撞 key 会让 Vue 复用错节点，正文渲染进别人的气泡。
//
// 病灶：用户气泡与助手气泡是在**同一个同步块**里先后创建的
// （useAgentStream: push(createUserBubble(...)) 紧接着 createAssistantBubble()），
// 两处 ID 都是 `msg-${Date.now()}` —— 同一毫秒 = 同一个 ID，几乎必撞。
// ChatInterface 的列表是 `:key="msg.id || index"`，key 撞了之后 Vue 的 diff 会复用错节点：
// 一条消息的正文渲染进另一条气泡、用户/助手样式串位、旧内容残留。
// 用户看到的就是「AI 历史对话记录里信息杂乱无序」。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { nextBubbleId, __resetBubbleIdSeqForTest } from '../../src/composables/bubbleId.js'

test('同一毫秒内连续生成的 ID 互不相同（这正是撞 key 的场景）', () => {
  __resetBubbleIdSeqForTest()
  // 同一个同步块里连出两个，就是真实代码里用户气泡 + 助手气泡的形状
  const a = nextBubbleId()
  const b = nextBubbleId()
  assert.notEqual(a, b, '用户气泡与助手气泡同毫秒创建，ID 不能相同')
})

test('一千个连续 ID 全部唯一', () => {
  __resetBubbleIdSeqForTest()
  const ids = new Set()
  for (let i = 0; i < 1000; i++) ids.add(nextBubbleId())
  assert.equal(ids.size, 1000, '存在重复 ID')
})

test('ID 仍带时间戳前缀，便于按 ID 排日志', () => {
  __resetBubbleIdSeqForTest()
  assert.match(nextBubbleId(), /^msg-\d+-\d+$/)
})

test('useAgentStream 不再用裸 Date.now() 当气泡 ID', () => {
  const src = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
  const code = src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  assert.ok(!/id:\s*`msg-\$\{Date\.now\(\)\}`/.test(code),
    '裸 Date.now() 当 ID 会让同毫秒创建的气泡撞 key')
  assert.match(code, /nextBubbleId\(\)/, '应改用 nextBubbleId()')
})
