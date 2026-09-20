// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 修订记录面板、横幅与头部入口的接线（dev-board#717，Task 13）：
 *   node --test office-addin/taskpane/lib/revisionLogPanel.test.js
 *
 * 面板背后的纯逻辑（条目状态、定位、撤销结局）已由 revisionLogUi.test.js 钉住，
 * 这里钉的是**接线与文案**——这两处断了，纯函数用例照样全绿，用户却看到裸 key
 * 或者一个永远打不开的面板：
 *   1. 面板/横幅用到的文案两本字典都有（只翻一半 = 另一种语言下冒出 key 本身）；
 *   2. ZH/EN 同一条文案的占位符集合一致（EN 漏掉 {count} 会把数字悄悄吞掉）；
 *   3. 所有 Vue 组件里 t('…') 的字面 key 都在字典里（打错一个字母不报错，只是显示 key）；
 *   4. App.vue 真的挂了面板、按文档绑定了记录、横幅的「查看」能打开面板。
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { ZH, EN } from './i18n.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const read = (rel) => fs.readFileSync(path.join(here, rel), 'utf8')

/** 面板与横幅的文案契约：少一条就意味着界面上有个位置在显示 key 本身 */
const REQUIRED_KEYS = [
  'revLogTitle', 'revLogEmpty', 'revLogFrom', 'revLogUnknownDoc',
  'revLogLocate', 'revLogLocateMiss',
  'revLogUndo', 'revLogUndone', 'revLogConflict', 'revLogUndoFailed',
  'revLogNotUndoable', 'revLogNoBefore', 'revLogClear',
  'crossDocBanner', 'crossDocBannerOpen'
]

test('修订记录的文案 ZH/EN 两本字典都有', () => {
  const missingZh = REQUIRED_KEYS.filter((k) => !Object.prototype.hasOwnProperty.call(ZH, k))
  const missingEn = REQUIRED_KEYS.filter((k) => !Object.prototype.hasOwnProperty.call(EN, k))
  assert.deepEqual(missingZh, [], 'ZH 缺: ' + missingZh.join(', '))
  assert.deepEqual(missingEn, [], 'EN 缺: ' + missingEn.join(', '))
})

const placeholders = (s) => [...String(s).matchAll(/\{([a-zA-Z0-9_]+)\}/g)].map((m) => m[1]).sort()

test('横幅与条目文案的占位符 ZH/EN 一致（漏一个占位符 = 数字/文档名被静默吞掉）', () => {
  assert.deepEqual(placeholders(ZH.revLogFrom), ['name'])
  assert.deepEqual(placeholders(ZH.crossDocBanner), ['count', 'name'])
  assert.deepEqual(placeholders(ZH.revLogUndoFailed), ['message'])
  const mismatched = Object.keys(ZH)
    .filter((k) => Object.prototype.hasOwnProperty.call(EN, k))
    .filter((k) => placeholders(ZH[k]).join(',') !== placeholders(EN[k]).join(','))
  assert.deepEqual(mismatched, [], '占位符对不上的 key: ' + mismatched.join(', '))
})

const VUE_FILES = [
  '../App.vue',
  '../components/ChatView.vue',
  '../components/SettingsView.vue',
  '../components/TransferPanel.vue',
  '../components/RevisionLogPanel.vue'
]

for (const rel of VUE_FILES) {
  test(`${rel} 里 t('…') 用到的 key 都在字典里`, () => {
    const src = read(rel)
    const used = new Set()
    for (const m of src.matchAll(/\bt\(\s*'([A-Za-z0-9_]+)'/g)) used.add(m[1])
    assert.ok(used.size > 0, `${rel} 一个 t() 都没有？扫描正则或文件路径不对`)
    const missing = [...used].filter((k) => !Object.prototype.hasOwnProperty.call(ZH, k)
      || !Object.prototype.hasOwnProperty.call(EN, k))
    assert.deepEqual(missing, [], `${rel} 用了字典里没有的 key: ` + missing.join(', '))
  })
}

test('App.vue 把面板挂上了、按文档绑定记录、横幅与头部入口都通到面板', () => {
  const app = read('../App.vue')
  assert.match(app, /import RevisionLogPanel from '\.\/components\/RevisionLogPanel\.vue'/,
    '面板没有被 App.vue 引入')
  assert.match(app, /<RevisionLogPanel/, '面板没有挂在 App.vue 的模板里')
  // 记录按文档分开存：不绑定的话别的文档的条目会串到这一份文档上
  assert.match(app, /bindDocument\(\s*documentKey\(\)\s*\)/, '没有按当前文档绑定修订记录')
  // 头部入口与未读角标
  assert.match(app, /revisionLogOpen/, 'App.vue 没有用到面板开关')
  assert.match(app, /openRevisionLog\(/, '头部入口没有调 openRevisionLog（打开即清未读）')
  assert.match(app, /revisionUnread/, '头部没有未读角标')
  // 横幅：点「查看」打开面板并把横幅清掉
  assert.match(app, /crossDocBanner/, 'App.vue 没有消费跨文档横幅')
  assert.match(app, /crossDocBanner\.value\s*=\s*null/, '打开面板后没有清掉横幅')
})
