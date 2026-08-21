// 审计（dev-board#74）：VersionTimeline.load() 没有请求乱序保护。
// 这里不做源码文本断言，而是把 .vue 的 <script> 块抽出来真跑一遍 load()——
// 组件带 @/ 别名 import 不进来，改成把两个 api 函数当参数注入。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/version/VersionTimeline.vue', import.meta.url), 'utf8')

// 抽 <script> 块：去掉 import 行（别名解析不了），export default 换成 return。
function loadOptions(api) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const factory = new Function(
    'getVersionTimeline', 'getDraftTimeline', 'VersionNodeDetail', 'uni', body)
  return factory(api.getVersionTimeline, api.getDraftTimeline, {}, { showToast() {} })
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
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

test('先发的过滤请求后到，不许覆盖后发的全量结果', async () => {
  const gates = { A: deferred(), all: deferred() }
  const options = loadOptions({
    getVersionTimeline: (_pid, _limit, fileId) => gates[fileId || 'all'].promise,
    getDraftTimeline: () => Promise.resolve({ data: { versions: [] } }),
  })
  const vm = makeVm(options, { projectId: 1, fileFilter: { fileId: 'A' }, drafts: [] })

  // 1) 按文件过滤，请求在途
  const filtered = vm.load()
  // 2) 立刻点「查看全部」清掉过滤，第二个请求发出
  vm.fileFilter = null
  const unfiltered = vm.load()

  // 3) 后发的先回，先发的后回（网络乱序）
  gates.all.resolve({ data: { versions: [{ sha: 'unfiltered', kind: 'session' }] } })
  await unfiltered
  gates.A.resolve({ data: { versions: [{ sha: 'filtered', kind: 'session' }] } })
  await filtered

  assert.deepEqual(vm.versions.map(v => v.sha), ['unfiltered'],
    '过滤请求的迟到响应把已清掉过滤的时间线覆盖了')
  // isCurrentHead 读的是实时 fileFilter 配陈旧 versions，会高亮错节点
  assert.equal(vm.isCurrentHead({ sha: 'unfiltered' }), true)
})

test('正常顺序下最后一次请求的结果照常生效', async () => {
  const options = loadOptions({
    getVersionTimeline: (_pid, _limit, fileId) =>
      Promise.resolve({ data: { versions: [{ sha: fileId || 'all', kind: 'session' }] } }),
    getDraftTimeline: () => Promise.resolve({ data: { versions: [] } }),
  })
  const vm = makeVm(options, { projectId: 1, fileFilter: { fileId: 'A' }, drafts: [] })
  await vm.load()
  assert.deepEqual(vm.versions.map(v => v.sha), ['A'])
  vm.fileFilter = null
  await vm.load()
  assert.deepEqual(vm.versions.map(v => v.sha), ['all'])
})
