// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 交稿引导的状态 → 步骤映射（dev-board#645）。跑法：node --test tests/version-merge/*.test.mjs
import test from 'node:test'
import assert from 'node:assert/strict'
import { submitGuideSteps, SUBMIT_GUIDE_STEP_IDS } from '../../src/utils/submitGuide.js'

const stateOf = (out, id) => out.steps.find((s) => s.id === id).state

test('什么都不欠：标题说可以交稿，交稿那一步就是进行中', () => {
  const out = submitGuideSteps({ working: false, remoteAhead: false })
  assert.equal(out.title, 'version.submitGuideReady')
  assert.equal(out.pending, 0)
  assert.equal(out.canSubmit, true)
  assert.equal(stateOf(out, 'end-session'), 'done')
  assert.equal(stateOf(out, 'pull-latest'), 'done')
  assert.equal(stateOf(out, 'submit'), 'active')
})

test('手头有活 + 案件库领先：还差两步，且交稿不可点', () => {
  const out = submitGuideSteps({ working: true, remoteAhead: true, remoteAheadCount: 6 })
  assert.equal(out.title, 'version.submitGuideTwoLeft')
  assert.equal(out.pending, 2)
  assert.equal(out.canSubmit, false)
})

test('顺序解锁：两步都欠时只有第一步是进行中，后两步都待做', () => {
  const out = submitGuideSteps({ working: true, remoteAhead: true })
  assert.equal(stateOf(out, 'end-session'), 'active')
  assert.equal(stateOf(out, 'pull-latest'), 'todo')
  assert.equal(stateOf(out, 'submit'), 'todo')
})

test('只欠结束工作：还差一步，取回那一步已完成，交稿仍待做', () => {
  const out = submitGuideSteps({ working: true, remoteAhead: false })
  assert.equal(out.title, 'version.submitGuideOneLeft')
  assert.equal(out.canSubmit, false)
  assert.equal(stateOf(out, 'end-session'), 'active')
  assert.equal(stateOf(out, 'pull-latest'), 'done')
  assert.equal(stateOf(out, 'submit'), 'todo')
})

test('只欠取回：结束工作已完成，取回变成进行中', () => {
  const out = submitGuideSteps({ working: false, remoteAhead: true })
  assert.equal(out.title, 'version.submitGuideOneLeft')
  assert.equal(stateOf(out, 'end-session'), 'done')
  assert.equal(stateOf(out, 'pull-latest'), 'active')
  assert.equal(stateOf(out, 'submit'), 'todo')
})

test('取回那一步带上版数与「是不是我自己交的」，别的步不带', () => {
  const out = submitGuideSteps({ remoteAhead: true, remoteAheadCount: 6, remoteAheadBySelf: true })
  const pull = out.steps.find((s) => s.id === 'pull-latest')
  assert.equal(pull.count, 6)
  assert.equal(pull.bySelf, true)
  assert.equal(out.steps.find((s) => s.id === 'submit').count, undefined)
})

test('老服务端不回 remoteAheadCount：给 0，不给 undefined 让界面印出「NaN 版」', () => {
  const pull = submitGuideSteps({ remoteAhead: true }).steps.find((s) => s.id === 'pull-latest')
  assert.equal(pull.count, 0)
  assert.equal(pull.bySelf, false)
})

test('连不上案件库：只剩标题，一步都不摆（三步都要联网，全灰清单像卡死）', () => {
  const out = submitGuideSteps({ working: true, remoteAhead: true, offline: true })
  assert.equal(out.title, 'version.submitGuideOfflineTitle')
  assert.equal(out.offline, true)
  assert.deepEqual(out.steps, [])
  assert.equal(out.canSubmit, false)
})

test('步骤顺序恒定，且就是依赖顺序', () => {
  assert.deepEqual(SUBMIT_GUIDE_STEP_IDS, ['end-session', 'pull-latest', 'submit'])
  const ids = submitGuideSteps({ working: true, remoteAhead: true }).steps.map((s) => s.id)
  assert.deepEqual(ids, SUBMIT_GUIDE_STEP_IDS)
})

test('缺参/空参也给得出一份能渲染的清单', () => {
  for (const arg of [undefined, null, {}]) {
    const out = submitGuideSteps(arg)
    assert.equal(out.steps.length, 3)
    assert.equal(out.canSubmit, true)
  }
})
