// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#968（BUG-72 / QA C9-03）：资源管理器右键「转写」不经确认就提交平台转写并预扣 Credits。
// 断言三个提交入口「不确认不提交、确认后才提交」，外加确认逻辑本身的档位判定。
// 入口方法体从源码里抠出来真跑（同 panel-inputs.test.mjs 的做法），接口与弹窗用替身。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { readFileSync as _r, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { confirmPaidTranscription, estimateAsrCost } from '../../src/utils/paidTranscribeConfirm.js'

const tKey = (key, params) => (params ? key + JSON.stringify(params) : key)
const PLATFORM = { provider: 'platform', platformAvailable: true }

function deps({ ctx, balance = null, price = null, confirm = false, localPossible = true, timeoutMs } = {}) {
  const dialogs = []
  return {
    dialogs,
    timeoutMs,
    getContext: async () => { if (ctx instanceof Error) throw ctx; return ctx },
    getBalanceCents: typeof balance === 'function' ? balance : async () => balance,
    getAsrPrice: typeof price === 'function' ? price : async () => price,
    localPossible: () => localPossible,
    showDialog: async (o) => { dialogs.push(o); return { confirm, cancel: !confirm } },
    t: tKey,
  }
}

// ---------- 确认逻辑 ----------

test('平台档：弹确认框，取消即不提交；框里有计费说明、时长、预计费用、余额、本机转写出路', async () => {
  const d = deps({ ctx: PLATFORM, balance: 1230, price: { unit: 'minute', creditsPerUnit: 12 }, confirm: false })
  const ok = await confirmPaidTranscription({ durationMs: 125000 }, d)
  assert.equal(ok, false)
  assert.equal(d.dialogs.length, 1)
  const o = d.dialogs[0]
  assert.equal(o.title, 'meeting.paidConfirmTitle')
  assert.equal(o.confirmText, 'meeting.paidConfirmOk')
  assert.equal(o.cancelText, 'meeting.cancel')
  assert.match(o.content, /meeting\.paidConfirmBody/)
  assert.match(o.content, /meeting\.paidConfirmDuration\{"duration":"02:05"\}/)
  // 与网关同算法：ceil(125/60 分钟 × 12 分) = ceil(25) = 25 分 = 0.25 Credits
  assert.match(o.content, /meeting\.paidConfirmEstimate\{"credits":"0.25"\}/)
  assert.match(o.content, /meeting\.paidConfirmBalance\{"credits":"12.30"\}/)
  assert.match(o.content, /meeting\.paidConfirmLocalHint/)
})

test('平台档：确认后才放行；时长未知给每分钟单价，余额未知不硬凑', async () => {
  const d = deps({ ctx: PLATFORM, price: { unit: 'minute', creditsPerUnit: 12 }, confirm: true })
  assert.equal(await confirmPaidTranscription({}, d), true)
  assert.equal(d.dialogs.length, 1)
  assert.match(d.dialogs[0].content, /meeting\.paidConfirmPerMinute\{"credits":"0.12"\}/)
  assert.doesNotMatch(d.dialogs[0].content, /paidConfirmDuration|paidConfirmBalance|paidConfirmEstimate/)
})

test('估算：只认按分钟计价的正单价，其余不估', () => {
  assert.deepEqual(estimateAsrCost({ unit: 'minute', creditsPerUnit: 10 }, 60000), { kind: 'total', credits: '0.10' })
  assert.deepEqual(estimateAsrCost({ unit: 'minute', creditsPerUnit: 10 }, 60001), { kind: 'total', credits: '0.11' })
  // 分钟数保留小数、最后才向上取整（网关 asr.ts minutesOf 口径）：61 秒 × 3 分/分钟 = 3.05 → 4 分，不是 2 分钟 × 3 = 6 分
  assert.deepEqual(estimateAsrCost({ unit: 'minute', creditsPerUnit: 3 }, 61000), { kind: 'total', credits: '0.04' })
  assert.equal(estimateAsrCost({ unit: 'call', creditsPerUnit: 10 }, 60000), null)
  assert.equal(estimateAsrCost({ unit: 'minute', creditsPerUnit: 0 }, 60000), null)
  assert.equal(estimateAsrCost(null, 60000), null)
})

test('本机档 / 自备 Key / 本机没有平台档 / 已有记录不在可提交状态 / 转写没配好：后端不会提交，直接放行不弹框', async () => {
  for (const ctx of [
    { provider: 'local', platformAvailable: true },
    { provider: 'byok', platformAvailable: true },
    { provider: 'platform', platformAvailable: false },
    { ...PLATFORM, existingStatus: 'TRANSCRIBED' },
    { ...PLATFORM, existingStatus: 'TRANSCRIBING' },
    { ...PLATFORM, existingStatus: 'RECORDING' },
    { ...PLATFORM, configured: false },
  ]) {
    const d = deps({ ctx })
    assert.equal(await confirmPaidTranscription({}, d), true, JSON.stringify(ctx))
    assert.equal(d.dialogs.length, 0)
  }
})

test('已有记录但失败 / 未转写的，照样要确认（register-file 会重新提交）', async () => {
  for (const existingStatus of ['FAILED', 'RECORDED', 'EMPTY']) {
    const d = deps({ ctx: { ...PLATFORM, existingStatus } })
    await confirmPaidTranscription({}, d)
    assert.equal(d.dialogs.length, 1, existingStatus)
  }
})

test('档位读不到：按可能扣费处理，照样要确认', async () => {
  const d = deps({ ctx: new Error('403'), confirm: false })
  assert.equal(await confirmPaidTranscription({}, d), false)
  assert.equal(d.dialogs.length, 1)
})

test('余额与单价取数超时：到点就弹框，不显示那两行', async () => {
  const never = () => new Promise(() => {})
  const d = deps({ ctx: PLATFORM, balance: never, price: never, timeoutMs: 50 })
  const t0 = Date.now()
  await confirmPaidTranscription({ durationMs: 60000 }, d)
  const waited = Date.now() - t0
  assert.ok(waited < 1000, '不该等到取数完成，实际等了 ' + waited + 'ms')
  assert.equal(d.dialogs.length, 1)
  assert.doesNotMatch(d.dialogs[0].content, /paidConfirmBalance|paidConfirmEstimate/)
})

test('没有本机引擎（浏览器版）不给「改用本机转写」的指路', async () => {
  const d = deps({ ctx: PLATFORM, localPossible: false })
  await confirmPaidTranscription({}, d)
  assert.doesNotMatch(d.dialogs[0].content, /paidConfirmLocalHint/)
})

// ---------- 接线层 paidTranscribeGate.js（依赖改写成替身后真跑） ----------

async function loadGate(api, tag) {
  globalThis.__gateApi = api
  globalThis.__gateDialogs = []
  const stub = join(tmpdir(), `gate-stub-${tag}-${process.pid}.mjs`)
  writeFileSync(stub, `
    const api = globalThis.__gateApi
    export const getMeetingRecordings = (...a) => api.getMeetingRecordings(...a)
    export const getPlatformServices = (...a) => api.getPlatformServices(...a)
    export const getPlatformServiceRemote = (...a) => api.getPlatformServiceRemote(...a)
    export const getAccountBalance = (...a) => api.getAccountBalance(...a)
    export const showDialog = async (o) => { globalThis.__gateDialogs.push(o); return { confirm: false } }
    export const t = (k, p) => (p ? k + JSON.stringify(p) : k)
    export const host = { model: {} }
  `, 'utf8')
  const stubUrl = 'file://' + stub
  const src = _r(new URL('../../src/utils/paidTranscribeGate.js', import.meta.url), 'utf8')
    .replace(/from '@\/services\/api\.js'/, `from '${stubUrl}'`)
    .replace(/from '@\/utils\/dialog\.js'/, `from '${stubUrl}'`)
    .replace(/from '@\/i18n'/, `from '${stubUrl}'`)
    .replace(/from '@\/services\/host\.js'/, `from '${stubUrl}'`)
    .replace(/from '@\/utils\/paidTranscribeConfirm\.js'/,
      `from '${new URL('../../src/utils/paidTranscribeConfirm.js', import.meta.url).href}'`)
  const out = join(tmpdir(), `gate-under-test-${tag}-${process.pid}.mjs`)
  writeFileSync(out, src, 'utf8')
  return import(out)
}

const noPlatformServices = async () => { throw new Error('403 非管理员读不到') }

test('接线：档位优先取会议列表的 tier——服务器模式非管理员（byok）不弹框，也不去读机器级端点', async () => {
  let psCalls = 0
  const gate = await loadGate({
    getMeetingRecordings: async () => ({ meetings: [], tier: 'byok' }),
    getPlatformServices: async () => { psCalls++; throw new Error('403') },
    getPlatformServiceRemote: async () => ({}),
    getAccountBalance: async () => ({}),
  }, 'byok')
  assert.equal(await gate.confirmPaidTranscription({ projectId: 1 }), true)
  assert.equal(globalThis.__gateDialogs.length, 0)
  assert.equal(psCalls, 0)
})

test('接线：右键同一份已转写的音频按 audioFileId 找到记录，不弹框', async () => {
  const gate = await loadGate({
    getMeetingRecordings: async () => ({ tier: 'platform', meetings: [{ audioFileId: 77, status: 'TRANSCRIBED' }] }),
    getPlatformServices: noPlatformServices,
    getPlatformServiceRemote: async () => ({}),
    getAccountBalance: async () => ({}),
  }, 'done')
  assert.equal(await gate.confirmPaidTranscription({ projectId: 1, audioFileId: '77' }), true)
  assert.equal(globalThis.__gateDialogs.length, 0)
})

test('接线：列表没带 tier（旧后端）退回 /api/platform-services 的 asr 行；余额按「分/100」格式化、单价取 /remote 的 asrPrice', async () => {
  const gate = await loadGate({
    getMeetingRecordings: async () => ({ meetings: [] }),
    getPlatformServices: async () => ({ platformAvailable: true, services: [{ service: 'asr', provider: 'platform' }] }),
    getPlatformServiceRemote: async () => ({ asrPrice: { unit: 'minute', creditsPerUnit: 15 } }),
    getAccountBalance: async () => ({ connected: true, balanceCents: 98765 }),
  }, 'fallback')
  assert.equal(await gate.confirmPaidTranscription({ projectId: 1, durationMs: 30000 }), false)
  assert.equal(globalThis.__gateDialogs.length, 1)
  const c = globalThis.__gateDialogs[0].content
  assert.match(c, /paidConfirmBalance\{"credits":"987.65"\}/)
  // ceil(0.5 分钟 × 15 分) = 8 分
  assert.match(c, /paidConfirmEstimate\{"credits":"0.08"\}/)
  assert.match(c, /paidConfirmLocalHint/)
})

test('接线：会议列表 configured=false（转写没配好，后端不会提交）不弹框；configured=true 照问', async () => {
  for (const [configured, dialogs] of [[false, 0], [true, 1]]) {
    const gate = await loadGate({
      getMeetingRecordings: async () => ({ meetings: [], tier: 'platform', configured }),
      getPlatformServices: noPlatformServices,
      getPlatformServiceRemote: async () => ({}),
      getAccountBalance: async () => ({}),
    }, 'configured' + configured)
    await gate.confirmPaidTranscription({ projectId: 1, audioFileId: 5 })
    assert.equal(globalThis.__gateDialogs.length, dialogs, 'configured=' + configured)
  }
})

test('接线：未连账户 / 官网不可达时不显示余额（不拿 0 冒充）', async () => {
  for (const bal of [{ connected: false }, { connected: true, available: false, balanceCents: 0 }]) {
    const gate = await loadGate({
      getMeetingRecordings: async () => ({ meetings: [], tier: 'platform' }),
      getPlatformServices: noPlatformServices,
      getPlatformServiceRemote: async () => ({ asrPrice: null }),
      getAccountBalance: async () => bal,
    }, 'nobal' + (bal.connected ? 1 : 0))
    await gate.confirmPaidTranscription({ projectId: 1 })
    assert.doesNotMatch(globalThis.__gateDialogs[0].content, /paidConfirmBalance/, JSON.stringify(bal))
  }
})

// ---------- 入口一：资源管理器右键「转写」（project-overview onTranscribeAudio） ----------

function extractMethod(src, name) {
  const start = src.indexOf(`    async ${name}(`)
  assert.ok(start >= 0, `找不到方法 ${name}`)
  const bodyStart = src.indexOf('{', start)
  let depth = 0
  for (let i = bodyStart; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) return src.slice(src.indexOf('(', start), i + 1) }
  }
  throw new Error('方法体不闭合')
}

function overviewTranscribe(confirmResult) {
  const src = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const method = extractMethod(src, 'onTranscribeAudio')
  const calls = { register: [], confirm: 0, confirmArgs: [] }
  const fn = new Function('registerMeetingFromFile', 'confirmPaidTranscription', 'uni', 'console',
    `return async function ${method}`)(
    async (...a) => { calls.register.push(a); return { meeting: { id: 5 }, submitted: true } },
    async (o) => { calls.confirm++; calls.confirmArgs.push(o); return confirmResult },
    { showToast() {} },
    { error() {} },
  )
  const vm = { projectId: 9, leftPaneKey: 'files', toggleLeftPane() {}, $t: (k) => k }
  return { run: (file) => fn.call(vm, file), calls, vm }
}

test('右键转写：用户取消 → 不调 register-file（该接口会当场提交并预扣）', async () => {
  const { run, calls, vm } = overviewTranscribe(false)
  await run({ id: 77, name: 'a.mp3' })
  assert.equal(calls.confirm, 1)
  assert.equal(calls.register.length, 0)
  assert.notEqual(vm.voiceTab, 'recorder', '取消后不应跳到会议录音面板')
})

test('右键转写：确认后才调 register-file', async () => {
  const { run, calls } = overviewTranscribe(true)
  await run({ id: 77, name: 'a.mp3' })
  assert.equal(calls.confirm, 1)
  assert.deepEqual(calls.register, [[9, 77]])
  // 带上项目与文件，接线层据此找到已有记录（已转写的不再问）
  assert.deepEqual(calls.confirmArgs, [{ projectId: 9, audioFileId: 77 }])
})

// ---------- 入口二：会议录音面板「开始转写 / 重试转写」（onTranscribe） ----------

function panelTranscribe(confirmResult) {
  const source = readFileSync(new URL('../../src/components/MeetingRecordingPanel.vue', import.meta.url), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[\s\S]*?from ['"][^'"]+['"]\s*$/gm, '')
    .replace('export default', 'return')
  const calls = { transcribe: [], confirm: [] }
  const component = new Function('AwdSwitch', 'AwdSelect', 'transcribeMeetingRecording', 'confirmPaidTranscription', 'uni', 'formatSeconds', 'getMeetingRecordings', script)(
    {}, {},
    async (id) => { calls.transcribe.push(id) },
    async (o) => { calls.confirm.push(o); return confirmResult },
    { showToast() {} },
    (v) => String(v),
    async () => ({ meetings: [] }),
  )
  const vm = {
    projectId: 9,
    asrProvider: 'platform', asrNotice: { acknowledged: true },
    $t: (k) => k, loadMeetings: async () => {},
    ...component.methods,
  }
  Object.defineProperty(vm, 'needsAsrNotice', { get: () => false })
  return { vm, calls }
}

test('面板转写：用户取消 → 不调 /transcribe', async () => {
  const { vm, calls } = panelTranscribe(false)
  await vm.onTranscribe({ id: 3, durationMs: 60000 })
  assert.deepEqual(calls.confirm, [{ durationMs: 60000, projectId: 9 }])
  assert.equal(calls.transcribe.length, 0)
})

test('面板转写：确认后才调 /transcribe', async () => {
  const { vm, calls } = panelTranscribe(true)
  await vm.onTranscribe({ id: 3, durationMs: 60000 })
  assert.equal(calls.confirm.length, 1)
  assert.deepEqual(calls.transcribe, [3])
})
