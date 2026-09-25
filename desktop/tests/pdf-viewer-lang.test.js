// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 BUG-28：PDF 预览「⋮」更多菜单（Two page view / Annotations /
// Document properties）是英文，即便应用语言是中文。
//
// PDF 预览走的是 Chromium 内置 PDF 查看器，它的字符串来自 Chromium 自己那份 .pak，
// 只认启动期的 --lang 开关，所以 main.js 要在 app ready 之前按**落盘的**应用语言设一次。
//
// J1 复核抓到的阻断级回归：第一版在 ready 之前直接调 getAppLanguage()。新装机没有
// app-language.json 时它走 guessFromSystem()，而 ready 之前 app.getLocale() 返回空串 →
// 猜成 en-US 并写进缓存 → 中文系统首次启动，菜单/原生对话框/--lang 全成英文。
// 现在：只有落盘文件里有明确语言才追加 --lang，没有就什么都不追加，也不碰缓存。
//
// 下面真跑 app-language.js（假 electron），并把 main.js 里那段启动块原文抠出来真执行。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-lang-test-'))
let locale = '' // ready 之前 app.getLocale() 就是空串
const fakeApp = {
  getPath: () => userDataDir,
  getLocale: () => locale,
}
const electronId = require.resolve('electron')
require.cache[electronId] = { id: electronId, filename: electronId, loaded: true, exports: { app: fakeApp } }

const langPath = require.resolve('../main/app-language')
function freshLang() {
  delete require.cache[langPath]
  return require('../main/app-language')
}
const storeFile = path.join(userDataDir, 'app-language.json')

const SRC = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8').replace(/\r\n/g, '\n') // Windows 检出可能是 CRLF，正则按 \n 匹配
const BLOCK = (SRC.match(/\n\{\n\s*const startupLang = require\('\.\/app-language'\)\.getPersistedAppLanguage\(\)\n[\s\S]*?\n\}\n/) || [])[0]

/** 把 main.js 的启动块原文放进假 app 里真执行，返回追加了哪些开关。 */
function runStartupBlock() {
  const switches = []
  const app = { commandLine: { appendSwitch: (k, v) => switches.push([k, v]) } }
  const req = (id) => (id === './app-language' ? require('../main/app-language') : require(id))
  // eslint-disable-next-line no-new-func
  new Function('app', 'require', BLOCK)(app, req)
  return switches
}

test('main.js 的 --lang 启动块存在，且写在 app.whenReady() 之前', () => {
  assert.ok(BLOCK, 'main.js 里找不到「按落盘语言设 --lang」的启动块')
  const blockIdx = SRC.indexOf(BLOCK)
  const readyIdx = SRC.indexOf('app.whenReady().then(')
  assert.ok(readyIdx > -1)
  assert.ok(blockIdx < readyIdx, '--lang 开关必须在 app.whenReady() 之前设置，晚了 Chromium 已选完 .pak')
  const beforeReady = SRC.slice(0, readyIdx).replace(/\/\/.*$/gm, '')
  // 顶层（ready 之前、非函数体内）不许再出现无条件的 getAppLanguage() 调用做 --lang
  assert.doesNotMatch(beforeReady, /appendSwitch\('lang',\s*require\('\.\/app-language'\)\.getAppLanguage\(\)\)/,
    'ready 之前按 getAppLanguage() 设 --lang 会在新装机上把语言猜成英文')
})

test('新装机（无 app-language.json）：不追加 --lang，也不把「猜不出」写进语言缓存', () => {
  try { fs.unlinkSync(storeFile) } catch (e) { /* 本来就没有 */ }
  locale = ''
  freshLang()
  assert.deepStrictEqual(runStartupBlock(), [], '无落盘语言时不能改 Chromium locale')
  // ready 之后系统语言可读了：中文系统必须得到中文，而不是 ready 前缓存的 en-US
  locale = 'zh-CN'
  assert.strictEqual(require('../main/app-language').getAppLanguage(), 'zh-CN',
    '启动块把 ready 之前的空 locale 猜测写进了缓存——中文系统首启整个界面会变英文')
})

test('有落盘语言：按它追加 --lang（中文系统上选了英文界面也一样）', () => {
  fs.writeFileSync(storeFile, JSON.stringify({ language: 'en-US' }))
  locale = ''
  freshLang()
  assert.deepStrictEqual(runStartupBlock(), [['lang', 'en-US']])
  fs.writeFileSync(storeFile, JSON.stringify({ language: 'zh-CN' }))
  freshLang()
  assert.deepStrictEqual(runStartupBlock(), [['lang', 'zh-CN']])
})

test('落盘文件损坏或语言不受支持：当作没有，不追加', () => {
  fs.writeFileSync(storeFile, '{not json')
  freshLang()
  assert.deepStrictEqual(runStartupBlock(), [])
  fs.writeFileSync(storeFile, JSON.stringify({ language: 'fr-FR' }))
  freshLang()
  assert.deepStrictEqual(runStartupBlock(), [])
  assert.strictEqual(require('../main/app-language').getPersistedAppLanguage(), null)
})
