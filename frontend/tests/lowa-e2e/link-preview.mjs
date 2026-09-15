// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// LOWA_LINK_GUEST_DIR can point to an isolated Vite build; shared dist is untouched.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'
preflight()
const extraFiles = {}
if (process.env.LOWA_LINK_GUEST_DIR) {
  const dir = process.env.LOWA_LINK_GUEST_DIR
  for (const f of fs.readdirSync(dir, { recursive: true })) if (fs.statSync(path.join(dir, f)).isFile()) extraFiles['/' + f] = path.join(dir, f)
}
const server = await startServer({ extraFiles })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/_rels/document.xml.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="link" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink" Target="https://example.com/reference" TargetMode="External"/></Relationships>')
  zip.file('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><w:body><w:p><w:hyperlink r:id="link"><w:r><w:t>External website link</w:t></w:r></w:hyperlink></w:p><w:p><w:hyperlink w:anchor="target"><w:r><w:t>Internal bookmark link</w:t></w:r></w:hyperlink></w:p><w:p><w:fldSimple w:instr=" REF target \\h "><w:r><w:t>Target excerpt</w:t></w:r></w:fldSimple></w:p><w:p><w:r><w:t>Ordinary paragraph</w:t></w:r></w:p><w:p><w:bookmarkStart w:id="1" w:name="target"/><w:r><w:t>Target excerpt</w:t></w:r><w:bookmarkEnd w:id="1"/></w:p></w:body></w:document>')
  const page = await openEditor(browser)
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  assert.equal((await exec('load_document', { bytes: Array.from(await zip.generateAsync({ type: 'uint8array' })), name: 'link-preview.docx' })).success, true)
  await page.evaluate(() => {
    window.__linkMessages = []
    window.addEventListener('message', e => { if (e.data?.type === 'open-url') window.__linkMessages.push(e.data) })
  })
  const text = async () => (await exec('get_document_text')).paragraphs.map(p => p.text).join('\n')
  const original = await text()
  await exec('goto', { type: 'end' })
  await exec('insert_at_cursor', { text: ' EDIT' })
  const initial = await text()
  await new Promise(r => setTimeout(r, 1000))
  await page.screenshot({ path: '/tmp/awd-541-link-initial.png' })
  // Genuine Chromium mouse clicks on rendered glyphs, not a direct worker call.
  const results = []
  for (const [index, url, paragraph] of [[0, 'https://example.com/reference', 'External website link'], [1, '#target', 'Internal bookmark link'], [2, '#target', 'Target excerpt']]) {
    await exec('select_paragraph', { index }); await exec('collapse_selection', { to: 'start' })
    const raw = await exec('get_cursor_rect')
    results.push({ index, raw })
    // Fixture uses the same default Writer page setup as writing-ui.mjs.
    const x = 145
    const y = 252 + index * 18
    const count = await page.evaluate(() => window.__linkMessages.length)
    await page.keyboard.down('Control'); await page.mouse.click(x, y); await page.keyboard.up('Control')
    try { await page.waitForFunction(n => window.__linkMessages.length > n, { timeout: 5000 }, count) } catch (e) {
      console.error('CLICK FAILED', index, await exec('get_hyperlink_at_cursor'), await exec('get_cursor_context'));
      await page.screenshot({ path: '/tmp/awd-541-link-failed.png' }); throw e
    }
    const msg = await page.evaluate(() => window.__linkMessages.at(-1))
    await page.screenshot({ path: '/tmp/awd-541-link-current.png' })
    console.log('CLICK', index, JSON.stringify(msg))
    assert.equal(msg.url, url, JSON.stringify({ index, msg, raw }))
    if (index > 0) assert.equal(msg.target.text, 'Target excerpt')
    const ctx = await exec('get_cursor_context')
    assert.equal(ctx.paragraph, paragraph, 'preview must leave the source paragraph active')
    assert.equal(await text(), initial, 'preview cannot change document text')
    results.at(-1).message = msg
  }
  const count = await page.evaluate(() => window.__linkMessages.length)
  await page.mouse.click(145, 252)
  await new Promise(r => setTimeout(r, 250))
  assert.equal(await page.evaluate(() => window.__linkMessages.length), count, 'ordinary click does not activate')
  await exec('select_paragraph', { index: 0 })
  assert.equal((await exec('get_hyperlink_at_cursor')).url, '', 'selected hyperlink text is not activation')
  await page.keyboard.down('Meta'); await page.mouse.click(200, 252); await page.keyboard.up('Meta')
  await page.waitForFunction(n => window.__linkMessages.length > n, { timeout: 5000 }, count)
  assert.equal((await page.evaluate(() => window.__linkMessages.at(-1))).meta.metaKey, true)
  await exec('undo')
  assert.equal(await text(), original, 'preview leaves the undo stack untouched')
  await page.screenshot({ path: '/tmp/awd-541-link-clicks.png' })
  fs.writeFileSync('/tmp/awd-541-link-clicks.json', JSON.stringify(results, null, 2))
  console.log('PASS real external hyperlink, internal bookmark, REF field clicks; source position and text preserved')
} finally { await browser.close(); await new Promise(r => server.close(r)) }
