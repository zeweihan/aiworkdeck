// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 事项共享逻辑（components/calendar/taskUtils.js，dev-board#896）：类型元信息、提醒时刻、
// 到期徽标、议程分组、本地汇总、提醒判定。
//   cd frontend && npm run test:calendar
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  TASK_TYPES,
  typeMeta,
  normalizeType,
  REMIND_OPTIONS,
  remindOptionsFor,
  remindLabelKey,
  defaultRemindFor,
  remindAtOf,
  dueBadge,
  groupByDue,
  groupTodos,
  summarizeTasks,
  dueReminders,
  taskFiles,
  taskFileIds,
  addDaysKey,
} from '../../src/components/calendar/taskUtils.js'
import { groupTodos as reexportedGroupTodos, localDateKey } from '../../src/utils/personalCollections.js'

const tr = (k, p) => (p ? k + JSON.stringify(p) : k)

test('类型：五种、默认 DEADLINE、颜色全是 --awd-* 令牌、各不相同', () => {
  assert.deepEqual(TASK_TYPES, ['DEADLINE', 'HEARING', 'MEETING', 'TODO', 'OTHER'])
  assert.equal(typeMeta(undefined).key, 'DEADLINE')
  assert.equal(typeMeta('').key, 'DEADLINE')
  assert.equal(typeMeta('bogus').key, 'DEADLINE')
  assert.equal(normalizeType('hearing'), 'HEARING')
  const colors = TASK_TYPES.map((k) => typeMeta(k).color)
  for (const c of colors) assert.match(c, /^var\(--awd-[a-z0-9-]+\)$/)
  assert.equal(new Set(colors).size, 5, '五种类型五个色相')
  assert.equal(typeMeta('DEADLINE').color, 'var(--awd-accent)')
  assert.equal(typeMeta('HEARING').color, 'var(--awd-danger)')
  assert.equal(typeMeta('MEETING').color, 'var(--awd-mint)')
  // 不传 translate 回键名；传了回译文
  assert.equal(typeMeta('HEARING').label, 'calendar.typeHearing')
  assert.equal(typeMeta('HEARING', (k) => 'X:' + k).label, 'X:calendar.typeHearing')
  assert.ok(Array.isArray(typeMeta('TODO').icon) && typeMeta('TODO').icon.length > 0)
})

test('提醒选项：七档；全天事项隐藏 30 分 / 1 小时；标签键齐全', () => {
  assert.deepEqual(REMIND_OPTIONS, [null, 0, 30, 60, 1440, 4320, 10080])
  assert.deepEqual(remindOptionsFor(true), REMIND_OPTIONS)
  assert.deepEqual(remindOptionsFor(false), [null, 0, 1440, 4320, 10080])
  assert.equal(remindLabelKey(null), 'calendar.remindNone')
  assert.equal(remindLabelKey(0), 'calendar.remindAtTime')
  assert.equal(remindLabelKey(10080), 'calendar.remind1w')
})

test('remindAtOf：全天事项以当天 09:00 为基准；带时间用 dueDate+dueTime；null 不提醒', () => {
  assert.equal(remindAtOf({ dueDate: '2026-10-12', remindBefore: null }), null)
  assert.equal(remindAtOf({ dueDate: '2026-10-12' }), null)
  assert.equal(remindAtOf({ dueDate: null, remindBefore: 0 }), null)
  const allDay = remindAtOf({ dueDate: '2026-10-12', remindBefore: 1440 })
  assert.equal(allDay.getTime(), new Date(2026, 9, 11, 9, 0).getTime())
  const allDayOnTime = remindAtOf({ dueDate: '2026-10-12', remindBefore: 0 })
  assert.equal(allDayOnTime.getTime(), new Date(2026, 9, 12, 9, 0).getTime())
  const timed = remindAtOf({ dueDate: '2026-10-12', dueTime: '14:30', remindBefore: 30 })
  assert.equal(timed.getTime(), new Date(2026, 9, 12, 14, 0).getTime())
  // 后端偶尔回 HH:mm:ss
  const timedSec = remindAtOf({ dueDate: '2026-10-12', dueTime: '14:30:00', remindBefore: 60 })
  assert.equal(timedSec.getTime(), new Date(2026, 9, 12, 13, 30).getTime())
})

test('dueBadge：逾期 N 天 / 今天 / 明天 / N 天后 / M月D日 + time + kind', () => {
  const now = new Date(2026, 8, 25, 10, 0) // 2026-09-25
  assert.deepEqual(dueBadge({ dueDate: null }, tr, now), { text: '', kind: '', time: '' })
  assert.deepEqual(dueBadge({ dueDate: '2026-09-22' }, tr, now), { text: 'calendar.dueOverdueDays{"count":3}', kind: 'overdue', time: '' })
  assert.deepEqual(dueBadge({ dueDate: '2026-09-25', dueTime: '09:30' }, tr, now), { text: 'calendar.dueTodayShort', kind: 'today', time: '09:30' })
  assert.equal(dueBadge({ dueDate: '2026-09-26' }, tr, now).text, 'calendar.dueTomorrow')
  assert.equal(dueBadge({ dueDate: '2026-09-26' }, tr, now).kind, 'soon')
  assert.equal(dueBadge({ dueDate: '2026-09-30' }, tr, now).text, 'calendar.dueInDays{"count":5}')
  const week = dueBadge({ dueDate: '2026-10-02' }, tr, now)
  assert.equal(week.text, 'calendar.dueMonthDay{"month":10,"day":2}')
  assert.equal(week.kind, 'soon')
  assert.equal(dueBadge({ dueDate: '2026-10-20' }, tr, now).kind, 'later')
  assert.equal(dueBadge({ dueDate: '2027-01-05' }, tr, now).text, 'calendar.dueYearMonthDay{"year":2027,"month":1,"day":5}')
})

test('groupByDue：五桶、各桶排序、无日期落 later 末尾', () => {
  const today = '2026-09-25'
  const tasks = [
    { id: 1, dueDate: '2026-10-20', status: 'OPEN' },          // later
    { id: 2, dueDate: '2026-09-20', status: 'OPEN' },          // overdue
    { id: 3, dueDate: '2026-09-25', dueTime: '15:00', status: 'OPEN' }, // today
    { id: 4, dueDate: '2026-09-25', status: 'OPEN' },          // today（全天在前）
    { id: 5, dueDate: '2026-10-01', status: 'OPEN' },          // week（+6 天，边界内）
    { id: 6, dueDate: '2026-10-02', status: 'OPEN' },          // later（+7 天）
    { id: 7, dueDate: '2026-09-26', status: 'open' },          // week
    { id: 8, dueDate: null, status: 'OPEN' },                  // later 末尾
    { id: 9, dueDate: '2026-09-10', status: 'DONE' },          // done
    { id: 10, dueDate: '2026-09-24', status: 'done' },         // done（更近，在前）
    { id: 11, dueDate: '2026-09-01', status: 'OPEN' },         // overdue（更早，在前）
    null,
  ]
  const g = groupByDue(tasks, today)
  assert.deepEqual(Object.keys(g), ['overdue', 'today', 'week', 'later', 'done'])
  assert.deepEqual(g.overdue.map((t) => t.id), [11, 2])
  assert.deepEqual(g.today.map((t) => t.id), [4, 3])
  assert.deepEqual(g.week.map((t) => t.id), [7, 5])
  assert.deepEqual(g.later.map((t) => t.id), [6, 1, 8])
  assert.deepEqual(g.done.map((t) => t.id), [10, 9])
  const empty = groupByDue(undefined, today)
  assert.deepEqual(Object.values(empty).map((a) => a.length), [0, 0, 0, 0, 0])
})

test('personalCollections 只 re-export：groupTodos / localDateKey 与 taskUtils 是同一个函数', () => {
  assert.equal(reexportedGroupTodos, groupTodos)
  assert.equal(typeof localDateKey, 'function')
})

test('summarizeTasks：overdue / today / week(含今天 7 天) / nextDue，只算未完成', () => {
  const today = '2026-09-25'
  const s = summarizeTasks([
    { id: 1, dueDate: '2026-09-20', status: 'OPEN' },
    { id: 2, dueDate: '2026-09-25', dueTime: '10:00', status: 'OPEN' },
    { id: 3, dueDate: '2026-09-25', status: 'OPEN' },
    { id: 4, dueDate: '2026-10-01', status: 'OPEN' },
    { id: 5, dueDate: '2026-10-02', status: 'OPEN' },
    { id: 6, dueDate: '2026-09-25', status: 'DONE' },
    { id: 7, dueDate: null, status: 'OPEN' },
  ], today)
  assert.equal(s.overdue, 1)
  assert.equal(s.today, 2)
  assert.equal(s.week, 3)
  assert.equal(s.nextDue.id, 3, '同一天全天事项在前')
  assert.equal(addDaysKey('2026-12-30', 3), '2027-01-02')
})

test('dueReminders：到点且未通知才弹；改了时间重新弹；超 24 小时只记不弹；已完成跳过', () => {
  const now = new Date(2026, 8, 25, 10, 0)
  const a = { id: 1, uid: 'u1', dueDate: '2026-09-25', dueTime: '10:30', remindBefore: 30, status: 'OPEN' } // 10:00 到点
  const b = { id: 2, uid: 'u2', dueDate: '2026-09-25', dueTime: '12:00', remindBefore: 30, status: 'OPEN' } // 未到
  const c = { id: 3, uid: 'u3', dueDate: '2026-09-20', remindBefore: 0, status: 'OPEN' }                     // 5 天前：只记
  const d = { id: 4, uid: 'u4', dueDate: '2026-09-25', remindBefore: 0, status: 'DONE' }                     // 已完成
  const e = { id: 5, dueDate: '2026-09-25', remindBefore: null, status: 'OPEN' }                             // 不提醒
  const r1 = dueReminders([a, b, c, d, e], {}, now)
  assert.deepEqual(r1.due.map((x) => x.key), ['u1'])
  assert.deepEqual(r1.skipped.map((x) => x.key), ['u3'])
  const notified = { u1: r1.due[0].at }
  assert.equal(dueReminders([a], notified, now).due.length, 0, '同一次提醒只弹一次')
  const moved = { ...a, dueTime: '10:20' }
  assert.equal(dueReminders([moved], notified, now).due.length, 1, '改了时间重新提醒')
})

test('taskFiles / taskFileIds：files[] 优先、旧 fileId 补进来、去重、悬空保留 null 名', () => {
  const t = { fileId: 7, fileName: 'a.docx', files: [{ fileId: 7, fileName: 'a.docx' }, { fileId: 9, fileName: null }] }
  assert.deepEqual(taskFiles(t), [{ fileId: 7, fileName: 'a.docx' }, { fileId: 9, fileName: null }])
  assert.deepEqual(taskFileIds(t), ['7', '9'])
  assert.deepEqual(taskFiles({ fileId: 3, fileName: 'old.pdf' }), [{ fileId: 3, fileName: 'old.pdf' }])
  assert.deepEqual(taskFiles({}), [])
})

test('defaultRemindFor：截止日/开庭默认提前 1 天，会议/待办/其他不提醒，未知类型按截止日', () => {
  assert.equal(defaultRemindFor('DEADLINE'), 1440)
  assert.equal(defaultRemindFor('HEARING'), 1440)
  assert.equal(defaultRemindFor('MEETING'), null)
  assert.equal(defaultRemindFor('TODO'), null)
  assert.equal(defaultRemindFor('OTHER'), null)
  assert.equal(defaultRemindFor(undefined), 1440)
  assert.ok(REMIND_OPTIONS.includes(defaultRemindFor('HEARING')), '默认值必须是可选档位之一')
})
