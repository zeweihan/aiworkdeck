// 审计（dev-board#74）确认的缺陷：TOTP 绑定慢响应覆盖新密钥。
//
// 病灶：PersonalSettingsPanel.vue 的 toggleTotpPanel 无 in-flight 闸、无请求代次，
// await 回来直接赋 totpSecret / totpQrDataUrl。后端每次 startSetup 都会新生成密钥
// 并落库（后来者覆盖）。反复点「绑定」/取消/「绑定」时，先发的 A 请求若后回，
// 界面显示 A 的密钥而服务端存的是 B 的密钥，用户扫到作废密钥，验证码恒报错。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { shouldAcceptResponse } from '../../src/utils/requestGeneration.js'

test('代次匹配（还是最后一次发出的请求）：接受响应', () => {
  assert.equal(shouldAcceptResponse(1, 1), true)
  assert.equal(shouldAcceptResponse(5, 5), true)
})

test('代次不匹配（发出之后又有更新的请求发出了）：丢弃响应', () => {
  assert.equal(shouldAcceptResponse(1, 2), false, '旧代次的响应必须被丢弃，不能覆盖新请求的结果')
})

test('模拟连续三次点击：只有最后一次的响应会被接受', () => {
  let currentSeq = 0
  const seqA = ++currentSeq // 第一次点击「绑定」
  const seqB = ++currentSeq // 用户很快又点了一次（比如先点了取消又点绑定）
  const seqC = ++currentSeq // 第三次

  // 无论三个请求以什么顺序回来，只有代次等于 currentSeq（此刻的 C）的那个会被接受
  assert.equal(shouldAcceptResponse(seqA, currentSeq), false)
  assert.equal(shouldAcceptResponse(seqB, currentSeq), false)
  assert.equal(shouldAcceptResponse(seqC, currentSeq), true)
})
