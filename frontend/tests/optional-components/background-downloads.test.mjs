// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 组件后台下载（dev-board#581）：五个入口共用一个应用级下载管理。
// 钉住的是「状态不跟组件实例走」这件事的五个后果：同一 packId 不重复发起、
// 入口卸载后任务照跑、进度在各入口之间是同一份、失败可重试、完成时没人盯着就给全局提示。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createComponentDownloadManager } from '../../src/composables/useComponentDownloads.js'
import { createComponentRequiredHandler, shouldAutoResend } from '../../src/composables/useComponentRequired.js'

const COMPONENTS = [
  { packId: 'pptx-runtime', service: 'pptx-service', installed: false, downloadBytes: 1000,
    unpackedBytes: 0, modelId: null, modelInstalled: false, modelBytes: 0, featureKeys: [] },
  { packId: 'kokoro-runtime', service: 'kokoro-service', installed: false, downloadBytes: 2000,
    unpackedBytes: 0, modelId: 'kokoro-models', modelInstalled: false, modelBytes: 3000, featureKeys: [] },
]

/**
 * pack 段用「手动闸门」驱动：packStatus 在闸门状态改成 ready/failed 之前一直回 downloading，
 * 测试可以在任务半路做事（卸载入口、从别的入口再点一次、重新 load）。
 */
function deps(over = {}) {
  const calls = []
  const notes = []
  const listeners = new Set()
  const gates = {}
  const gate = (id) => {
    if (!gates[id]) gates[id] = { state: 'downloading', bytes: 0, error: '' }
    return gates[id]
  }
  return {
    calls, notes, gates, gate,
    state: {},
    optionalComponents: async () => ({ components: COMPONENTS.map((c) => ({ ...c })) }),
    packInstall: async (id) => { calls.push('install:' + id) },
    packStatus: async (id) => {
      const g = gate(id)
      return { status: { state: g.state, bytesDownloaded: g.bytes, bytesTotal: 100, error: g.error } }
    },
    packInfo: async () => ({}),
    modelDownload: async (id) => {
      calls.push('model:' + id)
      for (const cb of [...listeners]) cb({ id, phase: 'done' })
    },
    onModelProgress: (cb) => { listeners.add(cb); return () => listeners.delete(cb) },
    ensureService: async (name) => { calls.push('ensure:' + name); return { ok: true } },
    notify: (item, ok) => notes.push((ok ? 'ok:' : 'fail:') + item.packId),
    // 轮询的 sleep 等一拍即可；闸门没改就一直 downloading
    sleep: () => new Promise((r) => setImmediate(r)),
    ...over,
  }
}

const tick = () => new Promise((r) => setImmediate(r))
async function until(fn, n = 200) {
  for (let i = 0; i < n; i++) { if (fn()) return; await tick() }
  throw new Error('等待超时')
}

test('去重：同一 packId 在途时第二个入口再点，不重新发起、拿到同一个结果', async () => {
  const d = deps()
  const m = createComponentDownloadManager(d)
  await m.load()
  const a = m.installOne(m.state.items[0])
  const b = m.installOne({ ...COMPONENTS[0] }) // 另一个入口手里是自己拼的 item（如 AI 对话的 payload）
  assert.equal(m.isInstalling('pptx-runtime'), true)
  d.gate('pptx-runtime').state = 'ready'
  const [ra, rb] = await Promise.all([a, b])
  assert.equal(ra, true)
  assert.equal(rb, true)
  assert.equal(d.calls.filter((c) => c === 'install:pptx-runtime').length, 1, 'pack 被重复发起了')
  assert.equal(m.isInstalling('pptx-runtime'), false)
})

test('进度共享：不同入口拿到的是同一个对象，半路重新 load 不会把进度打回 idle', async () => {
  const d = deps()
  const m = createComponentDownloadManager(d)
  await m.load()
  const fromDialog = m.state.items[0]
  const run = m.installOne(fromDialog)
  const g = d.gate('pptx-runtime')
  g.bytes = 40
  await until(() => fromDialog.percent === 40)
  // 设置 → 组件管理 打开：重新拉清单（后端此刻仍说未安装）
  const items = await m.load()
  const fromAdmin = items.find((i) => i.packId === 'pptx-runtime')
  assert.equal(fromAdmin, fromDialog, '两个入口看到的不是同一份状态')
  assert.equal(fromAdmin.phase, 'runtime')
  assert.equal(fromAdmin.percent, 40)
  // AI 对话拿 payload 来认领，同样落到这一份
  assert.equal(m.adopt({ ...COMPONENTS[0], downloadBytes: 0 }), fromDialog)
  g.state = 'ready'
  await run
  assert.equal(fromAdmin.phase, 'ready')
})

test('入口卸载后任务照跑，完成时因为没人盯着而给全局提示', async () => {
  const d = deps()
  const m = createComponentDownloadManager(d)
  await m.load()
  const release = m.claim('pptx-runtime') // 面板打开、在前台看着
  const run = m.installOne(m.state.items[0])
  release() // 点「后台下载」/ 面板被 reLaunch 卸载
  release() // 重复释放是无害的
  d.gate('pptx-runtime').state = 'ready'
  assert.equal(await run, true)
  assert.equal(m.state.items[0].phase, 'ready')
  assert.deepEqual(d.notes, ['ok:pptx-runtime'])
})

test('有入口在前台盯着时完成回调不弹全局提示（由那个入口自己交代）', async () => {
  const d = deps()
  const m = createComponentDownloadManager(d)
  await m.load()
  const release = m.claim('pptx-runtime')
  const run = m.installOne(m.state.items[0])
  d.gate('pptx-runtime').state = 'ready'
  await run
  release()
  assert.deepEqual(d.notes, [])
})

test('失败给全局提示、卡片停在 failed（重新 load 也不抹掉），重试会真的重新发起', async () => {
  const d = deps()
  const m = createComponentDownloadManager(d)
  await m.load()
  const item = m.state.items[0]
  const g = d.gate('pptx-runtime')
  g.state = 'failed'
  g.error = '镜像不可达'
  assert.equal(await m.installOne(item), false)
  assert.equal(item.phase, 'failed')
  assert.deepEqual(d.notes, ['fail:pptx-runtime'])
  await m.load()
  assert.equal(m.state.items[0].phase, 'failed', '失败原因被重新 load 抹掉了，用户找不到重试')
  assert.match(m.state.items[0].error, /镜像不可达/)
  g.state = 'ready'
  assert.equal(await m.installOne(item), true)
  assert.equal(d.calls.filter((c) => c === 'install:pptx-runtime').length, 2)
  assert.equal(item.phase, 'ready')
})

test('批量：面板关掉之后批次照样逐个装完，总进度只算本批', async () => {
  const d = deps()
  const m = createComponentDownloadManager(d)
  await m.load()
  for (const i of m.state.items) i.selected = true
  d.gate('pptx-runtime').state = 'ready'
  d.gate('kokoro-runtime').state = 'ready'
  const all = m.installAll(m.state.items.filter((i) => i.selected))
  assert.equal(m.state.running, true)
  assert.equal(m.state.totalCount, 2)
  assert.equal(await all, true)
  assert.equal(m.state.running, false)
  assert.equal(m.state.doneCount, 2)
  assert.equal(m.overallPercent(), 100)
  // 先 pack 后模型再 ensure 的顺序不因换了编排入口而变
  const k = d.calls.filter((c) => /kokoro/.test(c))
  assert.deepEqual(k, ['install:kokoro-runtime', 'model:kokoro-models', 'ensure:kokoro-service'])
  assert.deepEqual(d.notes.sort(), ['ok:kokoro-runtime', 'ok:pptx-runtime'])
})

test('activeCount 反映在途任务数（组件管理的卡片据此锁按钮）', async () => {
  const d = deps()
  const m = createComponentDownloadManager(d)
  await m.load()
  const run = m.installOne(m.state.items[0])
  assert.equal(m.state.activeCount, 1)
  d.gate('pptx-runtime').state = 'ready'
  await run
  assert.equal(m.state.activeCount, 0)
})

// ---------- AI 对话：后台下载与重发判据 ----------

test('重发判据：前台装完照旧重发；后台装完只有「没新消息且没在生成」才重发', () => {
  assert.equal(shouldAutoResend({ alive: true, backgrounded: false, streaming: true, userCountAtGate: 1, userCountNow: 1 }), true)
  assert.equal(shouldAutoResend({ alive: true, backgrounded: true, streaming: false, userCountAtGate: 1, userCountNow: 1 }), true)
  assert.equal(shouldAutoResend({ alive: true, backgrounded: true, streaming: true, userCountAtGate: 1, userCountNow: 1 }), false)
  assert.equal(shouldAutoResend({ alive: true, backgrounded: true, streaming: false, userCountAtGate: 1, userCountNow: 2 }), false)
  assert.equal(shouldAutoResend({ alive: false, backgrounded: false, streaming: false, userCountAtGate: 1, userCountNow: 1 }), false)
})

test('后台装完但用户已经发了新消息：不重发，只提示「已就绪可重试」', async () => {
  const calls = []
  const h = createComponentRequiredHandler({
    installOne: async (item) => { item.phase = 'ready'; return true },
    fillSizes: async () => {},
    confirm: async () => true,
    lastUserMessage: () => '做一份 PPT',
    resend: async (t) => calls.push('resend:' + t),
    toast: () => {},
    mark: () => 1,
    shouldResend: (mark) => mark === 2,
    readyNotice: () => calls.push('ready'),
  })
  const r = await h.onAction({ packId: 'pptx-runtime', service: 'pptx-service', sizeMb: 1 })
  assert.deepEqual(r, { installed: true, resent: false })
  assert.deepEqual(calls, ['ready'])
})

test('重发的是拦截那一刻的原消息，而不是装完时对话里最新的一条', async () => {
  const calls = []
  let last = '做一份 PPT'
  const h = createComponentRequiredHandler({
    installOne: async (item) => { last = '另一句话'; item.phase = 'ready'; return true },
    fillSizes: async () => {},
    confirm: async () => true,
    lastUserMessage: () => last,
    resend: async (t) => calls.push('resend:' + t),
    toast: () => {},
  })
  await h.onAction({ packId: 'pptx-runtime', service: 'pptx-service', sizeMb: 1 })
  assert.deepEqual(calls, ['resend:做一份 PPT'])
})

test('组件已经在别的入口下载中：不再问一遍，直接挂上进度等它装完', async () => {
  const calls = []
  const h = createComponentRequiredHandler({
    installOne: async (item) => { calls.push('install'); item.phase = 'ready'; return true },
    fillSizes: async () => {},
    confirm: async () => { calls.push('confirm'); return true },
    isInstalling: () => true,
    attach: () => calls.push('attach'),
    lastUserMessage: () => '做一份 PPT',
    resend: async () => calls.push('resend'),
    toast: () => {},
  })
  const r = await h.onAction({ packId: 'pptx-runtime', service: 'pptx-service', sizeMb: 1 })
  assert.equal(r.installed, true)
  assert.deepEqual(calls, ['attach', 'install', 'resend'])
})

test('同一轮重复报的那一次标 duplicate：调用方不能据此收起第一次挂出来的卡片', async () => {
  let release
  const h = createComponentRequiredHandler({
    installOne: async () => true,
    fillSizes: async () => {},
    confirm: () => new Promise((r) => { release = r }),
    lastUserMessage: () => '',
    resend: async () => {},
    toast: () => {},
  })
  const p = { packId: 'pptx-runtime', service: 'pptx-service', sizeMb: 1 }
  const first = h.onAction(p)
  const second = await h.onAction(p)
  assert.equal(second.duplicate, true)
  release(false)
  assert.equal((await first).duplicate, undefined)
})

// ---------- 接线（源码断言：五个入口确实都接到了单例上） ----------

const read = (p) => readFileSync(new URL('../../src/' + p, import.meta.url), 'utf8')
const ENTRIES = ['components/OptionalComponentsDialog.vue', 'components/ChatInterface.vue',
  'components/admin/AdminPane.vue', 'components/EasyVoicePane.vue', 'components/MeetingRecordingPanel.vue']

test('五个入口都用应用级单例，没有谁再自己 new 控制器', () => {
  for (const p of ENTRIES) {
    const s = read(p)
    assert.match(s, /import \{ componentDownloads \} from '@\/services\/componentDownloads\.js'/, p)
    assert.ok(!/createOptionalComponentsController\(/.test(s), p + ' 仍在自己 new 控制器')
  }
})

test('首次登录面板：下载中给「后台下载」，「稍后再说」/遮罩在下载中转后台而不是中断', () => {
  const s = read('components/OptionalComponentsDialog.vue')
  assert.match(s, /@tap="onBackground"[\s\S]*?components\.backgroundDownload/)
  assert.match(s, /async onLater\(\) \{[^}]*?if \(this\.controller\.state\.running\) return this\.onBackground\(\)/)
  assert.match(s, /components\.backgroundStarted/)
})

test('AI 对话拦截卡：下载中给「后台下载」，装完按 shouldAutoResend 判重发', () => {
  const s = read('components/ChatInterface.vue')
  assert.match(s, /@tap="backgroundComponentGate"/)
  assert.match(s, /shouldResend: \(mark, item\) => shouldAutoResend\(/)
})

test('全局提示的四条文案两语言齐全', async () => {
  const zh = (await import('../../src/locales/zh-CN/components.js')).default
  const en = (await import('../../src/locales/en-US/components.js')).default
  for (const k of ['backgroundDownload', 'backgroundStarted', 'backgroundDone', 'backgroundFailed', 'chatReadyRetry']) {
    assert.ok(zh[k], 'zh-CN 缺 components.' + k)
    assert.ok(en[k], 'en-US 缺 components.' + k)
  }
  assert.ok(zh.backgroundDone.includes('{name}') && en.backgroundDone.includes('{name}'))
  assert.ok(zh.backgroundFailed.includes('{msg}') && en.backgroundFailed.includes('{msg}'))
})
