// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 提交历史列表的行合并、按日分组与事件行文案（dev-board#624）——**纯函数，不许 import**。
 *
 * 列表里有两类行：
 *  · 版本行：本机 `/version/history` 的 entries（含 origin/master 上本机还没整合的 remote 行）；
 *  · 事件行：案件库 `/cloud/projects/{id}/events`（谁交了稿 / 谁签出 / 谁取回 / 谁加了人）。
 * 两类按时间倒序合成一条流，再按日分组——这是 IDE 里看 git log 的读法，
 * 「谁在什么时候动了这份案卷」和「动出了哪一版」本来就该在一条时间线上。
 *
 * `{谁}` 的三态（设计稿 §3.2）：
 *  · actor 是本人 **且** 事件的设备令牌就是本机这枚 → 「你」；
 *  · actor 是本人、设备不是本机 → 「你（{设备名}）」；设备名取不到就退回「你（另一台电脑）」；
 *  · 其他人 → 展示名。**永远不显示 username**（identity 契约，tests/identity/display-name-only）。
 */

const s = (v) => (v == null ? '' : String(v))

/** 本地日期键 YYYY-MM-DD（按用户所在时区分日，不用 UTC——跨零点会串到前一天）。 */
export function dayKeyOf(when) {
  const d = new Date(when)
  if (isNaN(d.getTime())) return ''
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}

const timeOf = (v) => {
  const t = new Date(v).getTime()
  return isNaN(t) ? 0 : t
}

/** actor 三态：'self' | 'selfOtherDevice' | 'other' */
export function actorKind(event, ctx = {}) {
  const actorId = s(event && event.actor && event.actor.userId)
  const selfId = s(ctx.selfUserId)
  if (!actorId || !selfId || actorId !== selfId) return 'other'
  const tokenId = s(event && event.device && event.device.tokenId)
  const selfToken = s(ctx.selfTokenId)
  if (tokenId && selfToken && tokenId === selfToken) return 'self'
  if (!tokenId) return 'self' // 没有设备信息（成员变更一类）就当是本人本机，不编一台电脑出来
  return 'selfOtherDevice'
}

/** 「{谁}」那一段。ctx: {selfUserId, selfTokenId} */
export function actorText(t, event, ctx = {}) {
  const kind = actorKind(event, ctx)
  if (kind === 'self') return t('version.actorYou')
  if (kind === 'selfOtherDevice') {
    const name = s(event && event.device && event.device.name).trim()
    return name
      ? t('version.actorYouOnDevice', { device: name })
      : t('version.actorYouOtherDevice')
  }
  const display = s(event && event.actor && event.actor.displayName).trim()
  return display || t('version.unnamedColleague')
}

/** 被操作的那个人（成员三事件）。同样只认展示名。 */
function targetText(t, event) {
  const display = s(event && event.target && event.target.displayName).trim()
  return display || t('version.unnamedColleague')
}

/**
 * 一条事件行的整句话。
 * @param {Function} t   $t
 * @param {Object} event 一条 collab event
 * @param {Object} ctx   { selfUserId, selfTokenId, roleLabel }
 */
export function eventRowText(t, event, ctx = {}) {
  const who = actorText(t, event, ctx)
  const kind = s(event && event.kind).toUpperCase()
  switch (kind) {
    case 'PUSH': {
      const n = Number(event.commitCount) || 0
      return n > 0
        ? t('version.eventPushedWithCount', { who, count: n })
        : t('version.eventPushed', { who })
    }
    case 'CHECKOUT': return t('version.eventCheckedOut', { who })
    case 'PULLED': return t('version.eventPulled', { who })
    case 'SHARED': return t('version.eventShared', { who })
    case 'MEMBER_ADDED': return t('version.eventMemberAdded', { who, name: targetText(t, event) })
    case 'MEMBER_REMOVED': return t('version.eventMemberRemoved', { who, name: targetText(t, event) })
    case 'MEMBER_ROLE_CHANGED': {
      const roleLabel = typeof ctx.roleLabel === 'function' ? ctx.roleLabel : (r) => s(r)
      const role = roleLabel(s(event.detail && event.detail.role) || s(event.role))
      return t('version.eventMemberRoleChanged', { who, name: targetText(t, event), role })
    }
    default: return ''
  }
}

/**
 * 版本行 + 事件行合成一条倒序流。
 * 每行 { kind:'version'|'event', key, at(ms), entry?|event? }。
 * key 用来做 :key 与键盘选中，两类行之间不会撞（版本用 sha，事件用 `ev-<id>`）。
 */
export function mergeHistoryRows(entries, events) {
  const rows = []
  for (const e of Array.isArray(entries) ? entries : []) {
    if (!e || !e.sha) continue
    rows.push({ kind: 'version', key: e.sha, at: timeOf(e.when), entry: e })
  }
  for (const ev of Array.isArray(events) ? events : []) {
    if (!ev || ev.id == null) continue
    rows.push({ kind: 'event', key: `ev-${ev.id}`, at: timeOf(ev.createdAt), event: ev })
  }
  // 同一时刻时版本行排在事件行前面：事件（「交了稿 · 2 版」）是对刚刚那几版的旁白，
  // 让它挨在被它描述的那几行下面读起来才顺。
  rows.sort((a, b) => b.at - a.at || (a.kind === b.kind ? 0 : (a.kind === 'version' ? -1 : 1)))
  return rows
}

/** 按日分组（保持倒序）。返回 [{ day, at, rows }]。 */
export function groupRowsByDay(rows) {
  const out = []
  let current = null
  for (const r of rows || []) {
    const day = dayKeyOf(r.at)
    if (!current || current.day !== day) {
      current = { day, at: r.at, rows: [] }
      out.push(current)
    }
    current.rows.push(r)
  }
  return out
}

/**
 * 右侧「这两版之间的改动」窗格此刻该显示什么：
 * `'idle'`（没选够两版）| `'loading'`（正在对比）| `'empty'`（真的没有改动）| `'list'`。
 *
 * `loaded` 这一位是关键：从选中第二行到清单回来之间，窗格既不在 loading 也没有清单，
 * 按「这一版没有文件改动」渲染的话，律师看到的是一句马上会被推翻的结论
 * （手工走查 2026-09-14）。没拉过就一律先说「正在对比」。
 */
export function comparePaneState({ selectedCount = 0, loading = false, loaded = false, changes = [] } = {}) {
  if (selectedCount !== 2) return 'idle'
  if (loading || !loaded) return 'loading'
  return (changes && changes.length) ? 'list' : 'empty'
}
