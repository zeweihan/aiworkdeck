// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// 构建脚本出站 UA 的格式契约（可溯源性设计规范附录 A9 / B3）。
//
// 后端那半由 ProductIdentityTest 守；Node 脚本引不了 Java 常量，只能两边各写一遍
// 同一个格式 `AIWorkDeck/<version> (<component>)`，靠这个用例把它们钉在一起。
// 版本号单一来源是 desktop/package.json——UA 里出现别的版本来源就是接错了线。
//
// fetch-drawio-assets.js 在模块顶层就 main()，require 进来会真去下 53MB 的 draw.war，
// 所以这里读源码、只把 USER_AGENT 那一行的表达式取出来求值，require 用指向脚本目录的
// 替身，免得「测试目录的 ../package.json 恰好也是同一个文件」这种巧合把错误接线放过去。

const test = require('node:test')
const assert = require('node:assert')
const fs = require('node:fs')
const path = require('node:path')

const SCRIPT = path.join(__dirname, '../scripts/fetch-drawio-assets.js')
const PKG = require('../package.json')

function evalUserAgent() {
  const src = fs.readFileSync(SCRIPT, 'utf8')
  const m = src.match(/^const USER_AGENT = (.+);$/m)
  assert.ok(m, 'fetch-drawio-assets.js 里找不到 `const USER_AGENT = ...;`（一行写完的形式）')
  const scriptRequire = (id) => require(path.resolve(path.dirname(SCRIPT), id))
  return new Function('require', `return ${m[1]}`)(scriptRequire)
}

test('构建脚本的 UA 与 AIWorkDeck/<package.json version> (build-script) 严格相等', () => {
  assert.strictEqual(evalUserAgent(), `AIWorkDeck/${PKG.version} (build-script)`)
})

test('构建脚本的 UA 形状与后端 ProductIdentity.userAgent() 一致', () => {
  assert.match(evalUserAgent(), /^AIWorkDeck\/[^ ]+ \(build-script\)$/)
})

test('旧字面量 aiworkdeck-build 已从构建脚本里消失', () => {
  const src = fs.readFileSync(SCRIPT, 'utf8')
  assert.ok(src.includes('draw.war'), '空断言护栏：连脚本都没读到就别谈「不含旧字面量」')
  assert.ok(!src.includes('aiworkdeck-build'), '出站 UA 必须走 USER_AGENT 常量，别再拼字面量')
})

test('UA 真的接到了下载函数上，不是个没人用的常量', () => {
  const src = fs.readFileSync(SCRIPT, 'utf8')
  assert.match(src, /headers:\s*\{\s*'User-Agent':\s*USER_AGENT\s*\}/)
})
