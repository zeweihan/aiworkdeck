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
