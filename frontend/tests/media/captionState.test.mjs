// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// CC 按钮五态（utils/media/captionState.js，规格 3.6）：会议记录 status → 五态。
import test from 'node:test'
import assert from 'node:assert/strict'
import { CAPTIONS_OFF_KEY, captionStateFrom, isTerminal } from '../../src/utils/media/captionState.js'

const seg = JSON.stringify([{ speaker: '1', start: 0, end: 1000, text: '有话' }])

test('CAPTIONS_OFF_KEY 是契约键名', () => {
  assert.equal(CAPTIONS_OFF_KEY, 'awd_media_captions_off')
})

test('captionStateFrom：无会议记录 → none', () => {
  assert.equal(captionStateFrom(null), 'none')
  assert.equal(captionStateFrom(undefined), 'none')
})

test('captionStateFrom：六种 status 映射到五态', () => {
  assert.equal(captionStateFrom({ status: 'RECORDING' }), 'pending')
  assert.equal(captionStateFrom({ status: 'RECORDED' }), 'pending')
  assert.equal(captionStateFrom({ status: 'TRANSCRIBING' }), 'transcribing')
  assert.equal(captionStateFrom({ status: 'TRANSCRIBED', transcriptJson: seg }), 'ready')
  assert.equal(captionStateFrom({ status: 'EMPTY' }), 'empty')
  assert.equal(captionStateFrom({ status: 'FAILED', error: 'x' }), 'failed')
})

test('captionStateFrom：TRANSCRIBED 但段为空 / 缺失 / 坏 JSON → empty', () => {
  assert.equal(captionStateFrom({ status: 'TRANSCRIBED', transcriptJson: '[]' }), 'empty')
  assert.equal(captionStateFrom({ status: 'TRANSCRIBED' }), 'empty')
  assert.equal(captionStateFrom({ status: 'TRANSCRIBED', transcriptJson: '{oops' }), 'empty')
})

test('captionStateFrom：未知 status 当作没有可用记录 → none', () => {
  assert.equal(captionStateFrom({ status: 'SOMETHING_NEW' }), 'none')
  assert.equal(captionStateFrom({}), 'none')
})

test('isTerminal：只有 transcribing 需要继续轮询', () => {
  for (const s of ['ready', 'empty', 'failed', 'none', 'pending']) assert.equal(isTerminal(s), true, s)
  assert.equal(isTerminal('transcribing'), false)
})
