// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 事项（任务/日程）的共享判定与展示逻辑（唯一出处）。
// spec: docs/superpowers/specs/2026-09-25-task-calendar-redesign.md 第二节。
// 消费方：TaskRow / TaskDialog / utils/taskStore / utils/taskReminders、日程页、
// 工作台日程面板、概览页 TaskSchedule、设置页「我的待办」。
// 改「已完成」判定、类型配色、提醒时刻、徽标文案、分组口径，只改这里。
//
// **本文件刻意零依赖**（不 import @/ 别名、Vue、uni、i18n）：tests/calendar 与
// tests/project-home 都用 node --test 直接导入它。需要文案的函数一律收 translate 参数
// （组件里传 this.$t，非组件传 @/i18n 的 t）。

/** status 是否为已完成（后端契约：大写 OPEN/DONE）。 */
export function isDone(task) {
  return String((task && task.status) || '').toUpperCase() === 'DONE'
}

/** 本地时区的 YYYY-MM-DD（不能用 toISOString：东八区零点前后会差一天）。 */
export function localDateKey(date = new Date()) {
  const y = date.getFullYear()
  const m = String(date.getMonth() + 1).padStart(2, '0')
  const d = String(date.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

/** YYYY-MM-DD 加减整天，返回 YYYY-MM-DD（按本地日历推，不受夏令时影响）。 */
export function addDaysKey(dateKey, days) {
  const d = new Date(dateKey + 'T00:00:00')
  d.setDate(d.getDate() + days)
  return localDateKey(d)
}

/**
 * 距截止日的整天数：0=今天、正=还剩 N 天、负=已逾期 N 天。
 * dueDate 缺失或不可解析返回 null（脏数据不产出 NaN 徽标）。now 仅供测试注入。
 */
export function daysUntil(dueDate, now = new Date()) {
  if (!dueDate) return null
  const due = new Date(dueDate + 'T00:00:00')
  if (Number.isNaN(due.getTime())) return null
  const today = new Date(now.getTime())
  today.setHours(0, 0, 0, 0)
  return Math.round((due.getTime() - today.getTime()) / 86400000)
}

/** 'HH:mm' 或 ''（后端偶尔回 'HH:mm:ss'，统一截到分钟）。 */
export function timeOf(task) {
  const t = task && task.dueTime
  return t ? String(t).slice(0, 5) : ''
}

// ==================== 类型 ====================

export const TASK_TYPES = ['DEADLINE', 'HEARING', 'MEETING', 'TODO', 'OTHER']

// 24x24 线性图标 path（与 config/icons.js 同一套画法：stroke currentColor）。
const TYPE_ICONS = {
  // 沙漏：截止日
  DEADLINE: ['M6 3h12', 'M6 21h12', 'M7 3c0 5 10 5 10 9s-10 4-10 9', 'M17 3c0 5-10 5-10 9s10 4 10 9'],
  // 法院：开庭
  HEARING: ['M3 21h18', 'M6 17v-6', 'M10 17v-6', 'M14 17v-6', 'M18 17v-6', 'M4 8l8-5 8 5H4Z'],
  // 两个人：会议
  MEETING: ['M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z', 'M2 21a7 7 0 0 1 14 0', 'M16 3.5a4 4 0 0 1 0 7.5', 'M18 14.5a7 7 0 0 1 4 6.5'],
  // 勾选框：待办
  TODO: ['M9 11l3 3 8-8', 'M20 12v6a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h9'],
  // 圆点：其他
  OTHER: ['M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18Z', 'M12 12h.01'],
}

// 五种类型五个色相，全部是 App.vue 里的 --awd-* 令牌（浅/深两套主题自动跟随）。
// 截止日=品牌绿、开庭=朱砂红、会议=竹月青、待办=灰、其他=浅褐。
// 竹月青对白底只有 2.6:1，**只能做色条/描边，不能承载文字**（配色体系红线）。
const TYPE_STYLE = {
  DEADLINE: { color: 'var(--awd-accent)', soft: 'var(--awd-accent-soft)' },
  HEARING: { color: 'var(--awd-danger)', soft: 'var(--awd-danger-soft)' },
  MEETING: { color: 'var(--awd-mint)', soft: 'var(--awd-info-soft)' },
  TODO: { color: 'var(--awd-text-3)', soft: 'var(--awd-surface-2)' },
  OTHER: { color: 'var(--awd-gold-line)', soft: 'var(--awd-gold-soft)' },
}

const TYPE_LABEL_KEYS = {
  DEADLINE: 'calendar.typeDeadline',
  HEARING: 'calendar.typeHearing',
  MEETING: 'calendar.typeMeeting',
  TODO: 'calendar.typeTodo',
  OTHER: 'calendar.typeOther',
}

/** 归一化类型：空值与未知值一律按 DEADLINE（旧行没有 type 列）。 */
export function normalizeType(type) {
  const k = String(type || '').toUpperCase()
  return TASK_TYPES.includes(k) ? k : 'DEADLINE'
}

/**
 * 类型展示元信息：{ key, labelKey, label, color, soft, icon }。
 * label 需要 translate；不传时回 labelKey（便于测试断言键名）。
 * color 是色条/图标色，soft 是选中芯片的浅底。
 */
export function typeMeta(type, translate) {
  const key = normalizeType(type)
  const labelKey = TYPE_LABEL_KEYS[key]
  return {
    key,
    labelKey,
    label: typeof translate === 'function' ? translate(labelKey) : labelKey,
    color: TYPE_STYLE[key].color,
    soft: TYPE_STYLE[key].soft,
    icon: TYPE_ICONS[key],
  }
}

/** priority 是否为重要（后端 NORMAL/HIGH，空值按 NORMAL）。 */
export function isHigh(task) {
  return String((task && task.priority) || '').toUpperCase() === 'HIGH'
}

// ==================== 提醒 ====================

/** 提前多少分钟：不提醒 / 准时 / 30 分 / 1 小时 / 1 天 / 3 天 / 1 周。 */
export const REMIND_OPTIONS = [null, 0, 30, 60, 1440, 4320, 10080]

const REMIND_LABEL_KEYS = {
  null: 'calendar.remindNone',
  0: 'calendar.remindAtTime',
  30: 'calendar.remind30m',
  60: 'calendar.remind1h',
  1440: 'calendar.remind1d',
  4320: 'calendar.remind3d',
  10080: 'calendar.remind1w',
}

/** 全天事项（无 dueTime）隐藏「提前 30 分 / 1 小时」——以 09:00 为基准时这两档没意义。 */
export function remindOptionsFor(hasTime) {
  return hasTime ? REMIND_OPTIONS.slice() : REMIND_OPTIONS.filter((v) => v !== 30 && v !== 60)
}

/**
 * 新建事项的默认提醒：截止日、开庭默认提前 1 天（1440 分钟），其余类型默认不提醒。
 * 只用于 create 模式、且用户没手动改过提醒时（TaskDialog 切类型会跟着变）。
 */
export function defaultRemindFor(type) {
  const k = normalizeType(type)
  return k === 'DEADLINE' || k === 'HEARING' ? 1440 : null
}

export function remindLabelKey(value) {
  const k = value === undefined || value === null ? 'null' : String(value)
  return REMIND_LABEL_KEYS[k] || 'calendar.remindNone'
}

/**
 * 提醒时刻：dueTime 有值取 dueDate+dueTime，否则 dueDate 09:00，再减 remindBefore 分钟。
 * remindBefore 为 null/undefined 或没有 dueDate → null（不提醒）。
 */
export function remindAtOf(task) {
  if (!task || task.remindBefore === null || task.remindBefore === undefined || task.remindBefore === '') return null
  if (!task.dueDate) return null
  const minutes = Number(task.remindBefore)
  if (!Number.isFinite(minutes) || minutes < 0) return null
  const base = new Date(`${task.dueDate}T${timeOf(task) || '09:00'}:00`)
  if (Number.isNaN(base.getTime())) return null
  return new Date(base.getTime() - minutes * 60000)
}

/**
 * 当前该弹提醒的事项（纯函数，taskReminders 的判定核心）。
 * - 只看未完成的；remindAt 已到且 notified[key] 不等于这次的 remindAt ISO（改过时间会重新提醒）。
 * - graceMs 之前就该提醒的不再弹（刚装上/长时间离线回来不会一次涌出几十条），
 *   但同样记为已处理，由 skipped 返回给调用方写进已通知表。
 * 返回 { due: [{task, key, at}], skipped: [{task, key, at}] }，key = uid 优先、否则 id。
 */
export function dueReminders(tasks, notified, now = new Date(), graceMs = 24 * 3600 * 1000) {
  const due = []
  const skipped = []
  const seen = notified || {}
  for (const task of Array.isArray(tasks) ? tasks : []) {
    if (!task || isDone(task)) continue
    const at = remindAtOf(task)
    if (!at || now.getTime() < at.getTime()) continue
    const key = String(task.uid || task.id)
    const iso = at.toISOString()
    if (seen[key] === iso) continue
    const entry = { task, key, at: iso }
    if (now.getTime() - at.getTime() > graceMs) skipped.push(entry)
    else due.push(entry)
  }
  return { due, skipped }
}

// ==================== 到期徽标 ====================

/**
 * 到期徽标。translate 传组件的 this.$t；now 仅供测试注入。
 * 返回 { text, kind, time }：
 *   text：逾期 N 天 / 今天 / 明天 / N 天后（2-6 天）/ M月D日（7 天及以后；跨年带年份）
 *   kind ∈ 'overdue' | 'today' | 'soon'(1-7 天) | 'later' | ''（无日期）
 *   time：'HH:mm' 或 ''
 * 各组件自行把 kind 映射到自己的 CSS 类（TaskRow：overdue 红底/today 绿底/soon 琥珀/later 灰）。
 */
export function dueBadge(task, translate, now = new Date()) {
  const d = daysUntil(task && task.dueDate, now)
  const time = timeOf(task)
  if (d === null) return { text: '', kind: '', time: '' }
  let text
  if (d < 0) text = translate('calendar.dueOverdueDays', { count: -d })
  else if (d === 0) text = translate('calendar.dueTodayShort')
  else if (d === 1) text = translate('calendar.dueTomorrow')
  else if (d < 7) text = translate('calendar.dueInDays', { count: d })
  else text = formatMonthDay(task.dueDate, translate, now)
  let kind
  if (d < 0) kind = 'overdue'
  else if (d === 0) kind = 'today'
  else if (d <= 7) kind = 'soon'
  else kind = 'later'
  return { text, kind, time }
}

/** 'YYYY-MM-DD' → 「M月D日」，不在今年的带年份。 */
export function formatMonthDay(dateKey, translate, now = new Date()) {
  const [y, m, d] = String(dateKey || '').split('-').map((n) => parseInt(n, 10))
  if (!y || !m || !d) return String(dateKey || '')
  if (y !== now.getFullYear()) return translate('calendar.dueYearMonthDay', { year: y, month: m, day: d })
  return translate('calendar.dueMonthDay', { month: m, day: d })
}

/** task → FullCalendar 的 event.start（有 dueTime 拼 T，无则全天）。 */
export function toEventStart(task) {
  const time = timeOf(task)
  return time ? `${task.dueDate}T${time}` : task.dueDate
}

// ==================== 分组与汇总 ====================

/** 截止日升序；同一天全天事项（无时间）在前，其余按时间；再按 id。无日期排最后。 */
export function compareDue(a, b) {
  const da = a.dueDate || ''
  const db = b.dueDate || ''
  if (da !== db) {
    if (!da) return 1
    if (!db) return -1
    return da < db ? -1 : 1
  }
  const ta = timeOf(a)
  const tb = timeOf(b)
  if (ta !== tb) return ta < tb ? -1 : 1
  return (a.id || 0) - (b.id || 0)
}

/**
 * 议程分组：{ overdue, today, week, later, done }。
 *   overdue：截止日早于今天且未完成
 *   today：今天
 *   week：明天起 6 天内（与 today 合起来就是「今天起 7 天」，同 /api/calendar/summary 的 week 口径）
 *   later：再往后，以及没有日期的（排在最后）
 *   done：已完成，按截止日倒序（最近的在前）
 * today 传 YYYY-MM-DD（默认本机今天），方便测试。
 */
export function groupByDue(tasks, today = localDateKey()) {
  const groups = { overdue: [], today: [], week: [], later: [], done: [] }
  const weekEnd = addDaysKey(today, 6)
  for (const t of Array.isArray(tasks) ? tasks : []) {
    if (!t) continue
    if (isDone(t)) groups.done.push(t)
    else if (!t.dueDate) groups.later.push(t)
    else if (t.dueDate < today) groups.overdue.push(t)
    else if (t.dueDate === today) groups.today.push(t)
    else if (t.dueDate <= weekEnd) groups.week.push(t)
    else groups.later.push(t)
  }
  groups.overdue.sort(compareDue)
  groups.today.sort(compareDue)
  groups.week.sort(compareDue)
  groups.later.sort(compareDue)
  groups.done.sort((a, b) => -compareDue(a, b))
  return groups
}

/**
 * 设置页「我的待办」的旧分组形状：{ overdue, today, upcoming, noDate, done }（dev-board#872）。
 * 实现从 utils/personalCollections.js 挪来（那边只 re-export），与 groupByDue 同一套排序；
 * 后续卡把设置页换成 groupByDue 后即可删掉。
 */
export function groupTodos(tasks, today = localDateKey()) {
  const groups = { overdue: [], today: [], upcoming: [], noDate: [], done: [] }
  for (const t of Array.isArray(tasks) ? tasks : []) {
    if (!t) continue
    if (isDone(t)) groups.done.push(t)
    else if (!t.dueDate) groups.noDate.push(t)
    else if (t.dueDate < today) groups.overdue.push(t)
    else if (t.dueDate === today) groups.today.push(t)
    else groups.upcoming.push(t)
  }
  groups.overdue.sort(compareDue)
  groups.today.sort(compareDue)
  groups.upcoming.sort(compareDue)
  groups.noDate.sort((a, b) => (a.id || 0) - (b.id || 0))
  groups.done.sort((a, b) => -compareDue(a, b))
  return groups
}

/**
 * 本地汇总，形状同 GET /api/calendar/summary：{ overdue, today, week, nextDue }。
 * week = 今天起 7 天内（含今天）；nextDue = 今天及以后最近的一条未完成事项或 null。
 * taskStore 用它就地刷新按项目缓存的 summary（项目清单是全量，本地算得准）。
 */
export function summarizeTasks(tasks, today = localDateKey()) {
  const weekEnd = addDaysKey(today, 6)
  let overdue = 0
  let todayCount = 0
  let week = 0
  let nextDue = null
  for (const t of Array.isArray(tasks) ? tasks : []) {
    if (!t || isDone(t) || !t.dueDate) continue
    if (t.dueDate < today) { overdue++; continue }
    if (t.dueDate === today) todayCount++
    if (t.dueDate <= weekEnd) week++
    if (!nextDue || compareDue(t, nextDue) < 0) nextDue = t
  }
  return { overdue, today: todayCount, week, nextDue }
}

/** 事项关联的全部文件 id（files[] 与旧列 fileId 合并去重），字符串形式。 */
export function taskFileIds(task) {
  const ids = []
  if (!task) return ids
  for (const f of Array.isArray(task.files) ? task.files : []) {
    if (f && f.fileId != null && !ids.includes(String(f.fileId))) ids.push(String(f.fileId))
  }
  if (task.fileId != null && !ids.includes(String(task.fileId))) ids.push(String(task.fileId))
  return ids
}

/**
 * 事项的文件芯片：[{ fileId, fileName }]，files[] 优先；旧数据只有 fileId/fileName 时补一条。
 * fileName 为 null 表示文件已被删（悬空关联，沿用后端口径）。
 */
export function taskFiles(task) {
  if (!task) return []
  const out = []
  const seen = new Set()
  for (const f of Array.isArray(task.files) ? task.files : []) {
    if (!f || f.fileId == null || seen.has(String(f.fileId))) continue
    seen.add(String(f.fileId))
    out.push({ fileId: f.fileId, fileName: f.fileName == null ? null : f.fileName })
  }
  if (task.fileId != null && !seen.has(String(task.fileId))) {
    out.push({ fileId: task.fileId, fileName: task.fileName == null ? null : task.fileName })
  }
  return out
}
