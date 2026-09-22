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

// 提问那一行的摘要：剥标签 + 压空白 + 截断。用户气泡的正文落定后就不再变，而这个函数
// 每个 token 都会被整条会话跑一遍（200 轮 = 200 次全串正则），所以按气泡缓存一份
// （dev-board#811 K31）。WeakMap 的键是 Vue 给同一个原始对象的那个代理，身份稳定；
// 气泡被丢弃时条目自己消失。src 一并存下来是因为回退/重新生成会就地改写正文。
const labelCache = new WeakMap()
function turnLabel(bubble) {
  const source = bubble.displayContent || bubble.content || ''
  const cached = labelCache.get(bubble)
  if (cached && cached.source === source) return cached.label
  const label = source.replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim().slice(0, 120)
  labelCache.set(bubble, { source, label })
  return label
}

// 没有清单的轮次共用同一个空数组：todos 按身份比较（它是 planTodos 那个响应式数组本身），
// 每次现造一个 [] 会让第一轮永远命中不了复用。
const NO_TODOS = Object.freeze([])

const sameList = (a, b, equal) => a.length === b.length && a.every((item, i) => equal(item, b[i]))
const sameMember = (a, b) => a.bubble === b.bubble && a.index === b.index

/**
 * 两个 turn 对象是不是「同一轮、且渲染出来一模一样」。用于复用上一次的对象身份
 * （见 buildChatTurns 的 cache 参数）。
 *
 * **只比派生字段与成员清单，不比气泡内部的正文。** 气泡本身是响应式代理，
 * ConversationTurn 读 `turn.user.bubble.content` 时会自己登记依赖、正文一变它自己重渲；
 * 这里要保证的只是「派生出来的东西没变」与「这一轮还是这几条气泡」。
 */
function sameTurn(a, b) {
  return a.key === b.key
    && a.label === b.label
    && a.answerIndex === b.answerIndex
    && a.attentionIndex === b.attentionIndex
    && a.attentionKind === b.attentionKind
    && a.status === b.status
    && a.todos === b.todos
    && (a.user === b.user || (!!a.user && !!b.user && sameMember(a.user, b.user)))
    && sameList(a.assistants, b.assistants, sameMember)
    && sameList(a.processes, b.processes, (x, y) => x === y)
    && sameList(a.thoughts, b.thoughts, (x, y) => x.key === y.key && x.content === y.content && x.status === y.status)
}

/**
 * @param cache 调用方持有的一个普通对象（**不要放进响应式数据**）。给了它，内容没变的
 *   轮次会原样返回上一次那个对象——这正是 ConversationTurn 能在流式期跳过 200 棵历史
 *   子树的前提：props 身份不变，Vue 才会整棵跳过。不给就是原来的纯函数行为。
 */
export function buildChatTurns(bubbles, { isStreaming = false, runStatus = null, cache = null } = {}) {
  const turns = []
  let turn
  bubbles.forEach((bubble, index) => {
    if (bubble.role === 'USER' || !turn) {
      turn = { key: String(bubble.id ?? index), user: null, assistants: [], label: '', todos: turn?.todos || NO_TODOS, thoughts: [], processes: [], answerIndex: -1, attentionIndex: -1, attentionKind: '', status: 'idle' }
      turns.push(turn)
    }
    if (bubble.role === 'USER') {
      turn.user = { bubble, index }
      turn.label = turnLabel(bubble)
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
  if (!cache) return turns
  const previous = cache.byKey || new Map()
  const next = new Map()
  const stable = turns.map(fresh => {
    const old = previous.get(fresh.key)
    const kept = old && sameTurn(old, fresh) ? old : fresh
    next.set(fresh.key, kept)
    return kept
  })
  cache.byKey = next
  return stable
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
