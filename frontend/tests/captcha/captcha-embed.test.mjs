// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 国际站人机验证托管页（官网 /captcha-embed）父页侧逻辑。
// 纯逻辑在 src/utils/captchaEmbedCore.js（零依赖，node 直接导入）；接线处用源码断言钉住。
// 跑法：npm run test:captcha

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  MESSAGE_SOURCE,
  acceptMessage,
  buildEmbedUrl,
  createEmbedController,
  normalizeHeight,
  originOf,
} from '../../src/utils/captchaEmbedCore.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8')

/** 可手动推进的假定时器 */
function fakeTimers() {
  let now = 0
  let seq = 0
  const timers = new Map()
  return {
    setTimer(fn, ms) { const id = ++seq; timers.set(id, { at: now + ms, fn }); return id },
    clearTimer(id) { timers.delete(id) },
    advance(ms) {
      now += ms
      for (const [id, t] of [...timers]) {
        if (t.at <= now) { timers.delete(id); t.fn() }
      }
    },
    pending() { return timers.size },
  }
}

function makeController(extra = {}) {
  const sent = []
  const sizes = []
  const t = fakeTimers()
  const c = createEmbedController({
    post: (m) => sent.push(m),
    onSize: (h) => sizes.push(h),
    setTimer: t.setTimer,
    clearTimer: t.clearTimer,
    ...extra,
  })
  return { c, sent, sizes, t }
}

const msg = (type, rest = {}) => ({ source: MESSAGE_SOURCE, type, ...rest })
const types = (sent) => sent.map((m) => m.type)

test('托管页地址：去尾斜杠、lang/theme 只两档', () => {
  assert.equal(
    buildEmbedUrl('https://www.workdeck.ai/', { lang: 'en-US', theme: 'dark' }),
    'https://www.workdeck.ai/captcha-embed?lang=en&theme=dark',
  )
  assert.equal(
    buildEmbedUrl('https://www.workdeck.ai', { lang: 'zh-CN', theme: 'weird' }),
    'https://www.workdeck.ai/captcha-embed?lang=zh&theme=light',
  )
  assert.equal(originOf('https://www.workdeck.ai/captcha-embed?x=1'), 'https://www.workdeck.ai')
  assert.equal(originOf('not a url'), '')
})

test('消息过滤：source 与 origin 两道缺一不可', () => {
  const frame = {}
  const other = {}
  const opts = { expectedSource: frame, expectedOrigin: 'https://www.workdeck.ai' }
  const ok = { source: frame, origin: 'https://www.workdeck.ai', data: msg('ready') }
  assert.deepEqual(acceptMessage(ok, opts), msg('ready'))
  // 别的窗口冒充同 origin
  assert.equal(acceptMessage({ ...ok, source: other }, opts), null)
  // 我们的框被导航到了别的站（窗口对象不变，origin 变了）
  assert.equal(acceptMessage({ ...ok, origin: 'https://evil.example' }, opts), null)
  // file:// 父页常见的 'null' origin
  assert.equal(acceptMessage({ ...ok, origin: 'null' }, opts), null)
  // 协议字段不对
  assert.equal(acceptMessage({ ...ok, data: { source: 'other', type: 'token', token: 'x' } }, opts), null)
  assert.equal(acceptMessage({ ...ok, data: 'awd-captcha' }, opts), null)
  assert.equal(acceptMessage({ ...ok, data: { source: MESSAGE_SOURCE } }, opts), null)
  // 期望值缺失时全拒（originOf 解析失败回空串的那条路）
  assert.equal(acceptMessage(ok, { expectedSource: frame, expectedOrigin: '' }), null)
})

test('ready 之前的 getToken 排队，ready 到了先 reset 再 get-token', async () => {
  const { c, sent } = makeController()
  const p = c.getToken()
  assert.deepEqual(sent, [], 'ready 之前一条都不发')
  c.handle(msg('ready', { provider: 'turnstile' }))
  assert.deepEqual(types(sent), ['reset', 'get-token'])
  assert.ok(sent.every((m) => m.source === MESSAGE_SOURCE))
  assert.equal(c.state.provider, 'turnstile')
  c.handle(msg('token', { token: 'tok-1' }))
  assert.equal(await p, 'tok-1')
})

test('ready 之后每次取都重新 reset（token 一次性）', async () => {
  const { c, sent } = makeController()
  c.handle(msg('ready'))
  const p1 = c.getToken()
  c.handle(msg('token', { token: 'a' }))
  assert.equal(await p1, 'a')
  const p2 = c.getToken()
  c.handle(msg('token', { token: 'b' }))
  assert.equal(await p2, 'b')
  assert.deepEqual(types(sent), ['reset', 'get-token', 'reset', 'get-token'])
})

test('请求发出 8 秒没拿到 token 回空串，定时器清干净', async () => {
  const { c, t } = makeController()
  c.handle(msg('ready'))
  const p = c.getToken()
  t.advance(7999)
  let done = false
  p.then(() => { done = true })
  await Promise.resolve()
  assert.equal(done, false, '8 秒之前不收口')
  t.advance(1)
  assert.equal(await p, '')
  assert.equal(t.pending(), 0)
})

test('托管页一直不 ready：等 ready 另有上限，按钮不会永远转圈', async () => {
  const { c, sent, t } = makeController({ readyTimeoutMs: 15000 })
  const p = c.getToken()
  t.advance(15000)
  assert.equal(await p, '')
  assert.deepEqual(sent, [])
})

test('等待中的空串 token（reset 回调）不结束等待；error 立刻回空串', async () => {
  const { c } = makeController()
  c.handle(msg('ready'))
  const p = c.getToken()
  c.handle(msg('token', { token: '' }))
  c.handle(msg('token', { token: 'real' }))
  assert.equal(await p, 'real')

  const p2 = c.getToken()
  c.handle(msg('error', { code: '110200' }))
  assert.equal(await p2, '')
})

test('没人在等时的自动 token 不留存；并发调用共用一次请求', async () => {
  const { c, sent } = makeController()
  c.handle(msg('ready'))
  c.handle(msg('token', { token: 'stale' }))
  const a = c.getToken()
  const b = c.getToken()
  assert.equal(a, b)
  assert.deepEqual(types(sent), ['reset', 'get-token'])
  c.handle(msg('token', { token: 'fresh' }))
  assert.equal(await a, 'fresh')
})

test('size 消息调高度；非法值忽略、过大截断', () => {
  const { c, sizes } = makeController()
  c.handle(msg('size', { height: 140 }))
  c.handle(msg('size', { height: 'abc' }))
  c.handle(msg('size', { height: -5 }))
  c.handle(msg('size', { height: 99999 }))
  assert.deepEqual(sizes, [140, 600])
  assert.equal(normalizeHeight(64.6), 65)
})

test('disabled 与 destroy：在等的回空串，之后的 getToken 直接空串', async () => {
  let disabledCalls = 0
  const { c } = makeController({ onDisabled: () => { disabledCalls++ } })
  const p = c.getToken()
  c.handle(msg('disabled'))
  assert.equal(await p, '')
  assert.equal(disabledCalls, 1)
  assert.equal(await c.getToken(), '')

  const { c: c2 } = makeController()
  c2.handle(msg('ready'))
  const q = c2.getToken()
  c2.destroy()
  assert.equal(await q, '')
  c2.handle(msg('token', { token: 'late' }))
  assert.equal(await c2.getToken(), '')
})

test('接线：turnstile 分支不再本页 render，走 iframe + 两道过滤 + targetOrigin', () => {
  const src = read('utils/captcha.js')
  assert.ok(!src.includes('turnstile.render'), 'file:// 下直接 render 必报 110200')
  assert.ok(!src.includes('challenges.cloudflare.com'), '不再在本页加载 Turnstile 脚本')
  assert.match(src, /createElement\('iframe'\)/)
  assert.match(src, /allow-scripts allow-same-origin allow-forms allow-popups/)
  assert.match(src, /expectedSource: iframe\.contentWindow/)
  assert.match(src, /postMessage\(msg, expectedOrigin\)/)
  assert.match(src, /removeEventListener\('message'/)
  // 阿里云分支原样保留
  assert.match(src, /window\.AliyunCaptchaConfig = \{ region: 'cn', prefix: config\.prefix \}/)
  const unlock = read('pages/unlock/unlock.vue')
  assert.match(unlock, /teardownCaptcha\(\)/)
  assert.match(unlock, /'is-embed': captcha && captcha\.provider === 'turnstile'/)
})
