// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 契约测试：office_thread.js 里两个写进用户产物的命名前缀。
//
// 两个前缀都会以真实名字落进用户的文档文件里，并且被 clone 出去的项目 Git 仓库
// 记录成历史：
//   __ai_anchor_N —— Writer 隐藏书签名（§0.2 的稳定定位锚），存进 .odt/.docx；
//   __awd_shape_N —— 演示文稿里未命名形状被补上的稳定名，存进 .pptx。
// 之后每一条按名定位的原语（resolveShape 一族、锚点回读与清理）都按这两个前缀
// 在文档里找回自己放过的东西。
//
// 改前缀会破坏兼容：新代码在存量文档里找不到旧前缀的锚点/形状名，定位类原语
// 静默落空（找不到就当没有，不报错），而旧名字仍然留在用户文件里变成垃圾。
// 改动必须同时提供双前缀兼容读，不能只改常量。
//
// office_thread.js 是 worker 脚本（非 ES 模块，且顶层引用 UNO 全局），不便 import，
// 因此这里按源码正则断言字面量，与同目录 _workerFns.mjs 抠函数是同一路数。
//
// 命令：cd frontend && npm run test:lowa-unit
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { WORKER } from './_workerFns.mjs'

const src = fs.readFileSync(WORKER, 'utf8')

test('锚点书签前缀是字面量 __ai_anchor_', () => {
  assert.ok(/^const ANCHOR_PREFIX = '__ai_anchor_';$/m.test(src),
    "office_thread.js 必须保留 `const ANCHOR_PREFIX = '__ai_anchor_';`")
})

test('锚点名由 ANCHOR_PREFIX + 序号拼出（前缀是唯一出处，不许再散落硬编码）', () => {
  assert.ok(src.includes("const name = ANCHOR_PREFIX + (++anchorSeq);"),
    'anchorBookmark 必须用 ANCHOR_PREFIX 拼名字')
  // 除常量定义那一行外，源码里不该再出现第二处 '__ai_anchor_' 字面量
  const hits = src.match(/'__ai_anchor_'/g) || []
  assert.equal(hits.length, 1, "'__ai_anchor_' 字面量只应出现在常量定义处，实际 " + hits.length + " 处")
})

test('未命名形状的补名前缀是字面量 __awd_shape_', () => {
  assert.ok(src.includes("candidate = '__awd_shape_' + (counter++);"),
    "office_thread.js 必须保留 `candidate = '__awd_shape_' + (counter++);`")
})
