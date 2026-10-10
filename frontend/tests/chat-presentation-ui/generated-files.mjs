// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real ChatInterface -> native Chromium drag/drop -> real FileTree -> mocked move HTTP.
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import puppeteer from 'puppeteer-core'
const root = fileURLToPath(new URL('../../', import.meta.url))
const fixture = fileURLToPath(new URL('./', import.meta.url))
const server = await createServer({ configFile: false, root: fixture, plugins: [vue()], resolve: { alias: { '@': `${root}src` }, dedupe: ['vue'] }, server: { host: '127.0.0.1', port: 5218, strictPort: true, fs: { allow: [root] } } })
await server.listen()
const browser = await puppeteer.launch({ executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true })
try {
  const page = await browser.newPage()
  const errors = []; page.on('pageerror', e => errors.push(e.message))
  await page.setViewport({ width: 960, height: 800 })
  await page.goto('http://127.0.0.1:5218/generated-files.html')
  await page.addStyleTag({ content: 'html,body{margin:0;font-family:sans-serif} view,scroll-view{display:block} #app{position:absolute;right:0;top:0;width:460px;height:800px} #file-tree{position:absolute;left:0;top:0;width:430px;height:800px} .tree-content{min-height:650px} .tree-item{min-height:32px} .status-popup-item{min-height:32px} .status-popup.up{background:white;color:black;z-index:9999}' })
  await page.waitForFunction(() => window.dragFixtureReady && document.querySelector('[data-file-id="90"]'))
  await page.evaluate(() => {
    window.chatState.fileChanges.push({ fileId: 99, projectId: 1, fileName: '新报告.docx', changeType: 'ADDED' })
    window.chatState.showNewPopup = true
  })
  const item = await page.waitForSelector('.status-popup-item[draggable="true"]')
  const folder = await page.$('[data-file-id="90"]')
  await page.setDragInterception(true)
  const from = await item.boundingBox(), to = await folder.boundingBox()
  await page.mouse.dragAndDrop({ x: from.x + from.width / 2, y: from.y + from.height / 2 }, { x: to.x + to.width / 2, y: to.y + to.height / 2 }, { delay: 200 })
  await page.waitForFunction(() => window.fileMoves.length === 1, {timeout:5000})
  assert.deepEqual(await page.evaluate(() => window.fileMoves[0]), { projectId: 1, fileId: 99, parentId: 90, sortOrder: 0 })
  assert.equal(await page.evaluate(() => window.projectFiles.find(f => f.id === 98).parentId), 1, 'same-name source must remain untouched')
  await page.waitForFunction(() => !document.__checkbaDraggedFile)
  for (const empty of [false, true]) {
    if (empty) await page.evaluate(() => { window.fileTree.files = []; window.fileTree.allFiles = [] })
    const card = await page.$('.status-popup-item[draggable="true"]')
    const box = await card.boundingBox(), blank = { x: 180, y: 450 }
    const drag = await page.mouse.drag({ x: box.x + box.width / 2, y: box.y + box.height / 2 }, blank)
    await page.waitForFunction(() => window.fileTree.isAnyDragging, { timeout: 5000 })
    let target = blank
    if (empty) {
      const zone = await page.waitForSelector('.root-drop-zone-empty')
      const bounds = await zone.boundingBox(); target = { x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 }
    }
    await page.mouse.dragEnter(target, drag); await page.mouse.dragOver(target, drag)
    await page.mouse.drop(target, drag); await page.mouse.up()
    await page.waitForFunction(count => window.fileMoves.length === count, { timeout: 5000 }, empty ? 3 : 2)
    assert.equal(await page.evaluate(() => window.fileMoves.at(-1).parentId), null)
    await page.waitForFunction(() => !window.fileTree.isAnyDragging && !document.__checkbaDraggedFile)
  }
  await page.screenshot({ path: '/tmp/generated-files-drag.png', fullPage: true })
  assert.deepEqual(errors, [])
  console.log('PASS: native drag from real chat to real folder moved id=99, preserved same-name id=98, accepted blank root and empty tree, cleared fallback')
} finally { await browser.close(); await server.close() }
