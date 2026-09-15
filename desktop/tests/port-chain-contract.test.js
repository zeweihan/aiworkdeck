// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 契约测试：打包态后端端口链 [5269, 5369, 5169]。
//
// 这三个数字不是实现细节，它们写进了用户看得到、也照着敲的东西里：
//   - Office/WPS 插件的「服务器地址」说明与输入框占位符，直接告诉用户填
//     http://127.0.0.1:5269 连同机桌面版（office-addin/taskpane/lib/i18n.js）；
//   - 手机端/第三方集成按同一地址找本机桌面后端；
//   - 顺序本身也是契约：5269 是首选，被占才依次降到 5369、5169。
//     改顺序会让「用户已经填好 5269」的既有配置连到别的东西上或直接连不上。
// 改动即破坏兼容：外部配置里的地址不会跟着我们改。
//
// 断言写在**字面量**上，不引用生产常量（引用的话，把数字改掉测试照样绿）。
//
// 命令：cd desktop && node --test tests/port-chain-contract.test.js
const test = require('node:test')
const assert = require('node:assert/strict')

const { DESKTOP_PORT_CHAIN } = require('../main/services/backend-service')

test('打包态后端端口链是 [5269, 5369, 5169]，顺序不可改', () => {
  assert.deepStrictEqual(DESKTOP_PORT_CHAIN, [5269, 5369, 5169])
})

test('首选端口是 5269（插件说明与用户既有配置都按这个数字来）', () => {
  assert.strictEqual(DESKTOP_PORT_CHAIN[0], 5269)
})
