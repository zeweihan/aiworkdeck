// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 本机偏好（~/.aiworkdeck/prefs.json）。「可选组件面板提示过没有」存在这里而不是
// localStorage：那个随浏览器数据被清、也不区分重装，用户会被反复打扰。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { createPrefs } = require('../main/services/prefs')

test('写入后立即可读，并且真的落到 ~/.aiworkdeck/prefs.json', (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(dataDir, { recursive: true, force: true }))
  const prefs = createPrefs({ dataDir })

  assert.strictEqual(prefs.get('optionalComponentsPromptedVersion', null), null)
  prefs.set('optionalComponentsPromptedVersion', '0.38.0')
  assert.strictEqual(prefs.get('optionalComponentsPromptedVersion', null), '0.38.0')

  const onDisk = JSON.parse(fs.readFileSync(path.join(dataDir, 'prefs.json'), 'utf8'))
  assert.strictEqual(onDisk.optionalComponentsPromptedVersion, '0.38.0')
})

test('新进程读得到上一个进程写的值（面板的「不再打扰」跨重启有效）', (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(dataDir, { recursive: true, force: true }))
  createPrefs({ dataDir }).set('k', { a: 1 })
  assert.deepStrictEqual(createPrefs({ dataDir }).get('k', null), { a: 1 })
})

test('文件是坏 JSON 时按空表处理，不抛——它绝不能拦住应用启动', (t) => {
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(dataDir, { recursive: true, force: true }))
  fs.writeFileSync(path.join(dataDir, 'prefs.json'), '{ 半个文件')
  const prefs = createPrefs({ dataDir })
  assert.strictEqual(prefs.get('k', 'fallback'), 'fallback')
  prefs.set('k', 1)
  assert.strictEqual(prefs.get('k', null), 1, '坏文件应被整体重写，而不是永远写不进去')
})

test('目录不存在时自建；set 用临时文件+rename，中途断电不会留半个文件', (t) => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'prefs-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  const dataDir = path.join(root, 'nested', '.aiworkdeck')
  createPrefs({ dataDir }).set('k', 'v')
  assert.ok(fs.existsSync(path.join(dataDir, 'prefs.json')))
  assert.deepStrictEqual(fs.readdirSync(dataDir), ['prefs.json'], '不留 .tmp 残骸')
})
