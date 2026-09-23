// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real ChatInterface and send pipeline; synthetic identity, materials and HTTP only.
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
const server = await createServer({ configFile: false, root: fixture, plugins: [vue()], resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] }, server: { host: '127.0.0.1', port: 5197, strictPort: true, fs: { allow: [root] } } })
await server.listen()
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
const page = await browser.newPage()
const errors = []
page.on('pageerror', e => errors.push(e.message))
const switchSelector = '.decision-assist-switch'
const state = () => page.$eval(switchSelector, el => el.getAttribute('aria-checked'))
const open = async (query = '') => {
  await page.goto(`http://127.0.0.1:5197/${query}`)
  await page.waitForFunction(() => window.ready)
  await page.addStyleTag({ content: `${tokens}\nhtml,body,#app {margin:0;height:100%;font-family:system-ui;} *{box-sizing:border-box;} view,scroll-view{display:block;} text{display:inline;} #app{height:100dvh;}` })
}
const submit = (text, mode = 'steer') => page.evaluate(async ({ text, mode }) => {
  document.querySelector('.chat-input-rich').textContent = text
  await window.chatState.handleSubmit(mode)
  return window.chatPosts.at(-1)
}, { text, mode })
try {
  await page.setViewport({ width: 350, height: 860 })
  await open()
  assert.equal(await state(), 'false')
  assert.match(await page.$eval('.decision-assist-state', el => el.textContent), /已关闭/)
  assert.equal((await submit('默认关闭的合成问题')).decisionAssistEnabled, false)
  await page.click(switchSelector)
  assert.equal(await state(), 'true')
  assert.match(await page.$eval('.decision-assist-hint', el => el.textContent), /费用.*稍慢/)
  assert.equal(await page.$eval('.decision-assist-details', el => el.open), false)
  await page.click('.decision-assist-details > summary')
  assert.match(await page.$eval('.decision-assist-details', el => el.textContent), /本次输入.*工具类目.*工具名.*OpenRouter.*TypeSafe Jev/)
  assert.match(await page.$eval('.decision-assist-details', el => el.textContent), /附件、历史会话或文档正文.*粘贴/)
  assert.match(await page.$eval('.decision-assist-details', el => el.textContent), /2.5 秒.*原流程/)
  await page.click('.decision-assist-details > summary')
  await page.focus(switchSelector)
  await page.keyboard.press('Space')
  assert.equal(await state(), 'false', 'Space toggles the actual switch')
  await page.keyboard.press('Enter')
  assert.equal(await state(), 'true', 'Enter toggles the actual switch')
  for (const mode of ['steer', 'queue']) {
    const payload = await submit(`合成${mode}问题`, mode)
    assert.equal(payload.decisionAssistEnabled, true)
    assert.equal(payload.submissionMode, mode)
  }
  // Regeneration must carry the current setting through its rollback request.
  await open()
  await page.click('.message-row.assistant:last-child .msg-regen-btn')
  await page.waitForSelector('[data-rollback-confirm]')
  const beforeRegenerate = await page.evaluate(() => window.chatPosts.length)
  await page.$eval('[data-rollback-confirm]', el => el.dispatchEvent(new CustomEvent('tap', { bubbles: true })))
  await page.waitForFunction(n => window.chatPosts.length > n, {}, beforeRegenerate)
  assert.equal(await page.evaluate(() => window.chatPosts.at(-1).decisionAssistEnabled), true)
  // Persist and restore only the signed-in user's choice.
  await open()
  assert.equal(await state(), 'true')
  await page.evaluate(() => { uni.setStorageSync('checkba_user', { id: 1002 }); window.dispatchEvent(new Event('focus')) })
  assert.equal(await state(), 'false')
  assert.equal((await submit('另一用户的合成问题')).decisionAssistEnabled, false)
  await page.evaluate(() => { uni.setStorageSync('checkba_user', { id: 1001 }); window.dispatchEvent(new Event('focus')) })
  assert.equal(await state(), 'true')
  // The switch remains visible below the empty conversation's composer.
  await page.evaluate(() => window.chat.loadMessages('synthetic-empty', []))
  assert.ok(await page.$eval(switchSelector, el => el.offsetWidth > 0))
  assert.ok(await page.$eval('.decision-assist', el => el.getBoundingClientRect().top >= document.querySelector('.chat-input-rich').getBoundingClientRect().bottom))
  // Freeze the user's setting before awaiting the active-document flush.
  await page.evaluate(() => {
    window.chatFixtureProps.activeTab = { id: 13, name: '合成材料.docx', fileType: 'docx' }
    window.chatFixtureProps.flushActiveDocument = () => new Promise(resolve => { window.finishDecisionFlush = resolve })
  })
  await page.waitForFunction(() => window.chatState.activeDocChip)
  await page.evaluate(() => {
    document.querySelector('.chat-input-rich').textContent = '发送期间切换开关'
    window.pendingDecisionSend = window.chatState.handleSubmit('steer')
  })
  await page.waitForFunction(() => window.finishDecisionFlush)
  await page.click(switchSelector)
  assert.equal(await state(), 'false')
  await page.evaluate(async () => { window.finishDecisionFlush(true); await window.pendingDecisionSend })
  assert.equal(await page.evaluate(() => window.chatPosts.at(-1).decisionAssistEnabled), true, 'in-flight send keeps its opt-in snapshot')
  assert.equal((await page.evaluate(async () => {
    window.chatFixtureProps.flushActiveDocument = null
    await Promise.resolve()
    document.querySelector('.chat-input-rich').textContent = '下一条保持关闭'
    await window.chatState.handleSubmit('queue')
    return window.chatPosts.at(-1)
  })).decisionAssistEnabled, false)
  // A new identity during that same await can never inherit the previous identity's opt-in.
  await page.click(switchSelector)
  await page.evaluate(() => {
    window.finishDecisionFlush = null
    window.chatFixtureProps.flushActiveDocument = () => new Promise(resolve => { window.finishDecisionFlush = resolve })
  })
  await page.evaluate(() => {
    document.querySelector('.chat-input-rich').textContent = '发送期间切换用户'
    window.pendingDecisionSend = window.chatState.handleSubmit('steer')
  })
  await page.waitForFunction(() => window.finishDecisionFlush)
  await page.evaluate(async () => { uni.setStorageSync('checkba_user', { id: 1002 }); window.finishDecisionFlush(true); await window.pendingDecisionSend })
  assert.equal(await page.evaluate(() => window.chatPosts.at(-1).decisionAssistEnabled), false)
  // Narrow English and Chinese controls must remain visible without horizontal overflow.
  for (const lang of ['zh-CN', 'en-US']) {
    await open(`?lang=${lang}`)
    for (const width of [320, 350, 420]) {
      await page.setViewport({ width, height: 860 })
      assert.ok(await page.$eval('.decision-assist', el => el.scrollWidth <= el.clientWidth + 1), `${lang} at ${width}px`)
    }
    await page.screenshot({ path: `/tmp/awd-decision-assist-${lang}-off.png` })
    await page.click(switchSelector)
    await page.screenshot({ path: `/tmp/awd-decision-assist-${lang}-on.png` })
    await page.click('.decision-assist-details > summary')
    assert.ok(await page.$eval('.decision-assist', el => el.scrollWidth <= el.clientWidth + 1))
    await page.screenshot({ path: `/tmp/awd-decision-assist-${lang}-details.png` })
    await page.click('.decision-assist-details > summary')
    await page.click(switchSelector)
  }
  // Desktop local-mode (dev-board#877): no checkba_user, identity comes from /api/auth/me.
  await page.evaluate(() => uni.removeStorageSync('checkba_user'))
  await open('?localUser=1003')
  assert.equal(await state(), 'false')
  await page.click(switchSelector)
  assert.equal(await state(), 'true', 'the switch must visibly flip in local-mode')
  await page.waitForFunction(() => Object.entries(window.fixtureStorage).some(([k, v]) => k.startsWith('awd_decision_assist:') && k.endsWith(':1003') && v === true))
  assert.equal((await submit('本机免登的合成问题')).decisionAssistEnabled, true)
  await open('?localUser=1003')
  await page.waitForFunction(() => document.querySelector('.decision-assist-switch').getAttribute('aria-checked') === 'true')
  assert.equal(await page.evaluate(() => window.fixtureStorage.checkba_user), undefined, 'the real user is never written into checkba_user')
  await open('?localUser=1004')
  assert.equal(await state(), 'false', 'another local identity does not inherit consent')
  await page.evaluate(() => uni.setStorageSync('checkba_user', { id: 1001 }))
  await open('?provider=local')
  await page.waitForFunction(() => window.chatState.isLocalOnlyProvider)
  assert.match(await page.$eval('.decision-assist-hint', el => el.textContent), /本地模型.*不向 Jev/)
  await page.click(switchSelector)
  assert.match(await page.$eval('.decision-assist-hint', el => el.textContent), /本地模型.*不向 Jev/)
  assert.deepEqual(errors, [])
  console.log('PASS: visible on/off state, keyboard, per-user persistence, POST opt-in, queue/steer, frozen consent and identity changes, bilingual narrow layout')
} finally {
  await browser.close()
  await server.close()
}
