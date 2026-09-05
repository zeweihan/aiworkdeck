// 剪贴板采集桥的登录态闸门（dev-board#455）。
//
// 病灶：bindClipboardListener() 第一件事是 `const user = getCurrentUser(); if (!user) return`，
// 而桌面单机版免登后 checkba_user 这个本地存储永远为空（project-overview.vue 里同一句
// 注释写了两遍）。于是整座桥——桌面 IPC 订阅与 H5 三路兜底——都在第 17 行之前就被关掉，
// Cmd+C / pbcopy 一条都不入库；而 GET /api/clipboard 在 local-mode 下照常返回存量，
// 表现就是「最新条目停在某一天」。
//
// 这里既钉行为也钉源码：
//   - 行为：把 <script> 之外的模块体剥出来当普通对象跑（同 tests/_lib/review-panel-vm.mjs
//     的路子），桌面宿主 + 无登录态时必须真的订阅到 host.clipboard.onCopied；
//   - 源码：那条无条件的 `if (!user) return` 不许被写回来（锚点唯一）。
// 只留源码正则是近乎恒真的（换个写法就绕过去了），所以行为断言才是主证。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/pages/project-overview/clipboardBridge.js', import.meta.url), 'utf8')

// 依赖当形参喂进去；window/document/uni 一并作形参，避免污染 globalThis。
function makeBridge(deps) {
  const body = SRC
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace('export const clipboardBridgeMethods =', 'return')
  const names = ['saveClipboardText', 'saveClipboardFile', 'getCurrentUser', 'host', 'isDesktopHost', 'window', 'document', 'uni']
  // eslint-disable-next-line no-new-func
  return new Function(...names, body)(...names.map((n) => deps[n]))
}

function makeEnv(over = {}) {
  const calls = { saved: [], toasts: [], docListeners: [], winListeners: [], warns: [] }
  const subs = []
  const env = {
    calls,
    subs,
    saveClipboardText: over.saveClipboardText || (async (t) => { calls.saved.push(t); return { data: { id: 1, text: t } } }),
    saveClipboardFile: async () => ({ data: {} }),
    getCurrentUser: over.getCurrentUser || (() => null),
    isDesktopHost: over.isDesktopHost || (() => true),
    host: {
      clipboard: over.noClipboardHost ? undefined : { onCopied: (fn) => { subs.push(fn); return () => {} } },
    },
    window: { addEventListener: (n, f) => calls.winListeners.push(n) },
    document: { addEventListener: (n, f) => calls.docListeners.push(n) },
    uni: { showToast: (o) => calls.toasts.push(o) },
  }
  return env
}

function makeVm(methods, over = {}) {
  return Object.assign({
    isDesktopApp: over.isDesktopApp !== undefined ? over.isDesktopApp : true,
    $t: (k) => k,
    onClipboardSaved: () => {},
  }, methods)
}

test('桌面宿主 + 无 checkba_user：仍然订阅 host.clipboard.onCopied（免登不等于未登录）', () => {
  const env = makeEnv()
  const vm = makeVm(makeBridge(env))
  vm.bindClipboardListener()
  assert.equal(env.subs.length, 1, '桌面订阅点必须被接上；为 0 说明登录态闸门又把整座桥关掉了')
})

test('桌面宿主 + 无 checkba_user：推送的文本真的走到 saveClipboardText', async () => {
  const env = makeEnv()
  const vm = makeVm(makeBridge(env))
  vm.bindClipboardListener()
  await env.subs[0]({ type: 'TEXT', text: 'hello-455', ts: 1 })
  assert.deepEqual(env.calls.saved, ['hello-455'])
})

test('纯浏览器态 + 无登录：维持不采集（否则每次复制都打一次 4010）', () => {
  const env = makeEnv({ isDesktopHost: () => false })
  const vm = makeVm(makeBridge(env), { isDesktopApp: false })
  vm.bindClipboardListener()
  assert.deepEqual(env.calls.docListeners, [], '未登录的浏览器态不该挂 copy/paste 监听')
  assert.deepEqual(env.calls.winListeners, [])
})

test('纯浏览器态 + 已登录：照旧挂三路兜底监听', () => {
  const env = makeEnv({ isDesktopHost: () => false, getCurrentUser: () => ({ id: 1 }) })
  const vm = makeVm(makeBridge(env), { isDesktopApp: false })
  vm.bindClipboardListener()
  assert.deepEqual(env.calls.docListeners, ['paste', 'copy'])
  assert.deepEqual(env.calls.winListeners, ['keydown'])
})

test('桌面端入库失败不许 toast 风暴：主进程每秒轮询，一次机器级复制就是一次提示', async () => {
  const env = makeEnv({ saveClipboardText: async () => { throw new Error('401') } })
  const vm = makeVm(makeBridge(env))
  vm.bindClipboardListener()
  for (let i = 0; i < 5; i++) {
    await env.subs[0]({ type: 'TEXT', text: 'boom-' + i, ts: i })
  }
  assert.ok(env.calls.toasts.length <= 1, `桌面端失败提示最多一次，实际 ${env.calls.toasts.length} 次`)
})

test('源码闸门：bindClipboardListener 体内不许再有无条件的 if (!user) return', () => {
  // 锚点前面必须带换行 + 缩进，否则 unbindClipboardListener 会被当成第二处命中
  const ANCHOR = '\n    bindClipboardListener() {'
  const start = SRC.indexOf(ANCHOR)
  assert.notEqual(start, -1, '锚点 bindClipboardListener() { 未找到')
  assert.equal(SRC.indexOf(ANCHOR, start + 1), -1, '锚点必须唯一')
  const end = SRC.indexOf('\n    onClipboardSaved(', start)
  assert.ok(end > start, '锚点 onClipboardSaved 未找到')
  const bodyText = SRC.slice(start, end)
  assert.equal(/if\s*\(\s*!\s*user\s*\)\s*return/.test(bodyText), false,
    '无条件的登录态闸门被写回来了：桌面免登下 checkba_user 恒空，这一行会关掉整座采集桥')
})
