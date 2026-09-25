// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机 BUG-12（C3-08）：搜索面板连续输入会叠出一串请求——旧请求只是在结果
// 回来时被 seq 丢掉，HTTP 请求本身一直挂着、后端照样把每个文件抽一遍；回车与防抖各发
// 一次；清空关键词也要发一次空搜索。这里守前端这一半：防抖 300ms、新查询取消旧请求
// （AbortController → uni.request 的 RequestTask.abort）、被取消的请求静默收尾、
// 卸载时收干净。
// 同 search-panel-stale-toast.test.mjs：把 <script> 抽出来真跑，api 当参数注入。
import { test, mock } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/SearchPanel.vue', import.meta.url), 'utf8')
const API = readFileSync(
  new URL('../../src/services/api.js', import.meta.url), 'utf8')

function loadOptions(searchProjectContent, uni) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const factory = new Function(
    'searchProjectContent', 'getProjectTags', 'FileTypeIcon',
    'TAG_TYPE_PARTY', 'TAG_TYPE_ISSUE', 'TAG_TYPE_NORMAL', 'normalizeTagType',
    'uni', body)
  return factory(searchProjectContent, () => Promise.resolve([]), {},
    'PARTY', 'ISSUE', 'NORMAL', () => 'NORMAL', uni || { showToast() {} })
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

// 模拟 request()：signal 一 abort 就以 AbortError 拒绝（与 api.js 的实现同口径）
function abortableApi() {
  const calls = []
  const fn = (projectId, payload, opts) => {
    const d = deferred()
    const signal = opts && opts.signal
    calls.push({ payload, signal, ...d })
    if (signal) {
      signal.addEventListener('abort', () => {
        const e = new Error('aborted'); e.name = 'AbortError'; e.aborted = true
        d.reject(e)
      }, { once: true })
    }
    return d.promise
  }
  return { fn, calls }
}

const RESULT = { totalMatches: 1, totalFiles: 1, results: [] }

test('连续输入只在停顿 300ms 后发一次请求', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const api = abortableApi()
  const vm = makeVm(loadOptions(api.fn), { projectId: 1 })

  for (const q of ['借', '借款', '借款合', '借款合同']) {
    vm.searchQuery = q
    vm.onSearchInput()
    t.mock.timers.tick(100)
  }
  assert.equal(api.calls.length, 0, '还在输入就不该发请求')
  t.mock.timers.tick(199)
  assert.equal(api.calls.length, 0, '停顿不到 300ms 不发')
  t.mock.timers.tick(1)
  assert.equal(api.calls.length, 1, '停顿满 300ms 发且只发一次')
  assert.equal(api.calls[0].payload.query, '借款合同', '发的是最后一次输入')
})

test('新查询发起时取消上一次在途请求（不是只丢结果，HTTP 请求本身要断）', () => {
  const api = abortableApi()
  const vm = makeVm(loadOptions(api.fn), { projectId: 1 })

  vm.searchQuery = '借款'
  vm.performSearch()
  vm.searchQuery = '借款合同'
  vm.performSearch()

  assert.equal(api.calls.length, 2)
  assert.ok(api.calls[0].signal, '请求必须带 AbortSignal，否则无从取消')
  assert.equal(api.calls[0].signal.aborted, true, '旧请求在新查询发起时就该被取消')
  assert.equal(api.calls[1].signal.aborted, false, '新请求不能被误取消')
})

test('被取消的请求静默收尾：不弹「搜索失败」、不关掉新请求的加载态', async () => {
  const toasts = []
  const api = abortableApi()
  const vm = makeVm(loadOptions(api.fn, { showToast: (o) => toasts.push(o) }), { projectId: 1 })

  vm.searchQuery = '借款'
  const first = vm.performSearch()
  vm.searchQuery = '借款合同'
  const second = vm.performSearch()
  assert.ok(api.calls[0].signal && api.calls[0].signal.aborted, '旧请求没被取消（下面的 await 会永远挂住）')
  await first

  assert.deepEqual(toasts, [], '用户主动换了查询词，被取消的旧请求不是「失败」')
  assert.equal(vm.loading, true, '新请求还在路上，加载态要保持')

  api.calls[1].resolve(RESULT)
  await second
  assert.equal(vm.loading, false)
  assert.equal(vm.results.totalMatches, 1)
})

test('回车立即搜索，并撤掉还没到点的防抖（不再一次输入发两遍）', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const api = abortableApi()
  const vm = makeVm(loadOptions(api.fn), { projectId: 1 })

  vm.searchQuery = '借款合同'
  vm.onSearchInput()
  vm.performSearch() // @confirm
  t.mock.timers.tick(1000)

  assert.equal(api.calls.length, 1, '回车已经搜过了，防抖到点不该再发一遍')
})

test('只选了标签（关键词为空）照常发请求', () => {
  const api = abortableApi()
  const vm = makeVm(loadOptions(api.fn), { projectId: 1 })

  vm.toggleTag(7)

  assert.equal(api.calls.length, 1, '纯标签筛选是合法搜索')
  assert.deepEqual(api.calls[0].payload.tagIds, [7])
})

test('面板卸载时取消在途请求与待发的防抖', (t) => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const api = abortableApi()
  const options = loadOptions(api.fn)
  const vm = makeVm(options, { projectId: 1 })

  vm.searchQuery = '借款'
  vm.performSearch()
  vm.searchQuery = '借款合同'
  vm.onSearchInput()
  assert.equal(typeof options.beforeUnmount, 'function', 'SearchPanel 需要 beforeUnmount 收尾')
  options.beforeUnmount.call(vm)
  t.mock.timers.tick(1000)

  assert.equal(api.calls[0].signal.aborted, true, '卸载时在途请求要取消')
  assert.equal(api.calls.length, 1, '卸载后防抖不许再发请求')
})

// ---- services/api.js：request() 真正把 AbortSignal 接到 uni.request 的 RequestTask.abort ----

function loadRequest(uniStub) {
  const start = API.indexOf('function request(options) {')
  assert.ok(start >= 0, '找不到 request()')
  const end = API.indexOf('\n}\n', start)
  const src = API.slice(start, end + 2)
  const deps = {
    getApiBaseUrl: () => 'http://127.0.0.1:1',
    getAuthHeaders: () => ({}),
    t: (k) => k,
    uni: uniStub,
    isDesktopHost: () => true,
    clearSession: () => {},
    isOnLoginPage: () => false,
    console: { log() {}, warn() {}, error: (...a) => uniStub.errors.push(a) },
  }
  const names = Object.keys(deps)
  return new Function(...names, src + '\nreturn request')(...names.map((n) => deps[n]))
}

function fakeUni() {
  const u = { errors: [], tasks: [], lastOptions: null }
  u.request = (opts) => {
    u.lastOptions = opts
    const task = { aborted: 0, abort() { this.aborted++; opts.fail({ errMsg: 'request:fail abort' }) } }
    u.tasks.push(task)
    return task
  }
  return u
}

test('request()：signal 取消时调用 RequestTask.abort，以 AbortError 拒绝，不刷「网络请求失败」', async () => {
  const uni = fakeUni()
  const request = loadRequest(uni)
  const ctrl = new AbortController()
  const p = request({ url: '/api/projects/1/search', method: 'POST', data: {}, signal: ctrl.signal })
  ctrl.abort()

  await assert.rejects(p, (e) => e.name === 'AbortError')
  assert.equal(uni.tasks[0].aborted, 1, '必须真的断开底层请求')
  assert.equal(uni.errors.length, 0, '主动取消不是网络故障，不该打错误日志')
  assert.equal('signal' in uni.lastOptions, false, 'signal 不透传给 uni.request')
})

test('request()：已取消的 signal 不再发请求', async () => {
  const uni = fakeUni()
  const request = loadRequest(uni)
  const ctrl = new AbortController()
  ctrl.abort()

  await assert.rejects(request({ url: '/x', method: 'POST', signal: ctrl.signal }), (e) => e.name === 'AbortError')
  assert.equal(uni.tasks.length, 0)
})

test('searchProjectContent 把 signal 交给 request()', () => {
  const i = API.indexOf('export function searchProjectContent(')
  const body = API.slice(i, API.indexOf('\n}\n', i))
  assert.match(body, /signal/, 'searchProjectContent 要能接收并下传 AbortSignal')
})
