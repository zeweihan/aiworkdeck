// 审计（dev-board#74）：左栏日历面板的快速新建没有防重复提交。
// 输入框按回车触发一次 submitQuickCreate，网络往返还没回来时再点「保存」，
// 两次调用各自发起一次 createTask，服务端就多出一条标题与日期完全相同的任务。
//
// 组件带 @/ 别名 import 不进来（本目录既有写法），这里把 submitQuickCreate 的
// 函数体抠出来用 new Function 起真身跑，依赖全部注入，属于行为断言而非文本断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(
  new URL('../../src/components/project-calendar/ProjectCalendarPane.vue', import.meta.url), 'utf8')

// 从 `async submitQuickCreate() {` 起做括号配对，取出完整函数体
function extractBody(name) {
  const head = src.indexOf(`async ${name}() {`)
  assert.ok(head > 0, `找不到 ${name}`)
  const start = src.indexOf('{', head)
  let depth = 0
  for (let i = start; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}' && --depth === 0) return src.slice(start, i + 1)
  }
  throw new Error(`${name} 的括号没配上`)
}

function build(createTask) {
  const calls = { creates: 0, toasts: [], loads: 0 }
  const uni = { showToast: (o) => calls.toasts.push(o) }
  const fn = new Function(
    'createTask', 'uni',
    `return async function submitQuickCreate() ${extractBody('submitQuickCreate')}`,
  )(createTask, uni)
  const ctx = {
    projectId: 7,
    quickTitle: '开庭',
    quickDate: '2026-09-01',
    quickCreateOpen: true,
    quickSaving: false,
    $t: (k) => k,
    loadTasks: () => { calls.loads++ },
  }
  return { fn, ctx, calls }
}

test('第一次提交还在飞的时候再提交一次，不会建出两条同名任务', async () => {
  let release
  const pending = new Promise((r) => { release = r })
  const { fn, ctx, calls } = build(async () => { calls.creates++; await pending })

  const first = fn.call(ctx)
  // 回车之后、请求返回之前，用户又点了一下「保存」
  const second = fn.call(ctx)
  assert.equal(calls.creates, 1, '第二次提交必须被挡住，否则服务端多一条重复任务')

  release()
  await Promise.all([first, second])
  assert.equal(calls.creates, 1)
  assert.equal(ctx.quickCreateOpen, false)
})

test('提交失败后放开，允许重试', async () => {
  const { fn, ctx, calls } = build(async () => { calls.creates++; throw new Error('网络错误') })

  await fn.call(ctx)
  assert.equal(ctx.quickSaving, false, '失败后必须放开，否则用户再也点不动保存')
  await fn.call(ctx)
  assert.equal(calls.creates, 2, '失败后应当能重试')
})

test('保存按钮在提交期间给出禁用态，不是只靠代码里的闸悄悄吞掉点击', () => {
  const btn = src.match(/<view[^>]*pcp-quick-btn-primary[^>]*>/)
  assert.ok(btn, '找不到保存按钮')
  assert.match(btn[0], /quickSaving/, '保存按钮要跟着 quickSaving 变成禁用态')
})
