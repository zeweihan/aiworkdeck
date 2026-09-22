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
  assert.equal(await page.$('.turn-activity'), null, 'no fixed classification bar')
  assert.equal(await page.$$eval('.message-list .process-card', els => els.length), 0, 'historical execution starts collapsed')
  assert.ok(await page.$eval('.message-list', el => el.textContent.includes('第一部分') && el.textContent.includes('第二部分')), 'all historical reply segments survive')
  const summary = '.message-row.assistant .activity-summary'
  await page.click(summary)
  assert.ok(await visible('.process-card'), 'history can be expanded')
  await page.click('.tool-row')
  assert.ok(await visible('.tool-output'), 'tool output remains inspectable')
  await page.click(summary)
  for (const width of [320, 350, 420, 640]) {
    await page.setViewport({ width, height: 860 })
    assert.ok(await page.$eval('.message-list', el => el.scrollWidth <= el.clientWidth + 1), `no horizontal overflow at ${width}px`)
  }
  await page.setViewport({ width: 420, height: 860 })
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
  assert.ok(await visible('.return-to-latest .back-to-latest'))
  await page.click('.return-to-latest .back-to-latest')
  await wait(() => window.chatState.followLatest)
  // Interactive question and approval remain in the transcript, and stay reachable after
  // the conversation scrolls past them.
  const atBottom = () => wait(() => { const el = document.querySelector('.message-list'); return el.scrollHeight - el.clientHeight - el.scrollTop < 5 })
  for (const [kind, label] of [['question', '待你回答'], ['approval', '待审批']]) {
    await page.evaluate(kind => window.loadFixture(kind), kind)
    await wait(() => document.querySelector('[data-chat-attention]'))
    assert.ok(await page.$eval('.message-list', el => el.textContent.includes('30日') || el.textContent.includes('按此推进')))
    await atBottom()
    assert.equal(await page.$('.attention-locator'), null, `${kind}: no locator while the card is on screen`)
    await page.evaluate(() => { const el = document.querySelector('.message-list'); el.scrollTop = 0; el.dispatchEvent(new Event('scroll')) })
    await wait(() => document.querySelector('.attention-locator'))
    assert.ok(await page.$eval('.attention-locator', el => el.textContent.trim()).then(text => text.includes(label) && text.includes('1')), `${kind}: locator names what is waiting`)
    await page.click('.attention-locator')
    assert.ok(await page.$eval('[data-chat-attention]', el => {
      const view = document.querySelector('.message-list').getBoundingClientRect()
      const rect = el.getBoundingClientRect()
      return rect.top < view.bottom && rect.bottom > view.top
    }), `${kind}: clicking brings the card into view`)
    assert.ok(await page.$('[data-chat-attention].chat-attention-flash'), `${kind}: the card is highlighted on arrival`)
    await wait(() => !document.querySelector('.attention-locator'))
  }
  // Feed the real SSE handler, then answer a question: continuing must retain tasks.
  await page.evaluate(() => window.loadFixture('question'))
  await page.evaluate(() => {
    const todos = window.chatState.bubbles.at(-1).planTodos
    window.sseController.enqueue(new TextEncoder().encode(`event: plan_update\ndata: ${JSON.stringify({ todos })}\n\n`))
  })
  await page.click('.question-card .btn-option')
  await wait(() => window.chatState.bubbles.at(-1).isStreaming)
  const send = async (event, data) => page.evaluate(({ event, data }) => {
    window.sseController.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`))
  }, { event, data })
  await send('reasoning_delta', { content: '正在核对付款条款。' })
  await wait(() => document.querySelector('.thinking-card.is-working .body')?.textContent.includes('正在核对付款'))
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }])
  assert.equal(await page.$eval('.thinking-card.is-working .title', el => getComputedStyle(el).animationName), 'none')
  await page.emulateMediaFeatures([])
  await send('text_delta', { content: '先读原文。<process name="读取合同"><tool_code>read_document({})</tool_code></process>' })
  await wait(() => document.querySelector('.activity-summary.is-working'))
  assert.equal(await page.$('.thinking-card.is-working'), null, 'past thinking collapses when execution begins')
  await send('text_delta', { content: '<tool_output status="SUCCESS">付款期限30日</tool_output>' })
  await send('reasoning_delta', { content: '已经找到付款期限，正在整理答复。' })
  await wait(() => document.querySelector('.thinking-card.is-working'))
  assert.equal(await page.$('.activity-summary.is-working'), null, 'past tools collapse during new thinking')
  await send('text_delta', { content: '<final>建议明确验收起算日' })
  await wait(() => document.querySelector('.message-list').textContent.includes('建议明确验收起算日'))
  assert.equal(await page.$('.thinking-card.is-working'), null, 'answer delta arrives before closing tag and collapses thinking')
  await send('text_delta', { content: '。</final>' })
  await send('bubble_end', { status: 'finished' })
  await wait(() => !window.chatState.isStreaming)
  const order = await page.evaluate(() => { const els = [...[...document.querySelectorAll('.message-row.assistant')].at(-1).querySelector('.root-bubble-container').children]; return els.filter(e => e.matches('.thinking-card,.activity-entry,.main-content') && !e.textContent.startsWith('计划')).map(e => e.matches('.thinking-card') ? 'thinking' : e.matches('.main-content') ? 'text' : 'execution') })
  assert.deepEqual(order, ['thinking', 'text', 'execution', 'thinking', 'text'])
  // 「用到文档」按需展示（dev-board#728）。这里是唯一能证明 computed 真的接上了 v-if 的地方：
  // 判定本身有纯函数单测，而「判定 -> 模板」这一段接线只有真渲染看得见。
  const actions = () => page.evaluate(() =>
    !![...document.querySelectorAll('.message-row.assistant')].at(-1).querySelector('.message-actions'))
  assert.equal(await actions(), false, '一句回执下面不该挂插入/替换/导出')
  await page.evaluate(() => { window.chatState.bubbles.at(-1).content = '甲方应于本协议签署之日起十个工作日内，将标的股权对应的全部权利凭证交付乙方，并配合办理工商变更登记手续；逾期交付的，每逾期一日按转让价款的万分之五向乙方支付违约金，逾期超过三十日的，乙方有权解除本协议。' })
  await wait(() => [...document.querySelectorAll('.message-row.assistant')].at(-1).querySelector('.message-actions'))
  await page.evaluate(() => { window.chatState.bubbles.at(-1).documentEdited = true })
  await wait(() => ![...document.querySelectorAll('.message-row.assistant')].at(-1).querySelector('.message-actions'))
  // 插话的送达状态、两处对账、以及菜单停止（dev-board#779 K5 / K7）。
  // 这三件事都只在「AI 正在跑、用户又插了一句」这个真实形态下才成立，所以从
  // 回答问题进入生成中开始，一路用真的 handleSubmit / AgentInbox DOM 驱动。
  await page.evaluate(() => { window.inboxItems = []; window.nextReceiptState = 'applied' })
  await page.evaluate(() => window.loadFixture('question'))
  await wait(() => document.querySelector('.question-card .btn-option'))
  await page.click('.question-card .btn-option')
  await wait(() => window.chatState.isStreaming)
  const interject = (text) => page.evaluate(async (text) => {
    document.querySelector('.chat-input-rich').textContent = text
    await window.chatState.handleSubmit('steer')
  }, text)
  const lastReceipt = () => page.$$eval('.user-bubble', (els) =>
    els[els.length - 1]?.querySelector('.bubble-receipt')?.textContent.trim() || '')
  // 待处理区用的是 uni 的 @tap。这个夹具只跑 @vitejs/plugin-vue，没有 uni 的模板
  // 编译器把 tap 映射成 click，所以这里直接派发 tap——测的是处理函数的接线，
  // 而 tap→click 的映射由 uni 自己保证。
  const tap = (selector) => page.$eval(selector, (el) => el.dispatchEvent(new CustomEvent('tap', { bubbles: true })))

  await page.evaluate(() => { window.nextReceiptState = 'pending' })
  await interject('顺带核对一下签署页')
  await wait(() => window.chatState.pendingInbox.length === 1)
  const steered = await page.evaluate(() => window.chatState.pendingInbox[0].id)
  // 模型还没读到的插话：整条气泡淡一档 + 一行说明。数据一直在 SSE 里传，此前一处不渲染。
  assert.ok(await page.$('.user-bubble.is-unread'), 'pending interjection is visibly unread')
  assert.equal(await lastReceipt(), '尚未读取 · 立即调整', 'the badge says what it is waiting for')
  // 同一句话同时出现在对话流和输入框上方，没有关联的话第一次用的人会以为发重了：
  // 对话流留完整气泡，待处理区退成引用行 + 一个跳回去的入口。
  assert.ok(await page.$('.agent-inbox-row.is-quote .inbox-action.locate'), 'the queued row quotes the bubble in the transcript')
  await page.evaluate(() => { document.querySelector('.message-list').scrollTop = 0 })
  await tap('.agent-inbox-row .inbox-action.locate')
  assert.ok(await page.$('.message-row.chat-inbox-flash'), 'locating highlights that very bubble')

  // 被模型读取后转为「已送达」，淡态收掉。
  await send('input_applied', { messageId: steered, runId: 'fixture-run', sequence: 2, message: '顺带核对一下签署页' })
  await page.evaluate((id) => { window.inboxItems = window.inboxItems.map(i => i.id === id ? { ...i, state: 'applied' } : i) }, steered)
  await wait(() => window.chatState.bubbles.some(b => b.receiptState === 'applied' && b.wasPendingInbox))
  assert.equal(await page.$('.user-bubble.is-unread'), null, 'a read interjection is no longer dimmed')
  assert.ok(await page.$$eval('.bubble-receipt', els => els.some(el => el.textContent.trim() === '已送达')), 'delivery is acknowledged once')

  // 排队档（followUpMode=queue）说的是另一句话，判据同样只从 submissionMode 取。
  await page.evaluate(() => {
    const bubble = window.chatState.bubbles.filter(b => b.role === 'USER').at(-1)
    bubble.receiptState = 'pending'; bubble.submissionMode = 'queue'
  })
  await wait(() => [...document.querySelectorAll('.bubble-receipt')].some(el => el.textContent.trim() === '排队中'))
  await page.evaluate(() => {
    const bubble = window.chatState.bubbles.filter(b => b.role === 'USER').at(-1)
    bubble.receiptState = 'applied'; bubble.submissionMode = 'steer'
  })

  // 删掉待处理项，对话流里那条气泡必须跟着消失——此前删了之后气泡还在，更乱。
  await interject('再补一句：附件三也要看')
  await wait(() => window.chatState.pendingInbox.length === 1)
  const doomed = await page.evaluate(() => window.chatState.pendingInbox[0].id)
  assert.equal(await page.evaluate((id) => window.chatState.bubbles.filter(b => b.inboxMessageId === id).length, doomed), 1)
  await tap('.agent-inbox-row .inbox-action.danger')
  await wait(() => window.chatState.pendingInbox.length === 0)
  assert.equal(await page.evaluate((id) => window.chatState.bubbles.filter(b => b.inboxMessageId === id).length, doomed), 0,
    'deleting a pending message also removes its bubble')
  assert.ok(await page.evaluate(() => window.chatState.bubbles.some(b => b.receiptState === 'applied' && b.wasPendingInbox)),
    'the interjection the model already read survives the cleanup')

  // 菜单「停止当前任务」必须真能停下 AI（K5）：此前它只遍历后台任务，于是最常见的
  // 「只有 AI 在生成」点了毫无反应，模型照样在跑。
  const cancelsBefore = await page.evaluate(() => window.cancelCalls || 0)
  assert.equal(await page.evaluate(() => window.chat.menuState().aiRunning), true, 'the menu item is enabled while generating')
  assert.equal(await page.evaluate(() => window.chat.menuStop()), 1, 'menuStop reports it stopped the AI turn')
  assert.equal(await page.evaluate(() => window.chatState.isStreaming), false)
  assert.equal(await page.evaluate(() => window.cancelCalls || 0), cancelsBefore + 1, 'menuStop really posts the cancel')

  await page.evaluate(() => window.loadFixture('single'))
  await page.screenshot({ path: '/tmp/awd-chat-646-light.png' })
  await page.focus('.thinking-card .header')
  await page.keyboard.press('Enter')
  assert.ok(await visible('.thinking-card .body'), 'keyboard can expand thinking')
  await page.keyboard.press('Enter')
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'))
  await page.screenshot({ path: '/tmp/awd-chat-646-dark.png' })
  const colors = await page.evaluate(() => ({ answer: getComputedStyle(document.querySelector('.main-content .markdown-body')).color, thought: getComputedStyle(document.querySelector('.thinking-card .title')).color }))
  assert.notEqual(colors.answer, colors.thought, 'answer and process have distinct contrast')
  await page.emulateMediaFeatures([{ name: 'prefers-reduced-motion', value: 'reduce' }])
  await page.goto('http://127.0.0.1:5186/?lang=en-US')
  await wait(() => window.ready)
  assert.ok(await page.$eval('.message-list', el => el.textContent.includes('Ran 17 operations')), 'English controls interpolate')
  assert.deepEqual(errors, [], 'browser runtime errors')
  console.log('PASS: chronological history/live stream, automatic collapse, manual disclosures, output inspection, scrolling, attention cards and their locator, on-demand use-in-document actions, interjection receipts and inbox/transcript reconciliation, menu stop, narrow widths, themes, English')
} catch (error) {
  console.error('BROWSER ERRORS', errors)
  console.error(await page.evaluate(() => document.querySelector('.message-row.assistant:last-child')?.textContent))
  throw error
} finally {
  await browser.close()
  await server.close()
}
