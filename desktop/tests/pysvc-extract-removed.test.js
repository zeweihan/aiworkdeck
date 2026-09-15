// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 首启解压链路必须整段消失（设计 §3.2）。留着它的代价不是多几行死代码：
// 那段逻辑会在没有 pysvc.tar.gz 的 0.38.0 上给用户弹一个「本地组件解压失败」的错误框。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const MAIN = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
const PKG = JSON.parse(fs.readFileSync(path.join(__dirname, '../package.json'), 'utf8'))

test('main.js 不再有 pysvc 解压相关的任何符号', () => {
  for (const sym of ['resolvePysvcRoot', 'ensurePysvcReady', 'ensurePysvcExtracted', 'pysvc.tar.gz', 'pysvc.meta.json', 'syncSrcPatch']) {
    assert.ok(!MAIN.includes(sym), `main.js 仍引用 ${sym}`)
  }
})

test('splash 不再承诺「约一分钟」的解压——0.38.0 起首启没有解压这一步', () => {
  assert.ok(!MAIN.includes('约一分钟'), 'splash 文案仍写着解压耗时')
  assert.ok(!MAIN.includes('本地组件解压失败'), '解压失败弹框仍在')
})

test('createServices 传下去的 ctx 带 projectRoot、不带 pysvcRoot', () => {
  const start = MAIN.indexOf('function createServices()')
  const body = MAIN.slice(start, MAIN.indexOf('\n}', start))
  assert.ok(!/pysvcRoot/.test(body), 'ctx 仍在传 pysvcRoot')
  assert.match(body, /projectRoot/, 'model-manager 与 descriptor 解析 pack 需要 projectRoot')
})

test('extraResources 不再打包 pysvc.tar.gz / pysvc.meta.json', () => {
  const froms = PKG.build.extraResources.map((r) => r.from)
  assert.ok(!froms.some((f) => String(f).includes('pysvc')), 'extraResources 仍带 pysvc：' + froms.join(' '))
})
