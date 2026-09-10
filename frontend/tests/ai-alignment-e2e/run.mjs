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
import { fileURLToPath } from 'node:url'
import { cdpOwnershipError, hardenPageInput, pickCdpPort, spawnElectron, waitForCdpWs } from '../_lib/electron-cdp.mjs'
import { prepareWritingIsolation } from '../_lib/writing-isolation.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendDir = path.resolve(here, '../..')
const sourceDesktopDir = process.env.AI_ALIGNMENT_E2E_DESKTOP_SOURCE || path.resolve(frontendDir, '../desktop')

const BASE = process.env.AI_ALIGNMENT_E2E_BASE || 'http://127.0.0.1:5174'
const BACKEND = process.env.AI_ALIGNMENT_E2E_BACKEND || 'http://127.0.0.1:9797'
const CHROME = process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
const OUT = process.env.AI_ALIGNMENT_E2E_OUT || path.join(os.tmpdir(), 'ai-alignment-e2e')
const DESKTOP = process.env.AI_ALIGNMENT_E2E_DESKTOP === '1'
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
async function until(check, timeout = 30000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const value = await check()
    if (value) return value
    await sleep(200)
  }
  throw new Error('timed out waiting for backend state')
}
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
  let streamingMainRequests = 0
  let heldMainResponse = null
  const completeStream = (response, content) => {
    response.write(`data: ${JSON.stringify({ id: 'fixture', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant', content }, finish_reason: null }] })}\n\n`)
    response.write(`data: ${JSON.stringify({ id: 'fixture', object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: 'stop' }] })}\n\n`)
    response.end('data: [DONE]\n\n')
  }
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
    if (isMain && payload.stream) streamingMainRequests += 1
    const content = isMain ? `<final>Fixture completed ${mainRequests}</final>` : 'Fixture helper response'

    if (payload.stream) {
      response.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache' })
      response.flushHeaders()
      if (isMain && streamingMainRequests === 1) {
        heldMainResponse = () => {
          if (!heldMainResponse) return
          heldMainResponse = null
          completeStream(response, content)
        }
      } else {
        setTimeout(() => completeStream(response, content), 25)
      }
      return
    }

    setTimeout(() => {
      response.writeHead(200, { 'Content-Type': 'application/json' })
      response.end(JSON.stringify({
        id: 'fixture', object: 'chat.completion', created: Math.floor(Date.now() / 1000), model: payload.model || 'fixture',
        choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
      }))
    }, 25)
  })
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve({
    server,
    baseUrl: `http://127.0.0.1:${server.address().port}/v1`,
    hasHeldResponse: () => heldMainResponse !== null,
    releaseHeldResponse: () => heldMainResponse?.(),
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
let electron = null
let killElectron = null
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

  let page
  if (DESKTOP) {
    const backendPort = new URL(BACKEND).port
    const isolation = prepareWritingIsolation({
      root: process.env.AI_ALIGNMENT_E2E_ROOT,
      desktopDir: sourceDesktopDir,
      editorDist: process.env.AI_ALIGNMENT_E2E_EDITOR_DIST || path.join(frontendDir, 'dist/zetaoffice'),
      backendPort,
    })
    const cdpPort = pickCdpPort('AI_ALIGNMENT_E2E_CDP_PORT', 9470)
    const launched = spawnElectron({
      desktopDir: isolation.desktopDir,
      cdpPort,
      env: { AIWORKDECK_DESKTOP_DEV: '1', CHECKBA_DEV_SERVER_URL: BASE, CHECKBA_BACKEND_PORT: backendPort },
    })
    electron = launched.elec
    killElectron = launched.killTree
    const electronLog = fs.createWriteStream(path.join(OUT, 'electron.log'))
    electron.stdout.pipe(electronLog)
    electron.stderr.pipe(electronLog)
    const ws = await waitForCdpWs(cdpPort, 60, electron)
    if (!ws) throw new Error(`Electron CDP did not start; see ${path.join(OUT, 'electron.log')}`)
    const ownership = cdpOwnershipError(cdpPort, electron)
    if (ownership) throw new Error(ownership)
    browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })
    page = await until(async () => (await browser.pages()).find((candidate) => candidate.url().startsWith(BASE)) || false, 30000)
    const injected = await page.evaluate(() => window.checkbaDesktop?.apiBaseUrl || null)
    if (!injected || new URL(injected).port !== backendPort) {
      throw new Error(`Electron injected backend ${injected}; expected ${BACKEND}`)
    }
  } else {
    browser = await puppeteer.launch({ executablePath: CHROME, headless: process.env.AI_ALIGNMENT_E2E_HEADFUL !== '1', args: ['--no-first-run'] })
    page = await browser.newPage()
    await page.setViewport({ width: 1280, height: 820 })
    await page.evaluateOnNewDocument((backend) => {
      window.checkbaDesktop = { apiBaseUrl: backend, shell: { openExternal: async () => true } }
    }, BACKEND)
  }
  await hardenPageInput(page)
  await page.goto(`${BASE}/#/pages/project-overview/project-overview?id=${project.id}`, { waitUntil: 'domcontentloaded', timeout: 120000 })
  await hardenPageInput(page)
  await page.waitForSelector('[title="AI 助手"], [title="AI Assistant"]', { timeout: 120000 })

  const click = async (selector) => {
    await page.waitForSelector(selector, { visible: true, timeout: 30000 })
    const point = await page.$eval(selector, (element) => {
      const box = element.getBoundingClientRect()
      const x = box.left + box.width / 2
      const y = box.top + box.height / 2
      const hit = document.elementFromPoint(x, y)
      return {
        x,
        y,
        blocker: !hit ? 'outside the viewport' : !element.contains(hit)
          ? `${hit.tagName.toLowerCase()}.${[...hit.classList].join('.')}`
          : null,
      }
    })
    if (point.blocker) throw new Error(`${selector} is covered by ${point.blocker}`)
    await page.mouse.click(point.x, point.y)
  }
  const setComposer = async (text) => {
    await click('.side-panel-ai .chat-input-rich')
    await page.keyboard.type(text, { delay: 2 })
    await page.waitForFunction((expected) => document.querySelector('.side-panel-ai .chat-input-rich')?.innerText.includes(expected),
      { timeout: 5000 }, text)
    await sleep(150)
  }
  const clickRowAction = async (contains, actionText) => {
    const point = await page.evaluate(({ contains, actionText }) => {
      const row = [...document.querySelectorAll('.agent-inbox-row')].find((node) => {
        const editor = node.querySelector('.agent-inbox-edit')
        const editValue = editor?.value || editor?.querySelector('input')?.value || ''
        return node.innerText.includes(contains) || editValue.includes(contains)
      })
      const action = row && [...row.querySelectorAll('.inbox-action')].find((node) => node.innerText.trim() === actionText)
      if (!action) return null
      const box = action.getBoundingClientRect()
      return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
    }, { contains, actionText })
    if (!point) throw new Error(`missing action ${actionText} for ${contains}`)
    await page.mouse.click(point.x, point.y)
  }
  const clickHistoryEntry = async () => {
    // 下拉每次打开都重拉历史，拉取期间只有一行「加载中...」；先等真实条目出来，
    // 否则下面「只剩一行就点它」的兜底会点在加载占位上，点击无效、下一步超时
    await page.waitForFunction(() => {
      const rows = [...document.querySelectorAll('.ai-dropdown-panel .menu-item:not(.header)')]
      return rows.length > 0 && !rows.some((node) => /加载中|Loading/i.test(node.innerText))
    }, { timeout: 10000 })
    const point = await page.evaluate((marker) => {
      const rows = [...document.querySelectorAll('.ai-dropdown-panel .menu-item:not(.header)')]
      const row = rows.find((node) => node.innerText.includes(marker)) || (rows.length === 1 ? rows[0] : null)
      if (!row) return null
      const box = row.getBoundingClientRect()
      return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
    }, MARKER)
    if (!point) throw new Error('running conversation is missing from history')
    await page.mouse.click(point.x, point.y)
  }

  await click('[title="AI 助手"], [title="AI Assistant"]')
  await page.waitForSelector('.side-panel-ai .chat-input-rich', { visible: true, timeout: 30000 })
  const paneWidth = await page.$eval('.side-panel-ai', (element) => Math.round(element.getBoundingClientRect().width))
  if (paneWidth < 300 || paneWidth > 360) throw new Error(`AI pane is not narrow: ${paneWidth}px`)
  await setComposer(`${MARKER} start a deliberately slow task`)
  const composerState = await page.$eval('.chat-input-rich', (element) => ({ text: element.innerText, html: element.innerHTML }))
  if (!composerState.text.includes(MARKER)) throw new Error(`composer did not retain typed marker: ${JSON.stringify(composerState)}`)
  await click('.send-btn')
  await page.waitForSelector('.stop-btn', { timeout: 10000 })
  const conversationId = await until(async () => {
    const payload = dataOf(await api(`/api/ai/conversations?projectId=${project.id}`))
    const conversations = Array.isArray(payload) ? payload : (payload?.items || payload?.conversations || [])
    const active = conversations.find((conversation) => conversation.title?.includes(MARKER)
      || conversation.lastMessage?.includes(MARKER)) || conversations[0]
    return active?.conversationId || false
  }, 20000)
  const originalRun = await until(async () => {
    const snapshot = await api(`/api/agent/inbox/${conversationId}`)
    return snapshot.status === 'RUNNING' && snapshot.runId ? snapshot : false
  }, 10000)
  await until(() => fixture.hasHeldResponse(), 10000)

  await click('[title="New Chat"]')
  await page.waitForSelector('.chat-interface.is-empty', { visible: true, timeout: 10000 })
  const detachedRun = await api(`/api/agent/inbox/${conversationId}`)
  if (detachedRun.status !== 'RUNNING' || detachedRun.runId !== originalRun.runId) {
    throw new Error(`New Chat changed the server run: ${JSON.stringify({ before: originalRun, after: detachedRun })}`)
  }
  await click('[title="History"]')
  await page.waitForSelector('.ai-dropdown-panel', { visible: true, timeout: 10000 })
  await clickHistoryEntry()
  await page.waitForFunction((marker) => [...document.querySelectorAll('.user-bubble')]
    .some((node) => node.innerText.includes(marker)), { timeout: 10000 }, MARKER)
  await page.waitForSelector('.stop-btn', { visible: true, timeout: 10000 })
  const reattachedRun = await api(`/api/agent/inbox/${conversationId}`)
  if (reattachedRun.status !== 'RUNNING' || reattachedRun.runId !== originalRun.runId) {
    throw new Error(`history reattach changed the server run: ${JSON.stringify({ before: originalRun, after: reattachedRun })}`)
  }
  await page.screenshot({ path: path.join(OUT, 'reattached-running-360px.png') })

  await setComposer('steer while the fixture model is running')
  await click('.send-btn')
  await page.waitForFunction(() => [...document.querySelectorAll('.user-bubble')]
    .some((node) => node.innerText.includes('steer while the fixture model is running')), { timeout: 10000 })
  await setComposer('queued follow up')
  await click('.alternate-send')
  await page.waitForFunction(() => [...document.querySelectorAll('.agent-inbox-row')]
    .some((node) => node.innerText.includes('queued follow up')), { timeout: 10000 })
  await page.screenshot({ path: path.join(OUT, 'queue-running-360px.png') })

  await clickRowAction('queued follow up', 'Edit').catch(() => clickRowAction('queued follow up', '编辑'))
  await page.$eval('.agent-inbox-edit', (editor, value) => {
    const input = editor.matches('input') ? editor : editor.querySelector('input')
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set
    setter.call(input, value)
    input.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: value }))
  }, 'queued follow up edited')
  await clickRowAction('queued follow up edited', 'Save').catch(() => clickRowAction('queued follow up edited', '保存'))
  await page.waitForFunction(() => [...document.querySelectorAll('.agent-inbox-message')]
    .some((node) => node.innerText.includes('queued follow up edited')), { timeout: 10000 })
  await clickRowAction('queued follow up edited', '↑')
  await click('.stop-btn')
  await page.waitForFunction(() => !document.querySelector('.stop-btn'), { timeout: 10000 })
  fixture.releaseHeldResponse()
  await until(async () => {
    const status = (await api(`/api/agent/inbox/${conversationId}`)).status
    return status === 'CANCELLED' || status === 'ERROR' || status === 'FINISHED'
  }, 20000)
  await clickRowAction('queued follow up edited', 'Send now').catch(() => clickRowAction('queued follow up edited', '立即发送'))
  await until(async () => {
    const snapshot = await api(`/api/agent/inbox/${conversationId}`)
    return snapshot.status === 'FINISHED' && !snapshot.items.some((item) => item.state === 'pending')
  }, 30000)

  await click('.memory-header-btn')
  await page.waitForSelector('.memory-dialog', { timeout: 10000 })
  // 记忆空间列表是弹窗打开后异步拉的，直接找会赶在列表出来之前
  if (memorySpace.scope === 'project') {
    await page.waitForFunction((projectName) => [...document.querySelectorAll('.memory-space')]
      .some((node) => node.innerText.includes(projectName)), { timeout: 10000 }, project.name)
  }
  const projectScopePoint = await page.evaluate((projectName) => {
    const item = [...document.querySelectorAll('.memory-space')]
      .find((node) => node.innerText.includes(projectName))
    if (!item) return null
    const box = item.getBoundingClientRect()
    return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
  }, project.name)
  if (memorySpace.scope === 'project') {
    if (!projectScopePoint) throw new Error('project memory scope is missing')
    await page.mouse.click(projectScopePoint.x, projectScopePoint.y)
    await page.waitForFunction(() => [...document.querySelectorAll('.memory-file')]
      .some((node) => node.innerText.includes('preferences.md')), { timeout: 10000 })
  }
  await page.screenshot({ path: path.join(OUT, 'memory-index.png') })
  const preferencePoint = await page.evaluate(() => {
    const item = [...document.querySelectorAll('.memory-file')].find((node) => node.innerText.includes('preferences.md'))
    if (!item) return null
    const box = item.getBoundingClientRect()
    return { x: box.left + box.width / 2, y: box.top + box.height / 2 }
  })
  if (!preferencePoint) throw new Error('preferences.md missing from memory browser')
  await page.mouse.click(preferencePoint.x, preferencePoint.y)
  await page.waitForFunction(() => {
    const editor = document.querySelector('.memory-textarea')
    return (editor?.value || editor?.querySelector('textarea')?.value || '').includes('Initial fixture value')
  }, { timeout: 10000 })
  const beforeSave = await page.$eval('.memory-textarea', (editor, marker) => {
    const textarea = editor.matches('textarea') ? editor : editor.querySelector('textarea')
    const next = `${textarea.value}\nRetained exactly: ${marker}`
    const setter = Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype, 'value').set
    setter.call(textarea, next)
    textarea.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: next }))
    return next
  }, MARKER)
  await click('.memory-button.primary')
  await page.waitForFunction((expected) => {
    const editor = document.querySelector('.memory-textarea')
    return (editor?.value || editor?.querySelector('textarea')?.value || '') === expected
  }, { timeout: 10000 }, beforeSave)
  await page.screenshot({ path: path.join(OUT, 'memory-editor.png') })

  const savedFile = dataOf(await api(`/api/ai/memory/file?spaceId=${encodeURIComponent(memorySpace.id)}&path=preferences.md`))
  if (savedFile.content !== beforeSave) throw new Error('memory Markdown did not round-trip exactly')
  console.log(`AI alignment ${DESKTOP ? 'Electron ' : ''}E2E passed. Screenshots: ${OUT}`)
} catch (error) {
  failed = true
  if (browser) {
    const pages = await browser.pages().catch(() => [])
    const page = pages[pages.length - 1]
    if (page) await page.screenshot({ path: path.join(OUT, 'failure.png'), fullPage: true }).catch(() => {})
  }
  console.error(error.stack || error)
} finally {
  if (browser) {
    if (DESKTOP) await browser.disconnect().catch(() => {})
    else await browser.close().catch(() => {})
  }
  if (killElectron) killElectron()
  fixture.releaseHeldResponse()
  fixture.server.closeAllConnections?.()
  await new Promise((resolve) => fixture.server.close(resolve))
}

process.exit(failed ? 1 : 0)
