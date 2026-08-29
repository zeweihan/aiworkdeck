// 事件通道（插件规范 v2.7）SDK 行为测试：订阅/推送/退订/老宿主降级。
// 与 theme-channel.test.mjs 同一配方：把 SDK 源码注入假 DOM 环境直接执行。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../../..')
const src = readFileSync(resolve(root, 'sdk/plugin-sdk/awd-plugin-sdk.js'), 'utf8')

function bootSdk() {
  const sent = []          // 插件 -> 宿主 的消息
  let listener = null      // SDK 注册的 message 监听
  const parent = { postMessage: (msg) => sent.push(msg) }
  const win = {
    parent,
    addEventListener: (type, cb) => { if (type === 'message') listener = cb },
    awd: null
  }
  const doc = {
    documentElement: { setAttribute() {}, style: { setProperty() {} } },
    body: { classList: { toggle() {} } }
  }
  new Function('window', 'document', src + '\n;window.awd = window.awd || awd;')(win, doc)
  const fromHost = (msg) => listener({ source: parent, data: msg })
  return { awd: win.awd, sent, fromHost }
}

test('events.on 自动发 events.subscribe，推送触发回调，退订发 events.unsubscribe', () => {
  const { awd, sent, fromHost } = bootSdk()
  const got = []
  const off = awd.events.on('files.changed', (d) => got.push(d))
  const sub = sent.find(m => m.type === 'call' && m.method === 'events.subscribe')
  assert.ok(sub, '首个监听者应自动订阅')
  assert.deepEqual(sub.params.events, ['files.changed'])

  fromHost({ awd: 1, type: 'event', event: 'files.changed', data: { projectId: '7' } })
  assert.equal(got.length, 1)
  assert.equal(got[0].projectId, '7')

  // 未订阅的事件不触发
  fromHost({ awd: 1, type: 'event', event: 'selection.changed', data: {} })
  assert.equal(got.length, 1)

  off()
  const unsub = sent.find(m => m.type === 'call' && m.method === 'events.unsubscribe')
  assert.ok(unsub, '最后一个监听者移除后应自动退订')
  fromHost({ awd: 1, type: 'event', event: 'files.changed', data: {} })
  assert.equal(got.length, 1, '退订后不再触发')
})

test('多个监听者共享一次订阅；一个回调抛错不打断其余', () => {
  const { awd, sent, fromHost } = bootSdk()
  const got = []
  awd.events.on('selection.changed', () => { throw new Error('boom') })
  awd.events.on('selection.changed', () => got.push(1))
  const subs = sent.filter(m => m.type === 'call' && m.method === 'events.subscribe')
  assert.equal(subs.length, 1, '同一事件只订阅一次')
  fromHost({ awd: 1, type: 'event', event: 'selection.changed', data: {} })
  assert.equal(got.length, 1)
})

test('老宿主降级：subscribe 被 unknown_method 拒绝后 on 不抛、退订函数可用', async () => {
  const { awd, sent, fromHost } = bootSdk()
  const off = awd.events.on('files.changed', () => {})
  const sub = sent.find(m => m.type === 'call' && m.method === 'events.subscribe')
  // 宿主回 unknown_method（老宿主行为）——SDK 应静默吞掉这个 rejection
  fromHost({ awd: 1, type: 'result', seq: sub.seq, ok: false, error: { code: 'unknown_method', message: 'x' } })
  await new Promise(r => setTimeout(r, 0))
  assert.doesNotThrow(() => off())
})

test('非法来源与非法协议号的事件消息被忽略', () => {
  const { awd, sent, fromHost } = bootSdk()
  const got = []
  awd.events.on('files.changed', () => got.push(1))
  fromHost({ awd: 2, type: 'event', event: 'files.changed', data: {} })
  assert.equal(got.length, 0)
})
