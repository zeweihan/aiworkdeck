// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// B6（dev-board#481）定向复测：真实解析器与 Vue 响应式，问题正文必须在本轮结束时可见，
// 不依赖用户再发一条消息触发刷新。没有复现「折叠点不开」，此文件只钉住数据到卡片的契约。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { ref, reactive, nextTick, computed, watchEffect } from 'vue'
import { createProtocolTagRegex, decodeProtocolTags } from '../../src/composables/agentTagProtocol.mjs'
import {
  applyInboxReceipt,
  applyInboxSnapshot,
  applyInputApplied,
  createInboxState,
  markInboxEvent,
  removeInboxItem,
  replaceInboxItem,
} from '../../src/composables/agentInboxState.mjs'
import { nextBubbleId } from '../../src/composables/bubbleId.js'

const source = readFileSync(new URL('../../src/composables/useAgentStream.js', import.meta.url), 'utf8')

function stream(overrides = {}) {
  const body = source.replace(/^import .*$/gm, '')
    .replace('export function useAgentStream()', 'function useAgentStream()')
    .replace('        bubbles,\n', '        bubbles, handleEvent, currentAssistantBubble, createAssistantBubble, createUserBubble,\n')
  const factory = new Function('ref', 'reactive', 'nextTick', 'onUnmounted', 'getCurrentInstance',
    'createProtocolTagRegex', 'decodeProtocolTags', 't', 'nextBubbleId',
    'createInboxState', 'applyInboxReceipt', 'applyInboxSnapshot', 'applyInputApplied', 'markInboxEvent', 'removeInboxItem', 'replaceInboxItem',
    'getApiBaseUrl', 'getSessionId', 'getAgentInbox', 'updateAgentInboxItem', 'deleteAgentInboxItem', 'getConversationMetadata',
    body + '\nreturn useAgentStream()')
  const value = factory(ref, reactive, nextTick, () => {}, () => null,
    createProtocolTagRegex, decodeProtocolTags, key => key, nextBubbleId,
    createInboxState, applyInboxReceipt, applyInboxSnapshot, applyInputApplied, markInboxEvent, removeInboxItem, replaceInboxItem,
    () => 'http://test.local', () => 'test-session',
    overrides.getAgentInbox || (async () => ({ items: [], runId: null, status: null })),
    overrides.updateAgentInboxItem || (async () => null),
    overrides.deleteAgentInboxItem || (async () => ({ items: [] })),
    async () => null)
  const bubble = value.createAssistantBubble()
  bubble.isStreaming = true
  value.bubbles.value.push(bubble)
  value.currentAssistantBubble.value = bubble
  return value
}

function deferred() {
  let resolve
  let reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
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

test('inbox snapshot and applied event render steering once and keep one empty assistant segment', () => {
  const s = stream()
  s.handleEvent('plan_update', JSON.stringify({ todos: [{ content: 'Inherited task', status: 'in_progress' }] }))
  s.bubbles.value.unshift(s.createUserBubble('initial request'))

  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-1',
    status: 'RUNNING',
    items: [{ id: 'message-1', message: 'first steer', state: 'pending', runId: 'run-1', sequence: 1 }],
  }))
  const firstApplied = {
    messageId: 'message-1', runId: 'run-1', sequence: 1,
    message: 'canonical first steer', displayText: 'first steer', clientRequestId: 'request-1', submissionMode: 'steer',
  }
  s.handleEvent('input_applied', JSON.stringify(firstApplied))
  s.handleEvent('input_applied', JSON.stringify(firstApplied))

  assert.deepEqual(s.bubbles.value.map((bubble) => bubble.role), ['USER', 'USER', 'ASSISTANT'])
  assert.equal(s.bubbles.value[1].content, 'canonical first steer')
  assert.equal(s.bubbles.value[1].displayContent, 'first steer')

  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-1',
    status: 'RUNNING',
    items: [
      { id: 'message-1', message: 'canonical first steer', state: 'applied', runId: 'run-1', sequence: 1 },
      { id: 'message-2', message: 'second steer', state: 'pending', runId: 'run-1', sequence: 2 },
    ],
  }))
  s.handleEvent('input_applied', JSON.stringify({
    messageId: 'message-2', runId: 'run-1', sequence: 2, message: 'second steer', submissionMode: 'steer',
  }))
  s.handleEvent('input_applied', JSON.stringify({
    messageId: 'stale-message', runId: 'old-run', sequence: 99, message: 'must stay hidden', submissionMode: 'steer',
  }))

  assert.deepEqual(s.bubbles.value.map((bubble) => bubble.role), ['USER', 'USER', 'USER', 'ASSISTANT'])
  assert.deepEqual(s.bubbles.value.filter((bubble) => bubble.role === 'USER').map((bubble) => bubble.content),
    ['initial request', 'canonical first steer', 'second steer'])
  assert.equal(s.bubbles.value.at(-1).planTodos[0].content, 'Inherited task')
})

test('a restore snapshot that resolves after input_applied cannot restore a phantom pending item', async () => {
  const request = deferred()
  const s = stream({ getAgentInbox: async () => request.promise })
  s.setConversationId('conversation-1')

  const restoring = s.restoreInbox()
  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-1', status: 'RUNNING',
    items: [{ id: 'message-1', message: 'steer', state: 'pending', runId: 'run-1', sequence: 1 }],
  }))
  s.handleEvent('input_applied', JSON.stringify({
    messageId: 'message-1', runId: 'run-1', sequence: 1, message: 'steer', submissionMode: 'steer',
  }))
  request.resolve({
    runId: 'run-1', status: 'RUNNING',
    items: [{ id: 'message-1', message: 'steer', state: 'pending', runId: 'run-1', sequence: 1 }],
  })
  await restoring

  assert.equal(s.inboxState.items.length, 1)
  assert.equal(s.inboxState.items[0].state, 'applied')
})

test('an edit response that resolves after switching conversations cannot overwrite the new inbox', async () => {
  const request = deferred()
  const s = stream({ updateAgentInboxItem: async () => request.promise })
  s.setConversationId('conversation-1')
  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-1', status: 'RUNNING', items: [{ id: 'old', message: 'old', state: 'pending' }],
  }))

  const editing = s.updateInbox('old', { message: 'edited', expectedRevision: 1 })
  s.setConversationId('conversation-2')
  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-2', status: 'RUNNING', items: [{ id: 'new', message: 'new', state: 'pending' }],
  }))
  request.resolve({ id: 'old', message: 'edited', state: 'pending', revision: 2 })
  await editing

  assert.deepEqual(s.inboxState.items.map(item => item.id), ['new'])
})

test('a delete response that resolves after switching conversations cannot overwrite the new inbox', async () => {
  const request = deferred()
  const s = stream({ deleteAgentInboxItem: async () => request.promise })
  s.setConversationId('conversation-1')
  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-1', status: 'RUNNING', items: [{ id: 'old', message: 'old', state: 'pending' }],
  }))

  const deleting = s.deleteInbox('old', 1)
  s.setConversationId('conversation-2')
  s.handleEvent('inbox_updated', JSON.stringify({
    runId: 'run-2', status: 'RUNNING', items: [{ id: 'new', message: 'new', state: 'pending' }],
  }))
  request.resolve({ runId: 'run-1', status: 'RUNNING', items: [] })
  await deleting

  assert.deepEqual(s.inboxState.items.map(item => item.id), ['new'])
})

test('a late HTTP receipt after New Chat cannot repopulate the new conversation', async () => {
  const originalFetch = globalThis.fetch
  let releaseReceipt
  globalThis.fetch = async (url, options = {}) => {
    if (url.includes('/api/agent/connect/')) {
      return {
        ok: true,
        body: {
          getReader: () => ({
            read: () => new Promise((resolve, reject) => {
              if (options.signal.aborted) {
                reject(new DOMException('aborted', 'AbortError'))
                return
              }
              options.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
            }),
          }),
        },
      }
    }
    if (url.includes('/api/agent/tasks/active')) {
      return { ok: true, json: async () => [] }
    }
    if (url.endsWith('/api/agent/chat')) {
      return new Promise((resolve) => { releaseReceipt = resolve })
    }
    throw new Error(`unexpected fetch ${url}`)
  }

  try {
    const s = stream()
    s.clearBubbles()
    s.setConversationId('old-conversation')
    const pending = s.sendMessage({
      prompt: 'keep working', projectId: '1', clientRequestId: 'request-old', submissionMode: 'steer',
    })
    while (!releaseReceipt) await Promise.resolve()

    s.setConversationId(null)
    s.clearBubbles()
    releaseReceipt({
      ok: true,
      status: 200,
      json: async () => ({
        status: 'accepted', messageId: 'message-old', runId: 'run-old', submissionMode: 'steer', state: 'applied',
      }),
    })

    const receipt = await pending
    assert.equal(receipt.messageId, 'message-old')
    assert.equal(s.currentConversationId.value, null)
    assert.deepEqual(s.bubbles.value, [])
    assert.deepEqual(s.inboxState.items, [])
  } finally {
    globalThis.fetch = originalFetch
  }
})

test('applying an interjection preserves buffered text in its original assistant segment', () => {
  const s = stream()
  s.bubbles.value.unshift(s.createUserBubble('initial request'))
  s.handleEvent('text_delta', JSON.stringify({ content: 'Visible untagged reply before steer' }))
  s.handleEvent('input_applied', JSON.stringify({ messageId: 'steer-buffer', message: 'Focus on payment', runId: 'run-buffer', sequence: 1 }))
  const prior = s.bubbles.value.filter(b => b.role === 'ASSISTANT')[0]
  assert.equal(prior.content, 'Visible untagged reply before steer')
  assert.equal(prior.isStreaming, false)
  s.handleEvent('text_delta', JSON.stringify({ content: '<final>New reply after steer</final>' }))
  s.handleEvent('bubble_end', JSON.stringify({ status: 'finished' }))
  assert.equal(s.bubbles.value.at(-1).content, 'New reply after steer')
  assert.equal(prior.content, 'Visible untagged reply before steer')
})
