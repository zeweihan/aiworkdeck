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
