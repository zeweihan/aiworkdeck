// 审计（dev-board#74）：标签管理的「添加」没有在途闸。newTagName 要等 await 返回才清，
// 按钮的 :disabled 又只看 newTagName，所以在请求返回前双击（或回车后紧接着点一下）
// 会用同一个名字发两次 createTag：第一次建成，第二次被后端的同项目重名校验驳回，
// 弹出「Failed to create tag」——一次其实成功的操作却给了用户失败提示。
//
// 组件带 @/ 别名 import 不进来（本目录既有写法），这里把 handleAdd 的函数体抠出来
// 用 new Function 起真身跑，依赖全部注入，属于行为断言而非文本断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const src = readFileSync(new URL('../../src/components/TagManager.vue', import.meta.url), 'utf8')

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

// 后端对同一项目内的标签名唯一，重名请求直接抛错
function build({ latency = true } = {}) {
  const calls = { creates: [], toasts: [], releases: [] }
  const names = new Set()
  const api = {
    createTag: (projectId, payload) => {
      calls.creates.push(payload.name)
      const run = () => {
        if (names.has(payload.name)) throw new Error('标签名已存在')
        names.add(payload.name)
        return { id: names.size }
      }
      if (!latency) return Promise.resolve(run())
      return new Promise((resolve, reject) => {
        calls.releases.push(() => { try { resolve(run()) } catch (e) { reject(e) } })
      })
    }
  }
  const uni = { showToast: (o) => calls.toasts.push(o) }
  const fn = new Function(
    'api', 'uni', 'TAG_TYPE_NORMAL', 'TAG_TYPE_DEFAULT_COLORS',
    `return async function handleAdd() ${extractBody('handleAdd')}`,
  )(api, uni, 'NORMAL', { NORMAL: '#6B7280' })
  const ctx = {
    projectId: 1,
    newTagName: '原告主张',
    newTagType: 'NORMAL',
    newTagColor: '#6B7280',
    refreshTags() {},
  }
  return { fn, ctx, calls }
}

test('请求在途时再点添加不会重复提交同一个名字', async () => {
  const { fn, ctx, calls } = build()

  const first = fn.call(ctx)
  // 第一次请求还没返回（newTagName 也就还没清），用户又点了一下
  const second = fn.call(ctx)
  assert.equal(calls.creates.length, 1, '在途期间第二次点击必须被挡住')

  calls.releases.forEach((cb) => cb())
  await Promise.all([first, second])
  assert.deepEqual(calls.toasts, [], '一次成功的创建不该弹出失败提示')
  assert.equal(ctx.newTagName, '', '成功后输入框要清空')
})

test('创建失败后闸要放开，允许重试', async () => {
  const { fn, ctx, calls } = build({ latency: false })

  await fn.call(ctx)
  assert.equal(calls.creates.length, 1)

  ctx.newTagName = '原告主张'
  await fn.call(ctx)
  assert.equal(calls.creates.length, 2, '失败后应当能重试，闸不能卡死')
  assert.equal(calls.toasts.length, 1, '重名失败照旧提示')
})

test('按钮的禁用条件要带上在途标志', () => {
  assert.match(src, /:disabled="!newTagName \|\| adding"/)
})
