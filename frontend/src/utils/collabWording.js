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
 * 不编一个「· 0 版」出来。作者名单后端最多给 3 个，所以「等 N 人」里的 N
 * 在超过 3 人时会说少——说少好过说错，且这条路径上没有更准的数可用。
 */

/**
 * @param {Function} t          $t，签名 (key, params?) => string
 * @param {Object}   status     cloudStatus：{remoteAhead, remoteAheadCount, remoteAheadAuthors, remoteAheadBySelf}
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
  if (authors.length === 1) return t('version.remoteAheadOne', { name: authors[0], count })
  if (authors.length > 1) return t('version.remoteAheadMany', { name: authors[0], people: authors.length, count })
  return fallback()
}
