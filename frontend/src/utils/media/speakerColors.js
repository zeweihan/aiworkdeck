// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 说话人六色循环（从 MeetingRecordingPanel 抽出，会议面板与播放器逐字稿共用一套下标）。
// 会议面板的 .sp-0..5 样式仍在面板 scss 里（底色 + 字色成对），这里只管下标规则与字色令牌。
// 第 4 色（.sp-3）面板现值是字面量紫色，主题里没有对应令牌：给它起一个令牌名并把现值作
// var() 回退，颜色不变；将来在 App.vue 里定义该令牌即可接入深浅色。

export const SPEAKER_COLOR_TOKENS = [
  '--awd-accent-text',
  '--awd-info-text',
  '--awd-warning-text',
  '--awd-speaker-violet-text',
  '--awd-danger-text',
  '--awd-info-text',
]

const FALLBACKS = { 3: '#7A3FBF' }

function slot(speakerId) {
  const n = Number(speakerId)
  if (!Number.isFinite(n) || n < 0) return 0
  return Math.floor(n) % SPEAKER_COLOR_TOKENS.length
}

/**
 * 说话人字色 'var(--awd-...)'。
 * @param {string|number} speakerId 原始说话人 id（段里的 speaker 字段），取 Number(id) % 6，非法为 0，与会议面板同源
 */
export function speakerColorVar(speakerId) {
  const i = slot(speakerId)
  const fb = FALLBACKS[i]
  return 'var(' + SPEAKER_COLOR_TOKENS[i] + (fb ? ', ' + fb : '') + ')'
}

/**
 * 会议面板用的类名 'sp-N'。
 * @param {string|number} speakerId 原始说话人 id，取 Number(id) % 6，非法为 0
 */
export function speakerColorClass(speakerId) {
  return 'sp-' + slot(speakerId)
}
