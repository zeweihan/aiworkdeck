// 审计（dev-board#74）：DdRequestEditor 在 project-overview 里没有 :key，
// 切换不同尽调清单标签时是同一个实例被复用，requestId watcher 只是再调一次
// fetchData()，先发的那次若后返回就会把后发那次的数据整个盖掉——头部标题、
// 状态和行数据全是上一份清单的，而 requestId 指向的却是新那份。
// 另一条：删除清单后父组件没接 @deleted，面板还留着已删清单的行，
// 再操作就是拿已不存在的 id 打接口。组件这一侧得自己把状态清干净。
// 做法同 SearchPanel/VersionTimeline：把 <script> 抽出来真跑一遍，依赖注入。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/DdRequestEditor.vue', import.meta.url), 'utf8')

function loadOptions(api, uni) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const factory = new Function('api', 'getApiBaseUrl', 'getSessionId', 'ICONS', 'uni', body)
  return factory(api, () => '', () => '', {}, uni)
}

function makeVm(options, props) {
  const vm = Object.assign({}, options.data(), props)
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  for (const [k, fn] of Object.entries(options.computed))
    Object.defineProperty(vm, k, { get: fn.bind(vm) })
  vm.$t = (k) => k
  vm.emitted = []
  vm.$emit = (name, payload) => vm.emitted.push([name, payload])
  vm.$forceUpdate = () => {}
  return vm
}

const deferred = () => {
  let resolve, reject
  const promise = new Promise((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

const detail = (name, id) => ({ request: { id, name, status: 'DRAFT' }, items: [{ id, title: name, status: 'PENDING' }] })

test('切标签后迟到的旧清单响应不许盖掉当前清单', async () => {
  const gates = { A: deferred(), B: deferred() }
  let calls = 0
  const options = loadOptions(
    { getDdRequestDetails: () => (++calls === 1 ? gates.A.promise : gates.B.promise) },
    { showToast: () => {} })
  const vm = makeVm(options, { requestId: 1 })

  const first = vm.fetchData()   // 打开清单 A（网络慢）
  vm.requestId = 2               // 立刻切到清单 B，实例复用，watcher 再发一次
  const second = vm.fetchData()

  gates.B.resolve(detail('B', 2))
  await second
  gates.A.resolve(detail('A', 1))
  await first

  assert.equal(vm.request.id, 2, '显示的清单和 requestId 对不上了')
  assert.equal(vm.requestName, 'B')
  assert.deepEqual(vm.items.map(i => i.id), [2], '行数据是上一份清单的')
})

test('最新一次响应照常落地', async () => {
  const options = loadOptions(
    { getDdRequestDetails: (id) => Promise.resolve(detail('B', id)) },
    { showToast: () => {} })
  const vm = makeVm(options, { requestId: 7 })

  await vm.fetchData()

  assert.equal(vm.request.id, 7)
  assert.equal(vm.requestName, 'B')
  assert.equal(vm.items.length, 1)
})

test('删除清单后面板要清空，不再留着已删清单的行', async () => {
  const options = loadOptions(
    {
      getDdRequestDetails: (id) => Promise.resolve(detail('A', id)),
      deleteDdRequest: () => Promise.resolve()
    },
    { showToast: () => {}, showModal: (o) => o.success({ confirm: true }) })
  const vm = makeVm(options, { requestId: 1 })
  await vm.fetchData()
  assert.equal(vm.items.length, 1)

  await vm.handleDeleteRequest()
  await new Promise(r => setTimeout(r, 0))

  assert.equal(vm.request, null, '已删清单的抬头还在')
  assert.deepEqual(vm.items, [], '已删清单的行还在')
  assert.equal(vm.deleted, true)
  assert.deepEqual(vm.emitted.map(e => e[0]), ['deleted'])
})

test('删除时在途的 fetch 回来不许把已删清单重新填回去', async () => {
  const gate = deferred()
  const options = loadOptions(
    {
      getDdRequestDetails: () => gate.promise,
      deleteDdRequest: () => Promise.resolve()
    },
    { showToast: () => {}, showModal: (o) => o.success({ confirm: true }) })
  const vm = makeVm(options, { requestId: 1 })

  const pending = vm.fetchData()
  await vm.handleDeleteRequest()
  await new Promise(r => setTimeout(r, 0))
  gate.resolve(detail('A', 1))
  await pending

  assert.equal(vm.request, null, '已删的清单被在途响应填了回来')
  assert.deepEqual(vm.items, [])
})

test('删除后改标题不再对已不存在的清单发请求', async () => {
  const calls = []
  const options = loadOptions(
    {
      getDdRequestDetails: (id) => Promise.resolve(detail('A', id)),
      deleteDdRequest: () => Promise.resolve(),
      updateDdRequest: (id, name) => { calls.push([id, name]); return Promise.resolve() }
    },
    { showToast: () => {}, showModal: (o) => o.success({ confirm: true }) })
  const vm = makeVm(options, { requestId: 1 })
  await vm.fetchData()
  await vm.handleDeleteRequest()
  await new Promise(r => setTimeout(r, 0))

  vm.requestName = '随手改的名字'
  await vm.updateRequestName()

  assert.deepEqual(calls, [], '对着已删除的清单发了改名请求')
})
