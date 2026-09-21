// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 登录页该给哪个 tab（dev-board#766）。
 *
 * ## 这条逻辑解决的是什么
 * 官网两个站各只认一种账号本体：大陆站是手机号、国际站是邮箱
 * （官网 `lib/phone-policy.ts` / `lib/mail-policy.ts`：「两个都为真的站不存在，
 * 两个都为假也不存在」）。插件端此前判不了站点，登录页恒定停在「手机号」那个 tab，
 * 国际站用户要**填完号、点获取验证码、等完一次往返**，才被官网的
 * `sms_not_supported_on_site` 告知「当前站点不支持手机号方式，请改用邮箱」。
 * 现在云后端在 `/api/auth/account-login/captcha-config` 里带回 `accountIdentity`
 * （按 `ai.account.base-url` 指向的站点派生），窗格在渲染登录页那一刻就知道该给谁。
 *
 * ## 三条口径
 * - **认不出一律当未知**（空串），未知时两个 tab 都给——插件连的是用户自填的服务器地址，
 *   很可能是没有这个字段的旧云后端。当成某一种会把另一种账号的用户直接挡在门外，
 *   而这比「多给一个用不了的 tab」严重得多。这与 `visionCapability` 的「三态，
 *   undefined 不等于 false」是同一条纪律。
 * - **口令那条路不随站点隐藏**：站点只决定验证码走手机号还是邮箱，而口令是两站存量账号
 *   共用的第三条路。把它挂在邮箱 tab 里（改造前的形态）的话，大陆站隐掉邮箱 tab
 *   就顺手把存量口令用户的入口也埋了。
 * - **当前选中的 tab 一旦不可用就纠正**，不看「用户是不是手动点过」：那个 tab 马上就要
 *   从界面上消失，留着它等于让用户对着一个没有退路的表单发呆。
 */

export const IDENTITY_PHONE = 'phone'
export const IDENTITY_EMAIL = 'email'

/** 登录表单的三种形态：两条验证码路 + 一条口令路 */
export const MODE_PHONE = 'phone'
export const MODE_EMAIL = 'email'
export const MODE_PASSWORD = 'password'

/**
 * 从 captcha-config 的载荷里取站点账号本体。
 * @returns {'phone'|'email'|''} 空串 = 未知（旧后端、配置取不到、值不认识）
 */
export function accountIdentityOf(config) {
  const raw = config && typeof config.accountIdentity === 'string'
    ? config.accountIdentity.trim().toLowerCase()
    : ''
  return raw === IDENTITY_PHONE || raw === IDENTITY_EMAIL ? raw : ''
}

/**
 * 可见的验证码 tab（按界面从左到右的顺序）。
 * 未知时两个都给——与改造前的界面逐字一致。
 */
export function visibleCodeTabs(identity) {
  if (identity === IDENTITY_EMAIL) return [MODE_EMAIL]
  if (identity === IDENTITY_PHONE) return [MODE_PHONE]
  return [MODE_PHONE, MODE_EMAIL]
}

/** 默认选中哪个：国际站落邮箱，其余（含未知）落手机号 */
export function defaultMode(identity) {
  return identity === IDENTITY_EMAIL ? MODE_EMAIL : MODE_PHONE
}

/** 这个形态在该站点下还成立吗（口令永远成立，见文件头第二条口径） */
export function isModeAvailable(mode, identity) {
  if (mode === MODE_PASSWORD) return true
  return visibleCodeTabs(identity).includes(mode)
}
