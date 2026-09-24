// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#889：内置浏览器加载失败只显示空白。
// 复现：地址栏输入无法解析的域名 → did-fail-load 只被 console.warn 过，从没传到
// 渲染层，用户看到的是完全空白（AX 内容是 chrome-error://chromewebdata/）。
// 这份测试钉住「要不要显示」与「显示成哪句话」这两个纯函数的判断。
import test from 'node:test'
import assert from 'node:assert/strict'
import { shouldShowLoadError, loadErrorMessageKey } from '../../src/utils/browserLoadError.js'

test('shouldShowLoadError：ERR_ABORTED(-3) 必须忽略（重定向/用户中断的常见误报）', () => {
  assert.equal(shouldShowLoadError(-3), false)
  assert.equal(shouldShowLoadError('-3'), false)
})

test('shouldShowLoadError：真实失败要展示', () => {
  assert.equal(shouldShowLoadError(-105), true, 'ERR_NAME_NOT_RESOLVED 是本卡复现用例')
  assert.equal(shouldShowLoadError(-102), true, 'ERR_CONNECTION_REFUSED')
  assert.equal(shouldShowLoadError(-7), true, 'ERR_TIMED_OUT')
  assert.equal(shouldShowLoadError(-200), true, 'ERR_CERT_COMMON_NAME_INVALID')
})

test('shouldShowLoadError：非法/零值一律不展示', () => {
  assert.equal(shouldShowLoadError(0), false)
  assert.equal(shouldShowLoadError(undefined), false)
  assert.equal(shouldShowLoadError(null), false)
  assert.equal(shouldShowLoadError('not-a-code'), false)
})

test('loadErrorMessageKey：DNS 解析失败映射到专用文案键', () => {
  assert.equal(loadErrorMessageKey(-105), 'bpErrorNameNotResolved')
  assert.equal(loadErrorMessageKey(-137), 'bpErrorNameNotResolved')
})

test('loadErrorMessageKey：连接超时/被拒绝/证书错误各自映射', () => {
  assert.equal(loadErrorMessageKey(-7), 'bpErrorTimedOut')
  assert.equal(loadErrorMessageKey(-118), 'bpErrorTimedOut')
  assert.equal(loadErrorMessageKey(-102), 'bpErrorConnectionRefused')
  assert.equal(loadErrorMessageKey(-200), 'bpErrorCertInvalid')
  assert.equal(loadErrorMessageKey(-107), 'bpErrorCertInvalid')
})

test('loadErrorMessageKey：未收录的 code 落回通用文案，不是抛错或 undefined', () => {
  assert.equal(loadErrorMessageKey(-9999), 'bpErrorGeneric')
  assert.equal(loadErrorMessageKey(undefined), 'bpErrorGeneric')
})
