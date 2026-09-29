// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// CC 按钮五态（规格 3.6）：由「该文件的会议记录」推导。
//   none / pending / transcribing / ready / empty / failed
// （五态指按钮呈现：none 与 pending 呈现相同，点击动作不同。）
import { parseSegments } from './transcriptCues.js'

/** 本地存储只存「关」，默认开；再开则删键。 */
export const CAPTIONS_OFF_KEY = 'awd_media_captions_off'

export function captionStateFrom(meeting) {
  if (!meeting) return 'none'
  switch (meeting.status) {
    // RECORDING：会议面板正在录这份音频，记录在但还没提交转写，与 RECORDED 同处理
    case 'RECORDING':
    case 'RECORDED':
      return 'pending'
    case 'TRANSCRIBING':
      return 'transcribing'
    case 'TRANSCRIBED':
      return parseSegments(meeting.transcriptJson).length ? 'ready' : 'empty'
    case 'EMPTY':
      return 'empty'
    case 'FAILED':
      return 'failed'
    default:
      return 'none'
  }
}

/** 是否停止轮询：只有 transcribing 还要继续问。 */
export function isTerminal(state) {
  return state !== 'transcribing'
}
