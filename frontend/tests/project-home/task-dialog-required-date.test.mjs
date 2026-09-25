// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-08（v0.49.0 真机批次 H）：项目概览「日程与任务」添加任务不填日期点保存曾静默
// 失败无任何提示。#982 事项/日程重做后，项目概览的 TaskSchedule.vue 已经把新建/编辑
// 表单统一交给 components/calendar/TaskDialog.vue（同一份表单也服务日程页与设置页
// 「我的待办」），本测试直接钉住 TaskDialog.submit() 的校验顺序：标题 → 日期 → 项目，
// 缺日期时必须弹 calendar.requiredDate 提示并且不发起保存请求。
//
// 状态：not-fixed（根因未定位）。这里只钉住校验分支，**不证明 BUG-08 已修**：#982 之前的
// TaskSchedule.submitQuickCreate 同样有缺日期 toast，QA 仍看到静默失败，说明病灶不在
// 校验分支。已排除的方向（静态排查）：
//   - toast 被遮：AwdToastHost 的 .awd-toast-stack z-index 10001 > .task-dialog-mask 5000；
//   - toast 被立即清掉：全仓 src/ 除 utils/toast.js 外没有任何 uni.hideToast 调用，
//     hideLoading 只清 loading 不清 toast 队列；
//   - 日期控件占位串当值：AwdDatePicker 是原生 <input type="date">，mm/dd/yyyy 只是
//     浏览器占位不是 value，未填/只填了一半时 value 为 ''，且只在 change（值完整）时上抛。
// 仍需真机有头复现：确认 QA 当时点的是哪个保存入口、是否选了日期、网络面板有无请求。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/components/calendar/TaskDialog.vue', import.meta.url), 'utf8')

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

function build() {
  const calls = { toasts: [], createTask: 0, updateTask: 0 }
  const uni = { showToast: (o) => calls.toasts.push(o) }
  const createTask = async () => { calls.createTask++; return { id: 1 } }
  const updateTask = async () => { calls.updateTask++; return { id: 1 } }
  const fn = new Function(
    'uni', 'createTask', 'updateTask',
    `return async function submit() ${extractBody('submit')}`,
  )(uni, createTask, updateTask)
  return { fn, calls }
}

test('新建事项不填日期点保存：弹「请选择日期」提示，且不发起保存请求（BUG-08 回归）', async () => {
  const { fn, calls } = build()
  const ctx = {
    busy: false,
    form: { title: '开庭准备', dueDate: '' },
    isEdit: false,
    effectiveProjectId: 7,
    $t: (k) => k,
  }

  await fn.call(ctx)

  assert.deepEqual(calls.toasts, [{ title: 'calendar.requiredDate', icon: 'none' }])
  assert.equal(calls.createTask, 0, '缺日期时不应该发起创建请求')
  assert.equal(calls.updateTask, 0)
})

test('标题为空优先于日期校验：只弹「请填写标题」', async () => {
  const { fn, calls } = build()
  const ctx = {
    busy: false,
    form: { title: '  ', dueDate: '' },
    isEdit: false,
    effectiveProjectId: 7,
    $t: (k) => k,
  }

  await fn.call(ctx)

  assert.deepEqual(calls.toasts, [{ title: 'calendar.requiredTitle', icon: 'none' }])
})

test('标题与日期都填了、新建时未选项目：弹「请选择项目」', async () => {
  const { fn, calls } = build()
  const ctx = {
    busy: false,
    form: { title: '开庭准备', dueDate: '2026-10-01' },
    isEdit: false,
    effectiveProjectId: null,
    $t: (k) => k,
  }

  await fn.call(ctx)

  assert.deepEqual(calls.toasts, [{ title: 'calendar.requiredProject', icon: 'none' }])
  assert.equal(calls.createTask, 0)
})
