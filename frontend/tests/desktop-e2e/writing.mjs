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
const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-writing-e2e-project-'))
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

const opened = await api('/api/projects/open-local', { method: 'POST', body: { localRoot: projectRoot, createFolder: false, name: projectName } })
const project = { id: opened?.data?.projectId }
if (!project.id) throw new Error(`创建临时本地项目失败: ${JSON.stringify(opened)}`)
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
  console.log('阶段 1/4：进入工作台并打开文件面板')
  // Electron 主进程的首次 loadURL 可能晚于 CDP 连接完成；等它落定后重新取当前页，
  // 避免导航途中旧 execution context 被回收成 Promise was collected。
  await sleep(5000)
  page = (await browser.pages()).find((candidate) => candidate.url().startsWith(DEVURL) && !candidate.isClosed()) || page
  await hardenPageInput(page)
  page.on('request', (request) => {
    if (request.url().includes('/api/')) requests.push(`${request.method()} ${new URL(request.url()).pathname}`)
  })
  await page.evaluate(() => localStorage.setItem('awd_app_language', 'zh-CN'))
  const workbenchUrl = `${DEVURL}/#/pages/project-overview/project-overview?id=${project.id}`
  let workbenchReady = false
  let workbenchSnapshot = null
  for (let attempt = 0; attempt < 20 && !workbenchReady; attempt++) {
    if (!page.url().includes('project-overview/project-overview')) {
      await page.goto(workbenchUrl, { waitUntil: 'domcontentloaded' })
    }
    workbenchSnapshot = await page.evaluate((expectedId) => {
      const root = document.querySelector('.page-project-overview')
      if (!root) return {
        ready: false,
        route: location.hash,
        optionalDialog: !!document.querySelector('.optional-components-dialog'),
      }
      let seed = root.__vueParentComponent
        || [...root.querySelectorAll('*')].find((element) => element.__vueParentComponent)?.__vueParentComponent
      if (!seed) return { ready: false, route: location.hash, root: true }
      // 生产构建会裁掉部分静态 vnode children，不能只从应用根向下 BFS；
      // 真实工作台根 DOM 上的 owner 链稳定保留，先沿它向上找页面实例。
      for (let owner = seed; owner; owner = owner.parent) {
        const proxy = owner.proxy
        if (proxy && typeof proxy.openFile === 'function' && String(proxy.projectId) === String(expectedId)) {
          proxy.leftPaneKey = 'files'
          proxy.sidebarCollapsed = false
          const tree = proxy.$refs?.fileTree
          return {
            ready: !!tree && !!document.querySelector('.file-tree'),
            route: location.hash,
            root: true,
            projectId: proxy.projectId,
            leftPaneKey: proxy.leftPaneKey,
            sidebarCollapsed: proxy.sidebarCollapsed,
            hasTree: !!tree,
            fileTreeDom: !!document.querySelector('.file-tree'),
            expectedId,
          }
        }
      }
      return { ready: false, route: location.hash, root: true, owner: false }
    }, project.id).catch(() => null)
    workbenchReady = !!workbenchSnapshot?.ready
    if (!workbenchReady) {
      await sleep(1500)
      if (attempt === 5 || attempt === 12) await page.goto(workbenchUrl, { waitUntil: 'domcontentloaded' })
    }
  }
  if (!workbenchReady) throw new Error(`工作台/文件面板未就绪: ${JSON.stringify(workbenchSnapshot)}`)

  const created = await page.evaluate(async (expectedId) => {
    const root = document.querySelector('.page-project-overview')
    let seed = root?.__vueParentComponent
      || [...(root?.querySelectorAll('*') || [])].find((element) => element.__vueParentComponent)?.__vueParentComponent
    if (!seed) return { error: 'Vue root missing' }
    for (let owner = seed; owner; owner = owner.parent) {
      const proxy = owner.proxy
      const tree = proxy?.$refs?.fileTree
      if (tree && typeof tree.createBlankWord === 'function' && typeof proxy.openFile === 'function'
          && String(proxy.projectId) === String(expectedId)) {
        await tree.createBlankWord()
        await tree.loadFiles()
        const file = tree.displayFiles.find((item) => item.fileType === 'docx' || item.name?.endsWith('.docx'))
        if (!file) return { error: 'created docx missing' }
        proxy.openFile(file)
        return { id: file.id, name: file.name }
      }
    }
    return { error: 'FileTree owner missing' }
  }, project.id)
  if (created.error) throw new Error(created.error)
  console.log(`阶段 2/4：已创建并打开 ${created.name}`)
  await page.waitForSelector('webview', { timeout: 30000 })

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
  if (!guest) {
    const targets = browser.targets().filter((target) => target.type() === 'webview').map((target) => target.url())
    throw new Error(`LOWA guest 未就绪；webview targets=${JSON.stringify(targets)}`)
  }
  console.log('阶段 3/4：LOWA guest 已就绪，执行 IME 与 Tab 补全')
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
  if (!requests.includes(`GET /api/projects/${project.id}/completion`)) {
    throw new Error('请求监听未捕获词库加载，不能验证在线查询次数')
  }
  if (requests.some((item) => item.endsWith(`/projects/${project.id}/completion/lookup`))) {
    throw new Error(`输入与补全期间意外触发在线 lookup: ${JSON.stringify(requests)}`)
  }
  console.log('阶段 4/4：自动保存和下载校验完成')
  console.log(`通过：IME 输入 → 2 候选 → Tab 接受 → 自动保存 → docx 命中“${selectedName}”；在线 lookup=0。`)
} catch (error) {
  failed = error
  console.error(`失败：${error.stack || error.message}`)
} finally {
  try { await browser?.disconnect() } catch {}
  killTree()
  try { await api(`/api/projects/${project.id}`, { method: 'DELETE' }) }
  catch (error) { console.error(`清理项目失败：${error.message}`) }
  try { fs.rmSync(projectRoot, { recursive: true, force: true }) } catch {}
}

if (failed) process.exit(1)
