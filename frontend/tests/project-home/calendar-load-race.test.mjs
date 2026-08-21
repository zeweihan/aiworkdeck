// 审计（dev-board#74）：calendar.vue 的 loadTasks() 没有请求乱序保护。
// 连续快点 prev/next 或切视图，每次 datesSet 都立刻发一个新请求；先发的（旧区间）
// 响应若后到，就会把 calendarOptions.events 覆盖成旧区间的任务集合。FullCalendar
// 只画落在当前视图区间内的事件，所以用户看到的不是「别的月份的卡片」，而是当前
// 月份大面积空白——只剩两个区间重叠的那几条。
//
// 与本目录既有用例同口径（见 version-timeline-race.test.mjs）：把 .vue 的 <script>
// 块抽出来真跑一遍 loadTasks()，组件带 @/ 别名 import 不进来，改成注入依赖。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/pages/calendar/calendar.vue', import.meta.url), 'utf8')

function loadOptions(getCalendarTasks, uniStub) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const stub = {}
  const factory = new Function(
    'FullCalendar', 'dayGridPlugin', 'timeGridPlugin', 'listPlugin', 'interactionPlugin',
    'zhCnLocale', 'getCalendarTasks', 'getMyProjects', 'updateTask', 'getAppLanguage',
    'colorForProject', 'getDayMarkType', 'isDone', 'toEventStart', 'TaskDialog',
    'UpcomingList', 'uni', body)
  return factory(stub, stub, stub, stub, stub, stub,
    getCalendarTasks,
    () => Promise.resolve([]),
    () => Promise.resolve({}),
    () => 'zh-CN',
    () => ({ bg: '#000', text: '#fff' }),
    () => 'workday',
    () => false,
    (t) => t.dueDate,
    stub, stub,
    uniStub || { showToast() {} })
}

function makeVm(options) {
  const vm = Object.assign({}, options.data())
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.$t = (k) => k
  return vm
}

const deferred = () => {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

const taskOf = (id, dueDate) => ({ id, title: 't' + id, dueDate, projectId: 1 })

test('先发的旧月份请求后到，不许覆盖后发的新月份结果', async () => {
  const gates = { '2026-08-01': deferred(), '2026-09-01': deferred() }
  const vm = makeVm(loadOptions((from) => gates[from].promise))

  // 1) 停在 8 月，请求在途
  const aug = vm.loadTasks('2026-08-01', '2026-09-01')
  // 2) 立刻点「下一月」，datesSet 推进区间并发第二个请求
  vm.currentFrom = '2026-09-01'
  vm.currentTo = '2026-10-01'
  const sep = vm.loadTasks('2026-09-01', '2026-10-01')

  // 3) 后发的先回，先发的后回（网络乱序）
  gates['2026-09-01'].resolve({ data: { tasks: [taskOf(20, '2026-09-10')] } })
  await sep
  gates['2026-08-01'].resolve({ data: { tasks: [taskOf(10, '2026-08-10')] } })
  await aug

  assert.deepEqual(vm.calendarOptions.events.map((e) => e.id), ['20'],
    '旧月份的迟到响应把当前月份的事件覆盖了，用户看到的是一片空白')
})

test('旧请求失败不许给已经加载好的新月份弹加载失败', async () => {
  const gates = { '2026-08-01': deferred(), '2026-09-01': deferred() }
  const toasts = []
  const vm = makeVm(loadOptions((from) => gates[from].promise,
    { showToast: (o) => toasts.push(o) }))

  const aug = vm.loadTasks('2026-08-01', '2026-09-01')
  const sep = vm.loadTasks('2026-09-01', '2026-10-01')
  gates['2026-09-01'].resolve({ data: { tasks: [taskOf(20, '2026-09-10')] } })
  await sep
  gates['2026-08-01'].resolve(Promise.reject(new Error('boom')))
  await aug

  assert.deepEqual(toasts, [], '被放弃的旧请求失败了也不该打断当前视图')
  assert.deepEqual(vm.calendarOptions.events.map((e) => e.id), ['20'])
})

test('正常顺序下最后一次请求的结果照常生效', async () => {
  const vm = makeVm(loadOptions((from) =>
    Promise.resolve({ data: { tasks: [taskOf(from === '2026-08-01' ? 10 : 20, from)] } })))
  await vm.loadTasks('2026-08-01', '2026-09-01')
  assert.deepEqual(vm.calendarOptions.events.map((e) => e.id), ['10'])
  await vm.loadTasks('2026-09-01', '2026-10-01')
  assert.deepEqual(vm.calendarOptions.events.map((e) => e.id), ['20'])
})
