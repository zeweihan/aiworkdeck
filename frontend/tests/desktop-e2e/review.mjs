// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Bounded review QA in our own Electron process/profile and synthetic project.
// Run only after the intended native engine has been installed into dist/zetaoffice/lowa.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import JSZip from 'jszip'
import puppeteer from 'puppeteer-core'
import { pickCdpPort, spawnElectron, waitForCdpWs, cdpOwnershipError, hardenPageInput } from '../_lib/electron-cdp.mjs'

const frontend = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const DEVURL = process.env.APP_E2E_DEV_URL || 'http://127.0.0.1:5177'
const BACKEND = process.env.APP_E2E_BACKEND || 'http://127.0.0.1:5269'
const port = pickCdpPort('REVIEW_E2E_CDP_PORT', 9380)
const stamp = `review-587-${Date.now()}-${process.pid}`
const artifacts = path.join(os.tmpdir(), stamp)
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms))
const api = async (endpoint, method = 'GET', body) => {
  const response = await fetch(BACKEND + endpoint, { method, headers: { 'Content-Type':'application/json' }, body: body && JSON.stringify(body) })
  const result = await response.json().catch(() => null)
  assert.ok(response.ok, `${method} ${endpoint}: ${response.status} ${JSON.stringify(result)}`)
  return result
}
const until = async (label, read, predicate, timeout = 30000) => {
  const deadline = Date.now() + timeout
  let result
  while (Date.now() < deadline) {
    result = await read()
    if (predicate(result)) return result
    await sleep(350)
  }
  throw new Error(`${label}: ${JSON.stringify(result)}`)
}
for (const url of [DEVURL, BACKEND + '/api/skills/market/list']) assert.ok(await fetch(url).then(r => r.ok), `Preflight: ${url}`)
assert.equal((await api('/api/admin/wizard')).initialized, true, 'Existing backend must already be initialized; this test never changes global setup')
for (const file of ['dist/zetaoffice/lowa/soffice.js', '../desktop/node_modules']) assert.ok(fs.existsSync(path.resolve(frontend, file)), `Missing ${file}`)
fs.mkdirSync(artifacts)
const projectRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-review-fixture-'))
const anchor = '这是新增批注的独立锚点。'
const existingComment = '原有批注：确认付款期限和附件。'
const newComment = '桌面真实点击新增批注：完整保存。'
const zip = new JSZip()
zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/><Override PartName="/word/comments.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml"/></Types>')
zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
zip.file('word/_rels/document.xml.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments" Target="comments.xml"/></Relationships>')
zip.file('word/comments.xml', `<w:comments xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:comment w:id="0" w:author="测试审阅人" w:date="2026-09-10T08:00:00Z"><w:p><w:r><w:t>${existingComment}</w:t></w:r></w:p></w:comment></w:comments>`)
zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>桌面审阅布局回归</w:t></w:r></w:p><w:p><w:commentRangeStart w:id="0"/><w:r><w:t>付款期限需要核对。</w:t></w:r><w:commentRangeEnd w:id="0"/><w:r><w:commentReference w:id="0"/></w:r></w:p><w:p><w:del w:id="1" w:author="测试审阅人" w:date="2026-09-10T08:00:00Z"><w:r><w:delText>应删除的旧合同条款。</w:delText></w:r></w:del><w:ins w:id="2" w:author="测试审阅人" w:date="2026-09-10T08:00:00Z"><w:r><w:t>应在正文内显示下划线的新合同条款。</w:t></w:r></w:ins></w:p><w:p><w:r><w:t>${anchor}</w:t></w:r></w:p><w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr></w:body></w:document>`)
fs.writeFileSync(path.join(projectRoot, 'review-fixture.docx'), await zip.generateAsync({ type:'nodebuffer' }))
let projectId, browser, killTree, host
try {
  projectId = (await api('/api/projects/open-local', 'POST', { localRoot:projectRoot, createFolder:false, name:stamp })).data.projectId
  const file = (await api(`/api/projects/${projectId}/files`)).find(f => f.name === 'review-fixture.docx')
  assert.ok(file?.id, 'Only our synthetic document is indexed')
  const child = spawnElectron({ desktopDir:path.resolve(frontend, '../desktop'), cdpPort:port, env:{ AIWORKDECK_DESKTOP_DEV:'1', CHECKBA_DEV_SERVER_URL:DEVURL, CHECKBA_BACKEND_PORT:new URL(BACKEND).port } })
  killTree = child.killTree
  const log = fs.createWriteStream(path.join(artifacts, 'electron.log'))
  child.elec.stdout.pipe(log); child.elec.stderr.pipe(log)
  const ws = await waitForCdpWs(port, 60, child.elec)
  assert.ok(ws, 'Our Electron CDP starts')
  assert.equal(cdpOwnershipError(port, child.elec), null, 'Never attach to another Electron process')
  browser = await puppeteer.connect({ browserWSEndpoint:ws, defaultViewport:null })
  host = await until('Our main renderer', async () => (await browser.pages()).find(p => p.url().startsWith(DEVURL)), Boolean)
  await host.waitForFunction(() => document.readyState === 'complete')
  await hardenPageInput(host)
  await host.evaluate(() => localStorage.setItem('awd_app_language', 'zh-CN'))
  await host.goto(`${DEVURL}/#/pages/project-overview/project-overview?id=${projectId}`, { waitUntil:'domcontentloaded' })
  const workbench = ({ id, file, action, params }) => {
    const root = document.querySelector('.page-project-overview')
    const seed = root?.__vueParentComponent || [...(root?.querySelectorAll('*') || [])].find(el => el.__vueParentComponent)?.__vueParentComponent
    for (let owner = seed; owner; owner = owner.parent) {
      const vm = owner.proxy
      if (String(vm?.projectId) !== String(id) || typeof vm.openFile !== 'function') continue
      if (file) { vm.leftPaneKey='files'; vm.sidebarCollapsed=false; vm.openFile(file); return true }
      const executor = vm._libreExecMap?.['left:' + params.fileId]
      if (executor) return executor.executeCommand(action, params.payload)
    }
    return null
  }
  await until('Open our document', () => host.evaluate(workbench, { id:projectId, file }), Boolean)
  const exec = (action, payload = {}) => host.evaluate(workbench, { id:projectId, action, params:{ fileId:file.id, payload } })
  const ok = async (action, payload) => { const r=await exec(action,payload);assert.equal(r?.success,true,`${action}: ${JSON.stringify(r)}`);return r }
  await until('Real document engine', () => exec('list_comments'), r => r?.success && r.comments.some(c => c.content === existingComment), 240000)
  let guest
  await until('Our document webview', async () => {
    for (const target of browser.targets().filter(t => t.type() === 'webview')) {
      const p = await target.page().catch(() => null)
      if (p && await p.evaluate(text => [...document.querySelectorAll('.awd-rb-card')].some(c => c.textContent.includes(text)), existingComment).catch(() => false)) { guest=p; return true }
    }
    return false
  }, Boolean, 30000)
  await hardenPageInput(guest)
  const click = async (selector, text) => {
    const point = await host.evaluate(({selector,text}) => {
      const el=[...document.querySelectorAll(selector)].find(n => (!text || n.textContent.trim() === text) && n.getBoundingClientRect().width)
      if (!el) return null
      el.scrollIntoView({block:'nearest',inline:'nearest'})
      const r=el.getBoundingClientRect(), x=r.left+r.width/2, y=r.top+r.height/2
      return { x,y,hit:el.contains(document.elementFromPoint(x,y)) }
    }, {selector,text})
    assert.ok(point?.hit, `Visible, unobscured click target ${selector} ${text || ''}`)
    await host.mouse.click(point.x, point.y)
  }
  const hostWidths = () => host.evaluate(() => [...document.querySelectorAll('.libre-canvas-wrap')].filter(n => n.getBoundingClientRect().width).map(n => ({ canvas:n.getBoundingClientRect().width, webview:n.querySelector('webview')?.getBoundingClientRect().width })))
  const before = await hostWidths(), canvasBefore = await guest.$eval('#qtcanvas', n => n.getBoundingClientRect().width)
  await click('.etb-btn[title="审阅面板"]')
  await host.waitForSelector('.libre-review-overview', {visible:true})
  assert.deepEqual(await hostWidths(), before, 'Overview must not shrink native host/webview')
  assert.equal(await guest.$eval('#qtcanvas', n => n.getBoundingClientRect().width), canvasBefore, 'Overview must not shrink native canvas')
  assert.equal(await host.$eval('.libre-review-overview', n => getComputedStyle(n).position), 'absolute')
  await host.screenshot({path:path.join(artifacts,'overview.png')})
  await click('.rp-close')
  await host.waitForSelector('.libre-review-overview', {hidden:true})
  await click('.etb-field[title="修订显示方式"]')
  await click('.etb-item', '批注框中显示修订')
  const layout = await until('Native balloon mode', () => exec('get_review_layout'), r => r?.success && r.available && r.mode === 'balloons' && r.sidebarWidth === 280)
  assert.ok(layout.pages.length && layout.items.length, 'Real native pages/anchors required')
  await guest.waitForFunction(() => document.querySelectorAll('.awd-rb-card.deletion').length > 0)
  assert.equal(await guest.$('.awd-rb-rail'), null, 'No duplicate blank review rail')
  assert.equal(await guest.$('.awd-rb-head'), null, 'No duplicate review heading')
  assert.equal(await guest.$eval('#qtcanvas', n => Math.abs(n.getBoundingClientRect().width-innerWidth)<2), true)
  const revisions = (await ok('list_revisions')).revisions
  assert.ok(revisions.some(r=>r.type==='Insert') && revisions.some(r=>r.type==='Delete'))
  assert.equal(await guest.evaluate(() => [...document.querySelectorAll('.awd-rb-card')].some(c=>c.textContent.includes('应在正文内显示下划线的新合同条款。'))), false, 'Insertion stays in native body')
  await host.screenshot({path:path.join(artifacts,'balloons-host.png')})
  await guest.screenshot({path:path.join(artifacts,'balloons-native.png')})
  console.log('PASS actual Electron overview overlay, native canvas width and toolbar balloon mode')

  const found = await ok('find_text_locations', {keyword:anchor})
  assert.ok(found.matches?.[0]?.anchorId)
  await ok('set_selection', {anchor:found.matches[0].anchorId})
  // Toolbar reads the real native selection before opening its existing dialog.
  await until('Toolbar selection', () => host.evaluate(() => {
    // .etb belongs to uni's built-in View; the toolbar component sits further up.
    const el=document.querySelector('.etb');let o=el?.__vueParentComponent
    while (o && !(o.proxy && 'noSelection' in o.proxy)) o=o.parent
    return o?.proxy?.noSelection === false
  }), Boolean)
  await click('.etb-field[title="插入"]')
  await click('.etb-item', '批注…')
  // uni-h5 draws the placeholder in its own element, not as a textarea attribute.
  await host.waitForSelector('.etb-form textarea', {visible:true})
  assert.ok((await host.$eval('.etb-form-t', n=>n.textContent)).includes(anchor.slice(0,8)), 'Dialog retains native selected anchor')
  await click('.etb-form-b.ok')
  await host.waitForFunction(() => document.querySelector('.etb-err')?.textContent.includes('请填写批注内容'))
  assert.equal((await ok('list_comments')).comments.length, 1, 'Empty dialog does not create comment')
  await click('.etb-form textarea')
  await host.keyboard.type(newComment)
  await host.screenshot({path:path.join(artifacts,'new-comment-dialog.png')})
  await click('.etb-form-b.ok')
  const comments = await until('New native comment', () => exec('list_comments'), r => r?.success && r.comments.some(c => c.content === newComment))
  const created = comments.comments.find(c=>c.content===newComment)
  assert.equal(comments.comments.length, 2)
  assert.equal(created.anchorText, anchor, 'Physical dialog submission comments on the original selection')
  await guest.waitForFunction(text => [...document.querySelectorAll('.awd-rb-card')].some(c=>c.textContent.includes(text)), {}, newComment)
  await host.screenshot({path:path.join(artifacts,'new-comment-saved.png')})
  fs.writeFileSync(path.join(artifacts,'result.json'),JSON.stringify({passed:true,projectId,fileId:file.id,backend:BACKEND,canvasBefore,nativePageCount:layout.pages.length,newCommentId:created.id},null,2))
  console.log('PASS actual Electron new-comment dialog, empty validation and native anchored save')
  console.log(`Artifacts: ${artifacts}`)
} catch (error) {
  if (host && !host.isClosed()) await host.screenshot({path:path.join(artifacts,'failure.png')}).catch(()=>{})
  console.error(`Artifacts: ${artifacts}`)
  throw error
} finally {
  if (browser) await browser.disconnect()
  if (killTree) killTree()
  if (projectId) await api(`/api/projects/${projectId}`, 'DELETE')
  fs.rmSync(projectRoot, {recursive:true,force:true})
}
