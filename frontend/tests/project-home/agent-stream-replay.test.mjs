// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * dev-board#803 断点续传：SSE 行协议里的 `id:` 字段。
 *
 * 两件事要守住：
 *   1. 游标要随着派发推进 —— 它就是重连时 Last-Event-ID 的值，不推进等于永远补不回来；
 *   2. id 不大于已收过的事件一律丢弃 —— 补发重了的代价是正文里凭空多一段、
 *      或者一条迟到的 bubble_end 把新一轮的气泡当场结束掉，两种都不报错。
 *
 * 与 agent-stream-connect.test.mjs 同法：从真源码里切出 parseSSELineFull 跑，
 * 不另抄一份实现（抄的那份永远不会跟着改）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')
const code = source.slice(
  source.indexOf('    const parseSSELineFull = (line) => {'),
  source.indexOf('    const handleEvent = (evt, dataStr) => {')
)

function setup() {
  const seen = []
  const build = new Function('handleEvent', `
    let currentEventName = null, currentEventData = '', currentEventId = null;
    let lastSseEventId = '', lastSseEventSeq = 0, lastSseEventConversationId = null;
    const currentConversationId = { value: 'conv-1' };
    ${code}
    return {
      parseSSELineFull,
      cursor: () => lastSseEventId,
      cursorConversation: () => lastSseEventConversationId,
    };
  `)
  const api = build((evt, data) => seen.push([evt, data]))
  // 一整条事件 = event: / id: / data: 三行 + 一个空行（空行才是派发点）
  const feed = (evt, id, data) => {
    if (evt) api.parseSSELineFull(`event: ${evt}`)
    if (id !== null && id !== undefined) api.parseSSELineFull(`id: ${id}`)
    api.parseSSELineFull(`data: ${data}`)
    api.parseSSELineFull('')
  }
  return { feed, seen, cursor: api.cursor, cursorConversation: api.cursorConversation }
}

test('游标随派发推进，且记下它属于哪条会话', () => {
  const s = setup()
  s.feed('text_delta', 7, '{"content":"a"}')
  s.feed('text_delta', 8, '{"content":"b"}')
  assert.equal(s.cursor(), '8')
  assert.equal(s.cursorConversation(), 'conv-1')
  assert.equal(s.seen.length, 2)
})

test('补发里 id 不大于已收过的事件一律丢弃，正文不会多出一段', () => {
  const s = setup()
  s.feed('text_delta', 5, '{"content":"已经收到过"}')
  // 重连后后端把 5 号又发了一遍（缓冲区边界、代次重置等），这一条不许再派发
  s.feed('text_delta', 5, '{"content":"已经收到过"}')
  s.feed('text_delta', 4, '{"content":"更早的"}')
  s.feed('text_delta', 6, '{"content":"真正的新内容"}')
  assert.deepEqual(s.seen.map(([, d]) => d), [
    '{"content":"已经收到过"}',
    '{"content":"真正的新内容"}'
  ])
  assert.equal(s.cursor(), '6')
})

test('没有 id 的事件（心跳等不进补发缓冲的）照常派发，也不动游标', () => {
  const s = setup()
  s.feed('text_delta', 3, '{"content":"x"}')
  s.feed('heartbeat', null, 'ping')
  s.feed('heartbeat', null, 'ping')
  assert.equal(s.seen.length, 3)
  assert.equal(s.cursor(), '3')
})

test('id 不是数字时不参与去重，也不把游标污染成非数字', () => {
  const s = setup()
  s.feed('text_delta', 2, '{"content":"x"}')
  s.feed('text_delta', 'abc', '{"content":"y"}')
  assert.equal(s.seen.length, 2)
  assert.equal(s.cursor(), '2')
})
