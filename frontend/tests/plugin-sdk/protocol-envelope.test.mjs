// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 契约测试：Web 插件 SDK 的握手信封字段 `awd: 1`。
//
// 第三方插件依赖这个字面量。宿主与插件之间的 postMessage 双向都以 `msg.awd !== PROTOCOL`
// 作为「这条消息是不是给我的」的唯一判据，而插件是第三方在自己的仓库里写、装到用户机器上
// 跑的：字段名或版本号一改，所有已发布插件当场全哑——它们发出的消息宿主不认，
// 宿主推来的事件插件不收，而且没有任何报错，只是静默地什么都不发生。
//
// 改动它必须同时提供双读兼容期，不能只改一处常量。因此这里断言在**字面量 1** 上，
// 而不是引用 SDK 里的 PROTOCOL 常量（引用常量的话，把 1 改成 2 测试照样绿）。
//
// 命令：cd frontend && npm run test:plugin-sdk
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const src = readFileSync(resolve(root, 'sdk/plugin-sdk/awd-plugin-sdk.js'), 'utf8')

// 与 events-channel.test.mjs / theme-channel.test.mjs 同一配方：把 SDK 源码注入假 DOM 执行。
function bootSdk() {
  const sent = []
  let listener = null
  const parent = { postMessage: (msg) => sent.push(msg) }
  const win = {
    parent,
    addEventListener: (type, cb) => { if (type === 'message') listener = cb },
    awd: null,
  }
  const doc = {
    documentElement: { setAttribute() {}, style: { setProperty() {} } },
    body: { classList: { toggle() {} } },
  }
  new Function('window', 'document', src + '\n;window.awd = window.awd || awd;')(win, doc)
  const fromHost = (msg) => listener({ source: parent, data: msg })
  return { awd: win.awd, sent, fromHost }
}

test('源码里的协议常量是字面量 1', () => {
  assert.ok(/\bvar PROTOCOL = 1;/.test(src),
    'sdk/plugin-sdk/awd-plugin-sdk.js 必须保留 `var PROTOCOL = 1;`')
})

test('收信判据是 msg.awd（信封字段名不可改）', () => {
  assert.ok(src.includes('msg.awd !== PROTOCOL'),
    '收信侧必须按 msg.awd 判信封归属')
})

test('插件发给宿主的每一条消息，信封都是 awd: 1', () => {
  const { awd, sent } = bootSdk()
  awd.tools.invoke('doc_read', {})
  awd.events.on('files.changed', () => {})
  assert.ok(sent.length >= 2, 'SDK 应当已经 postMessage 过')
  for (const msg of sent) {
    assert.equal(msg.awd, 1, '信封字段必须叫 awd，值必须是 1，实际：' + JSON.stringify(msg))
  }
})

test('宿主用 awd: 1 发来的消息才被接收；换成别的版本号一律忽略', async () => {
  const { awd, fromHost } = bootSdk()

  // 正确信封：握手完成，ready() 兑现
  fromHost({ awd: 1, type: 'init', context: { projectId: '7' } })
  const ctx = await awd.ready()
  assert.equal(ctx.projectId, '7')

  // 错误信封（版本号变了 / 字段名变了）：一律丢弃，事件不派发
  const got = []
  awd.events.on('files.changed', (d) => got.push(d))
  fromHost({ awd: 2, type: 'event', event: 'files.changed', data: {} })
  fromHost({ protocol: 1, type: 'event', event: 'files.changed', data: {} })
  assert.equal(got.length, 0, '非 awd:1 的信封必须被忽略')

  fromHost({ awd: 1, type: 'event', event: 'files.changed', data: { ok: true } })
  assert.equal(got.length, 1, 'awd:1 的信封必须被接收')
})
