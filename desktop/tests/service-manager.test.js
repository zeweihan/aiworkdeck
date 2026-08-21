const test = require('node:test')
const assert = require('node:assert')
const path = require('path')
const { createServiceManager, findFreePort, isPortOpen } = require('../main/services/service-manager')

// 用 node 自身起一个最小 HTTP 服务当"假服务"，验证 spawn/端口等待/停止全链路
const FAKE_SERVICE = `
  const http = require('http');
  const port = Number(process.env.PORT);
  http.createServer((req, res) => res.end('ok')).listen(port, '127.0.0.1');
`

function fakeDescriptor(overrides) {
  return Object.assign({
    name: 'fake',
    eager: true,
    logName: 'fake',
    port: async () => findFreePort(),
    startTimeoutMs: () => 10000,
    commands: (ctx) => [{
      cmd: process.execPath,
      args: ['-e', FAKE_SERVICE],
      env: { ...process.env, PORT: String(ctx.ports.fake) },
      cwd: ctx.dataDir
    }]
  }, overrides)
}

function makeManager() {
  return createServiceManager({
    packaged: false,
    resourcesPath: null,
    dataDir: require('os').tmpdir(),
    projectRoot: path.join(__dirname, '..')
  })
}

test('findFreePort returns a usable port', async () => {
  const port = await findFreePort()
  assert.ok(port > 0)
  assert.strictEqual(await isPortOpen(port), false)
})

test('start/stop lifecycle: spawns, waits for port, stops', async () => {
  const mgr = makeManager()
  mgr.register(fakeDescriptor())
  await mgr.allocatePorts()
  const res = await mgr.start('fake')
  assert.strictEqual(res.ok, true)
  assert.strictEqual(res.reused, false)
  assert.strictEqual(await isPortOpen(mgr.ports.fake), true)
  await mgr.stop('fake')
  assert.strictEqual(await isPortOpen(mgr.ports.fake), false)
})

test('reuses already-open port without spawning', async () => {
  const http = require('http')
  const server = http.createServer((req, res) => res.end('ok'))
  const port = await findFreePort()
  await new Promise((r) => server.listen(port, '127.0.0.1', r))
  try {
    const mgr = makeManager()
    mgr.register(fakeDescriptor({ port: async () => port }))
    await mgr.allocatePorts()
    const res = await mgr.start('fake')
    assert.strictEqual(res.reused, true)
  } finally {
    server.close()
  }
})

test('verifyReuse=false triggers reallocatePort instead of reusing foreign process', async () => {
  const http = require('http')
  // 陌生进程占着首选端口
  const foreign = http.createServer((req, res) => res.end('not ours'))
  const occupied = await findFreePort()
  await new Promise((r) => foreign.listen(occupied, '127.0.0.1', r))
  try {
    const mgr = makeManager()
    mgr.register(fakeDescriptor({
      port: async () => occupied,
      verifyReuse: async () => false,
      reallocatePort: async () => findFreePort()
    }))
    await mgr.allocatePorts()
    const res = await mgr.start('fake')
    assert.strictEqual(res.ok, true)
    assert.strictEqual(res.reused, false)
    assert.notStrictEqual(mgr.ports.fake, occupied)
    await mgr.stopAll()
  } finally {
    foreign.close()
  }
})

test('verifyReuse=true keeps old reuse semantics', async () => {
  const http = require('http')
  const server = http.createServer((req, res) => res.end('ok'))
  const port = await findFreePort()
  await new Promise((r) => server.listen(port, '127.0.0.1', r))
  try {
    const mgr = makeManager()
    mgr.register(fakeDescriptor({ port: async () => port, verifyReuse: async () => true }))
    await mgr.allocatePorts()
    const res = await mgr.start('fake')
    assert.strictEqual(res.reused, true)
  } finally {
    server.close()
  }
})

test('backend allocator walks the 5269/5369/5169 chain past a foreign listener', async (t) => {
  // 直接驱动 allocateBackendPort 的探测逻辑：占住 5269（若本机空闲），期望分配落到 5369（或
  // 5169，如果 5369 恰好也被本机其它真实进程占用）。
  //
  // 原实现两处 `|| port > 1024` 让断言恒真——findFreePort() 兜底本来就保证 >1024，
  // 这个析取分支把"分配器真的按 5269→5369→5169 顺序走链"这条断言完全架空，标题写的
  // 场景从未被验证过。删掉之后断言收紧为「必须落在链上的下一跳」；当本机环境没法
  // 建立受控前提（5269/5369/5169 已经被别的真实进程占着，不知道分配器该落在哪）时，
  // 用 t.skip 如实说明，而不是继续放宽断言假装测过。
  const net = require('net')
  const { createBackendDescriptor } = require('../main/services/backend-service')
  const d = createBackendDescriptor()
  const holder = net.createServer()
  const first = await new Promise((resolve) => {
    holder.once('error', () => resolve(null)) // 5269 本机已被真实占用：没法建立受控前提
    holder.listen(5269, '127.0.0.1', () => resolve(5269))
  })
  if (!first) {
    t.skip('port 5269 already in use on this machine; cannot set up the controlled precondition')
    return
  }
  try {
    // 5369/5169 也必须是真空闲的，断言才有意义；否则分配器合理地会继续下探甚至
    // 退到 findFreePort() 的随机端口——那是本机环境凑巧全占用，不是代码坏了。
    if ((await isPortOpen(5369)) || (await isPortOpen(5169))) {
      t.skip('port 5369 or 5169 already in use on this machine; chain would legitimately fall through further')
      return
    }
    const port = await d.port({ packaged: true })
    // 5269 被占且不是我们的后端（无 /api/admin/wizard 响应）→ 应降级到 5369（或 5169）
    assert.notStrictEqual(port, 5269)
    assert.ok([5369, 5169].includes(port))
  } finally {
    holder.close()
  }
})

test('falls through failed candidate to next command', async () => {
  const mgr = makeManager()
  mgr.register(fakeDescriptor({
    commands: (ctx) => [
      // 第一个候选立刻退出（崩溃路径）
      { cmd: process.execPath, args: ['-e', 'process.exit(1)'], env: process.env, cwd: ctx.dataDir },
      { cmd: process.execPath, args: ['-e', FAKE_SERVICE], env: { ...process.env, PORT: String(ctx.ports.fake) }, cwd: ctx.dataDir }
    ]
  }))
  await mgr.allocatePorts()
  const res = await mgr.start('fake')
  assert.strictEqual(res.ok, true)
  await mgr.stopAll()
})
