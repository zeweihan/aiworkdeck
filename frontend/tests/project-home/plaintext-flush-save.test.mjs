// 关闭标签时的落盘竞态（dev-board#74 审计）：flushSave 只等固定几秒就放行，
// 在途的那一次自动保存还没回来时，saving 仍是 true，紧接着的 save() 原地 no-op
// （只重挂一个防抖定时器），而组件随即卸载、定时器被 beforeUnmount 清掉——
// 在途请求发出**之后**的那批输入就这么静默丢了。
//
// 组件带 @/ 别名 import 不进来，这里把 <script> 块摘出来、剥掉 import 后当函数求值
// （被剥掉的符号只在没被调用的方法体里出现），拿到 options 后手工拼一个实例。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SFC = readFileSync(
  new URL('../../src/components/PlainTextEditor.vue', import.meta.url), 'utf8')

function loadOptions(setTimeoutStub, clearTimeoutStub) {
  const script = SFC.match(/<script>([\s\S]*?)<\/script>/)[1]
  const body = script
    .replace(/^import .*$/gm, '')
    .replace('export default', 'return')
  // eslint-disable-next-line no-new-func
  return new Function('setTimeout', 'clearTimeout', body)(setTimeoutStub, clearTimeoutStub)
}

function makeEditor() {
  const uploads = []          // { content, resolve, reject, done }
  const deferred = []
  // 防抖/重试（>=1s）在测试里挂起不放行；flushSave 的百毫秒轮询立刻推进，
  // 免得真的等满十秒。
  const setTimeoutStub = (fn, ms) => {
    if (ms >= 1000) return 1
    Promise.resolve().then(fn)
    return 2
  }
  const opts = loadOptions(setTimeoutStub, () => {})
  const inst = Object.assign({}, opts.data(), {
    phase: 'ready',
    _loadOk: true,
    _seq: 0,
    _saveTimer: null,
    _retryTimer: null,
    _inflight: null,
    _view: { state: { doc: { toString: () => inst._text } } },
    _text: 'v1'
  })
  for (const [k, fn] of Object.entries(opts.methods)) inst[k] = fn.bind(inst)
  // 网络层替身：每次上传挂起，由测试决定什么时候回来
  inst.uploadContent = (content) => new Promise((resolve, reject) => {
    const d = { content, resolve, reject }
    uploads.push(d)
    deferred.push(d)
  })
  const edit = (text) => { inst._text = text; inst.onUserEdit() }
  return { inst, uploads, edit }
}

const tick = async (n = 8) => { for (let i = 0; i < n; i++) await Promise.resolve() }

test('flushSave 必须等在途保存回来，再把之后的输入存出去', async () => {
  const { inst, uploads, edit } = makeEditor()

  // 1) 自动保存起飞，抓的是当时的内容
  edit('v1 + 第一批')
  const first = inst.save()
  await tick()
  assert.equal(uploads.length, 1)
  assert.equal(uploads[0].content, 'v1 + 第一批')
  assert.equal(inst.saving, true)

  // 2) 请求还没回来，用户又打了一批字
  edit('v1 + 第一批 + 第二批')

  // 3) 关标签：closeFile 在这里 await flushSave
  let flushed = false
  const flush = inst.flushSave().then(() => { flushed = true })
  await tick(800)   // 足够老实现的百次轮询跑完

  assert.equal(flushed, false,
    'flushSave 不许在在途请求回来之前就放行——放行时 saving 还是 true，' +
    '接下来的 save() 只会 no-op，第二批输入随卸载一起丢')

  // 4) 慢请求终于回来
  uploads[0].resolve()
  await first
  await tick()
  assert.equal(uploads.length, 2, '在途保存结束后必须补发第二批输入')
  assert.equal(uploads[1].content, 'v1 + 第一批 + 第二批')

  uploads[1].resolve()
  await flush
  assert.equal(flushed, true)
  assert.equal(inst.dirty, false)
})

test('在途保存失败也要放行，并按当前内容再存一次', async () => {
  const { inst, uploads, edit } = makeEditor()
  edit('第一批')
  const first = inst.save()
  await tick()
  edit('第一批 + 第二批')

  const flush = inst.flushSave()
  uploads[0].reject(new Error('network error'))
  await first
  await tick()
  assert.equal(uploads.length, 2)
  assert.equal(uploads[1].content, '第一批 + 第二批')
  uploads[1].resolve()
  await flush
  assert.equal(inst.dirty, false)
})

test('没有在途保存时 flushSave 直接落盘一次（回归守卫）', async () => {
  const { inst, uploads, edit } = makeEditor()
  edit('只有一批')
  const flush = inst.flushSave()
  await tick()
  assert.equal(uploads.length, 1)
  uploads[0].resolve()
  await flush
  assert.equal(inst.dirty, false)
})

test('不脏也没有在途保存时不空存一次', async () => {
  const { inst, uploads } = makeEditor()
  await inst.flushSave()
  assert.equal(uploads.length, 0)
})
