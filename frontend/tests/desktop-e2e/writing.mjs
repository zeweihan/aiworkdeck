#!/usr/bin/env node
// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 真实 Electron + LOWA 写作辅助烟测。复用当前 dist，不执行构建，也不触碰系统剪贴板。

import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { fileURLToPath } from 'node:url'
import { pickCdpPort, spawnElectron, waitForCdpWs, cdpOwnershipError, hardenPageInput } from '../_lib/electron-cdp.mjs'
import { ensureUnlocked } from '../_lib/license-gate.mjs'
import { prepareWritingIsolation } from '../_lib/writing-isolation.mjs'

const here = path.dirname(fileURLToPath(import.meta.url))
const frontendDir = path.resolve(here, '../..')
const desktopDir = path.resolve(frontendDir, '../desktop')
const DEVURL = process.env.DESKTOP_E2E_DEVURL || 'http://127.0.0.1:5188'
const BACKEND = process.env.APP_E2E_BACKEND || 'http://127.0.0.1:9848'
const BACKEND_PORT = new URL(BACKEND).port
const CDP_PORT = pickCdpPort('WRITING_E2E_CDP_PORT', 9460)
const projectName = `写作辅助E2E_${Date.now()}`
const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-writing-e2e-project-'))
const selectedName = '青岛致衡贸易有限公司'
const otherName = '青岛致衡科技有限公司'
const linkKey = `EVID_WRITING_${Date.now()}`
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
const isolation = prepareWritingIsolation({ root: process.env.WRITING_E2E_ROOT, desktopDir,
  editorDist: process.env.WRITING_E2E_EDITOR_DIST || path.join(frontendDir, 'dist/zetaoffice'), backendPort: BACKEND_PORT })
await ensureUnlocked(api)
const wizard = await api('/api/admin/wizard')
if (wizard?.initialized === false) await api('/api/admin/wizard', { method: 'POST', body: { ai: { activeProvider: 'OPENROUTER' } } })

// Synthetic legal prose is the only candidate source. No completion/learn setup.
const zip = new JSZip()
zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
zip.file('word/_rels/document.xml.rels', `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="link" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="${BACKEND}/api/admin/wizard" TargetMode="External"/><Relationship Id="projectLink" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="FILELINK_PLACEHOLDER" TargetMode="External"/></Relationships>`)
zip.file('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:r><w:t>股东名册中青岛致衡贸易有限公司持股40%。</w:t></w:r></w:p><w:p><w:r><w:t>股东名册记载青岛致衡科技有限公司。</w:t></w:r></w:p><w:p><w:r><w:t>《公司章程》记载韩明远持股60%。</w:t></w:r></w:p><w:p><w:hyperlink r:id="link"><w:r><w:t>Local source link</w:t></w:r></w:hyperlink></w:p><w:p><w:hyperlink w:anchor="target"><w:r><w:t>Internal source link</w:t></w:r></w:hyperlink></w:p><w:p><w:hyperlink r:id="projectLink"><w:r><w:t>Project source link</w:t></w:r></w:hyperlink></w:p><w:p><w:r><w:t>第一条 付款金额：【待填写】。</w:t></w:r></w:p><w:p><w:bookmarkStart w:id="1" w:name="target"/><w:r><w:t>Target excerpt retained in original document.</w:t></w:r><w:bookmarkEnd w:id="1"/></w:p><w:p/></w:body></w:document>')
fs.writeFileSync(path.join(projectRoot, 'legal-source.docx'), await zip.generateAsync({ type: 'nodebuffer' }))
const targetZip = await JSZip.loadAsync(await zip.generateAsync({ type: 'nodebuffer' }))
targetZip.file('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>The linked project target excerpt.</w:t></w:r></w:p></w:body></w:document>')
fs.writeFileSync(path.join(projectRoot, 'linked-target.docx'), await targetZip.generateAsync({ type: 'nodebuffer' }))

const opened = await api('/api/projects/open-local', { method: 'POST', body: { localRoot: projectRoot, createFolder: false, name: projectName } })
const project = { id: opened?.data?.projectId }
if (!project.id) throw new Error(`创建临时本地项目失败: ${JSON.stringify(opened)}`)
const fixtureFiles = await api(`/api/projects/${project.id}/files`)
const sourceFile = fixtureFiles.find(file => file.name === 'legal-source.docx')
const targetFile = fixtureFiles.find(file => file.name === 'linked-target.docx')
assert.ok(sourceFile?.id && targetFile?.id, 'Both synthetic source and linked target must be indexed')
const link = await api(`/api/projects/${project.id}/evidence-links`, { method: 'POST', body: {
  docFileId: sourceFile.id, linkKey, anchorText: 'Project source link', createdByKind: 'human',
  targets: [{ fileId: targetFile.id, locatorJson: JSON.stringify({ type: 'docx', quote: 'The linked project target excerpt.' }) }],
} })
assert.ok(link.linkKey === linkKey, 'Synthetic project evidence link must be stored')
const relationships = await zip.file('word/_rels/document.xml.rels').async('string')
zip.file('word/_rels/document.xml.rels', relationships.replace('FILELINK_PLACEHOLDER', `checkba://filelink?k=${linkKey}&amp;projectId=${project.id}`))
fs.writeFileSync(path.join(projectRoot, 'legal-source.docx'), await zip.generateAsync({ type: 'nodebuffer' }))
await api(`/api/projects/${project.id}/completion/learned?scope=user`, { method: 'DELETE' })
assert.equal((await api(`/api/projects/${project.id}/completion`)).items.length, 0)
console.log(`项目 #${project.id} 使用空词库；将从真实正文提取候选。`)
const { elec, killTree } = spawnElectron({
  desktopDir: isolation.desktopDir,
  cdpPort: CDP_PORT,
  env: { AIWORKDECK_DESKTOP_DEV: '1', CHECKBA_DEV_SERVER_URL: DEVURL, CHECKBA_BACKEND_PORT: BACKEND_PORT },
})
const logPath = path.join(os.tmpdir(), `writing-e2e-electron-${CDP_PORT}.log`)
const log = fs.createWriteStream(logPath)
elec.stdout.pipe(log); elec.stderr.pipe(log)

let browser
let failed = null
const requests = []
const reviewRequests = []
try {
  const ws = await waitForCdpWs(CDP_PORT, 60, elec)
  if (!ws) throw new Error(`CDP 未就绪；日志 ${logPath}`)
  const ownership = cdpOwnershipError(CDP_PORT, elec)
  if (ownership) throw new Error(ownership)
  browser = await puppeteer.connect({ browserWSEndpoint: ws, defaultViewport: null })
  for (const event of ['targetcreated', 'targetchanged', 'targetdestroyed']) browser.on(event, target => { if (target.type() === 'webview') console.log('TARGET', event, target.url()) })
  const homeProof = JSON.parse(fs.readFileSync(path.join(isolation.root, 'home-proof.json'), 'utf8'))
  assert.equal(homeProof.home, isolation.home)
  assert.equal(homeProof.profile, isolation.profile)
  assert.equal(cdpOwnershipError(CDP_PORT, { pid: homeProof.pid }), null, 'home proof must describe this Electron instance')

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
    if (new URL(request.url()).pathname.endsWith('/insight/review')) {
      try { reviewRequests.push(JSON.parse(request.postData() || '{}')) } catch {}
    }
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

  const created = sourceFile
  const openedSource = await page.evaluate(({ expectedId, file }) => {
    const root = document.querySelector('.page-project-overview')
    const seed = root?.__vueParentComponent || [...(root?.querySelectorAll('*') || [])].find(el => el.__vueParentComponent)?.__vueParentComponent
    for (let owner = seed; owner; owner = owner.parent) {
      const vm = owner.proxy
      if (String(vm?.projectId) === String(expectedId) && typeof vm.openFile === 'function') {
        vm.openFile(file)
        return true
      }
    }
    return false
  }, { expectedId: project.id, file: sourceFile })
  assert.ok(openedSource, 'The isolated workbench must open the indexed source document')
  console.log(`阶段 2/4：已打开合成资料 ${created.name}`)
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
  console.log('阶段 3/4：LOWA guest 已就绪，从正文采集实体后执行 IME 与 Tab 补全')
  await hardenPageInput(guest)
  const sourceExec = (action, params = {}) => page.evaluate(async ({ projectId, fileId, action, params }) => {
    const root = document.querySelector('.page-project-overview')
    const seed = root?.__vueParentComponent || [...(root?.querySelectorAll('*') || [])].find(el => el.__vueParentComponent)?.__vueParentComponent
    for (let owner = seed; owner; owner = owner.parent) {
      const vm = owner.proxy
      if (String(vm?.projectId) !== String(projectId)) continue
      const executor = vm._libreExecMap?.['left:' + fileId]
      if (executor) return executor.executeCommand(action, params)
    }
    throw new Error('Source document executor missing')
  }, { projectId: project.id, fileId: created.id, action, params })
  const seedDeadline = Date.now() + 20000
  let seeded = false
  while (Date.now() < seedDeadline) {
    const vocabulary = await api(`/api/projects/${project.id}/completion`)
    if ([selectedName, otherName].every(text => vocabulary.items.some(item => item.text === text && item.scope === 'project'))) { seeded = true; break }
    await sleep(250)
  }
  assert.ok(seeded, 'Initial document extraction must learn both companies before keyboard input')

  await guest.screenshot({ path: path.join(isolation.root, 'source-document.png') })
  const canvas = await guest.$('#canvas, canvas')
  const box = await canvas.boundingBox()
  if (!box) throw new Error('LOWA canvas 不可见')
  await guest.mouse.click(box.x + Math.min(180, box.width / 2), box.y + Math.min(120, box.height / 2))
  const typeChinese = async text => {
    await guest.evaluate(() => document.activeElement?.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, data: '' })))
    await guest.keyboard.sendCharacter(text)
    await guest.evaluate(value => document.activeElement?.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: value })), text)
  }
  const initialBody = await sourceExec('get_document_text', { maxParagraphs: 200 })
  await sourceExec('select_paragraph', { index: initialBody.paragraphs.at(-1).index })
  await sourceExec('collapse_selection', { to: 'end' })
  await guest.keyboard.press('Enter')
  await typeChinese('青岛致衡')

  await guest.waitForFunction(() => document.querySelectorAll('.awd-wa-option').length >= 2, { timeout: 15000 })
  const candidates = await guest.$$eval('.awd-wa-option', (nodes) => nodes.map((node) => node.childNodes[0]?.textContent || node.textContent))
  if (!candidates.includes(selectedName) || !candidates.includes(otherName)) throw new Error(`候选异常: ${JSON.stringify(candidates)}`)
  for (let step = 0; step < candidates.indexOf(selectedName); step++) await guest.keyboard.press('ArrowDown')
  await guest.screenshot({ path: path.join(isolation.root, 'document-completion.png') })
  await guest.keyboard.press('Tab')
  await guest.waitForFunction((name) => document.querySelector('.awd-wa-heading')?.textContent.includes(name), { timeout: 15000 }, selectedName)

  // Continue normal writing: rules must inspect live text without an explicit parse.
  await guest.keyboard.press('Escape')
  await guest.keyboard.press('End')
  await guest.keyboard.press('Enter')
  const draft = '第一条 付款金额：【待填写】。'
  await guest.evaluate(() => document.activeElement?.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true, data: '' })))
  await guest.keyboard.sendCharacter(draft)
  await guest.evaluate((text) => document.activeElement?.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: text })), draft)
  await guest.waitForFunction(() => /[1-9]\d* 条提示/.test(document.querySelector('.awd-ir-status')?.textContent || ''), { timeout: 30000 }).catch(async error => {
    console.error('INLINE STATE', await guest.evaluate(() => ({ status: document.querySelector('.awd-ir-status')?.outerHTML, panel: document.querySelector('.awd-ir-panel')?.textContent })))
    console.error('REVIEW REQUEST COUNT', reviewRequests.length, 'LAST', JSON.stringify(reviewRequests.at(-1)))
    await guest.screenshot({ path: path.join(os.tmpdir(), 'awd-547-desktop-inline-failure.png') })
    throw error
  })
  await guest.click('.awd-ir-status button')
  await guest.waitForFunction(() => document.querySelector('.awd-ir-panel')?.textContent.includes('存在待定内容'), { timeout: 10000 })
  const clickLabel = async (surface, selector, label) => {
    const nodes = await surface.$$(selector)
    for (const node of nodes) {
      if ((await node.evaluate(el => el.textContent)).startsWith(label)) { await node.click(); return }
    }
    throw new Error(`No visible action: ${label}`)
  }
  console.log('验收：正文补全、Tab、即时规则已通过；拖动与分类')
  const panelBefore = await (await guest.$('.awd-ir-panel')).boundingBox()
  const dragHead = await (await guest.$('.awd-ir-head')).boundingBox()
  await guest.mouse.move(dragHead.x + 50, dragHead.y + 15)
  await guest.mouse.down(); await guest.mouse.move(dragHead.x + 180, Math.max(30, dragHead.y - 90), { steps: 12 }); await guest.mouse.up()
  const panelAfter = await (await guest.$('.awd-ir-panel')).boundingBox()
  assert.ok(Math.abs(panelAfter.x - panelBefore.x) + Math.abs(panelAfter.y - panelBefore.y) > 50, 'review must move with a real title-bar drag')
  const tabVisibility = await guest.$$eval('.awd-ir-tabs [role="tab"]', tabs => tabs.map(tab => {
    const r = tab.getBoundingClientRect(), box = tab.parentElement.getBoundingClientRect()
    return { label: tab.textContent, visible: r.width > 0 && r.left >= box.left - 1 && r.right <= box.right + 1 && r.top >= box.top - 1 && r.bottom <= box.bottom + 1 }
  }))
  assert.ok(tabVisibility.length >= 4 && tabVisibility.every(tab => tab.visible), `All review tabs must be fully visible: ${JSON.stringify(tabVisibility)}`)
  await guest.mouse.move(panelAfter.x + panelAfter.width / 2, panelAfter.y + panelAfter.height - 40)
  await guest.mouse.wheel({ deltaY: 500 })
  await guest.mouse.wheel({ deltaY: -1000 })
  await guest.waitForFunction(() => [...document.querySelectorAll('.awd-ir-tabs [role="tab"]')].every(tab => {
    const r = tab.getBoundingClientRect(), body = tab.closest('.awd-ir-body').getBoundingClientRect()
    return r.top >= body.top - 1 && r.bottom <= body.bottom + 1
  }), { timeout: 3000 })
  await clickLabel(guest, '.awd-ir-tabs [role="tab"]', '待补充')
  assert.ok(await guest.$eval('.awd-ir-panel', el => el.textContent.includes('存在待定内容')))
  await clickLabel(guest, '.awd-ir-tabs [role="tab"]', 'AI 审校')
  assert.equal(await guest.$$eval('.awd-ir-item', rows => rows.length), 0, 'AI category must not contain local placeholder findings')
  assert.equal(reviewRequests.some(body => body.deep), false, 'switching category must never run AI')
  await clickLabel(guest, '.awd-ir-tabs [role="tab"]', '全部')
  await guest.screenshot({ path: path.join(isolation.root, 'review-drag-tabs.png') })
  await clickLabel(guest, '.awd-ir-head button', '收起')

  // Read/position through the real host executor, then activate only by Chromium mouse input.
  console.log('验收：拖动与分类已通过；真实Ctrl链接')
  const textBeforeLinks = (await sourceExec('get_document_text', { maxParagraphs: 200 })).paragraphs.map(p => p.text).join('\n')
  for (const [kind, sourceLabel, label] of [['internal', 'Internal source link', 'Target excerpt retained'], ['project', 'Project source link', 'linked-target.docx'], ['external', 'Local source link', BACKEND + '/api/admin/wizard']]) {
    const paragraphs = (await sourceExec('get_document_text', { maxParagraphs: 200 })).paragraphs
    const index = paragraphs.find(p => p.text.includes(sourceLabel))?.index
    assert.ok(Number.isInteger(index), `Source link paragraph missing: ${sourceLabel}`)
    console.log('验收链接', index, label)
    await sourceExec('select_paragraph', { index }); await sourceExec('collapse_selection', { to: 'start' })
    const raw = await sourceExec('get_cursor_rect')
    const surface = await guest.$eval('#qtcanvas', el => { const r = el.getBoundingClientRect(); return { left: r.left, top: r.top, width: r.width, height: r.height } })
    const caret = raw.nativeCaret
    if (!caret) { console.error('LINK_GEOMETRY', JSON.stringify({raw,surface,index})); await guest.screenshot({path:path.join(isolation.root,'native-geometry-failed.png')}) }
    assert.ok(caret && caret.frameWidth > 0, 'native caret geometry is required for actual link click')
    const scale = surface.width / caret.frameWidth
    const x = surface.left + caret.x * scale + 20
    const y = surface.top + Math.max(0, surface.height - caret.frameHeight * scale) + (caret.y + caret.height / 2) * scale
    await guest.keyboard.down('Control'); await guest.mouse.click(x, y); await guest.keyboard.up('Control')
    await page.waitForSelector('.dlp-card', { timeout: 10000 })
    await page.waitForFunction(text => document.querySelector('.dlp-card')?.textContent.includes(text), { timeout: 10000 }, label)
    assert.ok(await page.$eval('.dlp-card', (el, text) => el.textContent.includes(text), label), 'preview must describe the clicked reference')
    const cursor = await sourceExec('get_cursor_context')
    assert.ok(cursor.paragraph.includes(sourceLabel), 'preview must preserve the source reading position')
    assert.equal((await sourceExec('get_document_text', { maxParagraphs: 200 })).paragraphs.map(p => p.text).join('\n'), textBeforeLinks, 'link preview must not edit text')
    await page.screenshot({ path: path.join(isolation.root, `link-preview-${index}.png`) })
    if (kind === 'internal') await page.click('.dlp-head [aria-label="关闭"]')
    else {
      await clickLabel(page, '.dlp-card uni-button, .dlp-card button', '分屏打开')
      await page.waitForSelector('.pane-right', { timeout: 10000 })
      if (kind === 'external') {
        await page.waitForFunction(url => [...document.querySelectorAll('.pane-right input')].some(el => el.value === url), { timeout: 15000 }, label)
        const webTarget = await browser.waitForTarget(target => target.type() === 'page' && target.url() === label, { timeout: 15000 })
        const webPage = await webTarget.page()
        await webPage.waitForFunction(() => document.body.innerText.includes('"initialized":true'), { timeout: 15000 })
        await webPage.waitForFunction(() => innerWidth > 100 && innerHeight > 100, { timeout: 10000 })
        await webPage.screenshot({ path: path.join(isolation.root, 'right-web-content.png') })
      }
      else await page.waitForFunction(async targetId => {
        const root = document.querySelector('.page-project-overview')
        const seed = root?.__vueParentComponent || [...(root?.querySelectorAll('*') || [])].find(el => el.__vueParentComponent)?.__vueParentComponent
        for (let owner = seed; owner; owner = owner.parent) {
          const vm = owner.proxy, executor = vm?._libreExecMap?.['right:' + targetId]
          if (executor && Number(vm.activeFileIdRight) === targetId) {
            const result = await executor.executeCommand('get_document_text', {})
            return result?.paragraphs?.some(p => p.text.includes('The linked project target excerpt.'))
          }
        }
        return false
      }, { timeout: 120000, polling: 1000 }, targetFile.id)
      const sourceAfterSplit = await sourceExec('get_cursor_context')
      assert.ok(sourceAfterSplit.paragraph.includes(sourceLabel), 'opening alongside must retain the left source document')
      await page.screenshot({ path: path.join(isolation.root, `link-right-split-${index}.png`) })
    }
  }
  await guest.screenshot({ path: path.join(os.tmpdir(), 'awd-547-desktop-inline.png') })
  if (!reviewRequests.some((body) => body.paragraphs?.some((p) => p.text.includes(draft)))) throw new Error('即时审校未读取刚输入的正文')
  if (reviewRequests.some((body) => body.deep !== false)) throw new Error('普通编辑意外调用深入审校')
  if (requests.some((item) => /\/insight\/(parse|entities\/[^/]+\/refresh)$/.test(item))) throw new Error('普通编辑意外触发全文在线核验或外部刷新')

  await page.waitForFunction(fileId => {
    let seed = [...document.querySelectorAll('*')].find((element) => element.__vueParentComponent)?.__vueParentComponent
    if (!seed) return false
    while (seed.parent) seed = seed.parent
    const queue = [seed]
    while (queue.length) {
      const component = queue.shift()
      if (Number(component.proxy?.file?.id) === fileId && typeof component.proxy.saveDocument === 'function') {
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
  }, { timeout: 70000 }, created.id)

  const files = await api(`/api/projects/${project.id}/files`)
  const doc = files.find((item) => Number(item.id) === created.id)
  if (!doc) throw new Error('API 文件列表无 docx')
  const response = await fetch(`${BACKEND}/api/files/${doc.id}/download`)
  if (!response.ok) throw new Error(`下载 docx 失败: ${response.status}`)
  const tempDoc = path.join(os.tmpdir(), `writing-e2e-${project.id}.docx`)
  fs.writeFileSync(tempDoc, Buffer.from(await response.arrayBuffer()))
  const xml = execFileSync('unzip', ['-p', tempDoc, 'word/document.xml'], { encoding: 'utf8' })
  fs.rmSync(tempDoc, { force: true })
  if (xml.split(selectedName).length - 1 < 2) throw new Error('下载的 docx 未在原始资料外新增 Tab 接受后的机构全称')
  if (!xml.includes('待填写')) throw new Error('即时审校不应自动删除待填写内容')
  if (!requests.includes(`GET /api/projects/${project.id}/completion`)) {
    throw new Error('请求监听未捕获词库加载，不能验证在线查询次数')
  }
  if (requests.some((item) => item.endsWith(`/projects/${project.id}/completion/lookup`))) {
    throw new Error(`输入与补全期间意外触发在线 lookup: ${JSON.stringify(requests)}`)
  }
  fs.writeFileSync(path.join(isolation.root, 'writing-acceptance.json'), JSON.stringify({ passed: true, projectId: project.id, fileId: created.id, home: isolation.home, profile: isolation.profile, backend: BACKEND, documentExtraction: true, tabAccepted: true, reviewDragged: true, reviewTabs: true, ctrlLinkPreview: true, sourcePreserved: true, rightSplit: true, savedDocx: true, automaticDeepCalls: reviewRequests.filter(r => r.deep).length }, null, 2))
  console.log('阶段 4/4：自动保存和下载校验完成')
  console.log(`通过：真实正文提取 → IME 输入 → 候选 → Tab 接受 → 自动保存 → docx 命中“${selectedName}”；本地即时审校识别待填写内容，AI/在线 lookup=0。`)
} catch (error) {
  failed = error
  console.error(`失败：${error.stack || error.message}`)
  if (process.env.WRITING_E2E_KEEP_FAILED === '1') {
    console.error(`Keeping isolated failed browser; send SIGUSR2 to runner PID ${process.pid} to clean up.`)
    await new Promise(resolve => process.once('SIGUSR2', resolve))
  }
} finally {
  try { await browser?.disconnect() } catch {}
  killTree()
  try { await api(`/api/projects/${project.id}`, { method: 'DELETE' }) }
  catch (error) { console.error(`清理项目失败：${error.message}`) }
  try { fs.rmSync(projectRoot, { recursive: true, force: true }) } catch {}
}

if (failed) process.exit(1)
