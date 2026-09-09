#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真实 Electron + LOWA 写作辅助烟测。复用当前 dist，不执行构建，也不触碰系统剪贴板。

import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { pickCdpPort, spawnElectron, waitForCdpWs, cdpOwnershipError, hardenPageInput } from '../_lib/electron-cdp.mjs'
import { ensureUnlocked } from '../_lib/license-gate.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendDir = path.resolve(here, '../..')
const desktopDir = path.resolve(frontendDir, '../desktop')
const DEVURL = process.env.DESKTOP_E2E_DEVURL || 'http://127.0.0.1:5188'
const BACKEND = process.env.APP_E2E_BACKEND || 'http://127.0.0.1:9848'
const BACKEND_PORT = new URL(BACKEND).port
const CDP_PORT = pickCdpPort('WRITING_E2E_CDP_PORT', 9460)
const projectName = `写作辅助E2E_${Date.now()}`
const selectedName = '北京当红晴天律师事务所'
const otherName = '北京当红科技有限公司'
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

let puppeteer
try { puppeteer = (await import('puppeteer-core')).default }
catch { console.error('缺少 puppeteer-core'); process.exit(2) }

async function api(endpoint, options = {}) {
  const response = await fetch(BACKEND + endpoint, {
    method: options.method || 'GET',
    headers: { 'Content-Type': 'application/json' },
    body: options.body ? JSON.stringify(options.body) : undefined,
  })
  const body = await response.json().catch(() => null)
  if (!response.ok) throw new Error(`${options.method || 'GET'} ${endpoint} -> ${response.status}: ${JSON.stringify(body)}`)
  return body
}

for (const [label, url] of [['Vite', DEVURL], ['后端', BACKEND + '/api/skills/market/list']]) {
  if (!await fetch(url).then((r) => r.ok).catch(() => false)) throw new Error(`${label} 未就绪: ${url}`)
}
if (!fs.existsSync(path.join(frontendDir, 'dist/zetaoffice/lowa/soffice.js'))) throw new Error('dist/zetaoffice 引擎缺失（本测试不重建）')
await ensureUnlocked(api)
const wizard = await api('/api/admin/wizard')
if (wizard?.initialized === false) await api('/api/admin/wizard', { method: 'POST', body: { ai: { activeProvider: 'OPENROUTER' } } })

const project = await api('/api/projects', { method: 'POST', body: { name: projectName, projectType: 'BLANK' } })
await api(`/api/projects/${project.id}/completion/learn`, {
  method: 'POST',
  body: { scope: 'project', entries: [
    { text: selectedName, kind: 'COMPANY' },
    { text: otherName, kind: 'COMPANY' },
  ] },
})

console.log(`项目 #${project.id} 已本地写入两个机构；启动 Electron，直接使用现有 dist。`)
const { elec, killTree } = spawnElectron({
  desktopDir,
  cdpPort: CDP_PORT,
  env: { AIWORKDECK_DESKTOP_DEV: '1', CHECKBA_DEV_SERVER_URL: DEVURL, CHECKBA_BACKEND_PORT: BACKEND_PORT },
})
const logPath = path.join(os.tmpdir(), `writing-e2e-electron-${CDP_PORT}.log`)
const log = fs.createWriteStream(logPath)
elec.stdout.pipe(log); elec.stderr.pipe(log)

let browser
let failed = null
const requests = []
try {
  const ws = await waitForCdpWs(CDP_PORT, 60, elec)
  if (!ws) throw new Error(`CDP 未就绪；日志 ${logPath}`)
  const ownership = cdpOwnershipError(CDP_PORT, elec)
  if (ownership) throw new Error(ownership)
  browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })

  let page
  for (let i = 0; i < 40 && !page; i++) {
    page = (await browser.pages()).find((candidate) => candidate.url().startsWith(DEVURL))
    if (!page) await sleep(1000)
  }
  if (!page) throw new Error('找不到 Electron 主渲染页')
  await hardenPageInput(page)
  page.on('request', (request) => {
    if (request.url().includes('/api/')) requests.push(`${request.method()} ${new URL(request.url()).pathname}`)
  })
  await page.evaluate(() => localStorage.setItem('awd_app_language', 'zh-CN'))
  await page.goto(`${DEVURL}/#/pages/project-overview/project-overview?id=${project.id}`, { waitUntil: 'networkidle2' })
  await page.reload({ waitUntil: 'networkidle2' })
  await page.waitForFunction(() => document.body.innerText.includes('资源管理器'), { timeout: 60000 })

  const created = await page.evaluate(async () => {
    let seed = [...document.querySelectorAll('*')].find((element) => element.__vueParentComponent)?.__vueParentComponent
    if (!seed) return { error: 'Vue root missing' }
    while (seed.parent) seed = seed.parent
    const queue = [seed]
    while (queue.length) {
      const component = queue.shift()
      const proxy = component.proxy
      const tree = proxy?.$refs?.fileTree
      if (tree && typeof tree.createBlankWord === 'function' && typeof proxy.openFile === 'function') {
        await tree.createBlankWord()
        await tree.loadFiles()
        const file = tree.displayFiles.find((item) => item.fileType === 'docx' || item.name?.endsWith('.docx'))
        if (!file) return { error: 'created docx missing' }
        proxy.openFile(file)
        return { id: file.id, name: file.name }
      }
      const stack = [component.subTree]
      while (stack.length) {
        const vnode = stack.pop()
        if (!vnode) continue
        if (vnode.component) queue.push(vnode.component)
        else if (Array.isArray(vnode.children)) stack.push(...vnode.children)
      }
    }
    return { error: 'FileTree owner missing' }
  })
  if (created.error) throw new Error(created.error)
  await page.waitForSelector('webview', { timeout: 30000 })
  await page.waitForFunction(() => {
    let seed = [...document.querySelectorAll('*')].find((element) => element.__vueParentComponent)?.__vueParentComponent
    if (!seed) return false
    while (seed.parent) seed = seed.parent
    const queue = [seed]
    while (queue.length) {
      const component = queue.shift()
      const editor = component.proxy
      if (editor?.file && editor.executor && (editor.ready === true || /就绪|Ready/i.test(editor.statusText || ''))) return true
      const stack = [component.subTree]
      while (stack.length) {
        const vnode = stack.pop()
        if (!vnode) continue
        if (vnode.component) queue.push(vnode.component)
        else if (Array.isArray(vnode.children)) stack.push(...vnode.children)
      }
    }
    return false
  }, { timeout: 180000 })

  let guest
  for (let i = 0; i < 180 && !guest; i++) {
    const targets = browser.targets().filter((target) => target.type() === 'webview')
    for (const target of targets) {
      const candidate = await target.page().catch(() => null)
      const active = candidate && await candidate.evaluate(() => {
        const toggle = document.querySelector('.awd-wa-toggle')
        return !!document.querySelector('#canvas, canvas') && !!toggle && !toggle.hidden
      }).catch(() => false)
      if (active) { guest = candidate; break }
    }
    if (!guest) await sleep(1000)
  }
  if (!guest) throw new Error('LOWA guest 未就绪')
  await hardenPageInput(guest)
  await sleep(3000)

  const canvas = await guest.$('#canvas, canvas')
  const box = await canvas.boundingBox()
  if (!box) throw new Error('LOWA canvas 不可见')
  await guest.mouse.click(box.x + Math.min(180, box.width / 2), box.y + Math.min(120, box.height / 2))
  await guest.evaluate(() => document.activeElement?.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, data: '' })))
  await guest.keyboard.sendCharacter('北京当红')
  await guest.evaluate(() => document.activeElement?.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: '北京当红' })))

  await guest.waitForFunction(() => document.querySelectorAll('.awd-wa-option').length === 2, { timeout: 15000 })
  const candidates = await guest.$$eval('.awd-wa-option', (nodes) => nodes.map((node) => node.childNodes[0]?.textContent || node.textContent))
  if (!candidates.includes(selectedName) || !candidates.includes(otherName)) throw new Error(`候选异常: ${JSON.stringify(candidates)}`)
  const activeCandidate = await guest.$eval('.awd-wa-option[aria-selected="true"]', (node) => node.childNodes[0]?.textContent || node.textContent)
  if (activeCandidate !== selectedName) await guest.keyboard.press('ArrowDown')
  await guest.keyboard.press('Tab')
  await guest.waitForFunction((name) => document.querySelector('.awd-wa-heading')?.textContent.includes(name), { timeout: 15000 }, selectedName)

  await page.waitForFunction(() => {
    let seed = [...document.querySelectorAll('*')].find((element) => element.__vueParentComponent)?.__vueParentComponent
    if (!seed) return false
    while (seed.parent) seed = seed.parent
    const queue = [seed]
    while (queue.length) {
      const component = queue.shift()
      if (component.proxy?.file && typeof component.proxy.saveDocument === 'function') {
        if (!component.proxy.dirty && !component.proxy.saving) return true
      }
      const stack = [component.subTree]
      while (stack.length) {
        const vnode = stack.pop()
        if (!vnode) continue
        if (vnode.component) queue.push(vnode.component)
        else if (Array.isArray(vnode.children)) stack.push(...vnode.children)
      }
    }
    return false
  }, { timeout: 70000 })

  const files = await api(`/api/projects/${project.id}/files`)
  const doc = files.find((item) => item.fileType === 'docx' || item.name?.endsWith('.docx'))
  if (!doc) throw new Error('API 文件列表无 docx')
  const response = await fetch(`${BACKEND}/api/files/${doc.id}/download`)
  if (!response.ok) throw new Error(`下载 docx 失败: ${response.status}`)
  const tempDoc = path.join(os.tmpdir(), `writing-e2e-${project.id}.docx`)
  fs.writeFileSync(tempDoc, Buffer.from(await response.arrayBuffer()))
  const xml = execFileSync('unzip', ['-p', tempDoc, 'word/document.xml'], { encoding: 'utf8' })
  fs.rmSync(tempDoc, { force: true })
  if (!xml.includes(selectedName)) throw new Error('下载的 docx 未包含 Tab 接受后的机构全称')
  if (requests.some((item) => item.endsWith(`/projects/${project.id}/completion/lookup`))) {
    throw new Error(`输入与补全期间意外触发在线 lookup: ${JSON.stringify(requests)}`)
  }
  console.log(`通过：IME 输入 → 2 候选 → Tab 接受 → 自动保存 → docx 命中“${selectedName}”；在线 lookup=0。`)
} catch (error) {
  failed = error
  console.error(`失败：${error.stack || error.message}`)
} finally {
  try { await browser?.disconnect() } catch {}
  killTree()
  try { await api(`/api/projects/${project.id}`, { method: 'DELETE' }) }
  catch (error) { console.error(`清理项目失败：${error.message}`) }
}

if (failed) process.exit(1)
