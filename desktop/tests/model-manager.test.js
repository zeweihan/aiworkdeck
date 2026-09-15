// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { createModelManager } = require('../main/services/model-manager')

// 假下载进程：向组件目录落盘字节（整体进度=已落盘字节/estBytes，不再解析
// stdout 百分比——那是单文件 tqdm，多文件会反复 0→100%），随后正常退出
const FAKE_DOWNLOAD_OK = `
  const fs = require('fs'), path = require('path')
  const dir = path.join(process.cwd(), 'models', 'mineru')
  fs.mkdirSync(dir, { recursive: true })
  fs.writeFileSync(path.join(dir, 'model.bin'), Buffer.alloc(550)) // 550/1000 -> 55%
  console.log('fetching model.bin 100%')
  setTimeout(() => process.exit(0), 300)
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
    progressPollMs: 50, // 测试提速：整体进度轮询间隔（生产 1s）
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

test('status lists all registered components (mineru + kokoro + asr)', () => {
  const mm = makeManager(tmpDataDir(), FAKE_DOWNLOAD_OK, [])
  const ids = mm.status().map((c) => c.id).sort()
  assert.deepStrictEqual(ids, ['asr-models', 'kokoro-models', 'mineru-models'])
})

test('status: absent initially, installed after successful download with marker', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const mm = makeManager(dataDir, FAKE_DOWNLOAD_OK, events)
  // 整体进度分母缩到测试规模：假下载落盘 550 字节 → 55%
  mm.component('mineru-models').estBytes = 1000

  assert.strictEqual(mm.isInstalled('mineru-models'), false)
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'absent')

  const res = await mm.download('mineru-models')
  assert.strictEqual(res.ok, true)
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'downloading')

  await waitFor(() => mm.isInstalled('mineru-models'))
  assert.strictEqual(mm.status().find((c) => c.id === 'mineru-models').state, 'installed')
  // 进度事件：至少一次字节级整体百分比（55%，且未到 done 前封顶 99）+ 一次 done
  assert.ok(events.some((e) => e.id === 'mineru-models' && e.percent === 55))
  assert.ok(events.every((e) => e.phase !== 'progress' || e.percent === undefined || e.percent <= 99))
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

// ── 下载源顺序（dev-board#583）：kokoro/asr 先 ModelScope，失败再回落 hf-mirror ──
// 假下载器按 source 决定成败，并把每次被调起的 source 记进 calls.log
const MS_FAIL_LINE = 'error[network]: URLError: <urlopen error boom-ms>'
const HF_FAIL_LINE = 'huggingface_hub.errors.LocalEntryNotFoundError: boom-hf'

function makeSourcedManager(dataDir, behavior, events) {
  const logFile = path.join(dataDir, 'calls.log')
  const mm = createModelManager({
    dataDir,
    resourcesPath: null,
    packaged: false,
    onProgress: (e) => events.push(e),
    progressPollMs: 50,
    spawnSpecOverride: (component, ctx, source) => {
      const mode = behavior[source] || 'fail'
      const failLine = mode === 'disk'
        ? 'error[disk]: OSError: [Errno 28] No space left on device'
        : (source === 'hf' ? HF_FAIL_LINE : MS_FAIL_LINE)
      const body = mode === 'ok'
        ? `console.log('done'); process.exit(0)`
        : mode === 'hang'
          ? `console.log('starting'); setInterval(() => {}, 1000)`
          : `console.log(${JSON.stringify(failLine)}); process.exit(1)`
      return {
        cmd: process.execPath,
        args: ['-e', `require('fs').appendFileSync(${JSON.stringify(logFile)}, ${JSON.stringify(String(source))} + '\\n'); ${body}`],
        env: process.env,
        cwd: dataDir
      }
    }
  })
  const calls = () => {
    try { return fs.readFileSync(logFile, 'utf8').split('\n').filter(Boolean) } catch (e) { return [] }
  }
  return { mm, calls }
}

test('kokoro/asr: ModelScope 成功就不走 hf 回落', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const { mm, calls } = makeSourcedManager(dataDir, { modelscope: 'ok', hf: 'ok' }, events)
  await mm.download('asr-models')
  await waitFor(() => mm.isInstalled('asr-models'))
  assert.deepStrictEqual(calls(), ['modelscope'])
})

test('kokoro/asr: ModelScope 失败回落 hf-mirror，回落成功即装好', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const { mm, calls } = makeSourcedManager(dataDir, { modelscope: 'fail', hf: 'ok' }, events)
  await mm.download('kokoro-models')
  await waitFor(() => mm.isInstalled('kokoro-models'))
  assert.deepStrictEqual(calls(), ['modelscope', 'hf'])
  assert.ok(!events.some((e) => e.phase === 'error'), '回落成功时不该发 error')
})

test('kokoro/asr: 两个源都失败时 message 是人话且附原始异常', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const { mm, calls } = makeSourcedManager(dataDir, { modelscope: 'fail', hf: 'fail' }, events)
  await mm.download('asr-models')
  await waitFor(() => events.some((e) => e.phase === 'error'))
  assert.deepStrictEqual(calls(), ['modelscope', 'hf'])
  const st = mm.status().find((c) => c.id === 'asr-models')
  assert.strictEqual(st.state, 'error')
  assert.match(st.message, /无法连接模型下载源|Could not reach the model download sources/)
  assert.match(st.message, /ModelScope/)
  assert.doesNotMatch(st.message, /^download exited/)
  assert.ok(st.message.includes('boom-ms'), 'ModelScope 的原始异常要附上')
  assert.ok(st.message.includes('LocalEntryNotFoundError: boom-hf'), 'hf 的原始异常要附上')
  const err = events.find((e) => e.phase === 'error')
  assert.strictEqual(err.message, st.message)
})

test('磁盘满时提示空间不足而不是网络', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const { mm } = makeSourcedManager(dataDir, { modelscope: 'disk', hf: 'fail' }, events)
  await mm.download('asr-models')
  await waitFor(() => events.some((e) => e.phase === 'error'))
  assert.match(mm.status().find((c) => c.id === 'asr-models').message, /磁盘空间不足|Not enough disk space/)
})

test('CHECKBA_MODEL_SOURCE=hf 强制只走 hf', async (t) => {
  const prev = process.env.CHECKBA_MODEL_SOURCE
  process.env.CHECKBA_MODEL_SOURCE = 'hf'
  t.after(() => { if (prev === undefined) delete process.env.CHECKBA_MODEL_SOURCE; else process.env.CHECKBA_MODEL_SOURCE = prev })
  const dataDir = tmpDataDir()
  const { mm, calls } = makeSourcedManager(dataDir, { modelscope: 'ok', hf: 'ok' }, [])
  await mm.download('kokoro-models')
  await waitFor(() => mm.isInstalled('kokoro-models'))
  assert.deepStrictEqual(calls(), ['hf'])
})

test('CHECKBA_MODEL_SOURCE=modelscope 失败时不回落', async (t) => {
  const prev = process.env.CHECKBA_MODEL_SOURCE
  process.env.CHECKBA_MODEL_SOURCE = 'modelscope'
  t.after(() => { if (prev === undefined) delete process.env.CHECKBA_MODEL_SOURCE; else process.env.CHECKBA_MODEL_SOURCE = prev })
  const dataDir = tmpDataDir()
  const events = []
  const { mm, calls } = makeSourcedManager(dataDir, { modelscope: 'fail', hf: 'ok' }, events)
  await mm.download('kokoro-models')
  await waitFor(() => events.some((e) => e.phase === 'error'))
  assert.deepStrictEqual(calls(), ['modelscope'])
})

test('ModelScope 下载中取消：不回落 hf、状态回 absent', async () => {
  const dataDir = tmpDataDir()
  const events = []
  const { mm, calls } = makeSourcedManager(dataDir, { modelscope: 'hang', hf: 'ok' }, events)
  await mm.download('asr-models')
  await waitFor(() => calls().length === 1)
  await mm.cancel('asr-models')
  await new Promise((r) => setTimeout(r, 400))
  assert.deepStrictEqual(calls(), ['modelscope'])
  assert.strictEqual(mm.status().find((c) => c.id === 'asr-models').state, 'absent')
})

test('mineru 仍是单一来源，一次调起', async () => {
  const dataDir = tmpDataDir()
  const { mm, calls } = makeSourcedManager(dataDir, { null: 'ok', undefined: 'ok' }, [])
  await mm.download('mineru-models')
  await waitFor(() => mm.isInstalled('mineru-models'))
  assert.strictEqual(calls().length, 1)
})

test('runtime pack 未装时，模型下载当场失败并说清要先装哪个组件', async (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mm-nopack-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const mgr = createModelManager({
    dataDir: path.join(root, '.aiworkdeck'),
    resourcesPath: path.join(root, 'res'),
    projectRoot: path.join(root, 'repo'),
    packaged: true,
    onProgress: () => {}
  })
  await assert.rejects(() => mgr.download('kokoro-models'), /kokoro-runtime/)
})
