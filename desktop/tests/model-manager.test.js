const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { createModelManager } = require('../main/services/model-manager')

// 假下载进程：打印带百分比的进度行后正常退出
const FAKE_DOWNLOAD_OK = `
  const lines = ['fetching 10%', 'fetching 55%', 'fetching 100%', 'done']
  let i = 0
  const t = setInterval(() => {
    console.log(lines[i++])
    if (i >= lines.length) { clearInterval(t); process.exit(0) }
  }, 50)
`
// 假下载进程：挂住直到被杀
const FAKE_DOWNLOAD_HANG = `console.log('starting 1%'); setInterval(() => {}, 1000)`
// 假下载进程：直接失败
const FAKE_DOWNLOAD_FAIL = `console.error('boom'); process.exit(3)`

function tmpDataDir() {
  return fs.mkdtempSync(path.join(os.tmpdir(), 'awd-mm-'))
}

function makeManager(dataDir, script, events) {
  return createModelManager({
    dataDir,
    resourcesPath: null,
    packaged: false,
    onProgress: (e) => events.push(e),
    // 测试注入：覆盖真实的 mineru 下载命令
    spawnSpecOverride: () => ({
      cmd: process.execPath,
      args: ['-e', script],
      env: process.env,
      cwd: dataDir
    })
  })
}

function waitFor(cond, timeoutMs = 5000) {
  return new Promise((resolve, reject) => {
    const start = Date.now()
    const t = setInterval(() => {
      if (cond()) { clearInterval(t); resolve() }
      else if (Date.now() - start > timeoutMs) { clearInterval(t); reject(new Error('waitFor timeout')) }
    }, 25)
  })
}

test('status lists all registered components (mineru + kokoro)', () => {
  const mm = makeManager(tmpDataDir(), FAKE_DOWNLOAD_OK, [])
  const ids = mm.status().map((c) => c.id).sort()
  assert.deepStrictEqual(ids, ['kokoro-models', 'mineru-models'])
})

test('status: absent initially, installed after successful download with marker', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const mm = makeManager(dataDir, FAKE_DOWNLOAD_OK, events)

  assert.strictEqual(mm.isInstalled('mineru-models'), false)
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'absent')

  const res = await mm.download('mineru-models')
  assert.strictEqual(res.ok, true)
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'downloading')

  await waitFor(() => mm.isInstalled('mineru-models'))
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'installed')
  // 进度事件：至少一次带百分比 + 一次 done
  assert.ok(events.some((e) => e.id === 'mineru-models' && typeof e.percent === 'number' && e.percent >= 10))
  assert.ok(events.some((e) => e.phase === 'done'))
})

test('download twice rejects while downloading', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const mm = makeManager(dataDir, FAKE_DOWNLOAD_HANG, events)
  await mm.download('mineru-models')
  await assert.rejects(() => mm.download('mineru-models'), /already/)
  await mm.cancel('mineru-models')
})

test('cancel returns state to absent without marker', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const mm = makeManager(dataDir, FAKE_DOWNLOAD_HANG, events)
  await mm.download('mineru-models')
  await mm.cancel('mineru-models')
  await waitFor(() => mm.status().find((c) => c.id === 'mineru-models').state === 'absent')
  assert.strictEqual(mm.isInstalled('mineru-models'), false)
})

test('failed download emits error phase and state error', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const mm = makeManager(dataDir, FAKE_DOWNLOAD_FAIL, events)
  await mm.download('mineru-models')
  await waitFor(() => events.some((e) => e.phase === 'error'))
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'error')
  assert.strictEqual(mm.isInstalled('mineru-models'), false)
})

test('remove clears installed state', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const mm = makeManager(dataDir, FAKE_DOWNLOAD_OK, events)
  await mm.download('mineru-models')
  await waitFor(() => mm.isInstalled('mineru-models'))
  await mm.remove('mineru-models')
  assert.strictEqual(mm.isInstalled('mineru-models'), false)
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'absent')
})
