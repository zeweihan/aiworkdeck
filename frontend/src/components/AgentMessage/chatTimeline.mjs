// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { toRaw } from 'vue'

// Record at parser boundaries, not during rendering: later text must never move above earlier tools.
const cursors = new WeakMap()
export function captureChatTimeline(bubble) {
  if (!bubble) return
  if (!bubble.timeline) bubble.timeline = []
  let cursor = cursors.get(toRaw(bubble))
  if (!cursor || cursor.timeline !== toRaw(bubble.timeline)) {
    cursor = { timeline: toRaw(bubble.timeline), counts: new WeakMap(), thinking: null, textEnd: 0, artifacts: 0, title: false, plan: null }
    cursors.set(toRaw(bubble), cursor)
  }
  const entries = bubble.timeline
  const thought = bubble.thinking
  if (thought && (thought.content || thought.status === 'thinking') && cursor.thinking !== toRaw(thought)) {
    entries.push({ type: 'thinking', data: thought })
    cursor.thinking = toRaw(thought)
  }
  if (bubble.title && !cursor.title) {
    entries.push({ type: 'title' })
    cursor.title = true
  }
  for (const proc of bubble.processes || []) {
    const items = proc.items || []
    for (let i = cursor.counts.get(toRaw(proc)) || 0; i < items.length; i++) {
      const item = items[i]
      if (item.type === 'thinking') entries.push({ type: 'thinking', data: item })
      else {
        let last = entries.at(-1)
        if (last?.type !== 'process' || last.source !== proc) {
          last = { type: 'process', source: proc, data: { ...proc, items: [] } }
          entries.push(last)
        }
        last.data.items.push(item)
      }
    }
    cursor.counts.set(toRaw(proc), items.length)
  }
  if (bubble.planTodos?.length) {
    if (cursor.plan === null) {
      cursor.plan = entries.findIndex(entry => entry.type === 'plan')
      if (cursor.plan < 0) {
        cursor.plan = entries.length
        entries.push({ type: 'plan', data: bubble.planTodos })
      }
    }
    entries[cursor.plan].data = bubble.planTodos
  } else if (cursor.plan !== null) entries[cursor.plan].data = []
  for (let i = cursor.artifacts; i < (bubble.artifacts || []).length; i++) {
    entries.push({ type: 'artifact', data: bubble.artifacts[i] })
  }
  cursor.artifacts = (bubble.artifacts || []).length
  const end = (bubble.content || '').length
  if (end > cursor.textEnd) {
    let last = entries.at(-1)
    if (last?.type !== 'text') {
      last = { type: 'text', start: cursor.textEnd, end }
      entries.push(last)
    }
    last.end = end
    cursor.textEnd = end
  }
}

export function visibleChatTimeline(bubble) {
  // Non-streaming system confirmations and older in-memory bubbles have no parser timeline.
  const entries = bubble.timeline || [
    ...(bubble.thinking?.content || bubble.isStreaming && bubble.thinking?.status === 'thinking' ? [{ type: 'thinking', data: bubble.thinking }] : []),
    ...(bubble.title ? [{ type: 'title' }] : []),
    ...(bubble.planTodos?.length ? [{ type: 'plan', data: bubble.planTodos }] : []),
    ...(bubble.processes || []).map(data => ({ type: 'process', data })),
    ...(bubble.artifacts || []).map(data => ({ type: 'artifact', data })),
    ...(bubble.content ? [{ type: 'text', start: 0, end: bubble.content.length }] : [])
  ]
  const result = []
  let textEnd = 0
  entries.forEach((entry, index) => {
    if (entry.type === 'thinking' && !entry.data.content && !(bubble.isStreaming && entry.data.status === 'thinking')) return
    if (entry.type === 'plan' && !entry.data.length) return
    if (entry.type === 'text') {
      textEnd = entry.end
      if (!bubble.content.slice(entry.start, entry.end).trim()) return
    }
    if (entry.type === 'process') {
      let group = result.at(-1)
      if (group?.type !== 'execution') {
        group = { type: 'execution', key: index, procs: [] }
        result.push(group)
      }
      group.procs.push(entry.data)
    } else result.push({ ...entry, key: index })
  })
  // Errors and editor status messages also append to content outside the text parser.
  if ((bubble.content || '').length > textEnd) result.push({ type: 'text', key: 'tail', start: textEnd, end: bubble.content.length })
  return result
}

export function isTimelineEntryActive(entry, index, entries, streaming) {
  if (!streaming) return false
  const latest = entries.findLastIndex(item => item.type !== 'plan' && item.type !== 'title')
  if (entry.type !== 'plan' && index !== latest) return false
  if (entry.type === 'thinking') return entry.data.status === 'thinking'
  if (entry.type === 'plan') return latest < 0 && entry.data.some(todo => todo.status === 'in_progress')
  return entry.type === 'execution' && entry.procs.some(proc => (proc.items || []).some(item => ['loading', 'doing', 'thinking'].includes(item.status)))
}
