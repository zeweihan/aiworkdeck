// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { localeValuesOf } from './_locale-text.mjs'

const SRC = readFileSync(
  resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/TaskSchedule.vue'),
  'utf8')
const ZH = readFileSync(new URL('../../src/locales/zh-CN/projects.js', import.meta.url), 'utf8')

// 只在「实际代码」里做禁字断言：注释里必须能写清楚为什么不做某件事，
// 那些说明性文字不该把断言判红。
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const CODE = stripComments(SRC)
// 文案类禁字断言要看组件实际引用的 locale 值——只查源码的话，
// 迁移后把 locale 改成禁用词也拦不住（见 _locale-text.mjs）。
const CODE_TEXT = CODE + '\n' + localeValuesOf(SRC)

test('e2e 锚点：根节点类名是 task-schedule', () => {
  assert.ok(SRC.includes('class="task-schedule"'))
})

test('props 契约：只收 projectId（数据自己读 taskStore），compact 给窄栏', () => {
  assert.match(SRC, /projectId:\s*\{\s*type:\s*\[Number,\s*String\],\s*required:\s*true\s*\}/)
  assert.match(SRC, /compact:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.ok(!/\btasks:\s*\{\s*type:\s*Array/.test(SRC), '不再由宿主传 tasks 数组')
})

test('空态文案存在（A 期唯一会渲染的分支）', () => {
  assert.ok(ZH.includes('还没有排任务'), '文案已迁 locale')
  assert.ok(SRC.includes('noTasksTitle'), '组件要引用该 key')
})

test('读写统一走 taskStore，行用 TaskRow，按到期分组 + 已完成折叠（dev-board#898）', () => {
  assert.ok(SRC.includes("from '@/utils/taskStore.js'"))
  assert.ok(CODE.includes('taskStore.byProject[String(this.projectId)]'), '读项目缓存')
  assert.ok(CODE.includes('loadProjectTasks(this.projectId'), '挂载/刷新时经 store 取数')
  assert.ok(CODE.includes('updateTask(task.id, { status: nextStatus })'), '完成勾选直接调 store')
  assert.ok(CODE.includes('deleteTask(task.id)'), '删除直接调 store')
  assert.ok(!/from '@\/services\/api\.js'/.test(SRC), '不再自己调接口')
  assert.ok(!CODE.includes('$emit(\'toggle\''), '写操作不再 emit 给宿主')
  assert.match(SRC, /<TaskRow[\s\S]*?:show-project="false"[\s\S]*?:show-files="true"/)
  assert.ok(CODE.includes('groupByDue(this.tasks)'))
  for (const k of ['groupOverdue', 'groupToday', 'groupWeek', 'groupLater', 'groupDoneCount']) {
    assert.ok(CODE.includes("'calendar." + k + "'"), '分组标题 ' + k)
  }
  assert.ok(CODE.includes('showDone && groups.done.length'), '已完成默认折叠')
})

test('「+ 添加」开统一弹窗并锁定本项目，不再内联两个输入框', () => {
  assert.match(SRC, /<TaskDialog[\s\S]*?:project-id="projectId"/)
  assert.ok(CODE.includes('@tap="openCreate"'))
  assert.ok(!CODE.includes('quick-create'), '内联快捷创建已撤')
  assert.ok(!CODE.includes('<input'), '不再有内联输入框')
  assert.ok(!CODE.includes('AwdDatePicker'))
})

test('宿主 ProjectHomePane 不再持有 tasks 数组，只把刷新转给日程块', () => {
  const PANE = stripComments(readFileSync(
    resolve(dirname(fileURLToPath(import.meta.url)), '../../src/components/project-home/ProjectHomePane.vue'),
    'utf8'))
  assert.match(PANE, /<TaskSchedule[\s\S]*?:project-id="projectId"/)
  for (const gone of ['onTaskToggle', 'onTaskQuickCreate', 'loadTasks', 'getProjectTasks', 'tasksLoading']) {
    assert.ok(!PANE.includes(gone), '宿主残留 ' + gone)
  }
  assert.ok(PANE.includes('this.$refs.taskSchedule.reload()'), 'refresh() 要让日程块强制重拉')
})

test('不混用 AI 步骤条的词', () => {
  assert.ok(!CODE_TEXT.includes('进度条'), '「进度」是 todo_write 的词，项目级里程碑一律叫「任务」')
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})
