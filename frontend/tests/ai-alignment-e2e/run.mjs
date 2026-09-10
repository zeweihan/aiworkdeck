#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real browser + real backend journey for durable steering/queue and Markdown memory.
// This test intentionally requires an isolated backend because it points OpenRouter at the
// deterministic local fixture below. It never calls a production model.

import fs from 'node:fs'
import http from 'node:http'
import os from 'node:os'
import path from 'node:path'

const BASE = process.env.AI_ALIGNMENT_E2E_BASE || 'http://127.0.0.1:5174'
const BACKEND = process.env.AI_ALIGNMENT_E2E_BACKEND || 'http://127.0.0.1:9797'
const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const OUT = process.env.AI_ALIGNMENT_E2E_OUT || path.join(os.tmpdir(), 'ai-alignment-e2e')
const MARKER = `AI_ALIGNMENT_${Date.now()}`
fs.mkdirSync(OUT, { recursive: true })

if (process.env.AI_ALIGNMENT_E2E_ISOLATED !== '1') {
  console.error('Refusing to change AI settings: start an isolated backend and set AI_ALIGNMENT_E2E_ISOLATED=1')
  process.exit(2)
}

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('Missing puppeteer-core; run npm ci in frontend'); process.exit(2) }

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
async function api(endpoint, options = {}) {
  const response = await fetch(BACKEND + endpoint, {
    method: options.method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
  })
  const text = await response.text()
  let body = null
  try { body = text ? JSON.parse(text) : null } catch { body = text }
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${endpoint}: HTTP ${response.status} ${text.slice(0, 200)}`)
  return body
}
const dataOf = (body) => body && Object.prototype.hasOwnProperty.call(body, 'data') ? body.data : body

function startModelFixture() {
  let mainRequests = 0
  const server = http.createServer(async (request, response) => {
    if (request.method !== 'POST' || !request.url.endsWith('/chat/completions')) {
      response.writeHead(404).end()
      return
    }
    let raw = ''
    for await (const chunk of request) raw += chunk
    const payload = JSON.parse(raw || '{}')
    const prompt = JSON.stringify(payload.messages || [])
    const isMain = prompt.includes(MARKER)
    if (isMain) mainRequests += 1
    const content = isMain ? `<final>Fixture completed ${mainRequests}</final>` : 'Fixture helper response'
    const delay = isMain && mainRequests === 1 ? 12000 : 25

    if (payload.stream) {
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
      setTimeout(() => {
        response.write(`data: ${JSON.stringify({ id: 'fixture', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant', content }, finish_reason: null }] })}\n\n`)
        response.write(`data: ${JSON.stringify({ id: 'fixture', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })}\n\n`)
        response.end('data: [DONE]\n\n')
      }, delay)
      return
    }

    setTimeout(() => {
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({
        id: 'fixture', object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: payload.model || 'fixture',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }))
    }, delay)
  })
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({
    server,
    baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
  })))
}

for (const [name, url] of [['frontend', BASE], ['backend', `${BACKEND}/api/auth/me`]]) {
  if (!await fetch(url).then((r) => r.ok).catch(() => false)) {
    console.error(`${name} is unavailable at ${url}`)
    process.exit(2)
  }
}

const fixture = await startModelFixture()
let browser
let failed = false
try {
  const config = await api('/api/admin/config')
  config.external ||= {}
  config.external.openRouter = { apiKey: 'fixture-only-key', baseUrl: fixture.baseUrl }
  config.ai ||= {}
  config.ai.activeProvider = 'OPENROUTER'
  config.ai.defaultModel = 'deepseek/deepseek-v4-flash'
  const saved = await api('/api/admin/config', { method: 'POST', body: config })
  if (saved?.code !== 0) throw new Error(`fixture AI config rejected: ${JSON.stringify(saved)}`)

  const project = await api('/api/projects', { method: 'POST', body: { name: `AI alignment E2E ${MARKER}`, projectType: 'BLANK' } })
  if (!project?.id) throw new Error(`project creation failed: ${JSON.stringify(project)}`)

  const spaces = dataOf(await api(`/api/ai/memory/spaces?projectId=${project.id}`))
  const memorySpace = spaces.find((space) => space.scope === 'project' && space.writable)
    || spaces.find((space) => space.scope === 'user' && space.writable)
  if (!memorySpace) throw new Error('no writable memory space')
  await api('/api/ai/memory/file', {
    method: 'PUT',
    body: { spaceId: memorySpace.id, path: 'preferences.md', content: '# Preferences\n\nInitial fixture value.\n', expectedRevision: 0 },
  })

  browser = await puppeteer.launch({ executablePath: CHROME, headless: process.env.AI_ALIGNMENT_E2E_HEADFUL !== '1', args: ['--no-first-run'] })
  const page = await browser.newPage()
  await page.setViewport({ width: 1280, height: 820 })
  await page.evaluateOnNewDocument((backend) => {
    window.checkbaDesktop = { apiBaseUrl: backend, shell: { openExternal: async () => true } }
  }, BACKEND)
  await page.goto(`${BASE}/#/pages/project-overview/project-overview?id=${project.id}`, { waitUntil: 'domcontentloaded', timeout: 120000 })
  await page.waitForSelector('[title="AI 助手"], [title="AI Assistant"]', { timeout: 120000 })

  const click = async (selector) => {
    await page.waitForSelector(selector, { visible: true, timeout: 30000 })
    const point = await page.$eval(selector, (element) => {
      const box = element.getBoundingClientRect()
      return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
    })
    await page.mouse.click(point.x, point.y)
  }
  const setComposer = async (text) => {
    await click('.chat-input-rich')
    await page.keyboard.type(text, { delay: 2 })
  }
  const clickRowAction = async (contains, actionText) => {
    const point = await page.evaluate(({ contains, actionText }) => {
      const row = [...document.querySelectorAll('.agent-inbox-row')].find((node) => node.innerText.includes(contains))
      const action = row && [...row.querySelectorAll('.inbox-action')].find((node) => node.innerText.trim() === actionText)
      if (!action) return null
      const box = action.getBoundingClientRect()
      return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
    }, { contains, actionText })
    if (!point) throw new Error(`missing action ${actionText} for ${contains}`)
    await page.mouse.click(point.x, point.y)
  }

  await click('[title="AI 助手"], [title="AI Assistant"]')
  await page.waitForSelector('.chat-input-rich', { timeout: 30000 })
  await setComposer(`${MARKER} start a deliberately slow task`)
  await click('.send-btn')
  await page.waitForSelector('.stop-btn', { timeout: 10000 })

  await setComposer('steer while the fixture model is running')
  await click('.send-btn')
  await page.waitForFunction(() => document.querySelectorAll('.agent-inbox-row').length >= 1, { timeout: 10000 })
  await setComposer('queued follow up')
  await click('.alternate-send')
  await page.waitForFunction(() => document.querySelectorAll('.agent-inbox-row').length >= 2, { timeout: 10000 })
  await page.screenshot({ path: path.join(OUT, 'queue-running-360px.png') })

  await clickRowAction('queued follow up', 'Edit').catch(() => clickRowAction('queued follow up', '编辑'))
  const edit = await page.$('.agent-inbox-edit')
  await edit.click({ clickCount: 3 })
  await page.keyboard.type('queued follow up edited')
  await clickRowAction('queued follow up edited', 'Save').catch(() => clickRowAction('queued follow up edited', '保存'))
  await clickRowAction('queued follow up edited', '↑')
  await click('.stop-btn')
  await page.waitForFunction(() => !document.querySelector('.stop-btn'), { timeout: 10000 })
  await clickRowAction('queued follow up edited', 'Send now').catch(() => clickRowAction('queued follow up edited', '立即发送'))
  await page.waitForSelector('.stop-btn', { timeout: 10000 })

  await click('.memory-header-btn')
  await page.waitForSelector('.memory-dialog', { timeout: 10000 })
  await page.screenshot({ path: path.join(OUT, 'memory-index.png') })
  const preferencePoint = await page.evaluate(() => {
    const item = [...document.querySelectorAll('.memory-file')].find((node) => node.innerText.includes('preferences.md'))
    if (!item) return null
    const box = item.getBoundingClientRect()
    return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
  })
  if (!preferencePoint) throw new Error('preferences.md missing from memory browser')
  await page.mouse.click(preferencePoint.x, preferencePoint.y)
  await page.waitForFunction(() => document.querySelector('.memory-textarea')?.value.includes('Initial fixture value'), { timeout: 10000 })
  const textarea = await page.$('.memory-textarea')
  await textarea.click()
  await page.keyboard.type(`\nRetained exactly: ${MARKER}`)
  const beforeSave = await page.$eval('.memory-textarea', (element) => element.value)
  await click('.memory-button.primary')
  await page.waitForFunction((expected) => document.querySelector('.memory-textarea')?.value === expected, { timeout: 10000 }, beforeSave)
  await page.screenshot({ path: path.join(OUT, 'memory-editor.png') })

  const savedFile = dataOf(await api(`/api/ai/memory/file?spaceId=${encodeURIComponent(memorySpace.id)}&path=preferences.md`))
  if (savedFile.content !== beforeSave) throw new Error('memory Markdown did not round-trip exactly')
  console.log(`AI alignment E2E passed. Screenshots: ${OUT}`)
} catch (error) {
  failed = true
  console.error(error.stack || error)
} finally {
  if (browser) await browser.close().catch(() => {})
  fixture.server.closeAllConnections?.()
  await new Promise((resolve) => fixture.server.close(resolve))
}

process.exit(failed ? 1 : 0)
