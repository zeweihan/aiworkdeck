// 审计（dev-board#74）确认的缺陷：录音设备中途死掉，界面照常计时报「录音中」。
//
// 病灶：ondataavailable 只入队、onstop 只置 recordingDone，两者都不动
// recorderState.status；模块内也没有任何 track 的 onended 监听，计时器按
// status==='recording' 无限自增，UI 照常画红点和「录音中」。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveTrackEndedStatus } from '../../src/utils/meetingRecorderStatus.js'

test('正在录音时轨道 ended：复位为 interrupted', () => {
  assert.equal(resolveTrackEndedStatus('recording'), 'interrupted')
})

test('暂停中轨道 ended：同样复位为 interrupted（暂停也是"确实在录"的一种）', () => {
  assert.equal(resolveTrackEndedStatus('paused'), 'interrupted')
})

test('starting/stopping/idle 状态下不处理，返回 null', () => {
  assert.equal(resolveTrackEndedStatus('starting'), null)
  assert.equal(resolveTrackEndedStatus('stopping'), null)
  assert.equal(resolveTrackEndedStatus('idle'), null)
})

test('已经是 interrupted 时不重复处理（多条音轨先后 ended 不应互相覆盖）', () => {
  assert.equal(resolveTrackEndedStatus('interrupted'), null)
})
