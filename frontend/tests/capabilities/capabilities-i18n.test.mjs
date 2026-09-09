// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 「能力升级」分区的 i18n 对拍：模板里用到的每个 admin.capability* / navCapabilities
 * 键，两个语言文件里都得有。check:locales 只保证两侧键集合一致，
 * 保证不了「模板引用的键真的存在」——漏一个键界面上就是一段空白。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = resolve(dirname(fileURLToPath(import.meta.url)), '../../src')
const adminPane = readFileSync(resolve(SRC, 'components/admin/AdminPane.vue'), 'utf8')
const zh = readFileSync(resolve(SRC, 'locales/zh-CN/admin.js'), 'utf8')
const en = readFileSync(resolve(SRC, 'locales/en-US/admin.js'), 'utf8')

const usedKeys = [...adminPane.matchAll(/\$t\('admin\.(capability[A-Za-z]*|navCapabilities)'/g)].map((m) => m[1])

test('模板/方法里引用的能力升级文案键在两个语言文件里都存在', () => {
  assert.ok(usedKeys.length >= 15, `应引用了足够多的键，实际 ${usedKeys.length}`)
  for (const key of new Set(usedKeys)) {
    assert.ok(new RegExp(`^\\s{2}${key}:`, 'm').test(zh), `zh-CN/admin.js 缺 ${key}`)
    assert.ok(new RegExp(`^\\s{2}${key}:`, 'm').test(en), `en-US/admin.js 缺 ${key}`)
  }
})

test('给 AI 的那句话带 url 与 note 两个占位符（少一个就把用户的说明丢了）', () => {
  for (const [name, src] of [['zh-CN', zh], ['en-US', en]]) {
    const line = src.split('\n').find((l) => l.trim().startsWith('capabilityAiPrompt:'))
    assert.ok(line, `${name} 缺 capabilityAiPrompt`)
    assert.ok(line.includes('{url}'), `${name} 的 capabilityAiPrompt 缺 {url}`)
    assert.ok(line.includes('{note}'), `${name} 的 capabilityAiPrompt 缺 {note}`)
  }
})

test('全局禁 emoji：能力升级的文案里不许出现 emoji', () => {
  const emoji = /[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/u
  for (const [name, src] of [['zh-CN', zh], ['en-US', en]]) {
    const lines = src.split('\n').filter((l) => /^\s{2}(capability|navCapabilities)/.test(l))
    assert.ok(lines.length > 0, `${name} 应有能力升级文案`)
    for (const l of lines) assert.ok(!emoji.test(l), `${name} 文案里出现 emoji: ${l.trim()}`)
  }
})
