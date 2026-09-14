// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「同事交了新稿」这句话的唯一出处（dev-board#623）——**纯函数，不许 import**。
 *
 * 病灶：判据是纯 ref 比较（origin/master 比本机主线新），不读作者。同一个官网账号在
 * 两台电脑上桥接同一个案件库，落到案件库的是**同一行用户**，于是律师自己在另一台
 * 电脑上交的稿，回到这台电脑被报成「同事交了新稿」——他会以为有人动了他的案卷。
 *
 * 四处同源消费：顶栏协作 chip、底部状态条、版本面板的 CloudSyncBar、协作抽屉。
 *
 * 降级：后端没回 remoteAheadCount（老服务端）就落回调用方给的那句老文案，
 * 不编一个「· 0 版」出来。作者名单后端最多给 3 个，「等 N 人」里的 N 因此要用
 * remoteAheadAuthorCount（去重后的作者总数）来算，拿名单长度算的话四个人以上
 * 永远说成 3 人；老服务端不回这个字段时才退回名单长度。
 */

/**
 * @param {Function} t          $t，签名 (key, params?) => string
 * @param {Object}   status     cloudStatus：{remoteAhead, remoteAheadCount, remoteAheadAuthors,
 *                              remoteAheadAuthorCount, remoteAheadBySelf}
 * @param {Object}   [opts]     { fallbackKey } 算不出作者时用的老文案键
 * @returns {string}
 */
export function remoteAheadText(t, status, opts = {}) {
  const fallbackKey = (opts && opts.fallbackKey) || 'version.colleagueSubmittedNew'
  const fallback = () => t(fallbackKey)
  const s = status || {}
  const count = Number(s.remoteAheadCount) || 0
  if (!count) return fallback()
  if (s.remoteAheadBySelf) return t('version.remoteAheadSelf', { count })
  const authors = (Array.isArray(s.remoteAheadAuthors) ? s.remoteAheadAuthors : [])
    .map((a) => (a == null ? '' : String(a).trim()))
    .filter(Boolean)
  if (!authors.length) return fallback()
  // 名单最多 3 个名字，人数另有其数：缺席（老服务端）才退回名单长度
  const people = Number(s.remoteAheadAuthorCount) || authors.length
  if (people <= 1) return t('version.remoteAheadOne', { name: authors[0], count })
  return t('version.remoteAheadMany', { name: authors[0], people, count })
}
