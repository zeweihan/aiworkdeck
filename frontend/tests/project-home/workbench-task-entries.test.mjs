// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 工作台里的事项入口（dev-board#899 入口重整 / #900 文件树与事项双向关联）：
// rail「日程」徽标、全局 TaskDialog、头像菜单「我的日程」、命令、文件树右键与到期徽标。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as taskUtils from '../../src/components/calendar/taskUtils.js'
import { COMMANDS as ALL_COMMANDS } from '../../src/config/commands/index.js'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const overview = read('pages/project-overview/project-overview.vue')
const fileTree = read('components/FileTree.vue')
const menuCommands = read('pages/project-overview/menuCommands.js')

function extractBlock(src, marker) {
  const i = src.indexOf(marker)
  assert.ok(i >= 0, '找不到 ' + marker)
  const start = src.indexOf('{', i + marker.length - 1)
  let depth = 0
  for (let j = start; j < src.length; j++) {
    if (src[j] === '{') depth++
    else if (src[j] === '}' && --depth === 0) return src.slice(start, j + 1)
  }
  throw new Error(marker + ' 的括号没配上')
}

test('rail 日程徽标：只挂在 calendar 项上，数 = 逾期 + 今天，0 不显，逾期转红', () => {
  assert.match(overview, /v-if="p\.key === 'calendar' && railScheduleCount > 0"\s+class="rail-badge"/)
  assert.match(overview, /'is-overdue': railScheduleSummary\.overdue > 0/)
  const summary = new Function('taskStore', `return function () ${extractBlock(overview, 'railScheduleSummary() {')}`)
  const store = { byProject: { 242: { summary: { overdue: 1, today: 2, week: 5 } } } }
  assert.deepEqual(summary(store).call({ projectId: 242 }), { overdue: 1, today: 2 })
  assert.deepEqual(summary(store).call({ projectId: 7 }), { overdue: 0, today: 0 }, '没加载过的项目按 0')
})

test('工作台只挂一个 TaskDialog，面板 / 文件树 / 命令都走 openTaskDialog', () => {
  assert.equal((overview.match(/<TaskDialog\b/g) || []).length, 1)
  assert.match(overview, /@new-task="openTaskDialog\(/)
  assert.match(overview, /@open-task="openTaskDialog\(\{ mode: 'edit'/)
  assert.match(overview, /@add-task="onFileTreeAddTask"/)
  assert.match(overview, /@view-tasks="onFileTreeViewTasks"/)
  assert.match(menuCommands, /case 'newTask': this\.openTaskDialog\(/)
  assert.match(menuCommands, /case 'goCalendar': this\.goCalendar\(\)/)
  assert.match(overview, /startTaskReminders\(\)/)
})

test('文件右键「添加事项…」预置关联这份文件；「查看事项」打开日程面板并按文件过滤', () => {
  const addTask = new Function(`return function (file) ${extractBlock(overview, 'onFileTreeAddTask(file) {')}`)()
  let opened
  addTask.call({ openTaskDialog: (o) => { opened = o } }, { id: 2391, name: 'a.docx' })
  assert.deepEqual(opened, { mode: 'create', presetFileIds: [2391] })

  const viewTasks = new Function(`return function (file) ${extractBlock(overview, 'onFileTreeViewTasks(file) {')}`)()
  const toggled = []
  const ctx = { leftPaneKey: 'files', sidebarCollapsed: false, toggleLeftPane: (k) => toggled.push(k) }
  viewTasks.call(ctx, { id: 2391, name: 'a.docx' })
  assert.equal(ctx.calendarFileFilter, 2391)
  assert.equal(ctx.calendarFileFilterName, 'a.docx')
  assert.deepEqual(toggled, ['calendar'])
  // 日程面板已经开着时不能再 toggle（同 key 的 toggle = 收起）
  const ctx2 = { leftPaneKey: 'calendar', sidebarCollapsed: false, toggleLeftPane: (k) => toggled.push(k) }
  viewTasks.call(ctx2, { id: 1, name: 'b.docx' })
  assert.deepEqual(toggled, ['calendar'])
})

test('命令：go.calendar 与 task.new 登记在册', () => {
  const byId = Object.fromEntries(ALL_COMMANDS.map((c) => [c.id, c]))
  assert.equal(byId['go.calendar'].run, 'wb:goCalendar')
  assert.deepEqual(byId['go.calendar'].label, { zh: '日程', en: 'Schedule' })
  assert.equal(byId['go.calendar'].accel, undefined)
  assert.equal(byId['task.new'].run, 'wb:newTask')
  assert.deepEqual(byId['task.new'].when, ['workbench', 'project'])
})

test('文件树：旧的内联「设置截止日」弹窗整套删掉，右键改为 emit 给宿主', () => {
  assert.doesNotMatch(fileTree, /openDeadlineDialog|confirmSetDeadline|showDeadlineDialog|calendar\.setDeadline/)
  assert.doesNotMatch(fileTree, /\bcreateTask\b/, '文件树不该再自己建事项')
  assert.match(fileTree, /\$emit\('add-task', contextMenu\.targetItem\)/)
  assert.match(fileTree, /\$emit\('view-tasks', contextMenu\.targetItem\)/)
})

test('文件树到期徽标索引：取未完成里最早的一条，计数含无日期的，已完成不算', () => {
  const body = extractBlock(fileTree, 'fileDueIndex() {')
  const fn = new Function('taskStore', 'isDone', 'dueBadge', 'compareDue', 'taskFileIds',
    `return function () ${body}`)
  const today = taskUtils.localDateKey()
  const tasks = [
    { id: 1, title: '举证期限', dueDate: taskUtils.addDaysKey(today, 3), status: 'OPEN', fileId: 10 },
    { id: 2, title: '开庭', dueDate: taskUtils.addDaysKey(today, -2), status: 'OPEN', files: [{ fileId: 10 }, { fileId: 11 }] },
    { id: 3, title: '已交', dueDate: taskUtils.addDaysKey(today, -9), status: 'DONE', fileId: 10 },
    { id: 4, title: '无日期', status: 'OPEN', fileId: 12 },
  ]
  const store = { byProject: { 5: { list: tasks } } }
  const index = fn(store, taskUtils.isDone, taskUtils.dueBadge, taskUtils.compareDue, taskUtils.taskFileIds)
    .call({ projectId: 5, $t: (k, p) => k + (p && p.count != null ? ':' + p.count : '') })
  assert.equal(index[10].kind, 'overdue', '文件 10 最早的未完成是逾期的「开庭」')
  assert.equal(index[10].count, 2, '已完成的不计数')
  assert.equal(index[11].kind, 'overdue')
  assert.equal(index[12].text, '', '只有无日期事项：不画徽标')
  assert.equal(index[12].count, 1, '但右键「查看事项 (1)」要有')
  assert.equal(index[99], undefined)
})
