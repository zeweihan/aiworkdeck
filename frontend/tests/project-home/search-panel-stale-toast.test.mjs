// 审计（dev-board#74）：SearchPanel.performSearch() 的 catch 分支没跟着 seq 走。
// 快速连点两个标签时（toggleTag 不去抖，每次都直接发请求），先发的那次若在
// 后发的那次成功落地之后才失败，仍会弹一次「搜索失败」——屏幕上明明是新结果。
// 同 VersionTimeline 的做法：把 <script> 抽出来真跑一遍，api 当参数注入。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/SearchPanel.vue', import.meta.url), 'utf8')

function loadOptions(api, uni) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const factory = new Function(
    'searchProjectContent', 'getProjectTags', 'FileTypeIcon',
    'TAG_TYPE_PARTY', 'TAG_TYPE_ISSUE', 'TAG_TYPE_NORMAL', 'normalizeTagType',
    'uni', body)
  return factory(api.searchProjectContent, () => Promise.resolve([]), {},
    'PARTY', 'ISSUE', 'NORMAL', () => 'NORMAL', uni)
}

function makeVm(options, props) {
  const vm = Object.assign({}, options.data(), props)
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  for (const [k, fn] of Object.entries(options.computed))
    Object.defineProperty(vm, k, { get: fn.bind(vm) })
  vm.$t = (k) => k
  return vm
}

const deferred = () => {
  let resolve, reject
  const promise = new Promise((r, j) => { resolve = r; reject = j })
  return { promise, resolve, reject }
}

test('先发请求的迟到失败不许在新结果已经渲染之后弹错误提示', async () => {
  const gates = { A: deferred(), B: deferred() }
  let calls = 0
  const toasts = []
  const options = loadOptions(
    { searchProjectContent: () => (++calls === 1 ? gates.A.promise : gates.B.promise) },
    { showToast: (o) => toasts.push(o) })
  const vm = makeVm(options, { projectId: 1 })

  const first = vm.performSearch()   // 勾标签 A
  const second = vm.performSearch()  // 立刻再勾标签 B

  // 后发的先成功，先发的后失败（网络抖动/超时）
  gates.B.resolve({ totalMatches: 1, totalFiles: 1, results: [] })
  await second
  gates.A.reject(new Error('network hiccup'))
  await first

  assert.deepEqual(toasts, [], '陈旧请求的失败弹了提示，但面板上是有效的新结果')
  assert.equal(vm.loading, false)
  assert.equal(vm.results.totalMatches, 1, '新结果不该被覆盖')
})

test('最新一次请求失败照常提示', async () => {
  const toasts = []
  const options = loadOptions(
    { searchProjectContent: () => Promise.reject(new Error('boom')) },
    { showToast: (o) => toasts.push(o) })
  const vm = makeVm(options, { projectId: 1 })

  await vm.performSearch()

  assert.equal(toasts.length, 1, '当前请求失败必须提示用户')
})
