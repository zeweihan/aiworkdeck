// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// utils/taskStore.js（dev-board#896）：写操作就地更新 byProject 与 global、广播、tasksForFile。
//
// taskStore 依赖 @/services/api.js（带 uni 与 @/ 别名，node 进不来），照
// tests/project-home/calendar-load-race.test.mjs 的口径：抽源码、去 import、注入依赖真跑。
// 每个用例现造一份新 store，互不串状态。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { reactive } from 'vue'
import * as taskUtils from '../../src/components/calendar/taskUtils.js'

const SRC = readFileSync(new URL('../../src/utils/taskStore.js', import.meta.url), 'utf8')
const EXPORTS = [...SRC.matchAll(/^export\s+(?:const|async function|function)\s+(\w+)/gm)].map((m) => m[1])

function makeStore(api) {
  const body = SRC
    .replace(/^import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/^export\s+/gm, '')
    + '\nreturn { ' + EXPORTS.join(', ') + ' }'
  const deps = {
    reactive,
    getProjectTasks: api.getProjectTasks || (async () => ({ code: 0, data: { tasks: [] } })),
    getCalendarTasks: api.getCalendarTasks || (async () => ({ code: 0, data: { tasks: [] } })),
    getCalendarSummary: api.getCalendarSummary || (async () => ({ code: 0, data: { overdue: 0, today: 0, week: 0, nextDue: null } })),
    apiCreateTask: api.createTask || (async () => { throw new Error('no create') }),
    apiUpdateTask: api.updateTask || (async () => { throw new Error('no update') }),
    apiDeleteTask: api.deleteTask || (async () => ({ code: 0, data: { deleted: true } })),
    compareDue: taskUtils.compareDue,
    summarizeTasks: taskUtils.summarizeTasks,
    taskFileIds: taskUtils.taskFileIds,
  }
  // eslint-disable-next-line no-new-func
  return new Function(...Object.keys(deps), body)(...Object.values(deps))
}

const ok = (data) => ({ code: 0, data })

test('导出齐全（spec 第二节列的全部）', () => {
  for (const name of ['taskStore', 'loadProjectTasks', 'loadGlobal', 'loadSummary', 'createTask', 'updateTask', 'deleteTask', 'tasksForFile', 'subscribe']) {
    assert.ok(EXPORTS.includes(name), '缺导出 ' + name)
  }
})

test('create 后 byProject 与 global 就地更新、补 projectName、广播 created', async () => {
  let nextId = 100
  const s = makeStore({
    getProjectTasks: async () => ok({ tasks: [{ id: 1, projectId: 7, title: 'a', dueDate: '2026-10-01', status: 'OPEN' }] }),
    getCalendarTasks: async () => ok({ tasks: [{ id: 1, projectId: 7, projectName: '甲案', title: 'a', dueDate: '2026-10-01', status: 'OPEN' }] }),
    createTask: async (body) => ok({ id: nextId++, status: 'OPEN', ...body }),
  })
  await s.loadProjectTasks(7)
  await s.loadGlobal({ from: '2026-09-01', to: '2026-10-31' })
  const events = []
  const off = s.subscribe((e) => events.push(e.kind))

  const created = await s.createTask({ projectId: 7, title: 'b', dueDate: '2026-09-28', fileIds: [5] })
  assert.equal(created.id, 100)
  assert.equal(created.projectName, '甲案', 'POST 响应没有 projectName，从缓存补')
  assert.deepEqual(s.taskStore.byProject['7'].list.map((t) => t.id), [100, 1], '按到期排序')
  assert.deepEqual(s.taskStore.global.list.map((t) => t.id), [100, 1])
  assert.equal(s.taskStore.byProject['7'].summary.week >= 0, true)
  assert.deepEqual(events, ['created'])

  // 落在全局已加载区间外的新事项不进 global（下次加载那个区间自然拿到）
  await s.createTask({ projectId: 7, title: 'c', dueDate: '2027-03-01' })
  assert.equal(s.taskStore.global.list.some((t) => t.title === 'c'), false)
  assert.equal(s.taskStore.byProject['7'].list.some((t) => t.title === 'c'), true, '项目缓存是全量，照放')
  off()
  await s.createTask({ projectId: 7, title: 'd', dueDate: '2026-09-30' })
  assert.deepEqual(events, ['created', 'created'], '退订后不再收到')
})

test('update 就地替换并保留 projectName；改到区间外从 global 摘掉', async () => {
  const base = { id: 1, projectId: 7, projectName: '甲案', title: 'a', dueDate: '2026-10-01', status: 'OPEN' }
  const s = makeStore({
    getProjectTasks: async () => ok({ tasks: [{ ...base, projectName: undefined }] }),
    getCalendarTasks: async () => ok({ tasks: [base] }),
    updateTask: async (id, patch) => ok({ ...base, projectName: undefined, ...patch }),
  })
  await s.loadProjectTasks(7)
  await s.loadGlobal({ from: '2026-09-01', to: '2026-10-31' })
  const u = await s.updateTask(1, { status: 'DONE' })
  assert.equal(u.status, 'DONE')
  assert.equal(u.projectName, '甲案')
  assert.equal(s.taskStore.byProject['7'].list[0].status, 'DONE')
  assert.equal(s.taskStore.global.list[0].status, 'DONE')
  assert.equal(s.taskStore.byProject['7'].summary.today + s.taskStore.byProject['7'].summary.week, 0, '完成后汇总不再计')
  await s.updateTask(1, { dueDate: '2027-01-01' })
  assert.equal(s.taskStore.global.list.length, 0)
  assert.equal(s.taskStore.byProject['7'].list[0].dueDate, '2027-01-01')
})

test('delete 从所有缓存移除并广播', async () => {
  const t1 = { id: 1, projectId: 7, title: 'a', dueDate: '2026-10-01', status: 'OPEN' }
  const t2 = { id: 2, projectId: 7, title: 'b', dueDate: '2026-10-02', status: 'OPEN' }
  const s = makeStore({
    getProjectTasks: async () => ok({ tasks: [t1, t2] }),
    getCalendarTasks: async () => ok({ tasks: [t1, t2] }),
  })
  await s.loadProjectTasks(7)
  await s.loadGlobal()
  const events = []
  s.subscribe((e) => events.push(e))
  const removed = await s.deleteTask(1)
  assert.equal(removed.id, 1)
  assert.deepEqual(s.taskStore.byProject['7'].list.map((t) => t.id), [2])
  assert.deepEqual(s.taskStore.global.list.map((t) => t.id), [2])
  assert.equal(events[0].kind, 'deleted')
  assert.equal(events[0].id, 1)
})

test('tasksForFile 命中旧列 file_id 与 files[] 任一', async () => {
  const s = makeStore({
    getProjectTasks: async () => ok({ tasks: [
      { id: 1, projectId: 7, fileId: 50, title: 'old', dueDate: '2026-10-01', status: 'OPEN' },
      { id: 2, projectId: 7, fileId: 60, files: [{ fileId: 60, fileName: 'x' }, { fileId: 50, fileName: 'y' }], title: 'multi', dueDate: '2026-09-30', status: 'OPEN' },
      { id: 3, projectId: 7, fileId: null, files: [], title: 'none', dueDate: '2026-09-29', status: 'OPEN' },
    ] }),
  })
  assert.deepEqual(s.tasksForFile(7, 50), [], '未加载时返回空')
  await s.loadProjectTasks(7)
  assert.deepEqual(s.tasksForFile(7, 50).map((t) => t.id), [2, 1])
  assert.deepEqual(s.tasksForFile('7', '60').map((t) => t.id), [2])
  assert.deepEqual(s.tasksForFile(7, 99), [])
})

test('loadGlobal 按区间合并：两个区间互不覆盖；已覆盖的区间走缓存；并发只发一次', async () => {
  let calls = 0
  const data = {
    '2026-09-01': [{ id: 1, projectId: 7, dueDate: '2026-09-10', status: 'OPEN' }],
    '2026-08-26': [{ id: 1, projectId: 7, dueDate: '2026-09-10', status: 'OPEN' }, { id: 2, projectId: 7, dueDate: '2026-10-05', status: 'OPEN' }],
  }
  const s = makeStore({
    getCalendarTasks: async (from) => { calls++; return ok({ tasks: data[from] || [] }) },
  })
  await Promise.all([s.loadGlobal({ from: '2026-09-01', to: '2026-09-30' }), s.loadGlobal({ from: '2026-09-01', to: '2026-09-30' })])
  assert.equal(calls, 1)
  await s.loadGlobal({ from: '2026-08-26', to: '2026-10-09' })
  assert.deepEqual(s.taskStore.global.list.map((t) => t.id), [1, 2])
  const cached = await s.loadGlobal({ from: '2026-09-05', to: '2026-09-20' })
  assert.equal(calls, 2, '子区间走缓存')
  assert.deepEqual(cached.map((t) => t.id), [1])
})

test('loadProjectTasks 已加载不重拉，force 才拉', async () => {
  let calls = 0
  const s = makeStore({ getProjectTasks: async () => { calls++; return ok({ tasks: [] }) } })
  await s.loadProjectTasks(7)
  await s.loadProjectTasks(7)
  assert.equal(calls, 1)
  await s.loadProjectTasks(7, { force: true })
  assert.equal(calls, 2)
})
