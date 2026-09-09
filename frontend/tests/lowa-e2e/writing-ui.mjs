// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'
preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  page.on('pageerror', e => console.error('PAGE ERROR:', e.message))
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  const text = async () => (await exec('get_document_text')).paragraphs.map(p => p.text).join('\n')
  await exec('ui_command', { name: 'select_all' }); await exec('replace_selection', { text: '' })
  await page.evaluate(() => {
    window.__writingRequests = []
    window.__writingNativeEscapes = 0
    document.getElementById('qtcanvas').addEventListener('keydown', e => {
      if (e.key === 'Escape' && !e.isTrusted) window.__writingNativeEscapes++
    })
    window.addEventListener('message', e => {
      const m = e.data
      if (m?.type !== 'writing-request') return
      window.__writingRequests.push(m)
      const result = m.action === 'lookup' ? { title: '北京当红晴天律师事务所', source: '测试资料 / Test fixture', date: '2026-09-09', variants: [{ rows: [['企业名称', '北京当红晴天律师事务所'], ['住所', '北京市（测试）']] }] } : {}
      window.postMessage({ __lo: 'lo-relay', type: 'writing-response', id: m.id, session: m.session, result }, location.origin)
    })
    window.postMessage({ __lo: 'lo-relay', type: 'writing-config', config: { session: 'test-document', writable: true, enabled: true, learning: true, hints: true, items: [
      { id: 'one', text: '北京当红晴天律师事务所', kind: 'COMPANY', scope: 'project', source: 'variable', uses: 3 },
      { id: 'two', text: '北京当红齐天集团', kind: 'COMPANY', scope: 'project', source: 'variable', uses: 2 },
      { text: '《中华人民共和国民法典》第一百条', kind: 'ARTICLE', scope: 'project', source: 'variable', uses: 3 },
      { text: '张三丰', kind: 'PERSON', scope: 'user', source: 'variable', uses: 2 },
    ] } }, location.origin)
  })
  const input = 'input[data-lo-ime]'
  await page.focus(input)
  const ime = async value => page.evaluate(value => {
    const el = document.querySelector('input[data-lo-ime]')
    el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    el.dispatchEvent(new CompositionEvent('compositionend', { data: value, bubbles: true }))
    el.dispatchEvent(new InputEvent('input', { data: value, inputType: 'insertCompositionText', bubbles: true }))
  }, value)
  await ime('北京当红')
  await page.waitForSelector('[role=option]', { timeout: 10000 })
  assert.equal(await page.$$eval('[role=option]', x => x.length), 2)
  assert.equal(await text(), '北京当红', 'showing suggestions does not edit the document')
  await page.keyboard.press('ArrowDown'); await page.keyboard.press('Tab')
  await page.waitForFunction(() => !document.querySelector('[role=option]'))
  assert.equal(await text(), '北京当红齐天集团')
  await exec('undo'); assert.equal(await text(), '北京当红', 'one undo reverts the accepted suffix')
  assert.equal(await page.evaluate(() => window.__writingRequests.filter(m => m.action === 'lookup').length), 0, 'typing and accepting do not query external sources')
  await page.keyboard.press('Escape')
  await exec('ui_command', { name: 'select_all' }); await exec('replace_selection', { text: '北京当红晴天律师事务所' })
  await exec('ui_command', { name: 'select_all' })
  const popupClip = { x: 165, y: 130, width: 305, height: 90 }
  const unobstructed = await page.screenshot({ clip: popupClip })
  // 真鼠标右键落在已选中的第一行，验证引擎输入转发不会吞掉选区菜单。
  await page.mouse.click(160, 232, { button: 'right' })
  await page.waitForFunction(() => [...document.querySelectorAll('.awd-wa-panel button')].some(b => b.textContent === '查询机构工商信息'))
  await page.evaluate(() => [...document.querySelectorAll('.awd-wa-panel button')].find(b => b.textContent === '查询机构工商信息').click())
  await page.waitForSelector('.awd-wa-panel table')
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))))
  assert.deepEqual(await page.screenshot({ clip: popupClip }), unobstructed, 'Qt popup is dismissed instead of remaining behind the writing preview')
  assert.equal(await page.evaluate(() => window.__writingNativeEscapes), 1)
  assert.deepEqual(await exec('get_selection'), { success: true, text: '北京当红晴天律师事务所', hasSelection: true }, 'closing the Qt popup preserves the original selection')
  assert.equal(await text(), '北京当红晴天律师事务所', 'querying and previewing do not insert content')
  assert.equal(await page.evaluate(() => window.__writingRequests.filter(m => m.action === 'lookup').length), 1)
  await page.screenshot({ path: '/tmp/awd-538-writing-preview.png' })
  await page.evaluate(() => [...document.querySelectorAll('.awd-wa-panel button')].find(b => b.textContent === '插入以上内容').click())
  await page.waitForFunction(() => document.querySelector('.awd-wa-panel')?.textContent.includes('已插入'))
  const exported = await page.evaluate(async () => { const r = await window.__loExecutor.executeCommand('export_document', { name: 'writing-ui.docx' }); return { success: r.success, size: r.bytes?.byteLength || r.bytes?.length || 0 } }); assert.ok(exported.success && exported.size > 0)
  await exec('undo'); assert.equal(await text(), '北京当红晴天律师事务所', 'table insertion preserves selected text and undoes in one step')
  await page.click('.awd-wa-panel button[aria-label="关闭"]')
  await exec('ui_command', { name: 'escape' })
  await page.mouse.click(160, 232)
  // The shorter, unselected-text menu opens below the pointer, unlike the
  // selected-text menu that Qt shifts upward to fit in this viewport.
  const normalClip = { x: 165, y: 245, width: 220, height: 80 }
  const normalBackground = await page.screenshot({ clip: normalClip })
  await page.mouse.click(160, 232, { button: 'right' })
  assert.equal((await exec('get_selection')).hasSelection, false)
  let nativeMenu
  for (let attempt = 0; attempt < 20; attempt++) {
    nativeMenu = await page.screenshot({ clip: normalClip })
    if (!nativeMenu.equals(normalBackground)) break
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  assert.equal(await page.evaluate(() => window.__writingNativeEscapes), 1, 'ordinary right-click without a selection does not dismiss Qt menus')
  assert.notDeepEqual(nativeMenu, normalBackground, 'ordinary native context menu remains visible')
  await page.screenshot({ path: '/tmp/awd-538-writing-native-menu.png' })
  console.log('PASS: real IME → local menu → arrows/Tab → undo; explicit right-click lookup → preview → table → export → undo; no automatic external lookup')
} finally { await browser.close(); await new Promise(r => server.close(r)) }
