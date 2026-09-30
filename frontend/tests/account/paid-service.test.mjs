// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { accountRequiredError, isCreditsRequired, requiresAiAccount, isAccountRequired } from '../../src/utils/requireAccountCore.js'

const api = readFileSync(new URL('../../src/services/api.js', import.meta.url), 'utf8')
const requestSource = api.slice(api.indexOf('function request(options)'), api.indexOf('// 查询公司基础信息'))
  .replaceAll("import('@/utils/requireAccount.js')", 'loadAccountUi()')
function harness(backend, loggedIn = false) {
  const prompts = []
  const request = new Function('uni', 'getApiBaseUrl', 'getAuthHeaders', 't', 'accountRequiredError', 'isCreditsRequired', 'isAccountRequired', 'loadAccountUi', 'console',
    requestSource + '\nreturn request')(
    { request: options => { options.success({ statusCode: 200, data: backend }); return {} } },
    () => 'https://synthetic.invalid', () => ({}), key => key, accountRequiredError, isCreditsRequired, isAccountRequired,
    async () => ({ requireAccountFor4011: async () => { prompts.push('login'); return loggedIn }, requireRecharge: async () => { prompts.push('recharge') } }),
    { log() {}, warn() {}, error() {} })
  return { request, prompts }
}

test('only explicit local Ollama bypasses the AI account gate', () => {
  for (const provider of ['AWD_CLOUD', 'OPENROUTER', '', undefined, 'GEMINI']) assert.equal(requiresAiAccount(provider), true)
  assert.equal(requiresAiAccount('OLLAMA'), false)
})

test('HTTP account failure opens login and remains a rejected operation even after login', async () => {
  for (const loggedIn of [false, true]) {
    const h = harness({ code: 4011, kind: 'NOT_CONNECTED', reason: 'gateway', message: 'Sign in' }, loggedIn)
    await assert.rejects(h.request({ url: '/lookup' }), e => e.accountRequired && e.loggedIn === loggedIn)
    assert.deepEqual(h.prompts, ['login'])
  }
})

test('HTTP wallet failures open recharge, preserve structured error, and never report success', async () => {
  for (const fields of [{ kind: 'CONFLICT', reason: 'no_credits' }, { gatewayKind: 'NO_CREDITS' }, { reason: 'insufficient_credits' }]) {
    const h = harness({ code: 1, message: 'Add credits', ...fields })
    await assert.rejects(h.request({ url: '/paid-service' }), e => isCreditsRequired(e))
    assert.deepEqual(h.prompts, ['recharge'])
  }
})

test('background requests and unrelated failures do not open payment prompts', async () => {
  for (const [backend, options] of [
    [{ code: 4011, kind: 'NOT_CONNECTED', message: 'Sign in' }, { accountPrompt: false }],
    [{ code: 1, reason: 'no_credits', message: 'Add credits' }, { accountPrompt: false }],
    [{ code: 1, kind: 'NETWORK', message: 'Unavailable' }, {}],
  ]) {
    const h = harness(backend)
    await assert.rejects(h.request({ url: '/status', ...options }))
    assert.deepEqual(h.prompts, [])
  }
})

const rechargeSource = readFileSync(new URL('../../src/utils/requireAccount.js', import.meta.url), 'utf8')
  .slice(readFileSync(new URL('../../src/utils/requireAccount.js', import.meta.url), 'utf8').indexOf('let rechargePending'))
  .replace('export function', 'function')
test('recharge dialog is mounted once, closes cleanly, and only automatic prompts have cooldown', async () => {
  let props, mounts = 0, removals = 0
  const overlays = []
  const requireRecharge = new Function('document', 'createApp', 'RechargeDialog', 'i18n', 'setGlobalOverlay', rechargeSource + '\nreturn requireRecharge')(
    { body: { appendChild() {} }, createElement: () => ({ remove() { removals++ } }) },
    (component, options) => { props = options; return { use() {}, mount() { mounts++ }, unmount() {} } },
    {}, {}, (...args) => overlays.push(args))
  const first = requireRecharge({ auto: true })
  assert.equal(requireRecharge({ auto: true }), first)
  assert.equal(mounts, 1)
  assert.equal(props.visible, true)
  props['onUpdate:visible'](false)
  await first
  assert.equal(removals, 1)
  assert.deepEqual(overlays, [[true, 'awd-account-recharge'], [false, 'awd-account-recharge']])
  await requireRecharge({ auto: true })
  assert.equal(mounts, 1)
  const manual = requireRecharge()
  assert.equal(mounts, 2)
  props['onUpdate:visible'](false)
  await manual
})

test('expired account and gateway credentials prompt sign-in without clearing the session', async () => {
  for (const fields of [{ kind: 'UNAUTHORIZED' }, { gatewayKind: 'UNAUTHORIZED' }]) {
    const h = harness({ code: 1, message: 'Sign in again', ...fields })
    await assert.rejects(h.request({ url: '/lookup' }), e => e.accountRequired)
    assert.deepEqual(h.prompts, ['login'])
  }
})


test('invalid login credentials reject in the current form without recursively opening login', async () => {
  const h = harness({ code: 1, kind: 'UNAUTHORIZED', message: 'Invalid verification code' })
  for (const name of ['loginAccount', 'connectAccount', 'sendAccountLoginCode']) {
    const functionSource = api.slice(api.indexOf('export function ' + name + '(')).split('\n}')[0] + '\n}'
    const method = new Function('request', 'unwrapEnvelope', functionSource.replace('export function', 'function') + '\nreturn ' + name)(h.request, value => value)
    await assert.rejects(method('synthetic'), e => e.kind === 'UNAUTHORIZED' && e.message === 'Invalid verification code' && !e.accountRequired)
  }
  assert.deepEqual(h.prompts, [])
})
