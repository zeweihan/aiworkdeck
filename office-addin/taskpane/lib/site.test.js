// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 官网站点映射的回归用例（dev-board#198）。
 *   node --test office-addin/taskpane/lib/site.test.js
 *
 * 钉住白名单语义：只有两个官方 addin 后端映射到充值页；
 * 私有部署与桌面本机绝不能推导出一个不存在的充值链接。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { legalUrl, rechargeUrl, signUpUrl } from './site.js'

test('官方两站映射到各自的 /account 账户页', () => {
  assert.equal(rechargeUrl('https://addin.aiworkdeck.com'), 'https://aiworkdeck.com/account')
  assert.equal(rechargeUrl('https://addin.workdeck.ai'), 'https://workdeck.ai/account')
  // normalizeBaseUrl 会裁尾部斜杠，带斜杠的配置同样命中
  assert.equal(rechargeUrl('https://addin.aiworkdeck.com/'), 'https://aiworkdeck.com/account')
})

test('私有部署 / 桌面本机 / 空地址一律回空串（入口隐藏）', () => {
  assert.equal(rechargeUrl('https://addin.yourfirm.com'), '')
  assert.equal(rechargeUrl('http://127.0.0.1:5269'), '')
  assert.equal(rechargeUrl(''), '')
  assert.equal(rechargeUrl('not a url'), '')
})

test('注册页：官方两站指向各自账户页（验证码即登录，账户页就是注册页）', () => {
  assert.equal(signUpUrl('https://addin.aiworkdeck.com'), 'https://aiworkdeck.com/account')
  assert.equal(signUpUrl('https://addin.workdeck.ai/'), 'https://workdeck.ai/account')
})

test('注册页：非官方后端回空串（入口隐藏）', () => {
  assert.equal(signUpUrl('https://addin.yourfirm.com'), '')
  assert.equal(signUpUrl('http://127.0.0.1:5269'), '')
  assert.equal(signUpUrl(''), '')
})

test('法律文件：官方两站派生服务条款与隐私政策', () => {
  assert.equal(legalUrl('https://addin.aiworkdeck.com', 'terms'), 'https://aiworkdeck.com/legal/terms')
  assert.equal(legalUrl('https://addin.aiworkdeck.com', 'privacy'), 'https://aiworkdeck.com/legal/privacy')
  assert.equal(legalUrl('https://addin.workdeck.ai', 'terms'), 'https://workdeck.ai/legal/terms')
  assert.equal(legalUrl('https://addin.workdeck.ai', 'privacy'), 'https://workdeck.ai/legal/privacy')
})

test('法律文件：非官方后端或未知 kind 回空串', () => {
  assert.equal(legalUrl('https://addin.yourfirm.com', 'terms'), '')
  assert.equal(legalUrl('', 'privacy'), '')
  assert.equal(legalUrl('https://addin.workdeck.ai', 'cookies'), '')
})
