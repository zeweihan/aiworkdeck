// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import puppeteer from 'puppeteer-core'
const root = fileURLToPath(new URL('../../', import.meta.url))
const fixture = fileURLToPath(new URL('./', import.meta.url))
const source = readFileSync(`${root}src/App.vue`, 'utf8')
const tokens = source.slice(source.indexOf("html,\nhtml[data-theme='light']"), source.indexOf('</style>', source.indexOf("html,\nhtml[data-theme='light']")))
const server = await createServer({ configFile: false, root: fixture, plugins: [vue()], resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] }, server: { host: '127.0.0.1', port: 5186, strictPort: true, fs: { allow: [root, fileURLToPath(new URL('../../node_modules', import.meta.url))] } } })
await server.listen()
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await browser.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))
const visible = selector => page.$eval(selector, el => !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length))
const wait = predicate => page.waitForFunction(predicate)
try {
  await page.setViewport({ width: 420, height: 860 })
  await page.goto('http://127.0.0.1:5186')
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app {margin:0;height:100%;font-family: -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;} *{box-sizing:border-box;} view,scroll-view{display:block;} text{display:inline;} button{font-family:inherit;} #app{height:100dvh;}` })
  await wait(() => window.ready)
  assert.equal(await page.$('.activity-panel'), null, 'details start collapsed')
  assert.equal(await page.$$eval('.message-list .process-card', els => els.length), 0, 'tool log does not flood transcript')
  assert.equal(await page.$$eval('.turn-activity-link', els => els.length), 2, 'one detail entry per turn')
  assert.ok(await page.$eval('.message-list', el => el.textContent.includes('第一部分') && el.textContent.includes('第二部分')), 'multiple finals survive history recovery')
  await page.click('.activity-trigger')
  await wait(() => document.querySelector('.activity-panel'))
  assert.ok(await page.$eval('.panel-body', el => el.textContent.includes('阅读合同及附件')), 'historical JSON tasks recovered')
  await page.keyboard.press('Escape')
  await wait(() => !document.querySelector('.activity-panel'))
  await page.click('.activity-trigger:nth-child(3)')
  await page.click('.panel-body .tool-row')
  assert.ok(await visible('.tool-output'), 'tool output remains inspectable')
  await page.keyboard.press('Escape')
  // Follow incremental text at the bottom; scrolling upwards opts out.
  await page.evaluate(() => window.chatState.scrollToBottom())
  await wait(() => { const el = document.querySelector('.message-list'); return el.scrollHeight - el.clientHeight - el.scrollTop < 5 })
  await page.evaluate(() => { window.chatState.bubbles.at(-1).content += '\n\n' + '新输出\n\n'.repeat(30) })
  await wait(() => { const el = document.querySelector('.message-list'); return el.scrollHeight - el.clientHeight - el.scrollTop < 5 })
  await page.evaluate(() => { const el = document.querySelector('.message-list'); el.scrollTop = 120; el.dispatchEvent(new Event('scroll')) })
  await wait(() => window.chatState.followLatest === false)
  const before = await page.$eval('.message-list', el => el.scrollTop)
  await page.evaluate(() => { window.chatState.bubbles.at(-1).content += '\n\n' + '继续输出\n\n'.repeat(20) })
  await page.waitForFunction(() => document.querySelector('.message-list').textContent.includes('继续输出'))
  assert.equal(await page.$eval('.message-list', el => el.scrollTop), before, 'stream must not steal reading position')
  assert.ok(await visible('.return-to-latest button'))
  await page.click('.return-to-latest button')
  await wait(() => window.chatState.followLatest)
  // Locator jumps to the answer start and closes its overlay.
  await page.click('.activity-trigger:nth-child(4)')
  await wait(() => document.querySelector('.location-actions button'))
  await page.click('.location-actions button')
  await wait(() => !document.querySelector('.activity-panel'))
  const targetOffset = await page.evaluate(() => document.querySelector('[data-chat-answer]').getBoundingClientRect().top - document.querySelector('.message-list').getBoundingClientRect().top)
  assert.ok(Math.abs(targetOffset - 12) <= 2, `answer locator offset: ${targetOffset}`)
  // Question and approval stay visible/actionable, outside the activity drawer.
  for (const kind of ['question', 'approval']) {
    await page.evaluate(kind => window.loadFixture(kind), kind)
    await wait(() => document.querySelector('[data-chat-attention]'))
    assert.ok(await page.$eval('.message-list', el => el.textContent.includes('30日') || el.textContent.includes('按此推进')))
    assert.equal(await page.$('.activity-panel'), null)
    await page.click('.attention-trigger')
    const attentionOffset = await page.evaluate(() => document.querySelector('[data-chat-attention]').getBoundingClientRect().top - document.querySelector('.message-list').getBoundingClientRect().top)
    assert.ok(attentionOffset >= 0 && attentionOffset < 500, `attention not visible: ${attentionOffset}`)
  }
  // Feed the real SSE handler, then answer a question: continuing must retain tasks.
  await page.evaluate(() => window.loadFixture('question'))
  await page.evaluate(() => {
    const todos = window.chatState.bubbles.at(-1).planTodos
    window.sseController.enqueue(new TextEncoder().encode(`event: plan_update\ndata: ${JSON.stringify({ todos })}\n\n`))
  })
  await page.click('.question-card .btn-option')
  await wait(() => window.chatState.bubbles.at(-1).isStreaming)
  assert.ok(await page.$eval('.turn-activity', el => el.textContent.includes('任务 1/3')), 'continue keeps server task state')
  assert.ok(await page.$eval('.activity-hint', el => el.textContent.includes('正在核对付款')), 'live current activity is visible')
  // Terminal state is not mistaken for success, even if a partial reply exists.
  await page.evaluate(() => {
    window.sseController.enqueue(new TextEncoder().encode('event: error\ndata: {"message":"Synthetic error"}\n\n'))
  })
  await wait(() => document.querySelector('.status-text')?.textContent.includes('执行出错'))
  assert.ok(await page.$('.message-list'), 'error does not blank the transcript')
  await page.evaluate(() => window.loadFixture('approval'))
  for (const width of [320, 420, 640]) {
    await page.setViewport({ width, height: 860 })
    await page.click('.activity-trigger:nth-child(3)')
    await wait(() => document.querySelector('.activity-panel'))
    const size = await page.$eval('.activity-panel', el => ({ width: el.getBoundingClientRect().width, height: el.getBoundingClientRect().height, right: el.getBoundingClientRect().right }))
    assert.ok(size.width <= width && size.right <= width + 1 && size.height <= 430, JSON.stringify(size))
    await page.keyboard.press('Escape')
  }
  await page.setViewport({ width: 420, height: 860 })
  await page.evaluate(() => window.loadFixture('single'))
  await page.screenshot({ path: '/tmp/awd-chat-presentation-collapsed.png' })
  await page.click('.activity-trigger')
  await wait(() => getComputedStyle(document.querySelector('.activity-panel')).opacity === '1')
  await page.screenshot({ path: '/tmp/awd-chat-presentation-tasks.png' })
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'))
  await page.screenshot({ path: '/tmp/awd-chat-presentation-dark.png' })
  await page.goto('http://127.0.0.1:5186/?lang=en-US')
  await wait(() => window.ready)
  assert.ok(await page.$eval('.turn-activity', el => el.textContent.includes('Tasks 1/3') && el.textContent.includes('Turn 2/2')), 'English controls and interpolation')
  assert.deepEqual(errors, [], 'browser runtime errors')
  console.log('PASS: history, grouping, tasks, disclosures, scrolling, attention cards, 320/420/640px, dark theme')
} finally {
  await browser.close()
  await server.close()
}
