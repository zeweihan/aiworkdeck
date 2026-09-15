// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 「转写中」进度提示（dev-board#532）。用真实的 zh-CN / en-US 词条跑 progressText，
// 少一个键或者阶段枚举对不上都会在这里露馅。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zh from '../../src/locales/zh-CN/meeting.js'
import en from '../../src/locales/en-US/meeting.js'

const source = readFileSync(new URL('../../src/components/MeetingRecordingPanel.vue', import.meta.url), 'utf8')
const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import[\s\S]*?from ['"][^'"]+['"]\s*$/gm, '')
  .replace('export default', 'return')

/** 词条里的 {x} 占位与 vue-i18n 同形，测试里做同样的替换即可。 */
function translator(dict) {
  return (key, params) => {
    const raw = dict[key.replace(/^meeting\./, '')]
    assert.ok(raw !== undefined, `词条缺失: ${key}`)
    return String(raw).replace(/\{(\w+)\}/g, (_, k) => (params && params[k] !== undefined ? params[k] : ''))
  }
}

function panel(dict) {
  const component = new Function(
    'AwdSwitch', 'AwdSelect', 'listAudioInputDevices', 'updateMeetingRecording', 'uni', 'formatSeconds', script)(
    {}, {}, async () => [], async () => {}, { getStorageSync: () => '', setStorageSync() {} },
    seconds => `${seconds}s`)
  return { $t: translator(dict), ...component.methods }
}

const transcribing = progress => ({ id: 1, status: 'TRANSCRIBING', progress })

test('上游转写阶段：阶段 + 已用时 + 预计时长，且明确标注是估算', () => {
  const vm = panel(zh)
  const text = vm.progressText(transcribing({
    stage: 'UPSTREAM', elapsedSec: 300, estimatedSec: 600, percent: 50, estimated: true
  }))
  assert.match(text, /转写与说话人分离/)
  assert.match(text, /300s/)
  assert.match(text, /600s/)
  // 三条上游都给不出真实百分比，不标「估算」就是把估算冒充真值
  assert.match(text, /估算/)
})

test('本机档阶段绝不能说「上传」——那一档一个字节都不出网', () => {
  const zhText = panel(zh).progressText(transcribing({
    stage: 'LOCAL', elapsedSec: 60, estimatedSec: 600, percent: 10, estimated: true
  }))
  assert.match(zhText, /本机转写/)
  assert.doesNotMatch(zhText, /上传/)

  const enText = panel(en).progressText(transcribing({
    stage: 'LOCAL', elapsedSec: 60, estimatedSec: 600, percent: 10, estimated: true
  }))
  assert.match(enText, /on this machine/)
  assert.doesNotMatch(enText, /upload/i)
})

test('云端档还没拿到任务号时说的是「转码与上传」', () => {
  const text = panel(zh).progressText(transcribing({
    stage: 'PREPARING', elapsedSec: 10, estimatedSec: 600, percent: 1, estimated: true
  }))
  assert.match(text, /转码与上传/)
})

test('音频时长未知时只报已用时，不编一个预计值出来', () => {
  const text = panel(zh).progressText(transcribing({
    stage: 'UPSTREAM', elapsedSec: 120, estimatedSec: null, percent: null, estimated: true
  }))
  assert.match(text, /120s/)
  assert.doesNotMatch(text, /预计/)
  assert.doesNotMatch(text, /估算/)
})

test('上游若哪天给出真百分比（estimated=false），「估算」标注自动消失', () => {
  const text = panel(zh).progressText(transcribing({
    stage: 'UPSTREAM', elapsedSec: 300, estimatedSec: 600, percent: 50, estimated: false
  }))
  assert.doesNotMatch(text, /估算/)
})

test('没有 progress 字段（存量后端 / 非转写中）不炸', () => {
  assert.equal(panel(zh).progressText({ id: 1, status: 'TRANSCRIBED' }), '')
})

test('英文版三个阶段词条齐全，中英键一一对应', () => {
  for (const key of ['transcribingStagePreparing', 'transcribingStageLocal', 'transcribingStageUpstream',
    'transcribingProgress', 'transcribingProgressNoEstimate', 'transcribingEstimatedMark']) {
    assert.ok(zh[key], `zh-CN 缺 ${key}`)
    assert.ok(en[key], `en-US 缺 ${key}`)
  }
})
