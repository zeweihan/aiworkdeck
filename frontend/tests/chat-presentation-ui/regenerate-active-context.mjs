// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real ChatInterface rollback/resend pipeline; synthetic identity, documents and HTTP only.
// dev-board#1118：重新生成必须按用户气泡上的 activeContext 发送时快照重放——
// 同文档重试带原身份；切走或关闭目标先阻止；原问没带文档不因当前开着一份而添入；
// 历史回灌的气泡没有快照则在回退前阻止，明确请用户定位文档后重发，
// 绝不猜第一份文件。附件重试与输入框不受影响。
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
const server = await createServer({ configFile: false, root: fixture, plugins: [vue()], resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] }, server: { host: '127.0.0.1', port: 5198, strictPort: true, fs: { allow: [root] } } })
await server.listen()
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await browser.newPage()
const errors = []
page.on('pageerror', e => errors.push(e.message))

const DOC_A = { id: 11, name: '股份认购协议.docx', fileType: 'docx' }
const DOC_B = { id: 13, name: '公司章程.docx', fileType: 'docx' }

const open = async (query = '') => {
  await page.goto(`http://127.0.0.1:5198/${query}`)
  await page.waitForFunction(() => window.ready)
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app {margin:0;height:100%;font-family:system-ui;} *{box-sizing:border-box;} view,scroll-view{display:block;} text{display:inline;} #app{height:100dvh;}` })
}
const submit = (text, mode = 'steer') => page.evaluate(async ({ text, mode }) => {
  document.querySelector('.chat-input-rich').textContent = text
  await window.chatState.handleSubmit(mode)
  // 夹具不跑真 SSE，助手气泡没有正文就没有「重新生成」按钮（判据 !!content）。
  // 这里只补一段正文让按钮出现，请求链路本身不受影响。
  const last = window.chatState.bubbles.filter(b => b.role === 'ASSISTANT').at(-1)
  if (last) { last.content = '<final>合成回答。</final>'; last.isStreaming = false }
  // 夹具没有真 SSE，run 永远不会收到 bubble_end；不落闸的话「重新生成」
  // 会被 waitCurrentChat 挡住（与生产里流自然结束后的状态对齐）。
  window.chatState.isStreaming = false
  await new Promise(resolve => requestAnimationFrame(resolve))
  return window.chatPosts.at(-1)
}, { text, mode })
const settle = () => page.evaluate(async () => {
  const last = window.chatState.bubbles.filter(b => b.role === 'ASSISTANT').at(-1)
  if (last) { last.content = last.content || '<final>合成回答。</final>'; last.isStreaming = false }
  window.chatState.isStreaming = false
  await new Promise(resolve => requestAnimationFrame(resolve))
})
const regenerate = async () => {
  await settle()
  await page.click('.message-row.assistant:last-child .msg-regen-btn')
  await page.waitForSelector('[data-rollback-confirm]')
  const before = await page.evaluate(() => window.chatPosts.length)
  await page.$eval('[data-rollback-confirm]', el => el.dispatchEvent(new CustomEvent('tap', { bubbles: true })))
  await page.waitForFunction(n => window.chatPosts.length > n, {}, before)
  return page.evaluate(() => window.chatPosts.at(-1))
}
const setActiveTab = (tab) => page.evaluate((tab) => { window.chatFixtureProps.activeTab = tab }, tab)

try {
  await page.setViewport({ width: 420, height: 860 })

  // ① 原问带文档 A、chip 仍是 A → 重试按快照带 A。
  await open()
  await setActiveTab(DOC_A)
  await page.waitForFunction(() => window.chatState.activeDocChip)
  await page.evaluate(() => window.chatState.contextFiles.push({ id: '12', name: '合成附件清单.xlsx', fileType: 'xlsx', kind: 'file' }))
  const originalPost = await submit('请审查这份协议的付款安排')
  const sameDoc = await regenerate()
  assert.deepEqual(sameDoc.contextItems, originalPost.contextItems, 'regenerate preserves attachments alongside the original document identity')
  assert.equal(sameDoc.contextItems[0].name, '合成附件清单.xlsx')
  assert.equal(sameDoc.activeContext.id, '11')
  assert.equal(sameDoc.activeContext.name, '股份认购协议.docx')
  assert.equal(sameDoc.activeContext.fileType, 'docx')
  assert.equal(sameDoc.activeContext.staleBody, true)

  // ② 发送后用户切到文档 B → 回退/API前阻止，回到A后允许。
  await setActiveTab(DOC_B)
  await page.waitForFunction(() => window.chatState.activeDocChip.name === '公司章程.docx')
  await settle()
  await page.click('.message-row.assistant:last-child .msg-regen-btn')
  await page.waitForSelector('[data-rollback-confirm]')
  const switchedBefore = await page.evaluate(() => ({ posts: window.chatPosts.length, bubbles: window.chatState.bubbles.length, rollbacks: (window.rollbackCalls || []).length }))
  await page.$eval('[data-rollback-confirm]', el => el.dispatchEvent(new CustomEvent('tap', { bubbles: true })))
  await page.waitForFunction(() => window.lastToast === '请先回到原文档“股份认购协议.docx”再重新生成')
  assert.deepEqual(await page.evaluate(() => ({ posts: window.chatPosts.length, bubbles: window.chatState.bubbles.length, rollbacks: (window.rollbackCalls || []).length })), switchedBefore)
  await setActiveTab(DOC_A)
  await page.waitForFunction(() => window.chatState.activeDocChip.name === '股份认购协议.docx')
  assert.equal((await regenerate()).activeContext.id, '11', 'returning to the original document allows regeneration')
  await open()
  await setActiveTab(DOC_A)
  await page.waitForFunction(() => window.chatState.activeDocChip)
  await submit('请审查这份协议的付款安排')
  await setActiveTab(null)
  await page.waitForFunction(() => !window.chatState.activeDocChip)
  await settle()
  await page.click('.message-row.assistant:last-child .msg-regen-btn')
  await page.waitForSelector('[data-rollback-confirm]')
  const closedBefore = await page.evaluate(() => ({ posts: window.chatPosts.length, rollbacks: (window.rollbackCalls || []).length }))
  await page.$eval('[data-rollback-confirm]', el => el.dispatchEvent(new CustomEvent('tap', { bubbles: true })))
  await page.waitForFunction(() => window.lastToast === '请先回到原文档“股份认购协议.docx”再重新生成')
  assert.deepEqual(await page.evaluate(() => ({ posts: window.chatPosts.length, rollbacks: (window.rollbackCalls || []).length })), closedBefore)


  // ③ 原问没带文档 → 重试 activeContext 必须是 null，即使此刻界面开着一份文档。
  await open()
  await submit('帮我查一下最新的司法解释')
  assert.equal((await page.evaluate(() => window.chatPosts.at(-1).activeContext)), null)
  await setActiveTab(DOC_B)
  await page.waitForFunction(() => window.chatState.activeDocChip)
  const noDoc = await regenerate()
  assert.equal(noDoc.activeContext, null, 'a question asked without a document must not gain one on retry')

  // ④ 历史身份未知：有/无当前chip均阻止，原历史及输入框保留。
  await open()
  await page.evaluate(() => {
    window.chat.loadMessages('fixture-history-context', [
      { id: 'hu1', role: 'USER', content: '请审查这份采购合同，重点关注付款和违约责任。' },
      { id: 'ha1', role: 'ASSISTANT', content: '<final>已核对第一轮条款。</final>' },
      // 第二轮：重新生成目标是它；截断后仍剩第一轮，输入框不会经历
      // 「空状态 ↔ 底部」两个 contenteditable 的互换（那是另一种已知的 DOM 现象，
      // 与本卡无关）。附件挂在这一轮——断言的是「重试把原问的附件原样再带」。
      { id: 'hu2', role: 'USER', content: '请给出完整的风险清单。', attachments: [{ fileId: '12', name: '股份认购协议-附件清单.xlsx', fileType: 'xlsx', kind: 'file' }] },
      { id: 'ha2', role: 'ASSISTANT', content: '<final>已整理风险清单。</final>' }
    ])
  })
  await setActiveTab(DOC_B)
  await page.waitForFunction(() => window.chatState.activeDocChip)
  await page.evaluate(() => {
    const editors = [...document.querySelectorAll('.chat-input-rich')]
    const editor = editors.find(el => el.offsetParent !== null) || editors.at(-1)
    editor.textContent = '输入框里已有的草稿'
  })
  for (const tab of [DOC_B, null]) {
    await setActiveTab(tab)
    await page.evaluate(() => { window.lastToast = null })
    await settle()
    await page.click('.message-row.assistant:last-child .msg-regen-btn')
    await page.waitForSelector('[data-rollback-confirm]')
    const before = await page.evaluate(() => ({ posts: window.chatPosts.length, bubbles: window.chatState.bubbles.length, rollbacks: (window.rollbackCalls || []).length }))
    await page.$eval('[data-rollback-confirm]', el => el.dispatchEvent(new CustomEvent('tap', { bubbles: true })))
    await page.waitForFunction(() => window.lastToast === '原提问的文档身份无法恢复，请重新定位文档后重发原问题')
    assert.deepEqual(await page.evaluate(() => ({ posts: window.chatPosts.length, bubbles: window.chatState.bubbles.length, rollbacks: (window.rollbackCalls || []).length })), before)
  }
  assert.equal(await page.$$eval('.chat-input-rich', els => {
    const editor = els.find(el => el.offsetParent !== null) || els.at(-1)
    return editor.textContent
  }), '输入框里已有的草稿', 'blocked regenerate must not touch the composer')

  assert.deepEqual(errors, [])
  console.log('PASS: regenerate replays the activeContext snapshot (same doc, doc switched, no doc, switch blocked, unknown history blocked with/without chip), attachments and composer intact')
} finally {
  await browser.close()
  await server.close()
}
