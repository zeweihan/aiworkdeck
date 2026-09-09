// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「把同事加进案卷」的纯逻辑（src/utils/memberLookup.js）。
//
// 这三组判定错了各有各的病：值得查的判定放太宽会把后端那道查人限频
// （AuthAbuseGuard.checkMemberLookupRate，与加人共用一个计数）在律师敲完号码之前
// 就烧光；轨道判定错一态，桌面端就会在只有本机账号的用户表里查同事，永远查不到；
// 邀请链接拼错语言段就是把同事送去一个 404。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import {
  TRACK, resolveTrack,
  normalizePhone, isPhoneLike, isEmailLike, isWorthLooking, lookupIdentifier,
  siteLangSegment, inviteLinkFor,
} from '../../src/utils/memberLookup.js'

// ==================== 值得查的判定 ====================

test('邮箱成形了才查', () => {
  assert.equal(isWorthLooking('zhang@example.com'), true)
  assert.equal(isWorthLooking('  zhang@example.com  '), true)
  // 敲到一半的邮箱不查：@ 后面还没有点，或点紧贴 @
  assert.equal(isWorthLooking('zhang@'), false)
  assert.equal(isWorthLooking('zhang@exa'), false)
  assert.equal(isWorthLooking('zhang@.com'), false)
  assert.equal(isWorthLooking('zhang@example.'), false)
})

test('11 位手机号才查，含 +86 与空格的写法要能认出来', () => {
  assert.equal(isWorthLooking('13800000000'), true)
  assert.equal(isWorthLooking('+8613800000000'), true)
  assert.equal(isWorthLooking('+86 138 0000 0000'), true)
  assert.equal(isWorthLooking('138-0000-0000'), true)
  assert.equal(isWorthLooking('8613800000000'), true)
  // 敲到一半的号码不查——不然敲一个号码要连发九次请求，第十次开始被限频拦住，
  // 表现成「输完了反而查不出人」
  assert.equal(isWorthLooking('138'), false)
  assert.equal(isWorthLooking('1380000000'), false)
  assert.equal(isWorthLooking('138000000000'), false)
})

test('账号名兜底：长度 ≥ 2 才查，空与单字符不查', () => {
  assert.equal(isWorthLooking('hanzewei'), true)
  assert.equal(isWorthLooking('张三'), true)
  assert.equal(isWorthLooking('张'), false)
  assert.equal(isWorthLooking('a'), false)
  assert.equal(isWorthLooking(''), false)
  assert.equal(isWorthLooking('   '), false)
  assert.equal(isWorthLooking(null), false)
  assert.equal(isWorthLooking(undefined), false)
})

// ==================== 归一 ====================

test('手机号归一去掉国家码与分隔符，邮箱一个字不动', () => {
  assert.equal(normalizePhone('+86 138 0000 0000'), '13800000000')
  assert.equal(normalizePhone('86-138-0000-0000'), '13800000000')
  assert.equal(normalizePhone('13800000000'), '13800000000')
  // 邮箱本地部分里的连字符是有意义的，剥掉就查不到人了
  assert.equal(normalizePhone('my-name@example.com'), 'my-name@example.com')
})

test('lookupIdentifier：手机号发归一后的，其余原样去空白', () => {
  assert.equal(lookupIdentifier('+86 138 0000 0000'), '13800000000')
  assert.equal(lookupIdentifier('  zhang@example.com '), 'zhang@example.com')
  assert.equal(lookupIdentifier(' hanzewei '), 'hanzewei')
})

test('isPhoneLike / isEmailLike 的边界', () => {
  assert.equal(isPhoneLike('13800000000'), true)
  assert.equal(isPhoneLike('23800000000'), false)
  assert.equal(isEmailLike('a@b.co'), true)
  assert.equal(isEmailLike('@b.co'), false)
  assert.equal(isEmailLike('a b@c.co'), false)
})

// ==================== 轨道判定 ====================

test('localMode 为假（自建多用户服务器）恒走本机轨', () => {
  assert.equal(resolveTrack({ localMode: false, linked: false }), TRACK.LOCAL)
  assert.equal(resolveTrack({ localMode: false, linked: true }), TRACK.LOCAL)
})

test('local-mode 桌面端：放进案件库了走云端轨，没放进去先要求放进去', () => {
  assert.equal(resolveTrack({ localMode: true, linked: true }), TRACK.CLOUD)
  assert.equal(resolveTrack({ localMode: true, linked: false }), TRACK.NEEDS_LIBRARY)
})

test('参数缺失按「不是 local-mode」处理——最差只是查不到人，不会把界面锁死', () => {
  assert.equal(resolveTrack(), TRACK.LOCAL)
  assert.equal(resolveTrack({}), TRACK.LOCAL)
})

// ==================== 邀请链接 ====================

test('官网只有 zh / en 两个语言段，其余一律回 zh', () => {
  assert.equal(siteLangSegment('zh-CN'), 'zh')
  assert.equal(siteLangSegment('en-US'), 'en')
  assert.equal(siteLangSegment('ja-JP'), 'zh')
  assert.equal(siteLangSegment(''), 'zh')
  assert.equal(siteLangSegment(undefined), 'zh')
})

test('邀请链接指向官网的 start 落地页，基址末尾的斜杠不许拼出双斜杠', () => {
  assert.equal(inviteLinkFor('https://www.aiworkdeck.com', 'zh-CN'), 'https://www.aiworkdeck.com/zh/start')
  assert.equal(inviteLinkFor('https://www.aiworkdeck.com', 'en-US'), 'https://www.aiworkdeck.com/en/start')
  assert.equal(inviteLinkFor('https://workdeck.ai/', 'en-US'), 'https://workdeck.ai/en/start')
  assert.equal(inviteLinkFor('https://workdeck.ai///', 'ja-JP'), 'https://workdeck.ai/zh/start')
})
