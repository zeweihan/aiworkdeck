// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 媒体取源（utils/media/mediaSource.js，规格 4.1 前端段）与预览类型表（utils/media/mediaTypes.js）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { buildStreamUrl, redactToken, shouldFallbackToBlob } from '../../src/utils/media/mediaSource.js'
import {
  PREVIEW_VIDEO_EXTENSIONS, PREVIEW_AUDIO_EXTENSIONS, previewKindOf, mimeTypeFor,
} from '../../src/utils/media/mediaTypes.js'
import { SPEAKER_COLOR_TOKENS, speakerColorVar, speakerColorClass } from '../../src/utils/media/speakerColors.js'

// ── buildStreamUrl ──────────────────────────────────────────────────────────

test('buildStreamUrl：有 sessionId 追加 ?token=，已有 query 用 &', () => {
  assert.equal(buildStreamUrl('/api/files/7/download', 'abc'), '/api/files/7/download?token=abc')
  assert.equal(buildStreamUrl('http://h/api/files/7/download?x=1', 'abc'), 'http://h/api/files/7/download?x=1&token=abc')
})

test('buildStreamUrl：token 做 URL 编码；有 #fragment 时插在其前', () => {
  assert.equal(buildStreamUrl('/d', 'a b&c'), '/d?token=a%20b%26c')
  assert.equal(buildStreamUrl('/d#t=5', 'abc'), '/d?token=abc#t=5')
})

test('buildStreamUrl：无 sessionId 原样返回；空地址原样返回', () => {
  assert.equal(buildStreamUrl('/api/files/7/download', ''), '/api/files/7/download')
  assert.equal(buildStreamUrl('/api/files/7/download', null), '/api/files/7/download')
  assert.equal(buildStreamUrl('/api/files/7/download'), '/api/files/7/download')
  assert.equal(buildStreamUrl('', 'abc'), '')
})

// ── redactToken ─────────────────────────────────────────────────────────────

test('redactToken：token 值换成 ***，其余参数不动', () => {
  assert.equal(redactToken('/d?token=secret'), '/d?token=***')
  assert.equal(redactToken('/d?x=1&token=secret&y=2'), '/d?x=1&token=***&y=2')
  assert.equal(redactToken('/d?token=secret#t=5'), '/d?token=***#t=5')
})

test('redactToken：没有 token 原样返回；非字符串原样返回；不误伤 xtoken 这类参数名', () => {
  assert.equal(redactToken('/d?x=1'), '/d?x=1')
  assert.equal(redactToken('/d?xtoken=keep'), '/d?xtoken=keep')
  assert.equal(redactToken(''), '')
  assert.equal(redactToken(null), null)
  assert.equal(redactToken(buildStreamUrl('/d?a=1', 'sid')), '/d?a=1&token=***')
})

// ── shouldFallbackToBlob ────────────────────────────────────────────────────

test('shouldFallbackToBlob：NETWORK(2) / SRC_NOT_SUPPORTED(4) 且未回退过 → true', () => {
  assert.equal(shouldFallbackToBlob(2, false), true)
  assert.equal(shouldFallbackToBlob(4, false), true)
  assert.equal(shouldFallbackToBlob(4), true)
})

test('shouldFallbackToBlob：已回退过只触发一次；ABORTED(1)/DECODE(3)/非法码不回退', () => {
  assert.equal(shouldFallbackToBlob(2, true), false)
  assert.equal(shouldFallbackToBlob(4, true), false)
  assert.equal(shouldFallbackToBlob(1, false), false)
  assert.equal(shouldFallbackToBlob(3, false), false)
  assert.equal(shouldFallbackToBlob(undefined, false), false)
})

// ── mediaTypes ──────────────────────────────────────────────────────────────

test('扩展名表是契约值', () => {
  assert.deepEqual(PREVIEW_VIDEO_EXTENSIONS, ['mp4', 'webm', 'ogg', 'mov', 'mkv', 'avi', 'm4v'])
  assert.deepEqual(PREVIEW_AUDIO_EXTENSIONS, ['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac', 'opus'])
})

test('previewKindOf：视频/音频分流，大小写与前导点不敏感', () => {
  assert.equal(previewKindOf('mp4'), 'video')
  assert.equal(previewKindOf('MOV'), 'video')
  assert.equal(previewKindOf('.mkv'), 'video')
  assert.equal(previewKindOf('m4v'), 'video')
  assert.equal(previewKindOf('mp3'), 'audio')
  assert.equal(previewKindOf('Opus'), 'audio')
  assert.equal(previewKindOf('m4a'), 'audio')
})

test('previewKindOf：ogg 归音频、ogv 归视频；非媒体与空值返回 null', () => {
  assert.equal(previewKindOf('ogg'), 'audio')
  assert.equal(previewKindOf('ogv'), 'video')
  assert.equal(previewKindOf('pdf'), null)
  assert.equal(previewKindOf(''), null)
  assert.equal(previewKindOf(null), null)
  assert.equal(previewKindOf(undefined), null)
})

test('mimeTypeFor：覆盖规格 4.1 的媒体映射', () => {
  const expected = {
    mp4: 'video/mp4', m4v: 'video/mp4', webm: 'video/webm', ogv: 'video/ogg', mov: 'video/quicktime',
    mkv: 'video/x-matroska', avi: 'video/x-msvideo',
    mp3: 'audio/mpeg', wav: 'audio/wav', ogg: 'audio/ogg', m4a: 'audio/mp4', flac: 'audio/flac',
    aac: 'audio/aac', opus: 'audio/ogg',
  }
  for (const [ext, mime] of Object.entries(expected)) assert.equal(mimeTypeFor(ext), mime, ext)
  assert.equal(mimeTypeFor('MP4'), 'video/mp4')
})

test('mimeTypeFor：两张扩展名表里的每一项都有 MIME；未知与空值返回空串', () => {
  for (const ext of [...PREVIEW_VIDEO_EXTENSIONS, ...PREVIEW_AUDIO_EXTENSIONS]) assert.ok(mimeTypeFor(ext), ext)
  assert.equal(mimeTypeFor('docx'), '')
  assert.equal(mimeTypeFor(''), '')
  assert.equal(mimeTypeFor(null), '')
})

// ── speakerColors ───────────────────────────────────────────────────────────

test('speakerColors：参数是原始说话人 id，Number(id) % 6，与会议面板 .sp-N 同源', () => {
  assert.equal(SPEAKER_COLOR_TOKENS.length, 6)
  assert.ok(SPEAKER_COLOR_TOKENS.every(t => t.startsWith('--awd-')))
  assert.equal(speakerColorVar(0), 'var(--awd-accent-text)')
  assert.equal(speakerColorVar(6), speakerColorVar(0))
  assert.equal(speakerColorVar('7'), speakerColorVar(1))
  assert.equal(speakerColorClass(0), 'sp-0')
  assert.equal(speakerColorClass('9'), 'sp-3')
  assert.equal(speakerColorClass('2'), 'sp-2')
  assert.equal(speakerColorVar('2'), 'var(--awd-warning-text)')
})

test('speakerColors：第 4 色没有主题令牌，保留会议面板现值作回退；非法下标落到 0', () => {
  assert.match(speakerColorVar(3), /^var\(--awd-[a-z0-9-]+, #7A3FBF\)$/)
  assert.equal(speakerColorVar(-1), speakerColorVar(0))
  assert.equal(speakerColorVar('abc'), speakerColorVar(0))
  assert.equal(speakerColorClass(undefined), 'sp-0')
})
