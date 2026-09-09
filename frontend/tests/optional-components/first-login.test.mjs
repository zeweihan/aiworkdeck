// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 触发条件（设计 §4.1）：桌面端 + 登录后 + 有未装组件 + 本机没标记过。
// 标记落 electron prefs（不是 localStorage），值是大版本号：下个大版本多出新组件时再提示一次。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { shouldPromptOptionalComponents, PROMPTED_PREF_KEY } from '../../src/composables/useOptionalComponents.js'

const missing = [{ packId: 'kokoro-runtime', installed: false, modelId: 'kokoro-models', modelInstalled: false }]
const allIn = [{ packId: 'kokoro-runtime', installed: true, modelId: 'kokoro-models', modelInstalled: true }]

test('全新安装、有未装组件、没提示过 → 提示', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), true)
})

test('本大版本已经提示过 → 不再打扰（「稍后再说」之后也走这条）', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: '0.38.0', appVersion: '0.38.0', isDesktop: true }), false)
})

test('升到新大版本 → 再提示一次（可能有新组件）', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: '0.38.0', appVersion: '0.39.0', isDesktop: true }), true)
})

test('四个都装好了 → 不提示', () => {
  assert.equal(shouldPromptOptionalComponents({ items: allIn, promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), false)
})

test('浏览器端不提示（那里没有本机组件可言）', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: null, appVersion: '0.38.0', isDesktop: false }), false)
})

test('接口没拿到东西（离线部署 ai.packs.enabled=false）→ 不提示，绝不弹一个空面板', () => {
  assert.equal(shouldPromptOptionalComponents({ items: [], promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), false)
})

test('运行时装了但模型没下也算「未装」——面板要能把模型补上', () => {
  const half = [{ packId: 'mineru-runtime', installed: true, modelId: 'mineru-models', modelInstalled: false }]
  assert.equal(shouldPromptOptionalComponents({ items: half, promptedVersion: null, appVersion: '0.38.0', isDesktop: true }), true)
})

const dialogSrc = readFileSync(new URL('../../src/components/OptionalComponentsDialog.vue', import.meta.url), 'utf8')

test('prefs 键名是契约的一部分（写进 ~/.aiworkdeck/prefs.json，重装才重置）', () => {
  assert.equal(PROMPTED_PREF_KEY, 'optionalComponentsPromptedVersion')
})

test('面板：「稍后再说」写标记并关闭；「立即下载所选」只装勾选的', () => {
  assert.match(dialogSrc, /PROMPTED_PREF_KEY/)
  assert.match(dialogSrc, /host\.prefs/)
  assert.match(dialogSrc, /installAll\(\s*this\.controller\.state\.items\.filter\(\(i\) => i\.selected\)\s*\)/)
  assert.match(dialogSrc, /components\.later/)
  assert.match(dialogSrc, /components\.laterHint/)
  assert.ok(!/uni\.setStorageSync/.test(dialogSrc), '「提示过」不许落 localStorage/uni storage')
})

const listSrc = readFileSync(new URL('../../src/pages/project-list/project-list.vue', import.meta.url), 'utf8')

test('触发挂 onLoad 而不是 onShow（从项目页返回会反复触发 onShow）', () => {
  assert.match(listSrc, /maybePromptOptionalComponents/)
  const onShow = listSrc.slice(listSrc.indexOf('onShow()'), listSrc.indexOf('methods:'))
  assert.ok(!/maybePromptOptionalComponents/.test(onShow), 'onShow 里不许挂首次登录面板')
})
