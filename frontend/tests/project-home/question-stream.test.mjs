// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// B6（dev-board#481）定向复测：真实解析器与 Vue 响应式，问题正文必须在本轮结束时可见，
// 不依赖用户再发一条消息触发刷新。没有复现「折叠点不开」，此文件只钉住数据到卡片的契约。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, reactive, nextTick, computed, watchEffect } from 'vue'
import { createProtocolTagRegex, decodeProtocolTags } from '../../src/composables/agentTagProtocol.mjs'
import { nextBubbleId } from '../../src/composables/bubbleId.js'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

function stream() {
  const body = source.replace(/^import .*$/gm, '')
    .replace('export function useAgentStream()', 'function useAgentStream()')
    .replace('        bubbles,\n', '        bubbles, handleEvent, currentAssistantBubble, createAssistantBubble,\n')
  const factory = new Function('ref', 'reactive', 'nextTick', 'onUnmounted', 'getCurrentInstance',
    'createProtocolTagRegex', 'decodeProtocolTags', 't', 'nextBubbleId',
    body + '\nreturn useAgentStream()')
  const value = factory(ref, reactive, nextTick, () => {}, () => null,
    createProtocolTagRegex, decodeProtocolTags, key => key, nextBubbleId)
  const bubble = value.createAssistantBubble()
  bubble.isStreaming = true
  value.bubbles.value.push(bubble)
  value.currentAssistantBubble.value = bubble
  return value
}

const QUESTION = '时间跨度请选择：'
const OPTIONS = ['仅争议期间', '合同签订至起诉', '全部事实']
const MARKUP = `<question>${QUESTION}${OPTIONS.map(o => `<option>${o}</option>`).join('')}</question>`

for (const chunkSize of [1, 7, 9999]) {
  test(`反问按 ${chunkSize} 字符分片：本轮结束即显示正文与三选项，无需下一条用户消息`, async () => {
    const s = stream()
    let visible = ''
    const stop = watchEffect(() => {
      const b = s.bubbles.value[0]
      visible = b.question ? `${b.question.text}|${b.question.options.join('|')}|${!b.isStreaming}` : ''
    })
    for (let i = 0; i < MARKUP.length; i += chunkSize) {
      s.handleEvent('text_delta', JSON.stringify({ content: MARKUP.slice(i, i + chunkSize) }))
    }
    s.handleEvent('bubble_end', JSON.stringify({ status: 'awaiting_input' }))
    await nextTick()
    assert.equal(visible, `${QUESTION}|${OPTIONS.join('|')}|true`)
    assert.equal(s.bubbles.value[0].content, '', '问题正文只渲染一次，不串进普通正文')
    assert.equal(s.agentAwaitingInput.value, true)
    assert.equal(s.bubbles.value.length, 1, '测试未靠添加新消息触发刷新')
    stop()
  })
}

test('模型漏闭合 question 时，bubble_end 冲出缓冲，开放式问题仍完整可见', async () => {
  const s = stream()
  const questionText = computed(() => s.bubbles.value[0].question?.text || '')
  s.handleEvent('text_delta', JSON.stringify({ content: `<question>${QUESTION}` }))
  s.handleEvent('bubble_end', JSON.stringify({ status: 'awaiting_input' }))
  await nextTick()
  assert.equal(questionText.value, QUESTION)
  assert.equal(s.bubbles.value[0].isStreaming, false)
})
