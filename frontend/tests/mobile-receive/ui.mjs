// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Run against dev:h5. The actual workbench renders; all API responses use synthetic fixtures.
import puppeteer from 'puppeteer-core'
import assert from 'node:assert/strict'
import fs from 'node:fs'
const base = process.env.MOBILE_RECEIVE_UI_BASE || 'http://127.0.0.1:5189'
const out = process.env.MOBILE_RECEIVE_UI_OUT || '/tmp/awd-1171-ui'
fs.mkdirSync(out, { recursive: true })
const browser = await puppeteer.launch({ executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true, args: ['--no-sandbox'] })
const page = await browser.newPage()
await page.setViewport({ width: 1440, height: 1000 })
let phase = 'pending', checks = 0
const project = { id: 42, name: '同名测试项目', projectName: '同名测试项目', userId: 7, localRoot: '/synthetic/项目甲', projectType: 'BLANK' }
const errors = []
page.on('pageerror', e => errors.push(e.message))
await page.evaluateOnNewDocument(() => {
  if (location.protocol !== 'about:') localStorage.setItem('awd_app_language', 'zh-CN')
  window.checkbaDesktop = { ocr: {}, shell: { openExternal() {} } }
})
await page.setRequestInterception(true)
page.on('request', async req => {
  const url = new URL(req.url())
  if (!url.pathname.startsWith('/api/')) return req.continue()
  if (req.method() === 'OPTIONS') return req.respond({ status: 204, headers: { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' } })
  let body = []
  if (url.pathname === '/api/mobile-receive/status') body = {
    active: true, checking: phase === 'receiving', deviceId: 'test-desktop-device-1171', deviceName: '测试桌面', lastCheckedAt: Date.now(),
    project: { key: '42', name: project.name, path: project.localRoot },
    items: [42, 43].map((key, i) => ({ id: i + 1, fileName: `录音${i + 1}.m4a`, project: { key: String(key), name: project.name, path: '/synthetic/项目' + key }, phase, updatedAt: Date.now(), message: phase === 'failed' ? 'save' : '', savedPath: `现场录音/2026-10-10/录音${i + 1}.m4a` }))
  }
  else if (url.pathname === '/api/mobile-receive/check') { checks++; phase = 'receiving'; body = { code: 0 } }
  else if (url.pathname === '/api/projects/42') body = project
  else if (url.pathname === '/api/projects/my') body = [project]
  else if (url.pathname === '/api/auth/me') body = { id: 7, username: 'test' }
  else if (url.pathname.includes('/license/status')) body = { mode: 'none', accountConnected: false }
  else if (url.pathname === '/api/account/status') body = { connected: false }
  else if (url.pathname.includes('/version/status')) body = { enabled: false }
  else if (url.pathname.includes('/profile')) body = {}
  else if (url.pathname.includes('/admin/config')) body = {}
  await req.respond({ status: 200, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' }, body: JSON.stringify(body) })
})
try {
  await page.goto(base + '/#/pages/project-overview/project-overview?id=42', { waitUntil: 'networkidle0' })
  await page.waitForSelector('.page-project-overview .receive-trigger', { visible: true })
  assert.match(await page.$eval('.receive-trigger', e => e.textContent), /2 件待收取/)
  await page.click('.receive-trigger')
  await page.waitForSelector('.receive-panel', { visible: true })
  let text = await page.$eval('.receive-panel', e => e.textContent)
  for (const value of ['test-desktop-device-1171', '项目编号: 42', '/synthetic/项目甲', '项目编号 43']) assert.ok(text.includes(value), value)
  await page.screenshot({ path: out + '/pending.png' })
  const checkButton = await page.$('.receive-check')
  await checkButton.click()
  await page.waitForFunction(() => document.querySelector('.receive-panel').textContent.includes('接收中'))
  assert.equal(checks, 1)
  phase = 'failed'
  await page.waitForFunction(() => document.querySelector('.receive-panel').textContent.includes('保存失败'), { timeout: 8000 })
  assert.match(await page.$eval('.receive-trigger', e => e.textContent), /需要处理/)
  assert.match(await page.$eval('.receive-panel', e => e.textContent), /接收失败/)
  assert.doesNotMatch(await page.$eval('.receive-panel', e => e.textContent), /workbench\.mobileReceive/)
  await page.screenshot({ path: out + '/failed.png' })
  phase = 'saved'
  await page.waitForFunction(() => document.querySelector('.receive-trigger').textContent.includes('已保存 2 件'), { timeout: 8000 })
  assert.match(await page.$eval('.receive-panel', e => e.textContent), /现场录音\/2026-10-10\/录音1.m4a/)
  await page.screenshot({ path: out + '/saved.png' })
  await page.click('.receive-close')
  assert.equal(await page.$('.receive-panel'), null)
  await page.goto('about:blank')
  await page.goto(base + '/#/pages/project-overview/project-overview', { waitUntil: 'networkidle0' })
  await page.waitForSelector('.page-project-overview.no-project')
  await page.waitForSelector('.page-project-overview .receive-trigger', { visible: true })
  await page.click('.receive-trigger')
  await page.waitForSelector('.receive-panel', { visible: true })
  assert.match(await page.$eval('.receive-panel', e => e.textContent), /test-desktop-device-1171/)
  assert.equal(await page.$$eval('.receive-item', es => es.length), 2, '无项目态仍显示全设备收件')
  await page.evaluate(() => document.documentElement.setAttribute('data-theme', 'dark'))
  await page.setViewport({ width: 900, height: 700 })
  await page.screenshot({ path: out + '/no-project-dark.png' })
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ passed: true, checks, screenshotDirectory: out, unrelatedPageErrors: errors }, null, 2))
} finally { await browser.close() }
