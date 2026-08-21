/**
 * api.js 里两条匿名登录端点的人机验证回归用例。
 *   node --test office-addin/taskpane/lib/api.test.js
 *
 * 背景（dev-board#88）：官网给 `/api/auth/sms-login/send-code` 加了人机验证之后，
 * `verifyCaptcha` 排在发短信之前，不带 token 一律 403。桌面端与移动端都补了控件，
 * **插件端整条链却从头到尾没有这个参数**——`postAccountLoginSendCode` 只发 `{phone}`，
 * 任务窗格里也没有任何控件。用户点「获取验证码」永远只拿到
 * 「请先完成安全验证后再试」，滑块一次都没出现过，看起来像「插件形态不支持验证码」。
 *
 * 下面第一条用例就是那个病灶的还原：把 token 从请求体里拿掉就会转红。
 */
import test from 'node:test'
import assert from 'node:assert/strict'

import { getAccountLoginCaptchaConfig, postAccountLoginSendCode } from './api.js'

/** 替换 globalThis.fetch，返回 {calls, restore} */
function stubFetch(handler) {
  const original = globalThis.fetch
  const calls = []
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), options })
    return handler(String(url), options)
  }
  return {
    calls,
    restore: () => {
      if (original === undefined) delete globalThis.fetch
      else globalThis.fetch = original
    },
  }
}

function jsonReply(body, ok = true, status = 200) {
  return { ok, status, json: async () => body }
}

test('发验证码把人机验证 token 带进请求体——不带的话官网恒 403', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { sent: true } }))
  try {
    await postAccountLoginSendCode({ serverUrl: 'https://addin.example.com' }, '13800138000', 'verify-param')
    assert.equal(f.calls.length, 1)
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.phone, '13800138000')
    assert.equal(body.captchaToken, 'verify-param')
  } finally {
    f.restore()
  }
})

test('手机号与 token 都去掉首尾空白后再出站', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { sent: true } }))
  try {
    await postAccountLoginSendCode({ serverUrl: 'https://addin.example.com' }, '  13800138000 ', '  tok  ')
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.phone, '13800138000')
    assert.equal(body.captchaToken, 'tok')
  } finally {
    f.restore()
  }
})

test('官网未启用人机验证时不传 token，请求体里仍有该字段（空串）', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { sent: true } }))
  try {
    await postAccountLoginSendCode({ serverUrl: 'https://addin.example.com' }, '13800138000')
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.captchaToken, '')
  } finally {
    f.restore()
  }
})

test('取控件参数走匿名端点，不带 X-Session-Id（云后端登录前根本没有会话）', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { provider: 'aliyun', sceneId: 's1', prefix: 'p1' } }))
  try {
    const config = await getAccountLoginCaptchaConfig({ serverUrl: 'https://addin.example.com' })
    assert.equal(config.provider, 'aliyun')
    assert.equal(config.sceneId, 's1')
    assert.ok(f.calls[0].url.endsWith('/api/auth/account-login/captcha-config'))
    const headers = f.calls[0].options.headers || {}
    assert.equal(headers['X-Session-Id'], undefined)
  } finally {
    f.restore()
  }
})

test('老版本云后端没有该端点时静默降级成「未启用」，不把登录卡死', async () => {
  const f = stubFetch(() => jsonReply({ error: 'not found' }, false, 404))
  try {
    const config = await getAccountLoginCaptchaConfig({ serverUrl: 'https://addin.example.com' })
    assert.equal(config.provider, null)
  } finally {
    f.restore()
  }
})

test('网络直接抛错也降级成「未启用」，不外溢异常', async () => {
  const f = stubFetch(() => { throw new Error('offline') })
  try {
    const config = await getAccountLoginCaptchaConfig({ serverUrl: 'https://addin.example.com' })
    assert.equal(config.provider, null)
  } finally {
    f.restore()
  }
})

test('后端地址为空时不发请求，直接当未启用', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { provider: 'aliyun' } }))
  try {
    const config = await getAccountLoginCaptchaConfig({ serverUrl: '' })
    assert.equal(config.provider, null)
    assert.equal(f.calls.length, 0)
  } finally {
    f.restore()
  }
})
