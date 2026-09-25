// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-57：空录音 / 极短录音结束时不再自动提交云转写（平台档会预扣并结算），
// 会议留在 RECORDED，由用户确认后手动点「开始转写」。真跑一遍录音引擎，断言 finish 的 transcribe 参数。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import * as stubs from './stubs.mjs'
import { decideAutoTranscribe } from '../../src/utils/meetingRecorderStatus.js'

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

function buildModuleUrl(tag) {
  const src = readFileSync(new URL('../../src/utils/meetingRecorder.js', import.meta.url), 'utf8')
  const stubsUrl = new URL('./stubs.mjs', import.meta.url).href
  const rewritten = src
    .replace(/from 'vue'/, `from '${stubsUrl}'`)
    .replace(/from '@\/services\/api\.js'/, `from '${stubsUrl}'`)
    .replace(/from '@\/utils\/auth\.js'/, `from '${stubsUrl}'`)
    .replace(/from '@\/i18n'/, `from '${stubsUrl}'`)
    .replace(/from '@\/utils\/meetingRecorderStatus\.js'/,
      `from '${new URL('../../src/utils/meetingRecorderStatus.js', import.meta.url).href}'`)
  const out = join(tmpdir(), `meeting-recorder-skip-${tag}-${process.pid}.mjs`)
  writeFileSync(out, rewritten, 'utf8')
  return out
}

class FakeRecorder {
  constructor() { this.state = 'recording' }
  start() {}
  stop() { this.state = 'inactive'; if (this.onstop) this.onstop() }
}
FakeRecorder.isTypeSupported = () => false

function installGlobals(amplitude) {
  Object.defineProperty(globalThis, 'navigator', {
    value: { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [] }) } },
    configurable: true, writable: true,
  })
  globalThis.MediaRecorder = FakeRecorder
  class FakeCtx {
    createMediaStreamSource() { return { connect() {} } }
    createAnalyser() {
      return {
        fftSize: 0,
        frequencyBinCount: 16,
        getByteTimeDomainData(buf) { for (let i = 0; i < buf.length; i++) buf[i] = 128 + (i % 2 ? amplitude : -amplitude) },
      }
    }
    close() {}
  }
  globalThis.window = { AudioContext: FakeCtx }
}

async function recordAndStop(tag, { amplitude, seconds }) {
  installGlobals(amplitude)
  const rec = await import(buildModuleUrl(tag))
  await rec.startRecording('p1')
  await sleep(450) // 电平计 200ms 一跳，至少采两跳
  rec.recorderState.seconds = seconds
  const before = stubs.finishArgs().length
  await rec.stopRecording()
  return { rec, args: stubs.finishArgs()[before] }
}

test('判定：过短、全程无声不自动转写；拿不到电平时不猜', () => {
  assert.deepEqual(decideAutoTranscribe({ seconds: 1, peakLevel: 0.5, meterAvailable: true }),
    { transcribe: false, reason: 'too-short' })
  assert.deepEqual(decideAutoTranscribe({ seconds: 7, peakLevel: 0, meterAvailable: true }),
    { transcribe: false, reason: 'silent' })
  assert.deepEqual(decideAutoTranscribe({ seconds: 7, peakLevel: 0.3, meterAvailable: true }),
    { transcribe: true, reason: null })
  assert.deepEqual(decideAutoTranscribe({ seconds: 7, peakLevel: 0, meterAvailable: false }),
    { transcribe: true, reason: null })
})

test('7 秒全程静音：finish 带 transcribe=false，并留下跳过原因给面板', async () => {
  const { rec, args } = await recordAndStop('silent', { amplitude: 0, seconds: 7 })
  assert.equal(args.transcribe, false, 'finish 不应自动提交转写，实际参数 ' + JSON.stringify(args))
  assert.equal(args.durationMs, 7000)
  assert.equal(rec.recorderState.autoTranscribeSkipped, 'silent')
})

test('有声音的正常录音照旧自动转写', async () => {
  const { rec, args } = await recordAndStop('voice', { amplitude: 40, seconds: 7 })
  assert.notEqual(args.transcribe, false)
  assert.equal(rec.recorderState.autoTranscribeSkipped, null)
})
