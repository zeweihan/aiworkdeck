// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// ask_user 问题卡（dev-board#868）：真 ChatInterface + 真 SSE 解析器，合成事件与 HTTP，不调真模型。
//
// 验的是「后端发来的东西 → 卡片怎么画 → 用户点了之后发出去什么 → 卡片变成什么」这一整条：
//   单选点一下即作答 / 多选勾完 + 「其他」补充再提交 / 开放式提问直接给输入框 /
//   回答后整卡只读并高亮当时的选择 / 历史回灌同样高亮 / 键盘可达 / 窄栏不横向溢出 / 浅深两主题。
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
const server = await createServer({ configFile: false, root: fixture, plugins: [vue()], resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] }, server: { host: '127.0.0.1', port: 5211, strictPort: true, fs: { allow: [root, fileURLToPath(new URL('../../node_modules', import.meta.url))] } } })
await server.listen()
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const shots = process.env.AWD_SHOTS || '/tmp'
const page = await browser.newPage()
const errors = []
page.on('pageerror', e => errors.push(e.message))
const wait = (fn, arg) => page.waitForFunction(fn, { timeout: 8000 }, arg)
const send = (event, data) => page.evaluate(({ event, data }) => {
  window.sseController.enqueue(new TextEncoder().encode(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`))
}, { event, data })
const lastPost = () => page.evaluate(() => window.chatPosts.at(-1))
const card = '.question-card.is-ask-user'

// 与后端 AskUserQuestion.toMarkup / toEventJson 同形：先标记（text_delta），再结构化事件，最后停机
const markupOf = (q) => {
  const attr = (s) => String(s).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  let m = `\n<question kind="ask_user" id="${q.id}"${q.header ? ` header="${attr(q.header)}"` : ''}${q.multiSelect ? ' multi="true"' : ''}>${q.question}`
  for (const o of q.options) m += `\n<option${o.description ? ` description="${attr(o.description)}"` : ''}>${o.label}</option>`
  return m + '\n</question>\n'
}
const askTurn = async (q) => {
  await send('text_delta', { content: `<process name="向用户提问"><tool_code>ask_user({})</tool_code></process>` })
  await send('text_delta', { content: '<tool_output status="SUCCESS">已向你提问，等你回答后继续。</tool_output>' })
  await send('text_delta', { content: markupOf(q) })
  await send('ask_user', { v: 1, ...q })
  await send('bubble_end', { status: 'awaiting_input', documentEdited: false })
  await wait(() => !window.chatState.isStreaming)
  await wait((id) => !!document.querySelector(`.question-card.is-ask-user`) &&
    window.chatState.bubbles.at(-1).question?.id === id, q.id)
  // 页面上可能已有上一问（已作答、只读）的卡：给最新那张打个记号，后面的选择器只认它
  await page.evaluate((sel) => {
    const all = [...document.querySelectorAll(sel)]
    all.forEach(el => el.removeAttribute('data-latest'))
    all.at(-1).setAttribute('data-latest', '')
  }, card)
}
const submitText = (text) => page.evaluate(async (text) => {
  document.querySelector('.chat-input-rich').textContent = text
  await window.chatState.handleSubmit('steer')
}, text)
const latestCard = () => `${card}[data-latest]`

try {
  await page.setViewport({ width: 420, height: 860 })
  await page.goto('http://127.0.0.1:5211')
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app {margin:0;height:100%;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;} *{box-sizing:border-box;} view,scroll-view{display:block;} text{display:inline;} #app{height:100dvh;}` })
  await wait(() => window.ready)
  await page.evaluate(() => window.loadFixture('single'))

  // ---------- 1. 单选：点一下即作答 ----------
  await submitText('你能帮我清理已经打开的这个文档么')
  await wait(() => window.chatState.isStreaming)
  await askTurn({
    id: 'ask-single1', question: '「清理」具体指哪一种？', header: '清理范围', multiSelect: false,
    options: [
      { label: '删除混入的审查报告', description: '只删第 12-21 段那段审查意见，其余不动' },
      { label: '清理格式', description: '统一字体段落、删多余空行，不改文字' },
      { label: '接受全部修订', description: '' }
    ]
  })
  const single = await page.$eval(latestCard(), el => ({
    chip: el.querySelector('.q-chip')?.textContent.trim(),
    body: el.querySelector('.q-body')?.textContent.trim(),
    labels: [...el.querySelectorAll('.q-choice-label')].map(e => e.textContent.trim()),
    descs: [...el.querySelectorAll('.q-choice-desc')].map(e => e.textContent.trim()),
    submit: !!el.querySelector('.q-submit'),
    other: !!el.querySelector('.q-other-input'),
    radios: el.querySelectorAll('.q-mark.is-radio').length
  }))
  assert.equal(single.chip, '清理范围')
  assert.equal(single.body, '「清理」具体指哪一种？')
  assert.deepEqual(single.labels, ['删除混入的审查报告', '清理格式', '接受全部修订', '其他'], '选项 + 恒有的「其他」')
  assert.deepEqual(single.descs, ['只删第 12-21 段那段审查意见，其余不动', '统一字体段落、删多余空行，不改文字'])
  assert.equal(single.submit, false, '单选不需要提交按钮：点一下即作答')
  assert.equal(single.other, false, '「其他」输入框只在点开后出现')
  assert.equal(single.radios, 4)
  assert.equal(await page.evaluate(() => window.chatState.bubbles.at(-1).question.descriptions.length), 3,
    '事件覆盖后说明与选项按下标对齐')
  // 窄栏：不许横向溢出
  for (const width of [320, 420]) {
    await page.setViewport({ width, height: 860 })
    assert.ok(await page.$eval('.message-list', el => el.scrollWidth <= el.clientWidth + 1), `no horizontal overflow at ${width}px`)
  }
  await page.setViewport({ width: 420, height: 860 })
  await page.$eval(latestCard(), el => el.scrollIntoView({ block: 'center' }))
  await page.screenshot({ path: `${shots}/awd-ask-user-light.png` })
  // 选项有 0.15s 的底色过渡：切主题后等它走完再取色/截图，否则拿到的是过渡中间值
  const settle = () => new Promise(resolve => setTimeout(resolve, 400))
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'))
  await settle()
  const darkTheme = await page.$eval(`${latestCard()} .q-choice`, el => ({
    bg: getComputedStyle(el).backgroundColor,
    token: getComputedStyle(document.documentElement).getPropertyValue('--awd-surface').trim()
  }))
  await page.screenshot({ path: `${shots}/awd-ask-user-dark.png` })
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'light'))
  await settle()
  const light = await page.$eval(`${latestCard()} .q-choice`, el => getComputedStyle(el).backgroundColor)
  const dark = darkTheme.bg
  assert.equal(dark, 'rgb(34, 31, 26)', `深色下选项底色应取深色 --awd-surface（${darkTheme.token}）`)
  assert.notEqual(dark, light, '选项底色必须随主题切换（只用 --awd-* 令牌）')

  const postsBefore = await page.evaluate(() => window.chatPosts.length)
  await page.click(`${latestCard()} .q-choice:first-child`)
  await wait((n) => window.chatPosts.length === n + 1, postsBefore)
  let post = await lastPost()
  assert.ok(post.message.startsWith('<ask_user_answer id="ask-single1">'), post.message)
  assert.ok(post.message.includes('Question: 「清理」具体指哪一种？'))
  assert.ok(post.message.includes('Selected:\n- 删除混入的审查报告'))
  assert.equal(post.displayText, '删除混入的审查报告', '用户气泡只显示所选项')
  // 用户气泡显示的是人话，不是回答消息
  await wait(() => [...document.querySelectorAll('.user-bubble')].at(-1)?.textContent.includes('删除混入的审查报告'))
  assert.ok(!(await page.$$eval('.user-bubble', els => els.at(-1).textContent)).includes('ask_user_answer'))
  // 原卡变只读：按钮全部禁用，当时的选择高亮
  const answered = await page.$$eval(card, els => {
    const el = els.at(-1)
    return {
      enabled: [...el.querySelectorAll('button')].filter(b => !b.disabled).length,
      chosen: [...el.querySelectorAll('.q-choice.is-chosen .q-choice-label')].map(e => e.textContent.trim()),
      badge: !!el.querySelector('.q-badge')
    }
  })
  assert.equal(answered.enabled, 0, '作答后整卡只读')
  assert.deepEqual(answered.chosen, ['删除混入的审查报告'])
  assert.ok(answered.badge, '作答后显示「已回答」')

  // ---------- 2. 多选 + 「其他」补充 + 键盘 ----------
  await askTurn({
    id: 'ask-multi2', question: '这次清理包括哪些？', header: '', multiSelect: true,
    options: [{ label: '删审查报告', description: '' }, { label: '删空行', description: '' }, { label: '统一字体', description: '' }]
  })
  assert.equal(await page.$$eval(`${latestCard()} .q-mark.is-check`, els => els.length), 4, '多选用复选框形态')
  assert.equal(await page.$eval(`${latestCard()} .q-submit`, el => el.disabled), true, '一项都没选时提交置灰')
  await page.click(`${latestCard()} .q-choice:nth-child(1)`)
  // 键盘可达：Tab 到第三项，空格勾选
  await page.focus(`${latestCard()} .q-choice:nth-child(3)`)
  await page.keyboard.press('Space')
  await wait(() => document.querySelectorAll('.question-card[data-latest] .q-choice.is-chosen').length === 2)
  assert.equal(await page.evaluate(() => window.chatPosts.length), postsBefore + 1, '多选勾选不许立刻发出')
  await page.click(`${latestCard()} .q-choice-other`)
  await wait(() => document.activeElement && document.activeElement.classList.contains('q-other-input'))
  await page.keyboard.type('另外把页眉里的旧日期删掉')
  await page.click(`${latestCard()} .q-submit`)
  await wait((n) => window.chatPosts.length === n + 2, postsBefore)
  post = await lastPost()
  assert.ok(post.message.startsWith('<ask_user_answer id="ask-multi2">'), post.message)
  assert.ok(post.message.includes('- 删审查报告\n- 统一字体'), post.message)
  assert.ok(post.message.includes('Other: 另外把页眉里的旧日期删掉'), post.message)
  assert.equal(post.displayText, '删审查报告；统一字体；另外把页眉里的旧日期删掉')
  const multiDone = await page.$$eval(card, els => {
    const el = els.at(-1)
    return {
      chosen: [...el.querySelectorAll('.q-choice.is-chosen .q-choice-label')].map(e => e.textContent.trim()),
      other: el.querySelector('.q-answer-other')?.textContent.trim(),
      input: !!el.querySelector('.q-other-input')
    }
  })
  assert.deepEqual(multiDone.chosen, ['删审查报告', '统一字体'])
  assert.equal(multiDone.other, '补充：另外把页眉里的旧日期删掉')
  assert.equal(multiDone.input, false, '作答后不留第二个输入框')

  // ---------- 3. 开放式提问：没有选项，直接给输入框，回车发送 ----------
  await askTurn({ id: 'ask-open3', question: '审查报告那段从哪一段开始？', header: '', multiSelect: false, options: [] })
  assert.ok(await page.$(`${latestCard()} .q-other-input`), '开放式提问直接给输入框')
  await page.focus(`${latestCard()} .q-other-input`)
  await page.keyboard.type('第 12 段')
  await page.keyboard.press('Enter')
  await wait((n) => window.chatPosts.length === n + 3, postsBefore)
  post = await lastPost()
  assert.ok(post.message.startsWith('<ask_user_answer id="ask-open3">') && post.message.includes('Other: 第 12 段'), post.message)
  assert.equal(post.displayText, '第 12 段')

  // ---------- 4. 历史回灌：同一份标记 + 下一条用户消息 → 只读卡高亮当时的选择 ----------
  await page.evaluate(() => {
    const assistant = '<thinking>「清理」没说标准。</thinking><process name="向用户提问"><tool_code>ask_user({})</tool_code><tool_output status="SUCCESS">已向你提问，等你回答后继续。</tool_output></process>'
      + '\n<question kind="ask_user" id="ask-h1" header="清理范围" multi="true">「清理」具体指哪一种？'
      + '\n<option description="只删第 12-21 段">删除混入的审查报告</option>'
      + '\n<option description="统一字体 &quot;宋体&quot;">清理格式</option>\n</question>\n'
    const answer = '<ask_user_answer id="ask-h1">\nQuestion: 「清理」具体指哪一种？\nSelected:\n- 清理格式\nOther: 页码也重排\n</ask_user_answer>'
    window.chat.loadMessages('fixture-ask-history', [
      { id: 'h1', role: 'USER', content: '你能帮我清理已经打开的这个文档么' },
      { id: 'h2', role: 'ASSISTANT', content: assistant },
      { id: 'h3', role: 'USER', content: answer, displayContent: '清理格式；页码也重排' },
      { id: 'h4', role: 'ASSISTANT', content: '<final>已统一格式并重排页码。</final>' }
    ])
  })
  await wait(() => document.querySelector('.question-card.is-ask-user'))
  const history = await page.$eval(card, el => ({
    chip: el.querySelector('.q-chip')?.textContent.trim(),
    labels: [...el.querySelectorAll('.q-choice-label')].map(e => e.textContent.trim()),
    descs: [...el.querySelectorAll('.q-choice-desc')].map(e => e.textContent.trim()),
    checks: el.querySelectorAll('.q-mark.is-check').length,
    chosen: [...el.querySelectorAll('.q-choice.is-chosen .q-choice-label')].map(e => e.textContent.trim()),
    other: el.querySelector('.q-answer-other')?.textContent.trim(),
    enabled: [...el.querySelectorAll('button')].filter(b => !b.disabled).length
  }))
  assert.equal(history.chip, '清理范围')
  assert.deepEqual(history.labels, ['删除混入的审查报告', '清理格式'], '历史卡不再出现「其他」入口')
  assert.deepEqual(history.descs, ['只删第 12-21 段', '统一字体 "宋体"'], '属性实体要还原')
  assert.equal(history.checks, 2, 'multi="true" 读回成多选形态')
  assert.deepEqual(history.chosen, ['清理格式'])
  assert.equal(history.other, '补充：页码也重排')
  assert.equal(history.enabled, 0)
  assert.ok((await page.$$eval('.user-bubble', els => els.map(e => e.textContent))).some(t => t.includes('清理格式；页码也重排')),
    '历史里用户气泡显示 displayContent')
  await page.screenshot({ path: `${shots}/awd-ask-user-history.png` })

  // ---------- 5. 英文界面 ----------
  await page.goto('http://127.0.0.1:5211/?lang=en-US')
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app {margin:0;height:100%;} *{box-sizing:border-box;} #app{height:100dvh;}` })
  await wait(() => window.ready)
  await page.evaluate(() => window.loadFixture('single'))
  await submitText('Can you clean up this document?')
  await wait(() => window.chatState.isStreaming)
  await askTurn({ id: 'ask-en1', question: 'What does "clean up" mean here?', header: 'Scope', multiSelect: false,
    options: [{ label: 'Remove the review memo', description: '' }, { label: 'Fix formatting', description: '' }] })
  assert.ok(await page.$$eval(`${latestCard()} .q-choice-label`, els => els.map(e => e.textContent.trim()).includes('Other')))
  await page.click(`${latestCard()} .q-choice-other`)
  assert.equal(await page.$eval(`${latestCard()} .q-submit`, el => el.textContent.trim()), 'Send answer')

  assert.deepEqual(errors, [], 'page errors: ' + errors.join('\n'))
  console.log('ask-user question card: all checks passed; screenshots in ' + shots)
} finally {
  await browser.close()
  await server.close()
}
