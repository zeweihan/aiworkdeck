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
// 复制用例要真的读回剪贴板（只断言「我们调了接口」证明不了复制走的是什么）
await browser.defaultBrowserContext().overridePermissions('http://127.0.0.1:5186', ['clipboard-read', 'clipboard-write'])
const shots = process.env.AWD_SHOTS || '/tmp'
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
  // 这一条 steer 插话要活到停止之后：K22 要看的就是「轮次没了，它还挂在那里」。
  await interject('停之前再插一句')
  await wait(() => window.chatState.pendingInbox.length === 1)
  assert.equal(await page.evaluate(() => window.chatState.pendingInbox[0].submissionMode), 'steer')
  // 轮次还在跑时 steer 项不出「立即发送」：它已经排在下一个工具边界上了，
  // 再给一个按钮只会让人以为点了才发。
  assert.equal(await page.$('.agent-inbox-row .inbox-action.primary'), null,
    'a steer item needs no send-now button while a run is consuming it')
  assert.equal(await page.$('.agent-inbox-idle'), null, 'no idle notice while the run is live')

  const cancelsBefore = await page.evaluate(() => window.cancelCalls || 0)
  assert.equal(await page.evaluate(() => window.chat.menuState().aiRunning), true, 'the menu item is enabled while generating')
  assert.equal(await page.evaluate(() => window.chat.menuStop()), 1, 'menuStop reports it stopped the AI turn')
  assert.equal(await page.evaluate(() => window.chatState.isStreaming), false)
  assert.equal(await page.evaluate(() => window.cancelCalls || 0), cancelsBefore + 1, 'menuStop really posts the cancel')

  // K22（dev-board#802）：轮次被停掉之后，这条 steer 再没有人会来 claim 它。
  // 改造前界面上只剩编辑/上移/下移/删除，它看着像还会被处理，其实永远不会。
  await wait(() => window.chatState.pendingInbox.length === 1 && window.chatState.isStreaming === false)
  await wait(() => document.querySelector('.agent-inbox-row .inbox-action.primary'))
  assert.equal(await page.$eval('.agent-inbox-row .inbox-action.primary', el => el.textContent.trim()), '立即发送',
    'a stranded steer item gets a way out')
  assert.ok(await page.$eval('.agent-inbox-idle', el => el.textContent.includes('不会自动发出')),
    'the box says plainly that nothing will pick these up on its own')

  // 回退按钮的可用性（dev-board#779 K1 / 审查 D-02）。回退要么按数据库主键定位（历史回灌的
  // 气泡有），要么按 clientRequestId（本次会话内发出的气泡有）；两个都没有的气泡点下去注定
  // 失败——改造前那正是「刚发现自己问错了」的时刻，按钮看着能点、点了只弹一句服务器内部错误。
  await page.evaluate(() => window.loadFixture('single'))
  const rollbackBtn = '.message-row.user .rollback-btn'
  assert.ok(await visible(rollbackBtn), '历史回灌的用户气泡有主键，按钮可用')
  assert.equal(await page.$(`${rollbackBtn}.is-disabled`), null, '有主键就不该置灰')
  // 确认框的文案。这里走 setup 状态而不是真点按钮：本夹具是纯 vite + @vitejs/plugin-vue，
  // 没有 uni 插件，模板里的 @tap 不会被编译成 click，真点没有反应（真点这条路由 K1 的
  // 后端联调 e2e 覆盖）。这里要钉住的是「弹窗说了什么」。
  await page.evaluate(() => {
    const s = window.chatState
    s.openRollbackDialog(s.bubbles.find(b => b.role === 'USER'), 0)
  })
  await wait(() => document.querySelector('.awd-dialog'))
  const rollbackCopy = await page.$eval('.awd-dialog', el => el.innerText)
  // 回退不再销毁历史：先存分支再截断，所以不许再吓唬用户说「无法恢复」（dev-board#779 K1）
  assert.ok(!rollbackCopy.includes('无法恢复'), '回退确认框不该再说无法恢复')
  assert.ok(rollbackCopy.includes('存为分支') && rollbackCopy.includes('回退前存档'), '要说清先存档再回退')
  await page.screenshot({ path: '/tmp/awd-chat-779-rollback-dialog.png' })
  await page.evaluate(() => window.chatState.cancelRollback())
  await wait(() => !document.querySelector('.awd-dialog'))

  // 「从此分叉」（dev-board#779 K18 / 审查 D-06、F4）：回退旁边的非破坏入口。
  // 改造前普通对话里想换个思路只有回退一条路，而回退会把这条之后的全部对话删掉——
  // 律师要在一份合同上试两种改法时，试第二种就等于销毁第一种的全过程。
  const branchBtn = '.message-row.user .branch-btn'
  assert.ok(await visible(branchBtn), '用户气泡上有「从此分叉」入口')
  assert.equal(await page.$(`${branchBtn}.is-disabled`), null, '有定位键就不该置灰')
  const [branchTitle, rollbackTitle] = await page.evaluate((b, r) => [
    document.querySelector(b).getAttribute('title'),
    document.querySelector(r).getAttribute('title')
  ], branchBtn, rollbackBtn)
  // 两个按钮的区别必须在 title 里说清：分叉不改当前对话，回退会改
  assert.ok(branchTitle.includes('当前对话保持不变'), `分叉要说明它不动当前对话：${branchTitle}`)
  assert.ok(rollbackTitle.includes('移除'), `回退要说明它会改当前对话：${rollbackTitle}`)
  // 点了之后只 emit，由宿主去 fork 并切会话；这里钉住载荷带的是定位键而不是气泡自造 id
  const branchPayload = await page.evaluate(() => {
    let captured = null
    const s = window.chatState
    const vm = window.chat.$
    const originalEmit = vm.emit
    vm.emit = (event, ...args) => { if (event === 'fork-from-message') captured = args[0]; return originalEmit(event, ...args) }
    s.branchFromMessage(s.bubbles.find(b => b.role === 'USER'))
    vm.emit = originalEmit
    return captured
  })
  assert.ok(branchPayload, '分叉必须把动作交给宿主（fork + 切会话）')
  assert.equal(branchPayload.messageId, 'u1', '带的是落库主键，不是前端自造的气泡 id')
  assert.ok(String(branchPayload.conversationId || '').length > 0, '带上源会话 id')
  assert.equal(await page.$('.awd-dialog'), null, '分叉是非破坏的，不弹确认框')
  await page.screenshot({ path: '/tmp/awd-chat-798-branch-button.png' })

  await page.evaluate(() => {
    const user = window.chatState.bubbles.find(b => b.role === 'USER')
    user.dbMessageId = null
    user.id = 'msg-1790065781790-1'
    user.clientRequestId = ''
  })
  await wait(() => document.querySelector('.message-row.user .rollback-btn.is-disabled'))
  // 分叉与回退用的是同一套定位键，所以置灰也必须同步——否则用户会在同一条气泡上
  // 看到「回退不可用、分叉可用」，点了分叉却照样失败
  assert.ok(await page.$(`${branchBtn}.is-disabled`), '拿不到定位键时分叉一起置灰')
  assert.ok(await page.$eval(branchBtn, el => (el.getAttribute('title') || '').length > 0),
    '分叉置灰同样要说明原因')
  await page.evaluate(() => {
    const s = window.chatState
    s.openRollbackDialog(s.bubbles.find(b => b.role === 'USER'), 0)
  })
  assert.equal(await page.$('.awd-dialog'), null, '拿不到定位键时打不开确认框')
  assert.ok(await page.$eval(rollbackBtn, el => (el.getAttribute('title') || '').length > 0),
    '置灰要说明原因，不能只是点不动')

  // 钢琴键会话导航（dev-board#791 K12）。数据源与跳转原语都是既有的，这里测的是
  // 「刻度 -> 当前轮 -> 跳转」这一整条接线，它只有在真渲染里看得见。
  await page.setViewport({ width: 420, height: 860 })
  await page.evaluate(() => window.loadFixture('single'))
  await wait(() => window.chatState.chatTurns.length === 1)
  assert.equal(await page.$('.chat-turn-rail'), null, '单轮会话不渲染导航列')
  await page.evaluate(() => window.loadManyTurns(200))
  await wait(() => window.chatState.chatTurns.length === 200)
  await wait(() => document.querySelectorAll('.chat-turn-rail .rail-tick').length === 200)
  assert.equal(await page.$$eval('.conversation-turn[data-turn-key]', els => els.length), 200, '每一轮都可寻址')
  assert.equal(await page.$$eval('.chat-turn-rail .rail-tick', els => els.length),
    await page.evaluate(() => window.chatState.bubbles.filter(b => b.role === 'USER').length), '刻度数 = 用户提问轮数')
  assert.ok(await page.$eval('.message-list', el => el.scrollWidth <= el.clientWidth + 1), '导航列不撑出横向滚动')
  // 当前轮跟随滚动：IntersectionObserver 只观察轮级元素。
  await page.evaluate(() => { document.querySelector('.message-list').scrollTop = 0 })
  await wait(() => window.chatState.activeTurnKey === 'mu1')
  await page.evaluate(() => { const el = document.querySelector('.message-list'); el.scrollTop = el.scrollHeight / 2 })
  await wait(() => window.chatState.activeTurnKey && window.chatState.activeTurnKey !== 'mu1')
  // 静息刻度 + 展开浮层：浮层覆盖在消息区上，不挤压消息流（窄面板同理，见下）。
  const listWidthBefore = await page.$eval('.message-list', el => el.getBoundingClientRect().width)
  await page.hover('.chat-turn-rail')
  await wait(() => document.querySelector('.rail-panel'))
  assert.equal(await page.$$eval('.rail-panel .rail-item', els => els.length), 200, '浮层逐轮列出提问')
  assert.ok(await page.$eval('.rail-panel .rail-item.is-active', el => el.textContent.trim().length > 0), '当前轮在浮层里被标出')
  assert.equal(await page.$eval('.message-list', el => el.getBoundingClientRect().width), listWidthBefore, '浮层覆盖而不挤压消息流')
  await page.screenshot({ path: '/Users/zewei/aiworkdeck-qa/reports/ai-chat-audit-2026-09-22/k12/shots/k12-rail-expanded.png' })
  // 点第 N 格：那一轮进入视口顶部（对齐口径与 navigateToMessage 一致：列表顶 12px）。
  const jump = await page.evaluate(() => {
    const t0 = performance.now()
    document.querySelector('.rail-panel .rail-item[data-rail-key="mu120"]').click()
    const list = document.querySelector('.message-list')
    const row = document.querySelector('.conversation-turn[data-turn-key="mu120"]')
    // 读 rect 会强制同步布局，所以这个数字含样式+布局，不只是事件处理函数本身。
    const offset = row.getBoundingClientRect().top - list.getBoundingClientRect().top
    return { ms: performance.now() - t0, offset }
  })
  assert.ok(Math.abs(jump.offset - 12) < 4, `点击把该轮送到视口顶部（实测偏移 ${jump.offset}）`)
  console.log(`  200 轮会话单次跳转耗时 ${jump.ms.toFixed(2)}ms`)
  assert.ok(jump.ms < 100, `跳转必须在一两帧内完成（实测 ${jump.ms.toFixed(2)}ms）`)
  // 滚动跟随的代价：逐帧滚 20 步，量最坏的一帧。IntersectionObserver 在帧边界才跑，
  // 同步循环量不到它，所以必须一帧一步。observer 只挂轮级元素（200 个），改成挂每条
  // 消息或改回 scroll 里逐轮量 rect，这个数字会立刻爆掉。
  const follow = await page.evaluate(async () => {
    const list = document.querySelector('.message-list')
    const step = list.scrollHeight / 24
    let worst = 0
    let prev = performance.now()
    for (let i = 1; i <= 20; i += 1) {
      list.scrollTop = step * i
      await new Promise(resolve => requestAnimationFrame(resolve))
      const now = performance.now()
      worst = Math.max(worst, now - prev)
      prev = now
    }
    return worst
  })
  console.log(`  200 轮会话滚动跟随最坏一帧 ${follow.toFixed(2)}ms`)
  assert.ok(follow < 100, `滚动跟随不得整片掉帧（实测最坏一帧 ${follow.toFixed(2)}ms）`)
  // 键盘可达：Tab 到导航列 -> 上下箭头选轮 -> Enter 跳转。
  // 先把指针移开：悬停展开与键盘展开是同一个 open，指针不走的话光标停在刚点过的那一格。
  await page.mouse.move(10, 10)
  await wait(() => !document.querySelector('.rail-panel'))
  await page.evaluate(() => { document.querySelector('.message-list').scrollTop = 0 })
  await wait(() => window.chatState.activeTurnKey === 'mu1')
  await page.focus('.chat-turn-rail')
  await wait(() => document.querySelector('.rail-panel'))
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('ArrowDown')
  await page.keyboard.press('Enter')
  await wait(() => window.chatState.activeTurnKey === 'mu3')
  assert.ok(await page.evaluate(() => {
    const list = document.querySelector('.message-list')
    const row = document.querySelector('.conversation-turn[data-turn-key="mu3"]')
    return Math.abs(row.getBoundingClientRect().top - list.getBoundingClientRect().top - 12) < 4
  }), 'Enter 真的跳过去了')
  await page.keyboard.press('Escape')
  await wait(() => !document.querySelector('.rail-panel'))
  await page.screenshot({ path: '/Users/zewei/aiworkdeck-qa/reports/ai-chat-audit-2026-09-22/k12/shots/k12-rail-resting.png' })
  // 窄面板（<300px）：静息刻度仍在，浮层收窄后仍整块落在消息区内。
  await page.setViewport({ width: 280, height: 860 })
  await wait(() => document.querySelectorAll('.chat-turn-rail .rail-tick').length === 200)
  assert.ok(await visible('.chat-turn-rail'), '窄面板仍显示静息刻度')
  await page.hover('.chat-turn-rail')
  await wait(() => document.querySelector('.rail-panel'))
  assert.ok(await page.evaluate(() => {
    const area = document.querySelector('.message-area').getBoundingClientRect()
    const panel = document.querySelector('.rail-panel').getBoundingClientRect()
    return panel.left >= area.left - 1 && panel.right <= area.right + 1 && panel.width < area.width
  }), '窄面板里浮层收在消息区内')
  await page.setViewport({ width: 420, height: 860 })
  await page.mouse.move(10, 10)
  await wait(() => !document.querySelector('.rail-panel'))

  // ---- 复制（dev-board#790 / 审查 F2、F6、D-07）----
  // 全仓此前一处剪贴板调用都没有，而复制是对话类产品里点击率最高的那颗按钮。
  // 无头 Chrome 读不回剪贴板（异步 API 恒 NotAllowedError，execCommand('paste') 也被拒，
  // 见夹具 setClipboardData 的注释），所以验的是「组件算出来交给平台的是哪段文字」+
  // 「平台确实收下了」（成功分支走到、提示为已复制）。真机粘贴留给走查。
  const copyAndRead = async (selector, index = 0) => {
    await page.evaluate(() => { window.copiedText = ''; window.lastToast = '' })
    await page.$$eval(selector, (els, i) => els[i].click(), index)
    await wait(() => window.copiedText && window.lastToast)
    const [text, toast] = await page.evaluate(() => [window.copiedText, window.lastToast])
    assert.equal(toast, '已复制', `${selector}[${index}]：平台收下了才提示已复制`)
    return text
  }
  await page.evaluate(() => window.loadFixture('single'))
  await wait(() => document.querySelector('.message-row.assistant .msg-copy-btn'))
  // 关键的一条：复制不受「用到文档」那条判据门控。恰恰是 AI 刚改过文档的那一轮
  // （documentEdited=true，整组按钮消失），用户最想把修改说明拷进邮件。
  await page.evaluate(() => { window.chatState.bubbles.at(-1).documentEdited = true })
  await wait(() => ![...document.querySelectorAll('.message-row.assistant')].at(-1).querySelector('.message-actions'))
  assert.ok(await page.$('.message-row.assistant .msg-copy-btn'), '改过文档的那一轮仍然有复制键')
  const copiedAnswer = await copyAndRead('.message-row.assistant .msg-copy-btn')
  assert.ok(copiedAnswer.includes('第一部分：付款安排已核对。'), `复制的是这条回答的正文，实际：${copiedAnswer}`)
  assert.ok(!/<\/?(final|thinking|process|tool_code)/.test(copiedAnswer), '协议 XML 不许被复制走')

  // 工具卡：调用与输出各一颗 12px 图标，点了不该把刚展开的输出折叠回去。
  await page.click('.message-row.assistant .activity-summary')
  await wait(() => document.querySelector('.tool-copy-btn'))
  assert.equal(await page.$$eval('.tool-row', els => els[0].querySelectorAll('.tool-copy-btn').length), 2, '调用与输出各一颗')
  assert.equal(await copyAndRead('.tool-copy-btn', 0), 'read_document({"fileId":1})', '复制调用拿的是原样调用串')
  assert.equal(await copyAndRead('.tool-copy-btn', 1), '已核对条款 1，付款期限为30日。', '复制输出拿的是工具原始输出')
  assert.equal(await page.$('.tool-output'), null, '复制不该顺手把输出折叠状态弄翻')

  // 正文里的代码块：流式期每帧重写整段 v-html，手工框选会被当场清掉，这里必须有按钮。
  await page.evaluate(() => { window.chatState.bubbles.at(-1).content += '\n\n```\n第三条 违约金上限为合同总价的 20%。\n```\n' })
  await wait(() => document.querySelector('.md-copy-btn'))
  assert.equal((await copyAndRead('.md-copy-btn')).trim(), '第三条 违约金上限为合同总价的 20%。', '代码块复制拿的是块内原文')

  // ---- 运行状态条：工具名 + 秒数（dev-board#792 / 审查 F5）----
  // 「正在执行 3 项操作」分不清 AI 是在读合同、查企查查，还是卡在某个超时调用上。
  await page.evaluate(() => window.loadRunningFixture(12))
  const runningLabel = () => page.$eval('.activity-summary.is-working span', el => el.textContent.trim())
  await wait(() => document.querySelector('.activity-summary.is-working'))
  const firstLabel = await runningLabel()
  assert.ok(firstLabel.includes('读取文档'), `运行态要报当前工具名，实际：${firstLabel}`)
  assert.ok(/1[23] 秒/.test(firstLabel), `运行态要报已用秒数，实际：${firstLabel}`)
  assert.equal(await page.$('.message-row.assistant:last-child .msg-regen-btn'), null, '还在跑的时候不出重新生成')
  await page.screenshot({ path: `${shots}/k13-running-tool.png` })
  await wait(() => document.querySelector('.activity-summary.is-working span').textContent.includes('14 秒'))
  // 跑完了退回原来的计数文案，行为一字未变
  await page.evaluate(() => { window.chatState.bubbles.at(-1).processes[0].items[1].status = 'success' })
  await page.evaluate(() => { window.chatState.bubbles.at(-1).isStreaming = false })
  await wait(() => document.querySelector('.message-row.assistant:last-child .activity-summary').textContent.includes('已执行 2 项操作'))

  // ---- 本轮 token 用量一行（dev-board#792 / 审查 F3①）----
  assert.equal(await page.$('.status-bar-right'), null, '没有用量数据时不挂一个 0')
  await page.evaluate(() => { window.chatState.tokenUsage.totalTokens = 12345 })
  await wait(() => document.querySelector('.status-bar-right'))
  assert.ok(await page.$eval('.status-bar-right', el => el.textContent.includes('12,345') && el.textContent.includes('本轮')), '输入区上沿报本轮用量')

  // ---- 重新生成（dev-board#790 / 审查 D-07）----
  // 走的是和「回退到这条消息」同一条后端通道：先截断（K1 之后会先存档），再原样重问。
  await page.evaluate(() => { window.rollbackCalls = [] })
  await page.evaluate(() => window.loadFixture('long'))
  await wait(() => document.querySelector('.message-row.assistant:last-child .msg-regen-btn'))
  assert.equal(await page.$$eval('.msg-regen-btn', els => els.length), 1, '重新生成只给最新一条：对着中间某条点下去会静默毁掉后面好几轮')
  await page.click('.message-row.assistant:last-child .msg-regen-btn')
  await wait(() => document.querySelector('.rollback-warning-content'))
  assert.ok(await page.$eval('.awd-dialog-title', el => el.textContent.includes('重新生成')), '确认框说的是重新生成，不是回退')
  await page.screenshot({ path: `${shots}/k11-regenerate-confirm.png` })
  await tap('[data-rollback-confirm]')
  await wait(() => (window.rollbackCalls || []).length === 1)
  await wait(() => window.chatState.isStreaming)
  assert.ok(await page.evaluate(() => !document.querySelector('.message-list').textContent.includes('合同审查结果')), '旧回答已从对话流里撤下')
  assert.equal(await page.evaluate(() => window.chatState.bubbles.filter(b => b.role === 'USER').at(-1).content),
    '请给出完整的风险清单，并说明需要我确认的事项。', '原提问被原样重发')
  assert.equal(await page.evaluate(() => window.chatState.bubbles.filter(b => b.content === '请给出完整的风险清单，并说明需要我确认的事项。').length), 1,
    '重发不该在历史里留下两条一样的提问')
  assert.equal(await page.evaluate(() => document.querySelector('.chat-input-rich').textContent), '', '重新生成不碰输入框——用户此刻可能已经在里面打了别的')

  // ---- `@` 引用选择器（dev-board#794 K15）与输入框键位（#795 K16）----
  // 这一段全部走真按键：@ 触发靠的是 contenteditable 的 selection + input 事件，
  // 直接改 state 证明不了接线。
  await page.evaluate(() => { window.inboxItems = []; window.nextReceiptState = 'applied' })
  await page.evaluate(() => window.loadFixture('single'))
  const draftText = () => page.$eval('.chat-input-rich', el => el.innerText)
  // 标签以外的正文（`@股份` 那截清没清掉，只能这样量——标签自己也画着一个 @ 和文件名）
  const draftTextOutsideTags = () => page.$eval('.chat-input-rich', el => {
    const clone = el.cloneNode(true)
    clone.querySelectorAll('[data-file-id]').forEach(t => t.remove())
    return clone.textContent.replace(/ /g, ' ').trim()
  })
  const clearDraftDom = () => page.evaluate(() => {
    const el = document.querySelector('.chat-input-rich')
    el.innerHTML = ''
    el.dispatchEvent(new Event('input', { bubbles: true }))
  })

  await page.click('.chat-input-rich')
  await page.keyboard.type('@股份')
  await wait(() => document.querySelector('.mention-picker'))
  assert.deepEqual(await page.$$eval('.mention-picker .mp-name', els => els.map(e => e.textContent)),
    ['股份认购协议-附件清单.xlsx', '股份认购协议.docx'], '@ 弹出的是项目文件的模糊匹配')
  assert.equal(await page.$eval('.mention-picker .mp-item.is-active .mp-name', el => el.textContent), '股份认购协议-附件清单.xlsx')
  await page.screenshot({ path: `${shots}/k15-mention-picker.png` })
  await page.keyboard.press('ArrowDown')
  assert.equal(await page.$eval('.mention-picker .mp-item.is-active .mp-name', el => el.textContent), '股份认购协议.docx',
    '方向键在浮层里走，不去翻历史')
  await page.keyboard.press('Enter')
  await wait(() => !document.querySelector('.mention-picker'))
  assert.equal(await page.$eval('.chat-input-rich .context-tag-inline', el => el.getAttribute('title')), '股份认购协议.docx',
    '选中后插的是既有的内联标签')
  assert.equal(await page.evaluate(() => window.chatState.contextFiles.map(f => f.id).join(',')), '11',
    'contextFiles 跟着标签走')
  assert.equal(await draftTextOutsideTags(), '', '输入的 `@股份` 那一段被删掉，不会连同标签一起发出去')

  // Esc：浮层开着先收浮层，其次才轮到别的（否则第一下 Esc 会去停 AI 或清草稿）
  await page.keyboard.type(' @公司')
  await wait(() => document.querySelector('.mention-picker'))
  await page.keyboard.press('Escape')
  await wait(() => !document.querySelector('.mention-picker'))
  assert.ok((await draftText()).includes('@公司'), '收浮层不该顺手改掉用户打的字')

  // Esc 清草稿要按两次：清空会把附件标签一起带走，静默清掉就是销毁用户已经做的事
  await page.keyboard.press('Escape')
  assert.equal(await page.evaluate(() => window.lastToast), '再按一次 Esc 清空草稿')
  assert.notEqual(await draftTextOutsideTags(), '', '第一次 Esc 只给提示')
  await page.keyboard.press('Escape')
  await wait(() => document.querySelector('.chat-input-rich').innerText.trim() === '')
  assert.equal(await page.evaluate(() => window.chatState.contextFiles.length), 0, '草稿清了，挂在草稿上的附件也跟着清')

  // Esc 在流式中 = 停止（不进 config/commands，只是输入框局部监听）
  const cancelsBeforeEsc = await page.evaluate(() => window.cancelCalls || 0)
  await page.evaluate(() => { window.chatState.isStreaming = true })
  await page.click('.chat-input-rich')
  await page.keyboard.press('Escape')
  await wait(() => window.chatState.isStreaming === false)
  assert.equal(await page.evaluate(() => window.cancelCalls || 0), cancelsBeforeEsc + 1, '流式中的 Esc 真的发了取消')

  // 上箭头翻历史：空输入框才起步，到顶停住，下箭头翻回空草稿
  await page.evaluate(() => window.loadFixture('single'))
  await clearDraftDom()
  await page.click('.chat-input-rich')
  await page.keyboard.press('ArrowUp')
  await wait(() => document.querySelector('.chat-input-rich').innerText.includes('请审查这份采购合同'))
  await page.keyboard.press('ArrowUp')
  assert.ok((await draftText()).includes('请审查这份采购合同'), '到顶就停住，不绕回最新那条')
  await page.keyboard.press('ArrowDown')
  await wait(() => document.querySelector('.chat-input-rich').innerText.trim() === '')

  // Cmd+Enter 发送（Enter 仍发送、Shift+Enter 换行的老行为由 handleEnterKey 保持）。
  // 故意在引用浮层开着的时候按：Cmd+Enter 是明确的「发出去」，要压过「再选一个文件」，
  // 这也是这条分支唯一与普通 Enter 不同的地方——不这么测等于什么都没测。
  await page.keyboard.type('这一条用 Cmd+Enter 发出去 @股')
  await wait(() => document.querySelector('.mention-picker'))
  await page.keyboard.down('Meta')
  await page.keyboard.press('Enter')
  await page.keyboard.up('Meta')
  await wait(() => !document.querySelector('.mention-picker'))
  assert.equal(await page.evaluate(() => window.chatState.contextFiles.length), 0, 'Cmd+Enter 不该顺手挑一个文件进来')
  await wait(() => window.chatState.bubbles.filter(b => b.role === 'USER').at(-1)?.content === '这一条用 Cmd+Enter 发出去 @股')
  await wait(() => document.querySelector('.chat-input-rich').innerText.trim() === '') // 发完草稿清空

  // 发送 / 停止是真 <button>：能 Tab 到、能回车按（K16 ④）
  assert.equal(await page.$eval('.send-btn', el => el.tagName), 'BUTTON', '发送键是 button 不是 view')
  assert.equal(await page.$eval('.send-btn', el => el.tabIndex), 0, '发送键在 Tab 序里')
  assert.ok(await page.$eval('.send-btn', el => !!el.getAttribute('aria-label')), '发送键有无障碍名')
  await page.focus('.send-btn')
  assert.ok(await page.evaluate(() => document.activeElement.classList.contains('send-btn')), '发送键可聚焦')
  await page.evaluate(() => { window.chatState.isStreaming = true })
  await wait(() => document.querySelector('.stop-btn'))
  assert.equal(await page.$eval('.stop-btn', el => el.tagName), 'BUTTON', '停止键同样是 button')
  await page.evaluate(() => { window.chatState.isStreaming = false })

  // 三个下拉的选项进 Tab 序并报 role（K16 ⑤）
  await page.evaluate(() => { window.chatState.showModeDropdown = true })
  await wait(() => document.querySelector('.mode-option'))
  assert.ok(await page.$$eval('.mode-option', els => els.every(e => e.getAttribute('role') === 'option' && e.tabIndex === 0)),
    '模式下拉的每个选项都能 Tab 到')
  assert.equal(await page.$eval('.mode-dropdown', el => el.getAttribute('role')), 'listbox')
  await page.evaluate(() => { window.chatState.showModeDropdown = false })

  // 「+」对话框的「从项目选择」页签：与 @ 同一份候选集、同一个检索（K15 ③）
  await page.evaluate(() => window.chatState.triggerFileSelect())
  await wait(() => document.querySelector('.pick-tabs'))
  await tap('.pick-tabs .pick-tab:last-child')
  await wait(() => document.querySelector('.pick-row'))
  assert.equal(await page.$$eval('.pick-row .pick-name', els => els.length), 4, '页签里列的是整份项目清单（含文件夹）')
  await page.screenshot({ path: `${shots}/k15-project-pick-tab.png` })
  await page.evaluate(() => { window.chatState.projectPickQuery = '章程' })
  await wait(() => document.querySelectorAll('.pick-row').length === 1)
  await tap('.pick-row')
  await wait(() => window.chatState.contextFiles.some(f => String(f.id) === '13'))
  assert.ok(await page.$('.pick-row.picked'), '已经加过的那条标出来，别让人重复点')
  await page.evaluate(() => window.chatState.cancelUpload())
  await wait(() => !document.querySelector('.pick-tabs'))

  await page.evaluate(() => window.loadFixture('single'))
  await page.screenshot({ path: `${shots}/k11-copy-actions.png` })
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
  // 新加的四处复制 + 重新生成也必须跟着换语言（硬编码中文/英文在另一套界面里会原样露出来）
  await page.evaluate(() => { window.chatState.bubbles.at(-1).content += '\n\n```\nAlpha\n```\n' })
  await wait(() => document.querySelector('.md-copy-btn'))
  await page.click('.message-row.assistant:last-child .activity-summary')
  await wait(() => document.querySelector('.tool-copy-btn'))
  assert.deepEqual(await page.evaluate(() => [
    document.querySelector('.msg-copy-btn span').textContent.trim(),
    document.querySelector('.msg-regen-btn span').textContent.trim(),
    document.querySelector('.md-copy-btn').textContent.trim(),
    document.querySelector('.tool-copy-btn').getAttribute('title')
  ]), ['Copy', 'Regenerate', 'Copy', 'Copy call'], 'English labels for copy/regenerate')
  await page.screenshot({ path: `${shots}/k11k13-english.png` })
  assert.deepEqual(errors, [], 'browser runtime errors')
  console.log('PASS: chronological history/live stream, automatic collapse, manual disclosures, output inspection, scrolling, attention cards and their locator, on-demand use-in-document actions, rollback locator and its dialog, branch-from-here availability, ungated copy for answers/tool calls/tool output/code blocks, running tool name and elapsed seconds, per-turn token line, regenerate through the rollback channel, interjection receipts and inbox/transcript reconciliation, menu stop, turn rail navigation, stranded steer items getting a send-now, @ mention picker and the project-pick tab, composer key bindings (Esc/Cmd+Enter/history recall) and focusable send-stop buttons, narrow widths, themes, English')
} catch (error) {
  console.error('BROWSER ERRORS', errors)
  console.error(await page.evaluate(() => document.querySelector('.message-row.assistant:last-child')?.textContent))
  throw error
} finally {
  await browser.close()
  await server.close()
}
