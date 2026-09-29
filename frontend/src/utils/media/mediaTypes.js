// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 预览用的视频/音频扩展名表与 MIME 映射（规格 4.1）。
// 注意：这是「预览走播放器」的表，与「能转写」的表（utils/audioAttachment.js，
// 与后端 MeetingRecordingService 对拍）是两回事，别合并。
// ogg 两张表里都有：扩展名本身分不清音视频，previewKindOf 一律按音频处理，
// 只有显式的 ogv 才当视频。

export const PREVIEW_VIDEO_EXTENSIONS = ['mp4', 'webm', 'ogg', 'mov', 'mkv', 'avi', 'm4v']
export const PREVIEW_AUDIO_EXTENSIONS = ['mp3', 'wav', 'ogg', 'm4a', 'flac', 'aac', 'opus']

const MIME = {
  mp4: 'video/mp4',
  m4v: 'video/mp4',
  webm: 'video/webm',
  ogv: 'video/ogg',
  mov: 'video/quicktime',
  mkv: 'video/x-matroska',
  avi: 'video/x-msvideo',
  mp3: 'audio/mpeg',
  wav: 'audio/wav',
  ogg: 'audio/ogg',
  m4a: 'audio/mp4',
  flac: 'audio/flac',
  aac: 'audio/aac',
  opus: 'audio/ogg',
}

function normExt(ext) {
  if (!ext || typeof ext !== 'string') return ''
  return ext.trim().replace(/^\./, '').toLowerCase()
}

/** 'video' | 'audio' | null */
export function previewKindOf(fileType) {
  const ext = normExt(fileType)
  if (!ext) return null
  if (ext === 'ogv') return 'video'
  if (PREVIEW_AUDIO_EXTENSIONS.includes(ext)) return 'audio'
  if (PREVIEW_VIDEO_EXTENSIONS.includes(ext)) return 'video'
  return null
}

/** 媒体扩展名 → MIME；未知返回 ''。 */
export function mimeTypeFor(ext) {
  return MIME[normExt(ext)] || ''
}
