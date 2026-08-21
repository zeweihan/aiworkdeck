// 审计（dev-board#74）：浏览器降级建空白项目时，busy 在请求返回后立刻放开，
// 而真正的 reLaunch 还压在 500ms 的 setTimeout 里。这段窗口里按钮是活的，
// blankName 也没清，再点一下就会用同一个名字再建一个空白项目。
//
// 组件带 @/ 别名 import 不进来（本仓既有写法），这里把 onCreateBlank 的函数体抠出来
// 用 new Function 起真身跑，依赖全部注入，属于行为断言而非文本断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/pages/newproject/index.vue', import.meta.url), 'utf8')

// 从 `async onCreateBlank() {` 起做括号配对，取出完整函数体
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
  const calls = { posts: 0, toasts: [], relaunches: [], timers: [] }
  const createProject = async () => { calls.posts++; return { id: calls.posts } }
  const uni = {
    showToast: (o) => calls.toasts.push(o),
    reLaunch: (o) => calls.relaunches.push(o.url),
  }
  // 假 setTimeout：只收回调不真排期，测试里手动触发，免得等 500ms
  const fakeSetTimeout = (cb) => { calls.timers.push(cb); return 1 }
  const fn = new Function(
    'createProject', 'uni', 'setTimeout',
    `return async function onCreateBlank() ${extractBody('onCreateBlank')}`,
  )(createProject, uni, fakeSetTimeout)
  const ctx = { blankName: '甲公司诉乙公司', busy: false, $t: (k) => k }
  return { fn, ctx, calls }
}

test('创建成功后、reLaunch 真正跳走之前，再点创建不会重复建项目', async () => {
  const { fn, ctx, calls } = build()

  await fn.call(ctx)
  assert.equal(calls.posts, 1)
  assert.equal(calls.relaunches.length, 0, 'reLaunch 还压在 setTimeout 里，页面没跳走')

  // 用户在这 500ms 里又点了一下（成功反馈只有一个 toast，双击很常见）
  await fn.call(ctx)
  assert.equal(calls.posts, 1, '第二次点击必须被挡住，否则会多出一个同名空白项目')

  // 跳转照旧发生，落到第一次创建的项目
  calls.timers.forEach((cb) => cb())
  assert.deepEqual(calls.relaunches, ['/pages/project-overview/project-overview?id=1'])
})

test('创建失败后按钮要放开，允许重试', async () => {
  const { calls } = build()
  const boom = async () => { calls.posts++; throw new Error('网络错误') }
  const uni = { showToast: (o) => calls.toasts.push(o), reLaunch: () => {} }
  const fn = new Function(
    'createProject', 'uni', 'setTimeout',
    `return async function onCreateBlank() ${extractBody('onCreateBlank')}`,
  )(boom, uni, (cb) => { calls.timers.push(cb) })
  const ctx = { blankName: '甲公司诉乙公司', busy: false, $t: (k) => k }

  await fn.call(ctx)
  assert.equal(ctx.busy, false, '失败后 busy 必须放开，否则用户再也点不动创建')
  await fn.call(ctx)
  assert.equal(calls.posts, 2, '失败后应当能重试')
})
