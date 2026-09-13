// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board#602「断网时使用 AI 对话应用直接闪退」。
//
// 事故复盘（统一日志）：应用不是崩溃，主进程 `exited due to exit(0)`，子进程按
// stopAll 的注册顺序先死。真正的缺陷是退出流程「不可见且很长」——
// main.js 的 before-quit 在 e.preventDefault() 之后既不关窗也不置任何退出中状态，
// 然后串行 await stopAll()；service-manager 的 stop() 对每个服务 SIGTERM 后无条件
// 等 3000ms 再 SIGKILL（定时器还从不取消）。于是 ⌘Q 之后窗口原样可用 3–4 秒、
// 还能建 SSE，然后整个应用在同一瞬间消失——用户体验上与闪退不可区分。
//
// 本文件守三条：
//   1) stopAll 是并行的：N 个「装死」的服务不再把 N×3s 线性叠加；
//   2) 子进程正常退出后那个 SIGKILL 定时器被 clearTimeout，不留悬挂句柄；
//   3) before-quit 先把窗口藏掉再 await stopAll，且 stopAll 卡住时有墙钟上限兜底。
//
// 3000ms 这个强杀窗口刻意不动（缩小只会提高 H2 被硬杀的概率，后端的关闭阻塞点尚未定位）。
const test = require('node:test')
const assert = require('node:assert')
const path = require('path')
const fs = require('node:fs')
const vm = require('node:vm')
const { EventEmitter } = require('node:events')
const { createServiceManager, findFreePort } = require('../main/services/service-manager')

// 「装死」的假服务：监听端口让 start() 能就绪，但**明确忽略 SIGTERM**——这正是断网那次
// 后端的等价形态（SIGTERM 后一行关闭日志都没有，最后被 SIGKILL 掐掉）。
const DEAF_SERVICE = `
  process.on('SIGTERM', () => {});
  const http = require('http');
  http.createServer((req, res) => res.end('ok')).listen(Number(process.env.PORT), '127.0.0.1');
`
// 对照用：老老实实响应 SIGTERM 的服务（默认行为即退出）
const POLITE_SERVICE = `
  const http = require('http');
  http.createServer((req, res) => res.end('ok')).listen(Number(process.env.PORT), '127.0.0.1');
`

function descriptor(name, script) {
  return {
    name,
    eager: true,
    logName: name,
    port: async () => findFreePort(),
    startTimeoutMs: () => 10000,
    commands: (ctx) => [{
      cmd: process.execPath,
      args: ['-e', script],
      env: { ...process.env, PORT: String(ctx.ports[name]) },
      cwd: ctx.dataDir
    }]
  }
}

function makeManager(t) {
  const mgr = createServiceManager({
    packaged: false,
    resourcesPath: null,
    dataDir: require('os').tmpdir(),
    projectRoot: path.join(__dirname, '..')
  })
  t.after(() => mgr.stopAll())
  return mgr
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

test('stopAll 并行停服务：两个装死的服务一次 3s 兜底，不是 2×3s 串行叠加', async (t) => {
  const mgr = makeManager(t)
  mgr.register(descriptor('deaf1', DEAF_SERVICE))
  mgr.register(descriptor('deaf2', DEAF_SERVICE))
  await mgr.allocatePorts()
  await mgr.start('deaf1')
  await mgr.start('deaf2')

  // 防空断言：先证明「病灶确实落地」——这两个子进程真的对 SIGTERM 无动于衷。
  // 少了这一步，后面的时限断言可能因为子进程本来就秒退而恒绿。
  const p1 = mgr.procs.get('deaf1')
  const p2 = mgr.procs.get('deaf2')
  assert.ok(p1 && p2, '两个假服务都应该已经 spawn')
  p1.kill('SIGTERM')
  p2.kill('SIGTERM')
  await sleep(500)
  assert.strictEqual(p1.exitCode, null, 'deaf1 必须仍然存活（SIGTERM 被忽略），否则本用例什么都没测')
  assert.strictEqual(p1.signalCode, null, 'deaf1 不该已被信号杀死')
  assert.strictEqual(p2.exitCode, null, 'deaf2 必须仍然存活（SIGTERM 被忽略）')

  const t0 = Date.now()
  await mgr.stopAll()
  const elapsed = Date.now() - t0

  // 串行版本是 2×3000ms ≈ 6s；并行后两个 3s 兜底同时跑，总耗时一次 3s 上下。
  assert.ok(elapsed < 4000,
    `stopAll 必须并行：两个装死服务应在一次 3s 兜底内停完，实测 ${elapsed}ms（串行会 >6000ms）`)
  // 反向护栏：低于 2s 说明子进程其实是自己退的，上面的「装死」前提已经不成立
  assert.ok(elapsed > 2000,
    `耗时 ${elapsed}ms 过短，说明装死的前提没成立，本用例不再证明并行`)
})

test('子进程正常退出后，SIGKILL 兜底定时器被清掉（不留悬挂句柄）', async (t) => {
  const mgr = makeManager(t)
  mgr.register(descriptor('polite', POLITE_SERVICE))
  await mgr.allocatePorts()
  await mgr.start('polite')

  // 直接盯 stop() 建的那个 3000ms 定时器有没有被 clearTimeout：
  // 数活跃句柄总数会被 node:test 自己的定时器干扰，这条是确定性的。
  const realSet = globalThis.setTimeout
  const realClear = globalThis.clearTimeout
  const created = []
  const cleared = new Set()
  globalThis.setTimeout = (fn, ms, ...rest) => {
    const timer = realSet(fn, ms, ...rest)
    if (ms === 3000) created.push(timer)
    return timer
  }
  globalThis.clearTimeout = (timer) => { cleared.add(timer); return realClear(timer) }
  try {
    const t0 = Date.now()
    await mgr.stop('polite')
    const elapsed = Date.now() - t0
    // 防空断言：必须走的是「SIGTERM 后进程自己退了」这条快路径，否则测的是强杀路径
    assert.ok(elapsed < 2000, `polite 服务应该响应 SIGTERM 立即退出，实测 ${elapsed}ms`)
    assert.strictEqual(created.length, 1, 'stop() 应当建了且只建了一个 3000ms 强杀兜底定时器')
    assert.ok(cleared.has(created[0]),
      '进程已经退出，那个 3000ms SIGKILL 定时器必须 clearTimeout，否则 fd/句柄悬挂到超时')
  } finally {
    globalThis.setTimeout = realSet
    globalThis.clearTimeout = realClear
  }
})

// ---------------------------------------------------------------------------
// main.js 的 before-quit。照 main-window-lifecycle.test.js 的套路：把出货函数原样
// 抠出来在 vm 里真跑，只把 Electron/OS 的副作用换掉——源码正则断言看不出「hide 到底
// 有没有在 await 之前发生」。
// ---------------------------------------------------------------------------
const MAIN_SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
function section(from, to) {
  const start = MAIN_SRC.indexOf(from)
  const end = MAIN_SRC.indexOf(to, start)
  assert.ok(start >= 0 && end > start, `找不到源码片段: ${from}`)
  return MAIN_SRC.slice(start, end)
}

function quitHarness({ stopAll }) {
  const events = []
  const windows = []
  class Window extends EventEmitter {
    constructor() { super(); this.destroyed = false; this.hidden = false; windows.push(this) }
    isDestroyed() { return this.destroyed }
    hide() { this.hidden = true; events.push('hide') }
    static getAllWindows() { return windows.filter((w) => !w.destroyed) }
  }
  const app = new EventEmitter()
  app.exit = (code) => events.push('exit:' + code)
  new Window()
  new Window()
  const context = vm.createContext({
    app, BrowserWindow: Window, console, path,
    process: { pid: 4242, platform: 'darwin', env: {} },
    // 墙钟上限的那个定时器在测试里立刻到点：本用例要验的是「卡住也会退出」，不是等 4 秒
    setTimeout: (fn) => { Promise.resolve().then(fn); return 0 },
    logDesktopEvent: (type, data) => events.push('log:' + type),
    stopClipboardWatcher: () => events.push('stopClipboardWatcher'),
  })
  vm.runInContext(`
    let services = { stopAll: globalThis.__stopAll }
    let modelManager = { killAllActive: () => globalThis.__events.push('killAllActive') }
    ${section('const QUIT_STOP_TIMEOUT_MS', "ipcMain.handle('checkba:backend-restart'")}
  `, Object.assign(context, { __stopAll: stopAll, __events: events }))
  return { app, windows, events }
}

test('before-quit：窗口先藏起来，再去 await stopAll（⌘Q 后不能还有一个能用的窗口杵着）', async () => {
  let hiddenWhenStopAllRan = null
  const h = quitHarness({
    stopAll: async () => { hiddenWhenStopAllRan = h.windows.every((w) => w.hidden) }
  })
  let prevented = false
  h.app.emit('before-quit', { preventDefault: () => { prevented = true } })
  await new Promise((r) => setTimeout(r, 50))

  assert.strictEqual(prevented, true, 'before-quit 仍然要 preventDefault 才能把服务停干净')
  assert.strictEqual(hiddenWhenStopAllRan, true,
    'stopAll 开始时所有窗口必须已经 hide——这几秒里窗口还能用，正是「闪退」观感的来源')
  assert.ok(h.events.indexOf('hide') < h.events.indexOf('log:quit-stopped'),
    'hide 必须发生在停服务之前')
  assert.ok(h.events.includes('exit:0'), '最终仍然要 app.exit(0)')
})

test('before-quit：stopAll 卡死时有墙钟上限兜底，不会永远不退出', async () => {
  const h = quitHarness({ stopAll: () => new Promise(() => {}) }) // 永不 resolve
  h.app.emit('before-quit', { preventDefault: () => {} })
  await new Promise((r) => setTimeout(r, 50))

  assert.ok(h.events.includes('exit:0'),
    'stopAll 卡住时必须有总墙钟上限，到点直接 app.exit(0)；否则应用既没窗口也不退出')
  assert.ok(h.windows.every((w) => w.hidden), '卡死路径同样先藏窗口')
})

test('before-quit 起因与耗时进日志（下次复现能直接读出是谁发起的退出）', async () => {
  const h = quitHarness({ stopAll: async () => {} })
  h.app.emit('before-quit', { preventDefault: () => {} })
  await new Promise((r) => setTimeout(r, 50))
  assert.ok(h.events.includes('log:quit-begin'), '退出起点要落 ~/.aiworkdeck/logs/desktop.log')
  assert.ok(h.events.includes('log:quit-end'), '退出终点（含耗时）要落日志')
})

test('主进程有 uncaughtException / unhandledRejection 兜底，且明确不退出、不弹框', () => {
  const stripped = MAIN_SRC.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  const grab = (evName) => {
    const start = stripped.indexOf(`process.on('${evName}'`)
    assert.ok(start >= 0, `主进程缺少 ${evName} 兜底：一个未捕获的异步错误就能让整个应用无提示消失`)
    let depth = 0
    for (let i = start; i < stripped.length; i++) {
      if (stripped[i] === '(') depth++
      else if (stripped[i] === ')') { depth--; if (depth === 0) return stripped.slice(start, i + 1) }
    }
    return ''
  }
  for (const evName of ['uncaughtException', 'unhandledRejection']) {
    const body = grab(evName)
    assert.ok(/logDesktopEvent\(/.test(body), `${evName} 兜底要记日志，否则等于没发生`)
    assert.ok(!/app\.(exit|quit)\(/.test(body), `${evName} 兜底绝不能退出应用`)
    assert.ok(!/dialog\./.test(body), `${evName} 兜底不弹框：断网时错误源成片出现会连环弹`)
  }
})
