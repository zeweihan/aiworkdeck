// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
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

import {
  getAccountLoginCaptchaConfig,
  postAccountLogin,
  postAccountLoginSendCode,
  postAccountLoginSendEmailCode,
  fetchMobileDevices,
} from './api.js'

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

// ==================== 邮箱验证码（国际站主路径，dev-board#695） ====================
//
// 国际站的邮箱验证码注册建出来的账号 passwordHash 是空串（压根没有口令），口令注册也已关掉。
// 缺了这条凭据形状，那批用户在官网注册完就再也登不进插件——邮箱+口令那条路对他们无效。

test('邮箱发码：请求体带 email 与人机验证 token，且不混进 phone 字段', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { sent: true } }))
  try {
    await postAccountLoginSendEmailCode(
      { serverUrl: 'https://addin.example.com' }, '  hi@example.com ', ' tok-mail ')
    assert.equal(f.calls.length, 1)
    assert.ok(f.calls[0].url.endsWith('/api/auth/account-login/send-code'))
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.email, 'hi@example.com')
    assert.equal(body.captchaToken, 'tok-mail')
    // 后端 phone 优先：混着送两个字段会让「用户填了哪个」的判定变得含糊
    assert.equal(body.phone, undefined)
  } finally {
    f.restore()
  }
})

test('邮箱发码：官网未启用人机验证时 token 字段仍在（空串）', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { sent: true } }))
  try {
    await postAccountLoginSendEmailCode({ serverUrl: 'https://addin.example.com' }, 'hi@example.com')
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.captchaToken, '')
  } finally {
    f.restore()
  }
})

test('邮箱验证码登录：{email, code} 原样送出，换回 awdt_ 令牌', async () => {
  const f = stubFetch(() => jsonReply({ code: 0, data: { token: 'awdt_mail', userId: 9 } }))
  try {
    const token = await postAccountLogin(
      { serverUrl: 'https://addin.example.com' }, { email: 'hi@example.com', code: '123456' })
    assert.equal(token, 'awdt_mail')
    const body = JSON.parse(f.calls[0].options.body)
    assert.equal(body.email, 'hi@example.com')
    assert.equal(body.code, '123456')
    assert.equal(body.password, undefined)
  } finally {
    f.restore()
  }
})

test('邮箱发码失败：服务端文案原样透传（用户据此才知道该改用手机号）', async () => {
  const f = stubFetch(() => jsonReply(
    { code: 1, message: '当前站点不支持邮箱方式，请改用手机号' }, true, 200))
  try {
    await assert.rejects(
      () => postAccountLoginSendEmailCode({ serverUrl: 'https://addin.example.com' }, 'hi@example.com', 'tok'),
      /当前站点不支持邮箱方式/)
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

// ==================== fetchMobileDevices（dev-board#250）====================

test('fetchMobileDevices：正常返回数组时原样透传，带上会话头', async () => {
  const devices = [{ deviceId: 'dev-a', deviceName: 'Mac', online: true, projects: [{ key: '42', name: '某项目' }] }]
  const f = stubFetch(() => jsonReply(devices))
  try {
    const result = await fetchMobileDevices({ serverUrl: 'https://addin.example.com', token: 'awdt_xxx' })
    assert.deepEqual(result, devices)
    assert.ok(f.calls[0].url.endsWith('/api/mobile/devices'))
    assert.equal(f.calls[0].options.headers['X-Session-Id'], 'awdt_xxx')
  } finally {
    f.restore()
  }
})

test('fetchMobileDevices：旧后端 404 静默降级为 null', async () => {
  const f = stubFetch(() => jsonReply({ error: 'not found' }, false, 404))
  try {
    const result = await fetchMobileDevices({ serverUrl: 'https://addin.example.com', token: 'awdt_xxx' })
    assert.equal(result, null)
  } finally {
    f.restore()
  }
})

test('fetchMobileDevices：响应非数组或网络异常也一律降级为 null', async () => {
  const f = stubFetch(() => jsonReply({ code: 0 }))
  try {
    assert.equal(await fetchMobileDevices({ serverUrl: 'https://addin.example.com', token: 'awdt_xxx' }), null)
  } finally {
    f.restore()
  }

  const f2 = stubFetch(() => { throw new Error('offline') })
  try {
    assert.equal(await fetchMobileDevices({ serverUrl: 'https://addin.example.com', token: 'awdt_xxx' }), null)
  } finally {
    f2.restore()
  }
})

test('fetchMobileDevices：没有 token 或地址时不发请求，直接返回 null', async () => {
  const f = stubFetch(() => jsonReply([]))
  try {
    assert.equal(await fetchMobileDevices({ serverUrl: '', token: 'awdt_xxx' }), null)
    assert.equal(await fetchMobileDevices({ serverUrl: 'https://addin.example.com', token: '' }), null)
    assert.equal(f.calls.length, 0)
  } finally {
    f.restore()
  }
})
