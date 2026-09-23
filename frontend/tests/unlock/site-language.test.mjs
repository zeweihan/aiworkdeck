// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 解锁页切换地区时界面语言随动（dev-board#864）。
// 纯判定在 src/utils/siteLanguage.js（零依赖）；接线处用源码断言钉住。
// 跑法：npm run test:unlock

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { languageForSite, siteLanguageToApply } from '../../src/utils/siteLanguage.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8')

test('站点的默认语言：国际站英文，大陆站中文，未知站点不表态', () => {
  assert.equal(languageForSite('intl'), 'en-US')
  assert.equal(languageForSite('cn'), 'zh-CN')
  assert.equal(languageForSite(''), '')
  assert.equal(languageForSite('eu'), '')
})

test('两个方向都随动：切国际站换英文，切回大陆站换回中文', () => {
  assert.equal(siteLanguageToApply({ siteId: 'intl', current: 'zh-CN', manual: false }), 'en-US')
  assert.equal(siteLanguageToApply({ siteId: 'cn', current: 'en-US', manual: false }), 'zh-CN')
})

test('已是目标语言、用户亲手选过语言、未知站点：一律不动', () => {
  assert.equal(siteLanguageToApply({ siteId: 'intl', current: 'en-US', manual: false }), '')
  assert.equal(siteLanguageToApply({ siteId: 'cn', current: 'zh-CN', manual: false }), '')
  assert.equal(siteLanguageToApply({ siteId: 'intl', current: 'zh-CN', manual: true }), '')
  assert.equal(siteLanguageToApply({ siteId: 'cn', current: 'en-US', manual: true }), '')
  assert.equal(siteLanguageToApply({ siteId: 'eu', current: 'zh-CN', manual: false }), '')
})

test('接线：解锁页就地切语言（不整页 reload），离开时才整页重载', () => {
  const src = read('pages/unlock/unlock.vue')
  // 随动走纯判定，且两个方向都走
  assert.match(src, /siteLanguageToApply\(\{/)
  // 就地切：vue-i18n 的全局 locale + 页面自己的响应式语言
  assert.match(src, /applyI18nLocale\(lang\)/)
  assert.match(src, /this\.uiLang = lang/)
  // 页内切语言不再整页 reload（旧实现 setTimeout 600ms 后 reload，有闪屏、残留旧语言）
  const pick = src.slice(src.indexOf('pickLanguage(lang) {'), src.indexOf('pickLanguage(lang) {') + 600)
  assert.ok(!/location\.reload/.test(pick), 'pickLanguage 不应整页 reload')
  const follow = src.slice(src.indexOf('followSiteLanguage(siteId) {'), src.indexOf('followSiteLanguage(siteId) {') + 600)
  assert.ok(!/location\.reload/.test(follow), 'followSiteLanguage 不应整页 reload')
  // 语言高亮必须是响应式的（isEnglish() 不是响应式，就地切换后高亮不跟）
  assert.match(src, /isEn\(\) \{\s*return this\.uiLang === 'en-US'/)
  // 本页切过语言，离开时整页重载进启动分流（模块顶层取过的静态文案要按新语言重建）
  assert.match(src, /goLaunch\(\)/)
  assert.ok(!/uni\.reLaunch\(\{ url: '\/pages\/launch\/launch' \}\)[^\n]*\n[^\n]*\}, (800|900)\)/.test(src))

  const i18n = read('i18n/index.js')
  assert.match(i18n, /export function applyI18nLocale\(lang\)/)
  // 品牌区的英文排版类同样要跟着就地切
  const brand = read('components/BrandShowcase.vue')
  assert.match(brand, /en\(\) \{[^}]{0,120}?return String\(this\.\$i18n\.locale/)
})
