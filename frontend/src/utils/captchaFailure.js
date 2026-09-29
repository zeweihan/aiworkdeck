// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 人机验证「组件装不出来」的错误契约与提示文案判定（dev-board#1056）。零依赖，node 可直接导入
 * （`tests/captcha/captcha-failure.test.mjs`）。
 *
 * ## 为什么要把它和「未启用」分开
 * 官网 captcha-config 说 provider 非空 = 官网此刻**一定**校验 token。这时控件装不出来
 * （律所网络拦了 o.alicdn.com、托管页打不开）若按「未启用」处理，就会不带 token 盲发码，
 * 官网必回 403，用户只拿到一句「请先完成安全验证」，却从头到尾没见过任何验证控件。
 * 所以装配失败要以可辨识的错误抛出，解锁页据此重试一次、仍失败就说清楚要放行哪些地址。
 *
 * ## 错误形状
 * `Error`，`code === CAPTCHA_LOAD_FAILED`，另带 `provider`（aliyun|turnstile）与
 * `reason`（下面 REASONS 之一）。控件装出来之后才发现的失败（阿里云 onError、托管页
 * 一直不 ready）不抛，由控件的 `loadError()` 回 `{ provider, reason }`，形状同上两个字段。
 */

export const CAPTCHA_LOAD_FAILED = 'captcha_load_failed'

/** 阿里云验证码脚本。发不出去的地址要写进提示里，所以放在这里与文案同源。 */
export const ALIYUN_SCRIPT_URL = 'https://o.alicdn.com/captcha-frontend/aliyunCaptcha/AliyunCaptcha.js'
/** Turnstile 挑战帧的域名（托管页里的控件从这里加载）。 */
export const TURNSTILE_HOST = 'challenges.cloudflare.com'

export const REASONS = Object.freeze({
  SCRIPT_ERROR: 'script-error', // 脚本请求失败（被拦/断网）
  SCRIPT_TIMEOUT: 'script-timeout', // 脚本迟迟加载不完（丢包型拦截）
  INIT_MISSING: 'init-missing', // 脚本到了但没有 initAliyunCaptcha（被替换/被截断）
  INIT_ERROR: 'init-error', // 阿里云控件自己报初始化失败
  EMBED_NOT_READY: 'embed-not-ready', // 托管页一直没发 ready
  SITE_URL: 'site-url', // 官网地址解析不了，托管页无从挂起
  HOLDER_MISSING: 'holder-missing', // 页面上没有挂点
  UNEXPECTED: 'unexpected',
})

export function hostOf(url) {
  try {
    return new URL(String(url)).host
  } catch (e) {
    return ''
  }
}

/** 构造装配失败错误。 */
export function captchaLoadError(provider, reason, cause) {
  const e = new Error(`captcha load failed: ${provider || '?'} / ${reason || REASONS.UNEXPECTED}`)
  e.code = CAPTCHA_LOAD_FAILED
  e.provider = provider || ''
  e.reason = reason || REASONS.UNEXPECTED
  if (cause) e.cause = cause
  return e
}

export function isCaptchaLoadError(e) {
  return !!(e && e.code === CAPTCHA_LOAD_FAILED)
}

/**
 * 把任意装配异常归一成 `{ provider, reason }`：已是装配失败错误就取它的字段，
 * 其余（意料之外的异常）按 `configProvider` 记为 unexpected——官网已经说了要校验，
 * 装不出来就是装不出来，不许退回「未启用」。
 */
export function toCaptchaFailure(e, configProvider) {
  if (isCaptchaLoadError(e)) return { provider: e.provider || configProvider || '', reason: e.reason }
  return { provider: configProvider || '', reason: REASONS.UNEXPECTED }
}

/**
 * 装配失败时给用户看的提示：回 `{ key, params }`，由调用方 `$t(key, params)`。
 * 提示里写明要放行/能访问的地址——用户多半要拿这句话去找单位 IT。
 */
export function captchaFailureNotice({ provider, siteBaseUrl } = {}) {
  if (provider === 'aliyun') {
    return { key: 'onboarding.unlock.captchaLoadFailedAliyun', params: { host: hostOf(ALIYUN_SCRIPT_URL) } }
  }
  if (provider === 'turnstile') {
    const siteHost = hostOf(siteBaseUrl)
    if (siteHost) {
      return {
        key: 'onboarding.unlock.captchaLoadFailedTurnstile',
        params: { siteHost, cfHost: TURNSTILE_HOST },
      }
    }
  }
  return { key: 'onboarding.unlock.captchaLoadFailed', params: {} }
}
