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

test('props 契约', () => {
  assert.match(SRC, /tasks:\s*\{\s*type:\s*Array,\s*default:\s*\(\)\s*=>\s*\[\]\s*\}/)
  assert.match(SRC, /loading:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
})

test('空态文案存在（A 期唯一会渲染的分支）', () => {
  assert.ok(ZH.includes('还没有排任务'), '文案已迁 locale')
  assert.ok(SRC.includes('noTasksTitle'), '组件要引用该 key')
})

test('列表分支已落地（B 期真数据：未完成/已完成两个分支，写操作 emit 给宿主）', () => {
  // 2026-08-20 B 期落地（dev-board #52）：渲染分支从单一 tasks 拆成
  // openTasks（按 dueDate 升序）+ doneTasks（开关折叠），字段契约来自真实的
  // project_task 表（GET /api/projects/{id}/tasks）。
  assert.match(SRC, /v-for="t in openTasks"/)
  assert.match(SRC, /v-for="t in doneTasks"/)
  assert.ok(SRC.includes('t.title'))
  // 写操作不落在本组件：完成勾选/快捷创建一律 emit 给 ProjectHomePane
  assert.ok(SRC.includes("$emit('toggle', t)"))
  assert.ok(SRC.includes("'quick-create'"))
})

test('不混用 AI 步骤条的词', () => {
  assert.ok(!CODE_TEXT.includes('进度条'), '「进度」是 todo_write 的词，项目级里程碑一律叫「任务」')
})

test('禁 emoji + 浅色', () => {
  assert.ok(!/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u.test(SRC))
  assert.ok(!SRC.includes('#212629'))
})
