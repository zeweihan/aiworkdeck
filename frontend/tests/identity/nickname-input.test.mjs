// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 昵称输入框的两处时序缺口（v0.38.2 发版走查抓到）。
//  ① 打完字立刻回车：uni-h5 的 v-model 有 100ms 节流，confirm 那一刻 displayNameInput
//     还是上一拍的值，存上去的少最后一个字。改用 confirm/blur 事件自己带的 detail.value。
//  ② 「去填写」聚焦：请求发出时面板的资料还没拉回来（canEditProfile 为 false），
//     旧实现直接 return 把请求丢了。要挂起，资料就绪再兑现。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { submittedInputValue } from '../../src/utils/identityProfile.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const PANEL = readFileSync(
  path.resolve(HERE, '../../src/components/userprofile/PersonalSettingsPanel.vue'), 'utf8')

test('事件带了值就用事件的值（v-model 节流期内它才是最新的）', () => {
  assert.equal(submittedInputValue({ detail: { value: '韩律师二' } }, '韩律师'), '韩律师二')
  // 用户把框清空后回车：空串也是事件的真值，不能回落成旧值
  assert.equal(submittedInputValue({ detail: { value: '' } }, '韩律师'), '')
})

test('没有事件或事件不带字符串值 → 回落 v-model 的值', () => {
  assert.equal(submittedInputValue(undefined, '韩律师'), '韩律师')
  assert.equal(submittedInputValue({}, '韩律师'), '韩律师')
  assert.equal(submittedInputValue({ detail: {} }, '韩律师'), '韩律师')
  assert.equal(submittedInputValue({ detail: { value: 3 } }, '韩律师'), '韩律师')
})

function methodBody(src, marker) {
  const i = src.indexOf(marker)
  assert.ok(i >= 0, '找不到 ' + marker)
  const start = src.indexOf('{', i)
  let depth = 0
  for (let j = start; j < src.length; j++) {
    if (src[j] === '{') depth++
    else if (src[j] === '}' && --depth === 0) return src.slice(i, j + 1)
  }
  return ''
}

test('saveDisplayName 读的是事件值，不是节流中的 v-model', () => {
  const body = methodBody(PANEL, 'async saveDisplayName(')
  assert.match(body, /submittedInputValue\(/)
})

test('资料未就绪时的聚焦请求要挂起，loadAccountProfile 之后兑现', () => {
  // 带缩进与花括号定位方法定义本身（mounted 与 loadAccountProfile 里都有 this.focusDisplayName() 调用）
  const focus = methodBody(PANEL, '    focusDisplayName() {')
  assert.match(focus, /_pendingNameFocus\s*=\s*true/, '不可编辑时要记下请求而不是直接丢掉')
  const load = methodBody(PANEL, 'async loadAccountProfile(')
  assert.match(load, /_pendingNameFocus/, '资料拉回来之后要兑现挂起的聚焦')
})
