// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// meetingRecorder.js 的纯判定逻辑，刻意做成零依赖：既能被 meetingRecorder.js 直接用，
// 也能在 node:test 里真跑一遍（meetingRecorder.js 自己 import 了 @/ 别名，测不动）。

/**
 * 麦克风轨道被系统/设备中途结束（拔设备、权限被系统收回）时，录音状态应该复位成什么。
 *
 * 病灶：ondataavailable 只入队、onstop 只置 recordingDone，两者都不动 recorderState.status；
 * 计时器按 status==='recording' 无限自增，界面因此照常画红点和「录音中」，用户毫无察觉。
 *
 * 只在「确实在录音」的两个状态（recording / paused）里响应；starting/stopping/idle，
 * 或者已经因为上一条轨道 ended 而处于 interrupted 状态时，重复触发（比如多条音轨
 * 先后 ended）不应该覆盖别的收尾逻辑正在做的事。
 *
 * @param {string} currentStatus recorderState.status 此刻的值
 * @returns {string|null} 应该置入的新状态；不需要处理时返回 null
 */
export function resolveTrackEndedStatus(currentStatus) {
  if (currentStatus !== 'recording' && currentStatus !== 'paused') return null
  return 'interrupted'
}

// 结束录音时是否自动提交转写（BUG-57）。7 秒无人声的录音在平台档被自动提交、预扣并结算，
// 等来的只是「未检测到有效语音」或读不出的结果。这里只拦「自动」提交：会议仍落 RECORDED，
// 面板上「开始转写」照常可点，用户确认有声音时自己点一下即可——宁可多一次点击，不替他花钱。
export const MIN_AUTO_TRANSCRIBE_SECONDS = 2
// 电平是 RMS×4 封顶 1（见 meetingRecorder.js startLevelMeter）；0.02 ≈ RMS 0.005，
// 远低于正常说话（0.1 以上）与普通室内底噪，只有「完全没有输入」才会一直低于它。
export const SILENCE_PEAK_LEVEL = 0.02

/**
 * @param {{seconds:number, peakLevel:number, meterAvailable:boolean}} s
 * @returns {{transcribe:boolean, reason:(null|'too-short'|'silent')}}
 */
export function decideAutoTranscribe({ seconds, peakLevel, meterAvailable }) {
  if (!(seconds >= MIN_AUTO_TRANSCRIBE_SECONDS)) return { transcribe: false, reason: 'too-short' }
  // 拿不到电平（AudioContext 不可用）时不猜，照旧自动提交
  if (meterAvailable && !(peakLevel >= SILENCE_PEAK_LEVEL)) return { transcribe: false, reason: 'silent' }
  return { transcribe: true, reason: null }
}
