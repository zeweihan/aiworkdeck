// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const PLAN_TYPES = ['task_list', 'plan', 'implementation_plan']

const TODO_WRITE_CALL = /^(?:\w+\.)?todo_write\(([\s\S]*)\)$/

// The single predicate for "this execution item is where the plan snapshot came from".
// Callers that place the recovered plan in the timeline must use it too, or the card
// lands next to a different call than the one it was recovered from.
export function isPlanSnapshotCall(item) {
  return item?.type === 'tool' && item.status === 'success' && TODO_WRITE_CALL.test((item.code || '').trim())
}

// History stores tool calls, while live plan_update events carry a separate snapshot.
// Recover JSON arguments only; never execute model-generated tool expressions.
export function recoverPlanTodos(processes = []) {
  let snapshot = null
  for (const process of processes) {
    for (const item of process.items || []) {
      if (!isPlanSnapshotCall(item)) continue
      const match = (item.code || '').trim().match(TODO_WRITE_CALL)
      if (!match) continue
      try {
        const args = JSON.parse(match[1])
        const todos = typeof args.todos === 'string' ? JSON.parse(args.todos) : args.todos
        if (Array.isArray(todos) && todos.every(todo => todo && typeof todo.content === 'string' && ['pending', 'in_progress', 'completed', 'failed'].includes(todo.status))) snapshot = todos
      } catch { /* Older non-JSON calls remain available in the execution log. */ }
    }
  }
  return snapshot
}

export function buildChatTurns(bubbles, { isStreaming = false, runStatus = null } = {}) {
  const turns = []
  let turn
  bubbles.forEach((bubble, index) => {
    if (bubble.role === 'USER' || !turn) {
      turn = { key: String(bubble.id ?? index), user: null, assistants: [], label: '', todos: turn?.todos || [], thoughts: [], processes: [], answerIndex: -1, attentionIndex: -1, attentionKind: '', status: 'idle' }
      turns.push(turn)
    }
    if (bubble.role === 'USER') {
      turn.user = { bubble, index }
      turn.label = (bubble.displayContent || bubble.content || '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim().slice(0, 120)
      return
    }
    if (bubble.role !== 'ASSISTANT') return
    turn.assistants.push({ bubble, index })
    if (!turn.label) turn.label = bubble.title || ''
    turn.processes.push(...(bubble.processes || []))
    if (Array.isArray(bubble.planTodos)) turn.todos = bubble.planTodos
    if (bubble.content?.trim()) turn.answerIndex = index
    if (bubble.thinking?.content?.trim()) turn.thoughts.push({ ...bubble.thinking, key: `${index}-root` })
    for (const [pi, process] of (bubble.processes || []).entries()) {
      for (const [ii, item] of (process.items || []).entries()) {
        if (item.type === 'thinking' && item.content?.trim()) turn.thoughts.push({ ...item, key: `${index}-${pi}-${ii}` })
      }
    }
  })
  for (const entry of turns) {
    if (isStreaming && entry.assistants.some(({ bubble }) => bubble.isStreaming)) entry.status = 'running'
    if (!entry.assistants.length && entry.user?.bubble.receiptState === 'pending') entry.status = 'queued'
  }
  const latest = turns.at(-1)
  if (latest) {
    if (latest.status === 'queued' && runStatus) {
      const active = [...turns].reverse().find(entry => entry.assistants.length)
      if (active) active.status = runStatus.toLowerCase()
    }
    const last = latest.assistants.at(-1)
    const actionable = last && last.index === bubbles.length - 1 && !isStreaming && !last.bubble.isStreaming
    const pendingQuestion = actionable && last.bubble.question && !last.bubble.question.answered
    if (pendingQuestion || actionable && (last.bubble.artifacts || []).some(a => PLAN_TYPES.includes(a.type) && a.status === 'draft')) {
      latest.attentionIndex = last.index
      latest.attentionKind = pendingQuestion ? 'question' : 'approval'
    }
    if (latest.status !== 'queued') latest.status = (runStatus || (isStreaming ? 'RUNNING' : pendingQuestion ? 'AWAITING_INPUT' : latest.attentionIndex >= 0 ? 'AWAITING_APPROVAL' : 'IDLE')).toLowerCase()
  }
  return turns
}

// The locator bar must point only at cards the user can still act on, so it reads the
// attention judgment above instead of deriving a second one: a bar that jumps to an
// inert card is worse than no bar. Earliest first, because that is the one blocking
// the conversation; count so the bar can say how many are waiting.
export function pendingAttention(turns = []) {
  const waiting = turns.filter(turn => turn.attentionIndex >= 0)
  if (!waiting.length) return null
  return { index: waiting[0].attentionIndex, kind: waiting[0].attentionKind, count: waiting.length }
}
