/**
 * 官网站点映射的回归用例（dev-board#198）。
 *   node --test office-addin/taskpane/lib/site.test.js
 *
 * 钉住白名单语义：只有两个官方 addin 后端映射到充值页；
 * 私有部署与桌面本机绝不能推导出一个不存在的充值链接。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { rechargeUrl } from './site.js'

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
