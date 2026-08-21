// 审计（dev-board#74）：PluginPane 在三处调用点都没带 :key，同一个面板槽位换插件时
// 组件被复用、iframe 只是换了 src。插件 A 发起的桥调用（files.list / files.read 都是
// 真网络往返）若在换成插件 B 之后才 resolve，reply() 只检查 contentWindow 存在，
// 就把 A 的响应按 seq 投进了 B 的窗口——B 的 SDK 序号也从 1 起，会拿别人的数据
// 兑现自己的 promise（B 可能根本没有 file_read 权限）。
// 同 SearchPanel 的做法：把 <script> 抽出来真跑一遍，依赖当参数注入。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(
  new URL('../../src/components/PluginPane.vue', import.meta.url), 'utf8')

function loadOptions(deps) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const factory = new Function(
    'getProjectFiles', 'getFileText', 'getAppLanguage', 'uni', body)
  return factory(deps.getProjectFiles, deps.getFileText, () => 'zh-CN', deps.uni || {})
}

function makeVm(options, props) {
  const vm = Object.assign({}, options.data ? options.data() : {}, props)
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  for (const [k, fn] of Object.entries(options.computed))
    Object.defineProperty(vm, k, { get: fn.bind(vm) })
  return vm
}

// 换插件：改 url（顺带改 pluginId/permissions），并触发组件自己的 watch。
function switchPlugin(options, vm, next) {
  Object.assign(vm, next)
  const w = options.watch || {}
  for (const key of Object.keys(next)) {
    const h = w[key]
    if (typeof h === 'function') h.call(vm)
    else if (h && typeof h.handler === 'function') h.handler.call(vm)
  }
}

const deferred = () => {
  let resolve
  const promise = new Promise((r) => { resolve = r })
  return { promise, resolve }
}

const makeWindow = () => {
  const inbox = []
  return { inbox, postMessage: (m) => inbox.push(m) }
}

const URL_A = 'http://127.0.0.1:9696/api/plugin-web/alpha/index.html'
const URL_B = 'http://127.0.0.1:9696/api/plugin-web/beta/index.html'

test('换插件之后，前一个插件迟到的桥响应不许投进新插件的窗口', async () => {
  const gate = deferred()
  const options = loadOptions({ getProjectFiles: () => gate.promise })
  const winA = makeWindow()
  const winB = makeWindow()
  const frame = { contentWindow: winA }
  const vm = makeVm(options, {
    url: URL_A, pluginId: 'alpha', permissions: ['file_read'], projectId: 7
  })
  vm.$refs = { pluginFrame: frame }

  // 插件 A 发起一次慢调用
  const pending = vm.onMessage({
    source: winA,
    data: { awd: 1, type: 'call', seq: 1, method: 'files.list', params: {} }
  })

  // 还没回来就把同一个槽位换成插件 B：组件复用，iframe 换 src
  switchPlugin(options, vm, { url: URL_B, pluginId: 'beta', permissions: [] })
  frame.contentWindow = winB

  gate.resolve([{ id: 1, name: 'secret.txt', fileSize: 3, isFolder: false }])
  await pending

  assert.deepEqual(winB.inbox, [], '插件 A 的响应被投进了插件 B 的窗口')
})

test('没换插件时正常回响应', async () => {
  const options = loadOptions({
    getProjectFiles: () => Promise.resolve([
      { id: 1, name: 'a.txt', fileSize: 3, isFolder: false }
    ])
  })
  const win = makeWindow()
  const vm = makeVm(options, {
    url: URL_A, pluginId: 'alpha', permissions: ['file_read'], projectId: 7
  })
  vm.$refs = { pluginFrame: { contentWindow: win } }

  await vm.onMessage({
    source: win,
    data: { awd: 1, type: 'call', seq: 1, method: 'files.list', params: {} }
  })

  assert.equal(win.inbox.length, 1, '正常调用没有拿到响应')
  assert.equal(win.inbox[0].type, 'result')
  assert.equal(win.inbox[0].seq, 1)
  assert.equal(win.inbox[0].ok, true)
  assert.equal(win.inbox[0].result.files.length, 1)
})
