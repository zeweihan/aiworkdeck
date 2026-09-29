// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 登录后置（dev-board#1046，设计 2026-09-29 §5.1）：requireAccount 的判定与接线。
// 纯逻辑在 src/utils/requireAccountCore.js（零依赖）；接线处用源码断言钉住。
// 跑法：npm run test:account

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {
  createAccountGate, reasonFromBackend, accountRequiredError, isAccountLoginRetry, REASONS,
} from '../../src/utils/requireAccountCore.js'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const read = (rel) => fs.readFileSync(path.join(SRC, rel), 'utf8')

function harness({ connected = false, dialogResult = { ok: false }, statusThrows = false } = {}) {
  const calls = { status: 0, dialog: [], loggedIn: [] }
  let t = 1000
  const gate = createAccountGate({
    getStatus: async () => {
      calls.status += 1
      if (statusThrows) throw new Error('boom')
      return { connected }
    },
    openDialog: async (opts) => {
      calls.dialog.push(opts)
      return typeof dialogResult === 'function' ? dialogResult() : dialogResult
    },
    onLoggedIn: (r) => calls.loggedIn.push(r),
    now: () => t,
    ttlMs: 30000,
    autoCooldownMs: 60000,
  })
  return { gate, calls, advance: (ms) => { t += ms } }
}

test('已连接账户：直接 true，不开弹层', async () => {
  const { gate, calls } = harness({ connected: true })
  assert.equal(await gate.requireAccount({ reason: 'ai' }), true)
  assert.equal(calls.dialog.length, 0)
})

test('未连接：开弹层，reason 原样传进去；取消 → false，不广播', async () => {
  const { gate, calls } = harness({ connected: false, dialogResult: { ok: false } })
  assert.equal(await gate.requireAccount({ reason: 'market' }), false)
  assert.deepEqual(calls.dialog, [{ reason: 'market' }])
  assert.equal(calls.loggedIn.length, 0)
})

test('未连接：登录成功 → true，并广播一次（awd:account-changed 的数据源）', async () => {
  const { gate, calls } = harness({ connected: false, dialogResult: { ok: true, result: { previousAccountDiffers: true } } })
  assert.equal(await gate.requireAccount({ reason: 'ai' }), true)
  assert.equal(calls.loggedIn.length, 1)
  assert.equal(calls.loggedIn[0].previousAccountDiffers, true)
  // 登录之后本地记住已连接：紧接着的第二次调用不再查接口也不再开弹层
  assert.equal(await gate.requireAccount({ reason: 'ai' }), true)
  assert.equal(calls.dialog.length, 1)
})

test('并发的两个调用共用同一个弹层', async () => {
  let release
  const { gate, calls } = harness({
    connected: false,
    dialogResult: () => new Promise((r) => { release = () => r({ ok: true }) }),
  })
  const a = gate.requireAccount({ reason: 'ai' })
  const b = gate.requireAccount({ reason: 'team' })
  await new Promise((r) => setTimeout(r, 0))
  release()
  assert.deepEqual(await Promise.all([a, b]), [true, true])
  assert.equal(calls.dialog.length, 1)
})

test('连接状态有缓存：TTL 内不重复打 /api/account/status（它会顺手打官网 /me）', async () => {
  const { gate, calls, advance } = harness({ connected: true })
  await gate.requireAccount({})
  await gate.requireAccount({})
  assert.equal(calls.status, 1)
  advance(31000)
  await gate.requireAccount({})
  assert.equal(calls.status, 2)
})

test('状态查不到：放行（交给真实请求报错），不凭空弹登录框', async () => {
  const { gate, calls } = harness({ statusThrows: true })
  assert.equal(await gate.requireAccount({ reason: 'ai' }), true)
  assert.equal(calls.dialog.length, 0)
})

test('force：后端刚回了 4011，本地缓存说「已连接」也照样开弹层', async () => {
  const { gate, calls } = harness({ connected: true, dialogResult: { ok: false } })
  await gate.requireAccount({})
  assert.equal(await gate.requireAccount({ reason: 'gateway', force: true }), false)
  assert.equal(calls.dialog.length, 1)
})

test('auto（4011 自动触发）：用户取消后 60 秒内不再追着弹；用户自己点的不受限', async () => {
  const { gate, calls, advance } = harness({ connected: false, dialogResult: { ok: false } })
  assert.equal(await gate.requireAccount({ force: true, auto: true, reason: 'gateway' }), false)
  assert.equal(await gate.requireAccount({ force: true, auto: true, reason: 'gateway' }), false)
  assert.equal(calls.dialog.length, 1, '冷却期内的自动触发不再开弹层')
  assert.equal(await gate.requireAccount({ reason: 'ai' }), false)
  assert.equal(calls.dialog.length, 2, '用户主动触发不受冷却限制')
  advance(61000)
  await gate.requireAccount({ force: true, auto: true })
  assert.equal(calls.dialog.length, 3)
})

test('后端 reason → 前端说明键', () => {
  assert.equal(reasonFromBackend('platform_ai'), 'ai')
  assert.equal(reasonFromBackend('gateway'), 'gateway')
  assert.equal(reasonFromBackend('dictation'), 'dictation')
  assert.equal(reasonFromBackend(''), '')
  assert.equal(reasonFromBackend('something_new'), 'something_new')
})

test('api.js 4011 分支 reject 的错误可识别：登录成功才算「可重试」', () => {
  const backend = { code: 4011, kind: 'NOT_CONNECTED', reason: 'gateway', message: '尚未连接', gatewayKind: 'NOT_CONNECTED', canUseOwnKey: true }
  const ok = accountRequiredError(backend, true, '已登录，请再操作一次')
  assert.ok(ok instanceof Error)
  assert.equal(isAccountLoginRetry(ok), true)
  assert.equal(ok.message, '已登录，请再操作一次', '不认识 loggedIn 的调用方弹这句，而不是「尚未连接」')
  assert.equal(ok.reason, 'gateway')
  assert.equal(ok.gatewayKind, 'NOT_CONNECTED')
  const cancelled = accountRequiredError(backend, false, '已登录，请再操作一次')
  assert.equal(isAccountLoginRetry(cancelled), false)
  assert.equal(cancelled.accountRequired, true)
  assert.equal(cancelled.message, '尚未连接')
  assert.equal(isAccountLoginRetry({ code: 0, data: {} }), false)
  assert.equal(isAccountLoginRetry(null), false)
})

test('两语言都有全部 reason 的说明键', async () => {
  for (const lang of ['zh-CN', 'en-US']) {
    const mod = await import(pathToFileURL(path.join(SRC, 'locales', lang, 'account.js')).href)
    const reasons = mod.default.loginDialog && mod.default.loginDialog.reason
    assert.ok(reasons, `${lang} 缺 account.loginDialog.reason`)
    for (const r of REASONS) assert.ok(reasons[r], `${lang} 缺 account.loginDialog.reason.${r}`)
    assert.ok(reasons.generic, `${lang} 缺通用说明`)
  }
})

test('接线：api.js 有 4011 分支，4010 分支原样在', () => {
  const src = read('services/api.js')
  assert.match(src, /res\.data\.code === 4011/)
  assert.match(src, /res\.data\.code === 4010/)
  // 4011 分支不清会话、不跳登录页
  const i = src.indexOf('res.data.code === 4011')
  const branch = src.slice(i, src.indexOf('} else if', i + 10))
  assert.ok(!/clearSession/.test(branch), '4011 不是会话失效，不能清会话')
  assert.ok(!/pages\/login\/login/.test(branch), '4011 不能跳登录页')
})

test('接线：退出登录只断开账户，不 reLaunch、不解除授权', () => {
  const src = read('utils/signOut.js')
  assert.ok(!/deactivateLicense\(/.test(src), 'mode=account 也不再调 deactivateLicense')
  const desktop = src.slice(src.indexOf('isDesktopHost()'))
  assert.ok(!/uni\.reLaunch\(\{ url: '\/pages\/launch\/launch' \}\)/.test(desktop), '桌面端退出登录后停在当前页面')
  assert.match(src, /broadcastAccountChanged\(\{\s*connected: false/)
  const req = read('utils/requireAccount.js')
  assert.match(req, /ACCOUNT_CHANGED_EVENT = 'awd:account-changed'/)
  assert.match(req, /awd:entitlements-changed/)
})

test('接线：七个触发点都走 requireAccount', () => {
  const need = {
    'components/ChatInterface.vue': "reason: 'ai'",
    'components/MarketPane.vue': "reason: 'market'",
    'components/MarketSidebarPanel.vue': "reason: 'market'",
    'components/MarketDetailPane.vue': "reason: 'market'",
    'components/admin/TeamPanel.vue': "reason: 'team'",
    'components/collab/CollabDialog.vue': "reason: 'team'",
    'components/InviteMemberDialog.vue': "reason: 'team'",
    'components/MeetingRecordingPanel.vue': "reason: 'meeting'",
    'components/admin/AdminPane.vue': "reason: 'mobile'",
  }
  for (const [file, needle] of Object.entries(need)) {
    const src = read(file)
    assert.match(src, /requireAccount/, `${file} 没接 requireAccount`)
    assert.ok(src.includes(needle), `${file} 缺 ${needle}`)
  }
})

test('接线：解锁页是登录卡组件的薄壳，协议同意不再夹带 submitWizard', () => {
  const page = read('pages/unlock/unlock.vue')
  assert.match(page, /AccountLoginDialog/)
  assert.ok(!/submitWizard/.test(page), '首启初始化由后端接管（DataInitializer），前端不再提交向导')
  const comp = read('components/account/AccountLoginDialog.vue')
  assert.ok(!/submitWizard/.test(comp))
  assert.match(comp, /recordAccountConsents/)
  // 两枚同意都不预勾选
  assert.match(comp, /agreementChecked: false/)
  assert.match(comp, /crossBorderChecked: false/)
})
