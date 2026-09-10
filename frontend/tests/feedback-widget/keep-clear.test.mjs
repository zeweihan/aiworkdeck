// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 反馈浮钮让路几何（dev-board#574）。数值取自真实复现：1440x900 视口、en-US 空会话，
// 输入卡 [1105,401,311,151]，浮钮默认位 [1326,512,96,28]（right:16px, bottom:40vh）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { intersects, resolveLauncherTop } from '../../src/utils/keepClear.js'

const VH = 900
const launcher = { left: 1326, top: 512, width: 96, height: 28 }
const enComposer = { left: 1105, top: 401, width: 311, height: 151 }

test('复现：默认位与英文空会话输入卡相交', () => {
  assert.equal(intersects(launcher, enComposer), true)
})

test('压住输入卡时挪到离原位最近的空位，且不再相交', () => {
  const top = resolveLauncherTop(launcher, [enComposer], VH)
  // 输入卡底边 552 + gap 8 = 560，比挪到上方（401-8-28=365）近
  assert.equal(top, 560)
  assert.equal(intersects({ ...launcher, top }, enComposer, 8), false)
})

test('没压住任何东西时原地不动', () => {
  const farAway = { left: 100, top: 100, width: 300, height: 150 }
  assert.equal(resolveLauncherTop(launcher, [farAway], VH), 512)
  assert.equal(resolveLauncherTop(launcher, [], VH), 512)
})

test('下方放不下时往上让', () => {
  const low = { left: 1105, top: 480, width: 311, height: 400 }
  const top = resolveLauncherTop(launcher, [low], VH)
  assert.equal(top, 480 - 8 - 28)
})

test('多个障碍：跳过被另一个障碍占着的候选位', () => {
  const below = { left: 1300, top: 556, width: 140, height: 40 }
  const top = resolveLauncherTop(launcher, [enComposer, below], VH)
  // 560 被 below 占了、520 又压回输入卡；剩下 604（below 底边 596+8，距原位 92）
  // 与 365（输入卡上方，距原位 147），取近的
  assert.equal(top, 604)
})

test('整条竖线都被占满时留在原位，不推出视口', () => {
  const wall = { left: 1200, top: 0, width: 240, height: VH }
  assert.equal(resolveLauncherTop(launcher, [wall], VH), 512)
})

test('宽高为零的障碍（display:none 的元素）不算', () => {
  const hidden = { left: 1105, top: 401, width: 0, height: 0 }
  assert.equal(resolveLauncherTop(launcher, [hidden], VH), 512)
})
