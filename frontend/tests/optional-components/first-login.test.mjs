// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 触发条件（设计 §4.1）：桌面端 + 登录后 + 有未装组件 + 这批组件没提示过。
// 标记落 electron prefs（不是 localStorage）：记的是「提示过哪些组件」（dev-board#751），
// 不再按大版本重弹——用户点过「稍后再说」的那批，升级到 0.47 不该再问一遍。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  shouldPromptOptionalComponents,
  mergePromptedPackIds,
  PROMPTED_PREF_KEY,
  PROMPTED_PACKS_PREF_KEY,
} from '../../src/composables/useOptionalComponents.js'

const missing = [{ packId: 'kokoro-runtime', installed: false, modelId: 'kokoro-models', modelInstalled: false }]
const allIn = [{ packId: 'kokoro-runtime', installed: true, modelId: 'kokoro-models', modelInstalled: true }]

test('全新安装、有未装组件、没提示过 → 提示', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: null, promptedPackIds: null, isDesktop: true }), true)
})

test('这批组件提示过 → 不再打扰（「稍后再说」之后也走这条）', () => {
  assert.equal(shouldPromptOptionalComponents({
    items: missing, promptedVersion: '0.46.0', promptedPackIds: ['kokoro-runtime'], isDesktop: true,
  }), false)
})

test('升级到新大版本、缺的还是那几个 → 不再弹（dev-board#751）', () => {
  assert.equal(shouldPromptOptionalComponents({
    items: missing, promptedVersion: '0.46.0', promptedPackIds: ['kokoro-runtime'], isDesktop: true,
  }), false)
})

test('出现上次没提示过的新组件 → 再弹一次', () => {
  const withNew = [...missing, { packId: 'asr-runtime', installed: false, modelId: 'asr-models', modelInstalled: false }]
  assert.equal(shouldPromptOptionalComponents({
    items: withNew, promptedVersion: '0.46.0', promptedPackIds: ['kokoro-runtime'], isDesktop: true,
  }), true)
})

test('新组件已经装好了 → 不弹（判据是「缺的里面有新的」，不是「有新的」）', () => {
  const withNew = [...missing, { packId: 'asr-runtime', installed: true, modelId: 'asr-models', modelInstalled: true }]
  assert.equal(shouldPromptOptionalComponents({
    items: withNew, promptedVersion: '0.46.0', promptedPackIds: ['kokoro-runtime'], isDesktop: true,
  }), false)
})

test('老标记只记了版本号（0.46 及以前装的）→ 视为这批都提示过，不重弹', () => {
  assert.equal(shouldPromptOptionalComponents({
    items: missing, promptedVersion: '0.46.0', promptedPackIds: null, isDesktop: true,
  }), false)
})

test('四个都装好了 → 不提示', () => {
  assert.equal(shouldPromptOptionalComponents({ items: allIn, promptedVersion: null, promptedPackIds: null, isDesktop: true }), false)
})

test('浏览器端不提示（那里没有本机组件可言）', () => {
  assert.equal(shouldPromptOptionalComponents({ items: missing, promptedVersion: null, promptedPackIds: null, isDesktop: false }), false)
})

test('接口没拿到东西（离线部署 ai.packs.enabled=false）→ 不提示，绝不弹一个空面板', () => {
  assert.equal(shouldPromptOptionalComponents({ items: [], promptedVersion: null, promptedPackIds: null, isDesktop: true }), false)
})

test('运行时装了但模型没下也算「未装」——面板要能把模型补上', () => {
  const half = [{ packId: 'mineru-runtime', installed: true, modelId: 'mineru-models', modelInstalled: false }]
  assert.equal(shouldPromptOptionalComponents({ items: half, promptedVersion: null, promptedPackIds: null, isDesktop: true }), true)
})

// ---------- 「提示过哪些」的集合是并集：单调增，卸载一个组件不会把它变回「新组件」 ----------

test('记的是本次清单里的全部组件（不只是缺的），并集去重排序', () => {
  const items = [{ packId: 'kokoro-runtime' }, { packId: 'asr-runtime' }]
  assert.deepEqual(mergePromptedPackIds(null, items), ['asr-runtime', 'kokoro-runtime'])
  assert.deepEqual(mergePromptedPackIds(['pptx-runtime'], items), ['asr-runtime', 'kokoro-runtime', 'pptx-runtime'])
})

test('用户从组件管理卸载一个已提示过的组件，不会因此再弹面板', () => {
  const prompted = mergePromptedPackIds(null, allIn)
  assert.equal(shouldPromptOptionalComponents({
    items: missing, promptedVersion: '0.46.0', promptedPackIds: prompted, isDesktop: true,
  }), false)
})

const dialogSrc = readFileSync(new URL('../../src/components/OptionalComponentsDialog.vue', import.meta.url), 'utf8')

test('prefs 键名是契约的一部分（写进 ~/.aiworkdeck/prefs.json，重装才重置）', () => {
  assert.equal(PROMPTED_PREF_KEY, 'optionalComponentsPromptedVersion')
  assert.equal(PROMPTED_PACKS_PREF_KEY, 'optionalComponentsPromptedPacks')
})

test('面板：「稍后再说」写标记并关闭；「立即下载所选」只装勾选的', () => {
  assert.match(dialogSrc, /PROMPTED_PREF_KEY/)
  assert.match(dialogSrc, /host\.prefs/)
  assert.match(dialogSrc, /installAll\(this\.selectedItems\)/)
  assert.match(dialogSrc, /PROMPTED_PACKS_PREF_KEY/)
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

// ---------- 已就绪的组件不再给复选框（dev-board#751） ----------

test('面板：清单只列缺失的，已就绪的收成底部一行灰字', () => {
  assert.match(dialogSrc, /v-for="item in pendingItems"/)
  assert.match(dialogSrc, /:selectable="item\.phase !== 'ready'"/)
  assert.match(dialogSrc, /components\.alreadyReady/)
})

test('「立即下载所选」只算缺失项：已就绪的不参与勾选与总进度', () => {
  assert.match(dialogSrc, /selectedItems\(\)[\s\S]*?i\.selected && i\.phase !== 'ready'/)
})

test('已就绪那行文案两语言齐全', async () => {
  const zh = (await import('../../src/locales/zh-CN/components.js')).default
  const en = (await import('../../src/locales/en-US/components.js')).default
  for (const k of ['alreadyReady', 'nameSeparator']) {
    assert.ok(zh[k], 'zh-CN 缺 components.' + k)
    assert.ok(en[k], 'en-US 缺 components.' + k)
  }
  assert.ok(zh.alreadyReady.includes('{names}') && en.alreadyReady.includes('{names}'))
})

test('触发处把「提示过哪些」一起读出来、不提示时补齐老标记', () => {
  assert.match(listSrc, /PROMPTED_PACKS_PREF_KEY/)
  assert.match(listSrc, /mergePromptedPackIds/)
})
