// 任务/日程的共享判定与展示逻辑（唯一出处）。
// 消费方：pages/calendar/calendar.vue、TaskDialog、UpcomingList、
// project-home/TaskSchedule、project-calendar/ProjectCalendarPane。
// 改「已完成」判定、剩余天数阈值、徽标文案，只改这里。

/** status 是否为已完成（后端契约：大写 OPEN/DONE）。 */
export function isDone(task) {
  return String((task && task.status) || '').toUpperCase() === 'DONE'
}

/**
 * 距截止日的整天数：0=今天、正=还剩 N 天、负=已逾期 N 天。
 * dueDate 缺失或不可解析返回 null（脏数据不产出 NaN 徽标）。
 */
export function daysUntil(dueDate) {
  if (!dueDate) return null
  const due = new Date(dueDate + 'T00:00:00')
  if (Number.isNaN(due.getTime())) return null
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return Math.round((due.getTime() - today.getTime()) / 86400000)
}

/**
 * 剩余天数徽标。translate 传组件的 this.$t。
 * 返回 { text, kind }，kind ∈ 'overdue' | 'today' | 'soon'(≤7 天) | 'later' | ''，
 * 各组件自行把 kind 映射到自己的 CSS 类。
 */
export function dueBadge(task, translate) {
  const d = daysUntil(task && task.dueDate)
  if (d === null) return { text: '', kind: '' }
  if (d < 0) return { text: translate('calendar.overdueDays', { count: -d }), kind: 'overdue' }
  if (d === 0) return { text: translate('calendar.dueToday'), kind: 'today' }
  if (d <= 7) return { text: translate('calendar.daysLeft', { count: d }), kind: 'soon' }
  return { text: translate('calendar.daysLeft', { count: d }), kind: 'later' }
}

/** task → FullCalendar 的 event.start（有 dueTime 拼 T，无则全天）。 */
export function toEventStart(task) {
  return task.dueTime ? `${task.dueDate}T${task.dueTime}` : task.dueDate
}
