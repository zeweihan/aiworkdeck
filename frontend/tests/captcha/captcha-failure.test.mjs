// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 人机验证「组件装不出来」与「未启用」分开处理（dev-board#1056）。
// 纯逻辑在 src/utils/captchaFailure.js 与 captchaEmbedCore.js；接线处用源码断言钉住。
// 跑法：npm run test:captcha

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  ALIYUN_SCRIPT_URL,
  CAPTCHA_LOAD_FAILED,
  REASONS,
  TURNSTILE_HOST,
  captchaFailureNotice,
  captchaLoadError,
  isCaptchaLoadError,
  toCaptchaFailure,
} from '../../src/utils/captchaFailure.js'
import { MESSAGE_SOURCE, createEmbedController } from '../../src/utils/captchaEmbedCore.js'
import zhOnboarding from '../../src/locales/zh-CN/onboarding.js'
import enOnboarding from '../../src/locales/en-US/onboarding.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8')

test('错误形状：code/provider/reason 三个字段，可辨识', () => {
  const e = captchaLoadError('aliyun', REASONS.SCRIPT_ERROR)
  assert.ok(e instanceof Error)
  assert.equal(e.code, CAPTCHA_LOAD_FAILED)
  assert.equal(e.provider, 'aliyun')
  assert.equal(e.reason, 'script-error')
  assert.equal(isCaptchaLoadError(e), true)
  assert.equal(isCaptchaLoadError(new Error('x')), false)
  assert.equal(isCaptchaLoadError(null), false)
  assert.equal(captchaLoadError('turnstile').reason, REASONS.UNEXPECTED)
})

test('归一：装配失败错误取自身字段；意外异常按配置里的 provider 记 unexpected，不退回「未启用」', () => {
  assert.deepEqual(
    toCaptchaFailure(captchaLoadError('aliyun', REASONS.INIT_MISSING), 'aliyun'),
    { provider: 'aliyun', reason: 'init-missing' },
  )
  assert.deepEqual(toCaptchaFailure(new TypeError('boom'), 'turnstile'), { provider: 'turnstile', reason: 'unexpected' })
  assert.deepEqual(toCaptchaFailure(undefined, 'aliyun'), { provider: 'aliyun', reason: 'unexpected' })
})

test('提示：阿里云点名 o.alicdn.com；Turnstile 点名官网域名与 challenges.cloudflare.com', () => {
  assert.deepEqual(captchaFailureNotice({ provider: 'aliyun' }), {
    key: 'onboarding.unlock.captchaLoadFailedAliyun',
    params: { host: 'o.alicdn.com' },
  })
  assert.deepEqual(captchaFailureNotice({ provider: 'turnstile', siteBaseUrl: 'https://www.workdeck.ai/' }), {
    key: 'onboarding.unlock.captchaLoadFailedTurnstile',
    params: { siteHost: 'www.workdeck.ai', cfHost: 'challenges.cloudflare.com' },
  })
  assert.equal(TURNSTILE_HOST, 'challenges.cloudflare.com')
  // 官网地址解析不了、或 provider 未知：通用提示，不编造地址
  assert.deepEqual(captchaFailureNotice({ provider: 'turnstile', siteBaseUrl: 'nope' }), {
    key: 'onboarding.unlock.captchaLoadFailed', params: {},
  })
  assert.equal(captchaFailureNotice({ provider: 'other' }).key, 'onboarding.unlock.captchaLoadFailed')
  assert.equal(captchaFailureNotice().key, 'onboarding.unlock.captchaLoadFailed')
})

test('文案：三个键中英都有、占位符对齐、说清是「组件加载失败」且不含实现词与 emoji', () => {
  const keys = ['captchaLoadFailed', 'captchaLoadFailedAliyun', 'captchaLoadFailedTurnstile']
  const ph = (s) => (s.match(/\{\w+\}/g) || []).sort().join(',')
  for (const k of keys) {
    const zh = zhOnboarding.unlock[k]
    const en = enOnboarding.unlock[k]
    assert.equal(typeof zh, 'string', `zh 缺 ${k}`)
    assert.equal(typeof en, 'string', `en 缺 ${k}`)
    assert.equal(ph(zh), ph(en), `${k} 占位符不一致`)
    assert.match(zh, /安全验证组件加载失败/)
    assert.match(en, /security check could not load/)
    for (const s of [zh, en]) {
      assert.doesNotMatch(s, /Electron|webview|iframe/i)
      assert.doesNotMatch(s, /\p{Extended_Pictographic}/u)
    }
    assert.doesNotMatch(en, /[，。：「」]/)
    // 业务错误文案不许像掉线（与前端 4010 判定无关，但守住同一条口径）
    assert.doesNotMatch(zh, /登录已失效|未授权/)
  }
  assert.match(zhOnboarding.unlock.captchaLoadFailedAliyun, /\{host\}/)
  assert.match(zhOnboarding.unlock.captchaLoadFailedTurnstile, /\{siteHost\}.*\{cfHost\}/)
})

function fakeTimers() {
  let now = 0
  let seq = 0
  const timers = new Map()
  return {
    setTimer(fn, ms) { const id = ++seq; timers.set(id, { at: now + ms, fn }); return id },
    clearTimer(id) { timers.delete(id) },
    advance(ms) {
      now += ms
      for (const [id, t] of [...timers]) {
        if (t.at <= now) { timers.delete(id); t.fn() }
      }
    },
  }
}

test('托管页一直不 ready：回空串的同时置 loadFailed；ready 迟到则清掉', async () => {
  const t = fakeTimers()
  const c = createEmbedController({ post: () => {}, setTimer: t.setTimer, clearTimer: t.clearTimer, readyTimeoutMs: 15000 })
  assert.equal(c.state.loadFailed, false)
  const p = c.getToken()
  t.advance(15000)
  assert.equal(await p, '')
  assert.equal(c.state.loadFailed, true, '没 ready 是「组件没加载出来」，不是「没通过」')
  c.handle({ source: MESSAGE_SOURCE, type: 'ready', provider: 'turnstile' })
  assert.equal(c.state.loadFailed, false)
})

test('ready 过的托管页请求超时：只是没通过，不算加载失败', async () => {
  const t = fakeTimers()
  const c = createEmbedController({ post: () => {}, setTimer: t.setTimer, clearTimer: t.clearTimer })
  c.handle({ source: MESSAGE_SOURCE, type: 'ready' })
  const p = c.getToken()
  t.advance(8000)
  assert.equal(await p, '')
  assert.equal(c.state.loadFailed, false)
})

test('接线：captcha.js 装配失败抛可辨识错误，不再回 null 让调用方盲发', () => {
  const src = read('utils/captcha.js')
  assert.match(src, /aliyun: ALIYUN_SCRIPT_URL/, '脚本地址与提示文案同源')
  assert.equal(new URL(ALIYUN_SCRIPT_URL).host, 'o.alicdn.com')
  assert.match(src, /reject\(captchaLoadError\(provider, reason\)\)/)
  assert.match(src, /loading\.delete\(src\)/, '失败要摘缓存，否则重试永远拿到同一枚 reject')
  assert.match(src, /REASONS\.SCRIPT_TIMEOUT/)
  assert.match(src, /throw captchaLoadError\('aliyun', REASONS\.INIT_MISSING\)/)
  assert.match(src, /loading\.delete\(SCRIPTS\.aliyun\)\s*throw captchaLoadError\('aliyun', REASONS\.INIT_MISSING\)/)
  assert.doesNotMatch(src, /if \(!window\.initAliyunCaptcha\) return null/)
  assert.match(src, /initFailed = true/)
  assert.match(src, /reason: REASONS\.INIT_ERROR/)
  assert.match(src, /controller\.state\.loadFailed/)
  assert.match(src, /reason: REASONS\.EMBED_NOT_READY/)
  // 未启用仍回 null（官网此刻不校验）
  assert.match(src, /if \(!config \|\| !config\.provider\) return null/)
})

test('接线：解锁页区分「未启用」与「装不出来」，点按钮先重试一次装配，仍失败才报组件加载失败', () => {
  const src = read('pages/unlock/unlock.vue')
  assert.match(src, /captchaFailure: null/)
  // 配置读不到仍按未启用处理（单独的 try，return 前不记失败）
  assert.match(src, /config = await getAccountCaptchaConfig\(\)\s*\} catch \(e\) \{[\s\S]*?按未启用处理[\s\S]*?return\s*\}/)
  assert.match(src, /this\.captchaFailure = toCaptchaFailure\(e, config && config\.provider\)/)
  const acquire = src.slice(src.indexOf('async acquireCaptchaToken()'), src.indexOf('captchaFailureMessage(failure)'))
  assert.ok(acquire.length > 0)
  assert.match(acquire, /if \(this\.captchaSetup\) await this\.captchaSetup/)
  assert.match(acquire, /if \(this\.currentCaptchaFailure\(\)\) await this\.setupCaptchaWidget\(\)/)
  assert.ok(
    acquire.indexOf('setupCaptchaWidget()') < acquire.indexOf('widget.getToken()'),
    '重试装配必须在取 token 之前',
  )
  const send = src.slice(src.indexOf('async handleSendCode()'), src.indexOf('stopCooldown()'))
  assert.match(send, /await this\.acquireCaptchaToken\(\)/)
  assert.match(send, /captchaFailureMessage\(got\.failure\)/)
  assert.ok(
    send.indexOf('got.failure') < send.indexOf('sendAccountLoginCode('),
    '装不出来时必须在发码之前返回',
  )
  assert.match(src, /captchaFailureNotice\(\{ provider: failure && failure\.provider, siteBaseUrl: siteBaseUrl\(\) \}\)/)
})
