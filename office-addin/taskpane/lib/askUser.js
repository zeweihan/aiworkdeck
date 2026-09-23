// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * ask_user（向用户提问，dev-board#868）任务窗格侧的纯逻辑：Office 与 WPS 两个家族、
 * 三个宿主共用这一份（窗格代码本来就是同一套）。
 *
 * **前半截是桌面端 `frontend/src/utils/askUserAnswer.mjs` 的逐字搬运**（normalizeAskUserEvent /
 * formatAskUserAnswer / parseAskUserAnswer / decodeAttr）。不直接 import 那份的理由：插件是独立的
 * Vite 工程（root = office-addin/），跨仓目录引用要放开 server.fs 且让 build:wps 的拷贝链多一个依赖；
 * 而回答消息的格式是与后端 `AskUserQuestion.isAnswerMessage` 共享的契约——两份一旦漂移，
 * 插件发出去的回答后端认不出来（模型会再问一遍），且不报错。所以两份的对拍写成了用例
 * （askUser.test.js 的「与桌面端同一份口径」，桌面端那份存在时逐项比较输出）。
 * **改格式三处一起改**：后端 AskUserQuestion、桌面端 askUserAnswer.mjs、这里。
 *
 * 后半截是插件自己的：解析器产物 → 气泡上的 question 模型、问题卡的交互判定。
 */

export const ASK_USER_KIND = 'ask_user'
export const ASK_USER_ANSWER_TAG = 'ask_user_answer'

/** 后端给属性值做的实体转义（AskUserQuestion.attr）在这里还原。 */
export const decodeAttr = (s) => String(s == null ? '' : s)
  .replace(/&quot;/g, '"')
  .replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>')
  .replace(/&amp;/g, '&')

/**
 * 后端对 ask_user 的正文与选项做了协议标签中和（AgentTagProtocol.escape：`<final>` → `&lt;final>`，
 * 只动协议清单里的标签），这里还原。清单与后端 AgentTagProtocol.TAGS 逐字一致。
 */
const PROTOCOL_TAGS = [
  'thinking', 'title', 'process', 'step', 'tool_code', 'tool_output',
  'walkthrough', 'final', 'question', 'option', 'artifact'
]
const ESCAPED_TAG_RE = new RegExp(`&lt;(\\/?)(${PROTOCOL_TAGS.join('|')})(\\s+[^>]*)?>`, 'g')
export const decodeProtocolTags = (text) => {
  if (!text) return ''
  const s = String(text)
  return s.indexOf('&lt;') < 0
    ? s
    : s.replace(ESCAPED_TAG_RE, (_m, slash, name, attrs) => `<${slash}${name}${attrs || ''}>`)
}

const str = (v) => (v == null ? '' : String(v))

/**
 * SSE `ask_user` 事件载荷 → 气泡上的 question。
 * 版本号只加不改：v 缺省按 1 处理；认不出来（没有 question 正文）返回 null，调用方什么都不做。
 */
export const normalizeAskUserEvent = (payload) => {
  if (!payload || typeof payload !== 'object') return null
  const text = str(payload.question).trim()
  if (!text) return null
  const options = []
  const descriptions = []
  for (const o of Array.isArray(payload.options) ? payload.options : []) {
    const label = str(o && typeof o === 'object' ? o.label : o).trim()
    if (!label) continue
    options.push(label)
    descriptions.push(str(o && typeof o === 'object' ? o.description : '').trim())
  }
  return {
    kind: ASK_USER_KIND,
    version: Number(payload.v) || 1,
    id: str(payload.id),
    header: str(payload.header).trim(),
    text,
    options,
    descriptions,
    multiSelect: payload.multiSelect === true && options.length > 0,
    answered: false,
    answer: null
  }
}

// 放进回答消息的值不能长出标签：用户在「其他」里打一个 </ask_user_answer> 就能把回答截断
const safe = (s) => str(s).replace(/</g, '＜').replace(/>/g, '＞')
const oneLine = (s) => safe(s).replace(/\s*\n\s*/g, ' ').trim()
const safeId = (s) => str(s).replace(/[^A-Za-z0-9_-]/g, '')

/**
 * 用户的选择 → 发给模型的消息与显示文本。什么都没选、也没写「其他」时返回 null（不该发）。
 *
 * @param {{id?: string, question?: string, selected?: string[], other?: string}} answer
 * @param {{english?: boolean}} [opts]
 * @returns {{prompt: string, displayText: string} | null}
 */
export const formatAskUserAnswer = (answer, opts = {}) => {
  const selected = (Array.isArray(answer && answer.selected) ? answer.selected : [])
    .map(oneLine).filter(Boolean)
  const other = safe(answer && answer.other).trim()
  if (!selected.length && !other) return null
  const lines = [`<${ASK_USER_ANSWER_TAG} id="${safeId(answer && answer.id)}">`]
  const question = oneLine(answer && answer.question)
  if (question) lines.push(`Question: ${question}`)
  if (selected.length) {
    lines.push('Selected:')
    for (const s of selected) lines.push(`- ${s}`)
  }
  if (other) lines.push(`Other: ${other}`)
  lines.push(`</${ASK_USER_ANSWER_TAG}>`)
  const sep = opts.english ? '; ' : '；'
  const displayText = [...selected, other].filter(Boolean).join(sep)
  return { prompt: lines.join('\n'), displayText }
}

const ANSWER_RE = new RegExp(`^\\s*<${ASK_USER_ANSWER_TAG}\\s+id="([^"]*)"\\s*>([\\s\\S]*?)(?:</${ASK_USER_ANSWER_TAG}>|$)`)

/**
 * 用户消息 → 当时的选择。不是回答消息返回 null（只认开头，与后端 isAnswerMessage 同口径）。
 * @returns {{id: string, selected: string[], other: string} | null}
 */
export const parseAskUserAnswer = (content) => {
  const m = ANSWER_RE.exec(str(content))
  if (!m) return null
  const body = m[2]
  const selected = []
  let other = ''
  let inSelected = false
  const lines = body.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (/^Other:\s?/.test(line)) {
      other = [line.replace(/^Other:\s?/, ''), ...lines.slice(i + 1)].join('\n').trim()
      break
    }
    if (/^Selected:\s*$/.test(line)) { inSelected = true; continue }
    if (inSelected && /^- /.test(line)) { selected.push(line.slice(2).trim()); continue }
    if (inSelected && line.trim()) inSelected = false
  }
  return { id: m[1], selected, other }
}

// ==================== 以下为插件侧 ====================

/**
 * 标签流解析器（sse.js createTagStreamParser）交上来的一问 → 气泡上的 question 模型。
 *
 * - ask_user（带 kind="ask_user" 的 <question>）：保留 id/header/多选/选项说明，**无选项也保留**
 *   （开放式提问的卡片里直接给文本框作答）。正文与选项文字按后端的协议标签中和还原。
 * - 旧的 <question>（模型自己写的反问标签）：形状与引入 ask_user 之前逐字相同——
 *   无选项返回 null（在主输入框作答），有选项只挂 {options, answered}。
 */
export function questionFromParsed(q) {
  if (!q) return null
  if (q.kind !== ASK_USER_KIND) {
    return q.options && q.options.length ? { options: q.options, answered: false } : null
  }
  const clean = (t) => decodeProtocolTags(t || '').trim()
  // 选项与说明按下标成对，过滤空选项时必须一起过滤
  const pairs = (q.options || []).map((o, i) => [clean(o), ((q.descriptions || [])[i] || '').trim()])
    .filter(([o]) => o.length > 0)
  const options = pairs.map(([o]) => o)
  return {
    kind: ASK_USER_KIND,
    id: q.id || '',
    header: q.header || '',
    text: clean(q.text),
    options,
    descriptions: pairs.map(([, d]) => d),
    multiSelect: !!q.multiSelect && options.length > 0,
    answered: false,
    answer: null
  }
}

/** 是不是 ask_user 的问题卡（旧 <question> 的 question 模型没有 kind） */
export function isAskUserQuestion(q) {
  return !!(q && q.kind === ASK_USER_KIND)
}

/**
 * 回答消息在用户气泡上显示什么：优先后端落库的 displayContent；旧后端/显示文本缺失时
 * 从回答消息里读回所选各项拼一句——绝不把 `<ask_user_answer id=…>` 原文给人看。
 */
export function answerDisplayText(parsed, english = false) {
  if (!parsed) return ''
  return [...(parsed.selected || []), parsed.other || ''].filter(Boolean).join(english ? '; ' : '；')
}

/**
 * 问题卡上点了第 i 个选项之后的选中集合。
 * 单选：替换成只有这一项；多选：切换这一项（保持点选顺序）。
 */
export function toggleChoice(chosen, i, multiSelect) {
  const cur = Array.isArray(chosen) ? chosen : []
  if (!multiSelect) return [i]
  return cur.includes(i) ? cur.filter((x) => x !== i) : [...cur, i]
}

/**
 * 单选且没打开「其他」时点一下就作答；多选、打开了「其他」或开放式提问才需要一个明确的提交。
 */
export function needsSubmit(question, otherOpen) {
  const options = (question && question.options) || []
  return !!(question && question.multiSelect) || !!otherOpen || !options.length
}

/**
 * 问题卡上的当前状态 → 交给会话层的结构化回答（形状即 formatAskUserAnswer 的入参）。
 * 什么都没选、「其他」也是空的返回 null（不该发）。「其他」没打开时框里残留的文字不算数。
 */
export function collectAskUserAnswer(question, { chosen = [], otherOpen = false, otherText = '' } = {}) {
  if (!question) return null
  const options = question.options || []
  const other = (otherOpen || !options.length) ? String(otherText || '').trim() : ''
  const selected = chosen.map((i) => options[i]).filter(Boolean)
  if (!selected.length && !other) return null
  return { kind: ASK_USER_KIND, id: question.id || '', question: question.text || '', selected, other }
}

/**
 * 历史回灌后把每一问与紧跟着的那条用户回答对上（与桌面端 markAnsweredQuestions 同口径）：
 * 后面还有用户消息 = 答过了；那条用户消息是结构化回答且 id 对得上时，读回当时选了什么给只读态高亮。
 * id 对不上（用户没点卡、自己在输入框打了一句）只标已作答，不高亮。
 *
 * @param {Array<{role: string, question?: object, askAnswer?: object}>} list 插件消息模型
 */
export function linkAskUserAnswers(list) {
  for (let i = 0; i < list.length; i++) {
    const q = list[i] && list[i].question
    if (!isAskUserQuestion(q)) continue
    let nextUser = null
    for (let j = i + 1; j < list.length; j++) {
      if (list[j].role === 'user') { nextUser = list[j]; break }
    }
    if (!nextUser) continue
    q.answered = true
    const parsed = nextUser.askAnswer
    if (parsed && (!q.id || parsed.id === q.id) && !q.answer) {
      q.answer = { selected: parsed.selected || [], other: parsed.other || '' }
    }
  }
  return list
}
