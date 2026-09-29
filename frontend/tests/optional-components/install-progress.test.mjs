// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// pack 段的阶段可见与卡死兜底（dev-board#1015）：下载完成后的「校验 / 解压 / 核对 / 写入」
// 要逐段显示，不再停在 99% 的「正在准备组件…」；长时间无进展、后端重启丢了在途记录，
// 都要落到失败态给出重试，而不是无限转圈。
import test from 'node:test'
import assert from 'node:assert/strict'
import { createOptionalComponentsController, overallPercentOf } from '../../src/composables/useOptionalComponents.js'
import zh from '../../src/locales/zh-CN/components.js'
import en from '../../src/locales/en-US/components.js'

const MAX_POLLS = 2000

/** 按脚本逐次回 status；脚本走完后一直回最后一帧。轮询过多直接抛（旧实现无限轮询时不至于挂死测试）。 */
function scripted(frames, { clockStepMs = 1000 } = {}) {
  let i = 0
  let polls = 0
  let clock = 0
  const seen = []
  const item = { packId: 'pptx-runtime', service: 'pptx-service', installed: false, modelId: null,
    downloadBytes: 171000000, localeKey: 'pptxRuntime', phase: 'idle', percent: 0, error: '' }
  const d = {
    packInstall: async () => {},
    packStatus: async () => {
      polls += 1
      if (polls > MAX_POLLS) throw new Error('poll limit exceeded')
      const f = frames[Math.min(i, frames.length - 1)]
      i += 1
      return { code: 0, status: f }
    },
    ensureService: async () => ({ ok: true }),
    modelDownload: async () => {},
    onModelProgress: () => () => {},
    sleep: async () => {
      seen.push({ phase: item.phase, stage: item.stage, stageKey: item.stageKey, percent: item.percent })
      clock += clockStepMs
    },
    now: () => clock,
  }
  return { d, item, seen, polls: () => polls }
}

test('下载 → 校验 → 解压 n% → 核对 → 写入 逐段可见，下载段不再封顶 99%', async () => {
  const total = 171000000
  const { d, item, seen } = scripted([
    { state: 'downloading', phase: 'downloading', bytesDownloaded: total / 2, bytesTotal: total },
    { state: 'downloading', phase: 'downloading', bytesDownloaded: total, bytesTotal: total },
    { state: 'verifying', phase: 'verifying', bytesDownloaded: total, bytesTotal: total, phasePercent: -1 },
    { state: 'installing', phase: 'extracting', bytesDownloaded: total, bytesTotal: total, phasePercent: 40, filesDone: 4000 },
    { state: 'installing', phase: 'extracting', bytesDownloaded: total, bytesTotal: total, phasePercent: 80, filesDone: 8000 },
    { state: 'installing', phase: 'checking', bytesDownloaded: total, bytesTotal: total, phasePercent: 50, filesDone: 5000, filesTotal: 10000 },
    { state: 'installing', phase: 'finalizing', bytesDownloaded: total, bytesTotal: total, phasePercent: -1 },
    { state: 'ready', phase: 'ready', bytesDownloaded: total, bytesTotal: total },
  ])
  const c = createOptionalComponentsController(d)
  assert.equal(await c.installOne(item), true)

  const find = (stage, percent) => seen.find((s) => s.stage === stage && (percent === undefined || s.percent === percent))
  assert.ok(find('downloading', 50))
  assert.ok(find('downloading', 100), '下载字节满了就是 100%，不再封顶 99')
  assert.ok(find('verifying'))
  assert.ok(find('extracting', 40))
  assert.ok(find('extracting', 80))
  assert.ok(find('checking', 50))
  assert.ok(find('finalizing'))
  // 对话卡片那一句随阶段走：stageKey 与 stage 同步
  assert.equal(find('extracting', 40).stageKey, 'extracting')
  assert.equal(item.phase, 'ready')
})

test('解压阶段总进度继续前进，不会从下载的 100% 跳回 0（护栏：卡片上的阶段百分比会重置，总进度不能跟着退）', async () => {
  const { d, item } = scripted([
    { state: 'downloading', phase: 'downloading', bytesDownloaded: 10, bytesTotal: 10 },
    { state: 'installing', phase: 'extracting', bytesDownloaded: 10, bytesTotal: 10, phasePercent: 10, filesDone: 1 },
    { state: 'installing', phase: 'finalizing', bytesDownloaded: 10, bytesTotal: 10, phasePercent: -1 },
    { state: 'ready', phase: 'ready' },
  ])
  const snapshots = []
  const origSleep = d.sleep
  d.sleep = async () => { snapshots.push(overallPercentOf([{ ...item }])); await origSleep() }
  const c = createOptionalComponentsController(d)
  assert.equal(await c.installOne(item), true)
  assert.equal(snapshots.length, 3)
  for (let k = 1; k < snapshots.length; k++) {
    assert.ok(snapshots[k] >= snapshots[k - 1], `总进度不许回退: ${snapshots.join(',')}`)
  }
})

test('3 分钟内阶段与进度都没变 → 判失败（stalled），给出重试入口', async () => {
  const { d, item } = scripted([
    { state: 'installing', phase: 'extracting', bytesDownloaded: 10, bytesTotal: 10, phasePercent: 99, filesDone: 12000 },
  ], { clockStepMs: 30000 })
  const c = createOptionalComponentsController(d)
  assert.equal(await c.installOne(item), false)
  assert.equal(item.phase, 'failed')
  assert.equal(item.errorKey, 'stalled')
  assert.ok(item.error, '失败原因要有文字（后台提示直接用 item.error）')
})

test('还在推进（filesDone 在涨）就不算停滞，哪怕花了很久', async () => {
  const frames = []
  for (let k = 1; k <= 20; k++) frames.push({ state: 'installing', phase: 'extracting', bytesDownloaded: 10, bytesTotal: 10, phasePercent: 50, filesDone: k * 100 })
  frames.push({ state: 'ready', phase: 'ready' })
  const { d, item } = scripted(frames, { clockStepMs: 60000 })
  const c = createOptionalComponentsController(d)
  assert.equal(await c.installOne(item), true)
})

test('在途时后端回 not_installed（后端重启丢了记录）→ 判失败，提示重试', async () => {
  const { d, item } = scripted([
    { state: 'downloading', phase: 'downloading', bytesDownloaded: 5, bytesTotal: 10 },
    { state: 'not_installed' },
  ])
  const c = createOptionalComponentsController(d)
  assert.equal(await c.installOne(item), false)
  assert.equal(item.phase, 'failed')
  assert.equal(item.errorKey, 'backendRestarted')
})

test('后端直接回 failed：用后端给的原因（安装被中断等）', async () => {
  const { d, item } = scripted([
    { state: 'failed', phase: 'failed', error: '安装被中断，请重试' },
  ])
  const c = createOptionalComponentsController(d)
  assert.equal(await c.installOne(item), false)
  assert.equal(item.error, '安装被中断，请重试')
})

test('阶段文案两种语言都齐', () => {
  for (const loc of [zh, en]) {
    for (const k of ['stateVerifyingRuntime', 'stateExtractingRuntime', 'stateCheckingRuntime', 'stateFinalizingRuntime',
      'errorStalled', 'errorBackendRestarted']) {
      assert.equal(typeof loc[k], 'string', k)
    }
    for (const k of ['preparing', 'downloading', 'verifying', 'extracting', 'checking', 'finalizing', 'model', 'starting']) {
      assert.equal(typeof loc.chatStage[k], 'string', 'chatStage.' + k)
    }
  }
})
