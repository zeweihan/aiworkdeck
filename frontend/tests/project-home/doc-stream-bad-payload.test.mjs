// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 坏载荷必须表现为「什么都没发生」，不能表现为「看起来正在写」。
 *
 * 病灶（#663 引入，2026-08-30）：后端一度把裸 `Map.of("content", token)` 交给
 * `SseEmitterService.send`，而 send 里是 `String.valueOf(data)` —— 载荷成了 Java 的
 * `Map.toString()`（`{content=正文}`）而不是 JSON。前端这一支先把气泡置成
 * `isEditorStreaming` 并写上「正在向文档流式写入内容…」，**再**去 `JSON.parse`，
 * 于是 parse 抛错被 catch 吞成一行 console.error，而气泡已经被改成「正在写入」且此后
 * 正文会被 appendText 一路吞掉：用户拿到一份空白文档、对话永远停在「正在写入」，
 * 前后端谁都不报错（dev-board#465 的症状）。
 *
 * 与 agent-stream-replay.test.mjs 同法：从真源码里切出这一支跑，不另抄一份实现。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const START = "} else if (evt === 'doc_stream_data') {"
const END = "} else if (evt === 'doc_stream_end') {"
const body = source.slice(source.indexOf(START) + START.length, source.indexOf(END))
assert.ok(body.length > 100 && body.includes('JSON.parse'), '没能从源码里切出 doc_stream_data 分支')

function run(dataStr) {
  const forwarded = []
  const bubble = { content: '', isEditorStreaming: false }
  const errors = []
  const build = new Function('evt', 'dataStr', 'currentAssistantBubble', 't', 'clientActionHandler', 'console',
    body + '\n')
  build('doc_stream_data', dataStr, { value: bubble },
    (k) => k, { value: (a) => forwarded.push(a) },
    { error: (...a) => errors.push(a.join(' ')) })
  return { bubble, forwarded, errors }
}

test('合法 JSON：正文转发给消费端，气泡进入「正在写入」态', () => {
  const r = run('{"content":"第一条\\t甲方"}')
  assert.deepEqual(r.forwarded, [{ action: 'doc_stream_data', content: '第一条\t甲方' }])
  assert.equal(r.bubble.isEditorStreaming, true)
  assert.equal(r.bubble.content, 'agentStream.docStreamingPlaceholder')
  assert.deepEqual(r.errors, [])
})

test('坏载荷（后端裸 Map 的 toString）：不转发、不置位、不改气泡，只留一条错误日志', () => {
  const r = run('{content=第一条 甲方}')
  assert.deepEqual(r.forwarded, [], '解析失败还把内容转发出去了')
  assert.equal(r.bubble.isEditorStreaming, false,
    '解析失败却把气泡置成了 isEditorStreaming——此后正文会被 appendText 一路吞掉')
  assert.equal(r.bubble.content, '',
    '解析失败却写上了「正在向文档流式写入内容…」，对话会永远停在这句谎话上')
  assert.equal(r.errors.length, 1, '失败原因至少要留在控制台里')
})

test('已经在写入态的气泡不会被坏载荷重置，正文也不被占位符顶掉', () => {
  const forwarded = []
  const bubble = { content: '已经写了一段', isEditorStreaming: true }
  const build = new Function('evt', 'dataStr', 'currentAssistantBubble', 't', 'clientActionHandler', 'console', body + '\n')
  build('doc_stream_data', '{content=坏的}', { value: bubble },
    (k) => k, { value: (a) => forwarded.push(a) }, { error: () => {} })
  assert.equal(bubble.content, '已经写了一段')
  assert.deepEqual(forwarded, [])
})
