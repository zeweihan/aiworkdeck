// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real components and API error handling; all service responses are synthetic.
import assert from 'node:assert/strict'
import { mkdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import puppeteer from 'puppeteer-core'

const root = fileURLToPath(new URL('../../', import.meta.url))
const fixture = fileURLToPath(new URL('./', import.meta.url))
const artifacts = process.env.PAID_SERVICE_ARTIFACTS || '/tmp/awd-paid-service-ui'
mkdirSync(artifacts, { recursive: true })
const source = readFileSync(`${root}src/App.vue`, 'utf8')
const tokens = source.slice(source.indexOf("html,\nhtml[data-theme='light']"), source.indexOf('</style>', source.indexOf("html,\nhtml[data-theme='light']")))
const server = await createServer({ configFile: false, root: fixture, publicDir: `${root}public`, plugins: [vue()], resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] }, server: { host: '127.0.0.1', port: 5198, strictPort: true, fs: { allow: [root] } } })
await server.listen()
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await browser.newPage()
const errors = [], remoteRequests = []
page.on('pageerror', e => errors.push(e.message))
await page.setRequestInterception(true)
page.on('request', request => {
  if (/^https?:/.test(request.url()) && !request.url().startsWith('http://127.0.0.1:5198/')) {
    remoteRequests.push(request.url()); request.abort()
  } else request.continue()
})
try {
  await page.setViewport({ width: 1200, height: 900 })
  await page.goto('http://127.0.0.1:5198/')
  await page.waitForFunction(() => window.ready)
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app {margin:0;height:100%;font-family:system-ui;} *{box-sizing:border-box;} view,scroll-view{display:block;} text{display:inline;} #app{height:100dvh;}` })
  await page.evaluate(async root => {
    window.checkbaDesktop = {}
    window.paidRequests = []
    window.paidConnections = []
    const originalFetch = window.fetch
    window.fetch = (url, options = {}) => {
      if (!String(url).includes('/api/agent/connect/')) return originalFetch(url, options)
      window.paidConnections.push(options.signal)
      return Promise.resolve(new Response(new ReadableStream({ start(controller) {
        window.sseController = controller
        options.signal.addEventListener('abort', () => controller.error(new DOMException('Aborted', 'AbortError')), { once: true })
      } }), { headers: { 'Content-Type': 'text/event-stream' } }))
    }
    window.paidFailure = { code: 4011, kind: 'NOT_CONNECTED', reason: 'gateway', message: '请先登录后使用在线查询' }
    const originalRequest = uni.request
    uni.request = options => {
      const path = new URL(options.url).pathname
      let data
      if (path === '/api/account/status') data = { connected: !!window.paidConnected }
      else if (path === '/api/site') data = { current: 'cn', multiSite: false, sites: [] }
      else if (path === '/api/account/login') { window.invalidLoginCalls = (window.invalidLoginCalls || 0) + 1; data = { code: 1, kind: 'UNAUTHORIZED', message: '合成验证码错误，请重试' } }
      else if (path === '/api/account/captcha-config') data = { provider: '' }
      else if (path === '/api/license/status') data = { trialCodeEnabled: false }
      else if (path.endsWith('/completion/lookup')) data = window.paidFailure
      else if (path === '/api/account/recharge') {
        window.paidRequests.push(options.data)
        data = { present: 'qrcode', amount: options.data.amountCents, codeUrl: 'synthetic-payment-no-charge', outTradeNo: 'synthetic-order' }
      } else if (path === '/api/account/recharge/status') data = { order: { status: 'pending' } }
      else return originalRequest(options)
      options.success({ statusCode: 200, data })
      return {}
    }
    window.paidApi = await import('/@fs/' + root + 'src/services/api.js')
    window.paidUi = await import('/@fs/' + root + 'src/utils/requireAccount.js')
    document.querySelector('.chat-input-rich').textContent = '未登录的合成问题'
    window.pendingPaidSend = window.chatState.handleSubmit('steer')
  }, root)
  await page.waitForSelector('.awd-login-mask', { visible: true })
  assert.equal(await page.evaluate(() => window.chatPosts.length), 0)
  assert.match(await page.$eval('.awd-login-reason', el => el.textContent), /登录/)
  await page.waitForFunction(() => getComputedStyle(document.querySelector('.unlock-card')).opacity === '1')
  await page.screenshot({ path: `${artifacts}/login.png` })
  await page.click('.awd-login-close')
  await page.evaluate(() => window.pendingPaidSend)
  assert.equal(await page.$eval('.chat-input-rich', el => el.textContent), '未登录的合成问题')
  assert.equal(await page.evaluate(() => window.chatPosts.length), 0)

  await page.evaluate(async () => {
    window.paidFailure = { code: 1, kind: 'CONFLICT', reason: 'no_credits', message: '余额不足，请充值' }
    try { await window.paidApi.lookupWritingSelection(1, { kind: 'LAW', text: '合成法律' }) } catch (error) { window.paidError = error.reason }
  })
  await page.waitForSelector('.recharge-dialog', { visible: true })
  assert.equal(await page.evaluate(() => window.paidError), 'no_credits')
  assert.match(await page.$eval('.recharge-dialog .awd-btn-primary', el => el.textContent), /50/)
  await page.type('.recharge-custom-input', '25.50')
  assert.match(await page.$eval('.recharge-dialog .awd-btn-primary', el => el.textContent), /25.50/)
  await page.screenshot({ path: `${artifacts}/recharge.png` })
  await page.click('.recharge-dialog .awd-btn-primary')
  await page.waitForSelector('img.recharge-qr', { visible: true })
  assert.deepEqual(await page.evaluate(() => window.paidRequests), [{ amountCents: 2550 }])
  await page.screenshot({ path: `${artifacts}/synthetic-qr.png` })
  await page.click('.recharge-dialog .awd-btn-secondary')
  assert.equal(await page.$('.recharge-dialog'), null)
  // Exercise nonterminal tool denial, continuing text, and terminal wallet failure through the real SSE parser.
  await page.evaluate(async root => {
    const realNow = Date.now
    Date.now = () => realNow() + 61000
    const { useAgentStream } = await import('/@fs/' + root + 'src/composables/useAgentStream.js')
    window.paidStream = useAgentStream()
    await window.paidStream.sendMessage({ prompt: '合成流事件验证', projectId: 1 })
    window.sseController.enqueue(new TextEncoder().encode('event: account_action_required\ndata: ' + JSON.stringify({ code: 1, kind: 'CONFLICT', reason: 'no_credits', message: '余额不足，请充值' }) + '\n\n'))
  }, root)
  await page.waitForSelector('.recharge-dialog', { visible: true })
  assert.equal(await page.evaluate(() => window.paidStream.isStreaming.value), true)
  await page.evaluate(() => window.sseController.enqueue(new TextEncoder().encode('event: text_delta\ndata: ' + JSON.stringify({ content: '<final>根据已有资料继续回答。</final>' }) + '\n\n')))
  await page.waitForFunction(() => window.paidStream.bubbles.value.at(-1).content.includes('继续回答'))
  assert.equal(await page.evaluate(() => window.paidStream.isStreaming.value), true)
  await page.click('.recharge-dialog .awd-btn-secondary')
  await page.evaluate(() => {
    const priorNow = Date.now
    Date.now = () => priorNow() + 61000
    window.sseController.enqueue(new TextEncoder().encode('event: error\ndata: ' + JSON.stringify({ code: 1, kind: 'CONFLICT', reason: 'no_credits', message: '余额不足，请充值' }) + '\n\n'))
  })
  await page.waitForSelector('.recharge-dialog', { visible: true })
  assert.match(await page.evaluate(() => window.paidStream.bubbles.value.at(-1).content), /余额不足，请充值/)
  assert.equal(await page.evaluate(() => window.paidStream.isStreaming.value), false)
  await page.click('.recharge-dialog .awd-btn-secondary')
  await page.evaluate(() => window.paidStream.resetSSE())

  // Main chat uses fetch rather than api.js. A preflight denial has no accepted receipt or SSE error.
  for (const failure of [
    { code: 1, kind: 'CONFLICT', reason: 'no_credits', message: '主对话余额不足，请充值' },
    { code: 4011, kind: 'NOT_CONNECTED', reason: 'platform_ai', message: '主对话请先登录' },
    { code: 1, kind: 'NETWORK', message: '无法确认余额，请稍后重试' },
  ]) {
    const before = await page.evaluate(() => window.chatPosts.length)
    await page.evaluate(async failure => {
      const priorNow = Date.now
      Date.now = () => priorNow() + 61000
      window.paidConnected = true
      window.paidUi.noteAccountConnected(true)
      const priorFetch = window.fetch
      window.fetch = (url, options) => String(url).endsWith('/api/agent/chat')
        ? Promise.resolve(new Response(JSON.stringify(failure), { headers: { 'Content-Type': 'application/json' } }))
        : priorFetch(url, options)
      document.querySelector('.chat-input-rich').textContent = '主对话受理前拒绝后保留的草稿'
      window.preflightSubmission = window.chatState.handleSubmit('steer')
    }, failure)
    const selector = failure.code === 4011 ? '.awd-login-mask' : '.recharge-dialog'
    if (failure.kind !== 'NETWORK') await page.waitForSelector(selector, { visible: true })
    await page.evaluate(() => window.preflightSubmission)
    assert.equal(await page.evaluate(() => window.chatPosts.length), before, 'no accepted synthetic chat POST')
    assert.equal(await page.evaluate(() => window.paidConnections.at(-1).aborted), true, 'the rejected request closes only its newly created SSE connection')
    assert.match(await page.evaluate(() => window.chatState.bubbles.at(-1).content), new RegExp(failure.message))
    assert.equal(await page.evaluate(() => window.chatState.isStreaming), false)
    assert.equal(await page.$eval('.chat-input-rich', el => el.textContent), '主对话受理前拒绝后保留的草稿')
    if (failure.kind !== 'NETWORK') await page.click(failure.code === 4011 ? '.awd-login-close' : '.recharge-dialog .awd-btn-secondary')
    else assert.equal(await page.$('.awd-login-mask, .recharge-dialog'), null)
  }
  await page.evaluate(() => { const priorNow = Date.now; Date.now = () => priorNow() + 61000 })

  // An expired account is also handled in place and retains the local session.
  await page.evaluate(async () => {
    window.paidFailure = { code: 1, gatewayKind: 'UNAUTHORIZED', message: '账户已失效，请重新登录' }
    window.expiredLookup = window.paidApi.lookupWritingSelection(1, { kind: 'LAW', text: '合成法律' }).catch(error => { window.expiredError = error.accountRequired })
  })
  await page.waitForSelector('.awd-login-mask', { visible: true })
  await page.type('.awd-login-mask input[type="tel"]', '13800000000')
  await page.type('.awd-login-mask input[autocomplete="one-time-code"]', '000000')
  for (const mark of await page.$$('.awd-login-mask .consent-mark')) await mark.click()
  await page.click('.awd-login-mask .unlock-btn')
  await page.waitForFunction(() => document.querySelector('.unlock-error')?.textContent.includes('合成验证码错误'))
  assert.equal(await page.$eval('.awd-login-mask .unlock-btn', el => el.disabled), false)
  assert.equal(await page.evaluate(() => window.invalidLoginCalls), 1)
  assert.equal((await page.$$('.awd-login-mask')).length, 1, 'invalid code must not create or wait for another login dialog')
  await page.screenshot({ path: `${artifacts}/invalid-code.png` })
  await page.click('.awd-login-close')
  await page.evaluate(() => window.expiredLookup)
  assert.equal(await page.evaluate(() => window.expiredError), true)
  assert.equal(await page.evaluate(() => uni.getStorageSync('checkba_session_id')), 'synthetic-session')
  assert.deepEqual(remoteRequests, [], 'no external HTTP requests are allowed')
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ passed: true, artifacts, requests: 'synthetic only', assertions: ['AI login blocks send and preserves draft', 'wallet error opens recharge', 'native amount input and payment button work', 'synthetic QR renders', 'close removes dialog', 'SSE tool denial opens recharge and preserves streaming', 'SSE wallet failure opens recharge', 'chat HTTP preflight rejects without receipt and preserves draft', 'unknown balance preserves server error without a payment prompt', 'expired account prompts login without clearing session', 'invalid verification code keeps one dialog and restores submit button'] }))
} finally {
  await browser.close()
  await server.close()
}
