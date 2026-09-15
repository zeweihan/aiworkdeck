// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { computed, ref, reactive } from 'vue'
import { captureChatTimeline, visibleChatTimeline, isTimelineEntryActive } from '../../src/components/AgentMessage/chatTimeline.mjs'

function mount(bubble) {
  const source = readFileSync(new URL('../../src/components/AgentMessage/RootBubble.vue', import.meta.url), 'utf8')
  const body = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  return new Function('computed', 'ref', 't', 'defineProps', 'defineEmits', 'visibleChatTimeline', 'isTimelineEntryActive', body + '\nreturn { timeline, isExpanded, toggleEntry }')(
    computed, ref, k => k, () => ({ bubble }), () => () => {}, visibleChatTimeline, isTimelineEntryActive)
}
const base = () => ({ thinking: { status: 'idle', content: '' }, content: '', processes: [], artifacts: [], isStreaming: true })
const proc = (id, status = 'loading') => ({ id, stepIndex: 0, items: [{ type: 'tool', status, code: 'read_file({})' }] })

test('text → tools → thought → text keeps chronology; thought never gets merged into earlier thinking', () => {
  const bubble = reactive(base())
  bubble.content = 'Before'; captureChatTimeline(bubble)
  bubble.processes.push(proc('a')); captureChatTimeline(bubble)
  bubble.processes[0].items.push({ type: 'thinking', content: 'Review', status: 'thinking' }); captureChatTimeline(bubble)
  bubble.content += 'After'; captureChatTimeline(bubble)
  const entries = visibleChatTimeline(bubble)
  assert.deepEqual(entries.map(e => e.type), ['text', 'execution', 'thinking', 'text'])
  assert.deepEqual(entries.filter(e => e.type === 'text').map(e => bubble.content.slice(e.start, e.end)), ['Before', 'After'])
})

test('raw bubble becoming reactive does not duplicate waiting/thinking entries', () => {
  const raw = base(); raw.thinking.status = 'thinking'; captureChatTimeline(raw)
  const bubble = reactive(raw); bubble.thinking.content = 'Think'; captureChatTimeline(bubble)
  assert.equal(bubble.timeline.length, 1)
})

test('two separated executions of the same step expand independently', () => {
  const bubble = reactive(base())
  bubble.processes.push(proc('a', 'success')); captureChatTimeline(bubble)
  bubble.content = 'Retry'; captureChatTimeline(bubble)
  bubble.processes.push(proc('b', 'success')); captureChatTimeline(bubble)
  bubble.isStreaming = false
  const view = mount(bubble)
  const entries = view.timeline.value
  view.toggleEntry(entries[2], 2)
  assert.equal(view.isExpanded(entries[2], 2), true)
  assert.equal(view.isExpanded(entries[0], 0), false)
})

test('current execution expands, a later thought collapses it, completion collapses all', () => {
  const bubble = reactive(base())
  bubble.processes.push(proc('a')); captureChatTimeline(bubble)
  const view = mount(bubble)
  assert.equal(view.isExpanded(view.timeline.value[0], 0), true)
  view.toggleEntry(view.timeline.value[0], 0)
  assert.equal(view.isExpanded(view.timeline.value[0], 0), false, 'manual collapse during work')
  view.toggleEntry(view.timeline.value[0], 0)
  bubble.processes[0].items.push({ type: 'thinking', content: 'Review', status: 'thinking' }); captureChatTimeline(bubble)
  assert.equal(view.isExpanded(view.timeline.value[0], 0), false, 'phase change auto-collapses past work')
  bubble.isStreaming = false
  assert.ok(view.timeline.value.every((e, i) => !isTimelineEntryActive(e, i, view.timeline.value, false)))
})

test('plan updates retain position and render updated statuses, explicit clear removes it', () => {
  const bubble = reactive(base())
  bubble.planTodos = [{ content: 'Read', status: 'in_progress' }]; captureChatTimeline(bubble)
  bubble.content = 'Result'; captureChatTimeline(bubble)
  bubble.planTodos = [{ content: 'Read', status: 'completed' }]; captureChatTimeline(bubble)
  assert.equal(bubble.timeline[0].data[0].status, 'completed')
  assert.deepEqual(visibleChatTimeline(bubble).map(e => e.type), ['plan', 'text'])
  bubble.planTodos = []; captureChatTimeline(bubble)
  assert.deepEqual(visibleChatTimeline(bubble).map(e => e.type), ['text'])
})
