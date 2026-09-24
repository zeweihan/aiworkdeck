// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 工作台左栏「日程」面板（dev-board#899 重做）。
//
// 取代原 project-calendar-quick-create.test.mjs（dev-board#74）：那份测的是面板里的内联
// 快速新建（回车 + 点保存双发 createTask）。重做后面板不再自己建事项——「新建事项」只 emit
// 给宿主，由工作台唯一的 TaskDialog 建，防重复提交是 TaskDialog 的 busy 闸（它自己的测试管）。
// 这里守的是「面板没有第二条写入口」与新入口的行为。
//
// 组件带 @/ 别名 import 不进来（本目录既有写法），方法体抠出来用 new Function 起真身跑。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(
  new URL('../../src/components/project-calendar/ProjectCalendarPane.vue', import.meta.url), 'utf8')
const script = src.slice(src.indexOf('<script>'))

function extractMethod(name) {
  const re = new RegExp(`\\n    (async )?${name}\\(([^)]*)\\) \\{`)
  const m = script.match(re)
  assert.ok(m, `找不到 ${name}`)
  const start = script.indexOf('{', m.index + 1)
  let depth = 0
  for (let i = start; i < script.length; i++) {
    if (script[i] === '{') depth++
    else if (script[i] === '}' && --depth === 0) {
      return { async: !!m[1], args: m[2], body: script.slice(start, i + 1) }
    }
  }
  throw new Error(`${name} 的括号没配上`)
}

function build(name, deps = {}) {
  const { async, args, body } = extractMethod(name)
  const names = Object.keys(deps)
  return new Function(...names, `return ${async ? 'async ' : ''}function ${name}(${args}) ${body}`)(
    ...names.map((k) => deps[k]))
}

test('面板不再有内联快速新建，也不直接调 createTask：新建只有 TaskDialog 一个入口', () => {
  assert.doesNotMatch(src, /submitQuickCreate|quickCreateOpen|pcp-quick-/, '内联快速新建应已删除')
  assert.doesNotMatch(script, /\bcreateTask\b/, '面板不该自己建事项')
  assert.doesNotMatch(script, /@fullcalendar/, '面板不该再依赖 FullCalendar')
})

test('「新建事项」按钮 emit new-task；按文件过滤时预置关联这份文件', () => {
  assert.match(src, /class="pcp-new-btn" @tap="onNewTask"/)
  const onNewTask = build('onNewTask')
  const emitted = []
  const ctx = { fileFilter: null, $emit: (e, p) => emitted.push([e, p]) }
  onNewTask.call(ctx)
  assert.deepEqual(emitted[0], ['new-task', { presetFileIds: [] }])
  ctx.fileFilter = 2391
  onNewTask.call(ctx)
  assert.deepEqual(emitted[1], ['new-task', { presetFileIds: [2391] }])
})

test('勾选在已完成/未完成之间切换，经 taskStore.updateTask 写', async () => {
  const calls = []
  const onToggle = build('onToggle', {
    updateTask: async (id, patch) => { calls.push([id, patch]) },
    isDone: (t) => t.status === 'DONE',
    uni: { showToast() {} },
  })
  const ctx = { $t: (k) => k }
  await onToggle.call(ctx, { id: 1, status: 'OPEN' })
  await onToggle.call(ctx, { id: 2, status: 'DONE' })
  assert.deepEqual(calls, [[1, { status: 'DONE' }], [2, { status: 'OPEN' }]])
})

test('删除先确认，确认后才经 taskStore.deleteTask 删', async () => {
  const deleted = []
  let modal
  const onDelete = build('onDelete', {
    deleteTask: async (id) => { deleted.push(id) },
    uni: { showModal: (o) => { modal = o }, showToast() {} },
  })
  onDelete.call({ $t: (k) => k }, { id: 9, title: '开庭' })
  assert.equal(deleted.length, 0, '没确认就删了')
  await modal.success({ confirm: false })
  assert.equal(deleted.length, 0)
  await modal.success({ confirm: true })
  assert.deepEqual(deleted, [9])
})

test('月份条：从「全部」点箭头先落到本月，之后才前后翻', () => {
  const shiftMonth = build('shiftMonth')
  const now = new Date()
  const ctx = { month: null }
  shiftMonth.call(ctx, -1)
  assert.deepEqual(ctx.month, { y: now.getFullYear(), m: now.getMonth() + 1 })
  ctx.month = { y: 2026, m: 1 }
  shiftMonth.call(ctx, -1)
  assert.deepEqual(ctx.month, { y: 2025, m: 12 })
  shiftMonth.call(ctx, 1)
  assert.deepEqual(ctx.month, { y: 2026, m: 1 })
})

test('「查看全盘日程」仍经宿主 leaveWorkbench 离开（dev-board#489 落盘口径）', () => {
  const openGlobalCalendar = build('openGlobalCalendar')
  const emitted = []
  openGlobalCalendar.call({ $emit: (e, p) => emitted.push([e, p]) })
  assert.deepEqual(emitted, [['leave-workbench', '/pages/calendar/calendar']])
  assert.doesNotMatch(script, /uni\.(reLaunch|navigateTo|redirectTo)/, '面板不许自己跳页')
})
