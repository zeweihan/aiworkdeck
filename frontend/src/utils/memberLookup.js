// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「把同事加进案卷」的纯逻辑：边输入边查人时该不该发请求、发什么、以及查不到时
 * 给出的那条邀请链接长什么样。
 *
 * 单独成文件是为了能被 node --test 直接跑（tests/member-invite/）——弹窗里做不到，
 * 而这几条判定恰恰是最容易悄悄错的：多发一次请求会撞上后端按项目管理员算的查人限频
 * （AuthAbuseGuard.checkMemberLookupRate，与加人共用一个计数），少发一次则表现成
 * 「输完了下面什么都不显示」，正是这次要修的那个病。
 *
 * 这里**不许 import 任何东西**：一旦引到 '@/services/api.js' 之类的别名，node 解析
 * 不了别名，这套测试就得整体退化成源码字符串断言。
 */

/** 后端 lookup 与前端轨道判定共用的三种轨道。 */
export const TRACK = {
  /** 自建多用户服务器：同事就在本机这台服务器的用户表里，走 /api/projects/{id}/members。 */
  LOCAL: 'LOCAL',
  /** 桌面端 local-mode 且案卷已放进团队案件库：同事在案件库那边，走 /api/cloud/...。 */
  CLOUD: 'CLOUD',
  /** 桌面端 local-mode 且案卷还没放进案件库：本机用户表里只有本机账号，加谁都没有意义。 */
  NEEDS_LIBRARY: 'NEEDS_LIBRARY',
}

/**
 * 轨道判定。
 *
 * localMode 为假 = 自建多用户服务器，这时案件库那条轨反而不适用（那是给单机桌面端
 * 跨机器找人用的），本机用户表就是同事名单本身。
 */
export function resolveTrack({ localMode, linked } = {}) {
  if (!localMode) return TRACK.LOCAL
  return linked ? TRACK.CLOUD : TRACK.NEEDS_LIBRARY
}

/**
 * 手机号归一：去掉 +86 / 86 国家码与中间的空格、连字符。
 * 律师从通讯录里粘过来的号码常常是 "+86 138 0000 0000" 这种形态，原样发上去查不到人。
 * 只对不含 @ 的值做——邮箱本地部分里的连字符是有意义的（my-name@x.com）。
 */
export function normalizePhone(value) {
  const raw = String(value == null ? '' : value).trim()
  if (raw.includes('@')) return raw
  return raw.replace(/^\+?86[\s-]*/, '').replace(/[\s-]/g, '')
}

/** 看着像手机号（归一之后是 1 开头的 11 位）。 */
export function isPhoneLike(value) {
  return /^1\d{10}$/.test(normalizePhone(value))
}

/** 看着像邮箱：含 @，@ 前有东西，@ 后还有一个点且点不紧贴 @。 */
export function isEmailLike(value) {
  const v = String(value == null ? '' : value).trim()
  if (/\s/.test(v)) return false
  const at = v.indexOf('@')
  if (at <= 0) return false
  const dot = v.indexOf('.', at)
  return dot > at + 1 && dot < v.length - 1
}

/**
 * 这一次输入值得不值得发一次查人请求。
 *
 * 三条正例（任一成立就查）：像邮箱、像手机号、其余长度 ≥ 2 的字符串（账号名兜底，
 * 本机轨与案件库轨的后端都拿用户名兜底查一次）。
 *
 * 两条额外的否例是任务书之外自己加的，理由都在于那道查人限频：
 * ① 含 @ 但还不成形的（"张三@" / "a@b"）——那是邮箱敲到一半，不是账号名；
 * ② 归一后是纯数字但不是 11 位手机号的——那是手机号敲到一半，
 *    不拦的话敲一个号码就会连发九次请求，第十次开始被限频拦住，
 *    表现成「输完了反而查不出人」。
 */
export function isWorthLooking(value) {
  const raw = String(value == null ? '' : value).trim()
  if (!raw) return false
  if (raw.includes('@')) return isEmailLike(raw)
  const normalized = normalizePhone(raw)
  if (/^\d+$/.test(normalized)) return isPhoneLike(raw)
  return raw.length >= 2
}

/** 真正发给后端的 identifier：手机号发归一后的，其余原样（去首尾空白）。 */
export function lookupIdentifier(value) {
  const raw = String(value == null ? '' : value).trim()
  return isPhoneLike(raw) ? normalizePhone(raw) : raw
}

/**
 * 官网语言段。官网只有 zh 与 en 两个语言段，其余一律回 zh——
 * 拼一个官网上不存在的段等于把同事送去 404。
 */
export function siteLangSegment(locale) {
  return String(locale == null ? '' : locale).toLowerCase().startsWith('en') ? 'en' : 'zh'
}

/**
 * 邀请链接：官网的「开始使用」落地页。同事从这里下载并用手机号登录一次之后，
 * 律师回到弹窗再查一次就能把他加进来。
 */
export function inviteLinkFor(baseUrl, locale) {
  const base = String(baseUrl == null ? '' : baseUrl).trim().replace(/\/+$/, '')
  return `${base}/${siteLangSegment(locale)}/start`
}
