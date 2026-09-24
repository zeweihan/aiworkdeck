// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 事项输入框 `@` 关联的纯文本逻辑（dev-board#896）：查询词提取、选中后的文本替换、成员匹配。
// MentionInput.vue 与 AgentMessage/MentionPicker.vue 共用；零依赖，node --test 直接导入。

/** 查询词最长多少字：超过就当作不是在 @ 人/文件（普通文字里出现的 @ 不该一直挂着浮层）。 */
export const MENTION_QUERY_MAX = 40

/**
 * 光标前那段 `@xxx`：`@` 前面必须是行首或空白，`@` 到光标之间不能有空白。
 * 返回 { query, start }（start 是 `@` 的下标），不在收集状态返回 null。
 */
export function extractMentionQuery(text, caret) {
  const s = String(text == null ? '' : text)
  const end = Math.max(0, Math.min(typeof caret === 'number' ? caret : s.length, s.length))
  const before = s.slice(0, end)
  const at = before.lastIndexOf('@')
  if (at < 0) return null
  const query = before.slice(at + 1)
  if (/\s/.test(query) || query.length > MENTION_QUERY_MAX) return null
  if (at > 0 && !/\s/.test(before[at - 1])) return null
  return { query, start: at }
}

/**
 * 把 [start, caret) 的 `@查询` 换成 `@名字 `，返回 { text, caret }（新光标落在补的空格之后）。
 */
export function applyMention(text, start, caret, name) {
  const s = String(text == null ? '' : text)
  const insert = '@' + String(name || '') + ' '
  const head = s.slice(0, start)
  let tail = s.slice(caret)
  // 后面本来就是空格的话不再多补一个
  if (tail.startsWith(' ')) tail = tail.slice(1)
  return { text: head + insert + tail, caret: head.length + insert.length }
}

/** 成员展示名：displayName 优先，没有用 username。 */
export function memberName(member) {
  if (!member) return ''
  return String(member.displayName || member.username || '')
}

/** 成员匹配：displayName / username 前缀命中（忽略大小写），空查询返回全部；最多 max 条。 */
export function matchMembers(members, query, max = 8) {
  const list = Array.isArray(members) ? members : []
  const q = String(query || '').trim().toLowerCase()
  const out = []
  for (const m of list) {
    if (!m) continue
    if (q) {
      const a = String(m.displayName || '').toLowerCase()
      const b = String(m.username || '').toLowerCase()
      if (!a.startsWith(q) && !b.startsWith(q)) continue
    }
    out.push(m)
    if (out.length >= max) break
  }
  return out
}
