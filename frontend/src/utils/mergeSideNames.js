// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 一次合并里两侧各该怎么称呼、以及喂给引擎的那两个署名（= 修订的分桶键）。
//
// 病灶（桌面端 v0.44.1 真机实测 A3）：一个律师用「稿」管对方的回稿——主线是他写的、
// 稿也是他签的——于是两侧的 authorName/self 完全相同，合并比对稿的抬头、裁决总览
// 那一行、右栏三选一按钮全都写着「你」：「你改了 11 处 · 你改了 0 处」、
// 「用你的 / 用你的 / 自己改」。而 11/0 也不是巧合：引擎按修订作者分桶，两侧署名
// 一样就把两边的修订全算进一桶。一个人拿「稿」管对方回稿是常见用法，不是边角。
//
// 所以分桶与称呼都不能只认「人」：两侧的人分不开时，一律退到**线**上
// （主线 / 稿《对方第三版回稿》 / 案件库那边 …）。线的称呼直接借
// historyMerges.mergeSideLabels——提交历史里那几句用的就是它，两处说法必须一致。
//
// 纯函数（只 import 另一个纯函数），node --test 直接跑：
//   tests/version-merge/mergeSideNames.test.mjs

import { mergeSideLabels } from './historyMerges.js'

/** 一侧提交的署名（后端给的展示名，绝不会是 username）。 */
function rawName(side) {
  const n = side && typeof side.authorName === 'string' ? side.authorName.trim() : ''
  return n
}

/**
 * @param {Function} t        $t
 * @param {Object}   [sides]  /status 冲突对象里的 sides：{main: {authorName, self}, other: {…}}
 * @param {Object}   [opts]   {mode: 'adopt'|'cloud'|'session-end', draftName}
 * @returns {{main: String, other: String, mainKey: String, otherKey: String, byPerson: Boolean}}
 *   main/other   = 界面上的称呼（本人说「你」）
 *   mainKey/otherKey = 喂给引擎的署名，也就是修订归哪一侧的判据；**保证互不相同**
 *   byPerson     = 两侧确实是两个分得开的人（此时称呼与署名都还是原来那一套，一字不变）
 */
export function resolveMergeSideNames(t, sides = {}, opts = {}) {
  const s = sides || {}
  const rawMain = rawName(s.main)
  const rawOther = rawName(s.other)
  if (rawMain && rawOther && rawMain !== rawOther) {
    return {
      main: s.main && s.main.self ? t('version.actorYou') : rawMain,
      other: s.other && s.other.self ? t('version.actorYou') : rawOther,
      mainKey: rawMain,
      otherKey: rawOther,
      byPerson: true,
    }
  }
  // 人分不开（同一个账号两边都是自己 / 两个同事同名 / 有一侧压根没署名）：按线分。
  const branch = mergeSideLabels(t, opts.mode, {})
  const mode = opts.mode || 'adopt'
  const other = (mode === 'adopt' && opts.draftName)
    ? t('version.mergeSideDraftNamed', { name: opts.draftName })
    : branch.other
  return { main: branch.main, other, mainKey: branch.main, otherKey: other, byPerson: false }
}
