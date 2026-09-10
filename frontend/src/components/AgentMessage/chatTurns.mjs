// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const PLAN_TYPES = ['task_list', 'plan', 'implementation_plan']

// History stores tool calls, while live plan_update events carry a separate snapshot.
// Recover JSON arguments only; never execute model-generated tool expressions.
export function recoverPlanTodos(processes = []) {
  let snapshot = null
  for (const process of processes) {
    for (const item of process.items || []) {
      if (item.type !== 'tool' || item.status !== 'success') continue
      const match = (item.code || '').trim().match(/^(?:\w+\.)?todo_write\(([\s\S]*)\)$/)
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
      turn = { key: String(bubble.id ?? index), user: null, assistants: [], label: '', todos: turn?.todos || [], thoughts: [], processes: [], answerIndex: -1, attentionIndex: -1, status: 'idle' }
      turns.push(turn)
    }
    if (bubble.role === 'USER') {
      turn.user = { bubble, index }
      turn.label = (bubble.displayContent || bubble.content || '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim()
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
    if (actionable && (last.bubble.question && !last.bubble.question.answered || (last.bubble.artifacts || []).some(a => PLAN_TYPES.includes(a.type) && a.status === 'draft'))) latest.attentionIndex = last.index
    if (latest.status !== 'queued') latest.status = (runStatus || (isStreaming ? 'RUNNING' : actionable && last.bubble.question && !last.bubble.question.answered ? 'AWAITING_INPUT' : latest.attentionIndex >= 0 ? 'AWAITING_APPROVAL' : 'IDLE')).toLowerCase()
  }
  return turns
}
