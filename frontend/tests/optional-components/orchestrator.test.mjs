// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 下载编排（设计 §3.1「底层两条通道顺序执行」/ §4.1「逐个顺序下载，带总进度」）。
// 顺序是硬约束：模型下载器本身跑在 pack 的 venv 里（model-manager.js 的 PYTHONPATH
// 指向 pack 的 lib/），先下模型必然 ModuleNotFoundError。
import test from 'node:test'
import assert from 'node:assert/strict'
import { createOptionalComponentsController, PACK_LOCALE_KEY } from '../../src/composables/useOptionalComponents.js'

function deps(overrides = {}) {
  const calls = []
  const listeners = new Set()
  const base = {
    calls,
    listeners,
    optionalComponents: async () => ({ code: 0, components: [
      { packId: 'pptx-runtime', service: 'pptx-service', state: 'not_installed', installed: false,
        downloadBytes: 173015040, unpackedBytes: 0, modelId: null, modelInstalled: false, modelBytes: 0,
        featureKeys: ['pptxGenerate'] },
      { packId: 'kokoro-runtime', service: 'kokoro-service', state: 'not_installed', installed: false,
        downloadBytes: 0, unpackedBytes: 0, modelId: 'kokoro-models', modelInstalled: false,
        modelBytes: 314572800, featureKeys: ['ttsPanel'] },
    ] }),
    packInstall: async (id) => { calls.push('install:' + id) },
    packStatus: async (id) => { calls.push('status:' + id); return { status: { state: 'ready', bytesDownloaded: 1, bytesTotal: 1 } } },
    packInfo: async (id) => { calls.push('info:' + id); return { latestVersion: '1.0.0', totalSize: 209715200, unpackedSize: 800000000 } },
    // 真实语义：host.model.download 打到 model-manager.download()，那个方法 spawn 完
    // 下载器就 return {ok:true}——resolve 的时刻模型一个字节都还没落盘。完成信号只有
    // model-progress 的 done/error。桩函数照这个语义写，否则测出来的「顺序」是假的。
    modelDownload: async (id) => {
      calls.push('model:' + id)
      for (const cb of [...listeners]) cb({ id, phase: 'done' })
    },
    onModelProgress: (cb) => { listeners.add(cb); return () => listeners.delete(cb) },
    ensureService: async (name) => { calls.push('ensure:' + name); return { ok: true } },
    sleep: async () => {},
  }
  return { ...base, ...overrides }
}

test('load 把四态字段与 locale 键都补齐', async () => {
  const c = createOptionalComponentsController(deps())
  await c.load()
  assert.equal(c.state.items.length, 2)
  assert.equal(c.state.items[0].localeKey, 'pptxRuntime')
  assert.equal(c.state.items[1].localeKey, 'kokoroRuntime')
  assert.equal(c.state.items[0].phase, 'idle')
  assert.deepEqual(PACK_LOCALE_KEY['asr-runtime'], 'asrRuntime')
})

test('downloadBytes 为 0 时才去打 /info 补体积（那条会发网络请求）', async () => {
  const d = deps()
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.fillSizes(c.state.items[0])
  await c.fillSizes(c.state.items[1])
  assert.ok(!d.calls.includes('info:pptx-runtime'), '已知体积不该再打 /info')
  assert.ok(d.calls.includes('info:kokoro-runtime'))
  assert.equal(c.state.items[1].downloadBytes, 209715200)
  assert.equal(c.state.items[1].unpackedBytes, 800000000)
})

test('installOne 的顺序恒为 pack → 模型 → ensure', async () => {
  const d = deps()
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installOne(c.state.items[1])
  const seq = d.calls.filter((x) => /^(install|model|ensure):/.test(x))
  assert.deepEqual(seq, ['install:kokoro-runtime', 'model:kokoro-models', 'ensure:kokoro-service'])
  assert.equal(c.state.items[1].phase, 'ready')
})

test('没有模型的组件跳过模型段，直接 pack → ensure', async () => {
  const d = deps()
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installOne(c.state.items[0])
  assert.deepEqual(d.calls.filter((x) => /^(install|model|ensure):/.test(x)),
    ['install:pptx-runtime', 'ensure:pptx-service'])
})

test('模型段等的是 done 事件，不是 download() 的 resolve（它 spawn 完就返回）', async () => {
  let release = null
  const d = deps()
  // 模拟真实：download() 立刻 resolve（spawn 完就返回），done 事件晚一拍才来
  d.modelDownload = async (id) => {
    d.calls.push('model:' + id)
    release = () => { for (const cb of [...d.listeners]) cb({ id, phase: 'done' }) }
  }
  const c = createOptionalComponentsController(d)
  await c.load()
  const p = c.installOne(c.state.items[1])
  await new Promise((r) => setTimeout(r, 5))
  assert.equal(c.state.items[1].phase, 'model', 'download() resolve 了就往下走 = 模型还没落盘就 ensure')
  assert.ok(!d.calls.includes('ensure:kokoro-service'))
  release()
  assert.equal(await p, true)
  assert.ok(d.calls.includes('ensure:kokoro-service'))
})

test('模型「已经装过了」抛的错要算成功——否则装了一半的组件永远重试不出去', async () => {
  const d = deps()
  d.modelDownload = async (id) => {
    d.calls.push('model:' + id)
    // model-manager.download() 对已装好的模型直接抛这句（model-manager.js:195）
    throw new Error(`${id} already installed`)
  }
  const c = createOptionalComponentsController(d)
  await c.load()
  assert.equal(await c.installOne(c.state.items[1]), true)
  assert.equal(c.state.items[1].phase, 'ready')
  assert.ok(d.calls.includes('ensure:kokoro-service'))
})

test('pack 装失败就不往下走：绝不在没有运行时的情况下去下模型', async () => {
  const d = deps({ packStatus: async () => ({ status: { state: 'failed', error: '镜像不可达' } }) })
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installOne(c.state.items[1])
  assert.equal(c.state.items[1].phase, 'failed')
  assert.match(c.state.items[1].error, /镜像不可达/)
  assert.ok(!d.calls.includes('model:kokoro-models'))
})

test('installAll 顺序执行、维护总进度，单个失败不中断后面的', async () => {
  let n = 0
  const d = deps({
    packStatus: async (id) => {
      n++
      return id === 'pptx-runtime'
        ? { status: { state: 'failed', error: 'boom' } }
        : { status: { state: 'ready', bytesDownloaded: 1, bytesTotal: 1 } }
    },
  })
  const c = createOptionalComponentsController(d)
  await c.load()
  await c.installAll(c.state.items)
  assert.equal(c.state.totalCount, 2)
  assert.equal(c.state.doneCount, 2, '失败也算「处理完了」，否则总进度永远停在那里')
  assert.equal(c.state.items[0].phase, 'failed')
  assert.equal(c.state.items[1].phase, 'ready')
  assert.equal(c.state.running, false)
  assert.ok(n >= 2)
})

test('总进度按段加权，中途读得出一个 0..100 的数', async () => {
  const c = createOptionalComponentsController(deps())
  await c.load()
  assert.equal(c.overallPercent(), 0)
  c.state.items[0].phase = 'ready'
  c.state.items[1].phase = 'model'
  c.state.items[1].percent = 50
  const p = c.overallPercent()
  assert.ok(p > 50 && p < 100, '一个装完 + 一个下到一半应当落在 50~100 之间，实际 ' + p)
})
