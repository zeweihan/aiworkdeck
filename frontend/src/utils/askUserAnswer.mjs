// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * ask_user（向用户提问，dev-board#868）前端侧的唯一口径：零依赖纯函数。
 *
 * 三件事：
 *   1. normalizeAskUserEvent —— SSE `ask_user` 事件载荷 → 气泡上的 question 对象；
 *   2. formatAskUserAnswer   —— 用户在问题卡上的选择 → 发给模型的消息（prompt）+ 给人看的一句（displayText）；
 *   3. parseAskUserAnswer    —— 历史回灌时从用户消息里读回「当时选了什么」，问题卡据此显示只读态。
 *
 * 回答消息的形状与后端 AskUserQuestion.ANSWER_TAG / isAnswerMessage 是一份契约：
 * 以 `<ask_user_answer id="…">` 开头，后端据此把末位提醒换成「按回答继续、不要再问」。
 * 改这里的格式必须同步改后端（ContextAssemblerAskUserTest 用的就是这个形状）。
 *
 * .mjs 是为了让 node --test 直接 import（frontend/package.json 没有 "type": "module"）。
 */

export const ASK_USER_KIND = 'ask_user'
export const ASK_USER_ANSWER_TAG = 'ask_user_answer'

/** 后端给属性值做的实体转义（AskUserQuestion.attr）在这里还原。 */
export const decodeAttr = (s) => String(s == null ? '' : s)
  .replace(/&quot;/g, '"')
  .replace(/&lt;/g, '<')
  .replace(/&gt;/g, '>')
  .replace(/&amp;/g, '&')

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
