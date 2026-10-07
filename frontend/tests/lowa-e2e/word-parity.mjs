// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor, ORIGIN } from './_boot.mjs'

const APP = '/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/'
const extraFiles = {}
for (const f of ['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf']) {
  const p = APP + f
  if (fs.existsSync(p)) extraFiles['/' + f] = p
}

preflight()
const server = await startServer({ extraFiles })
const browser = await launchBrowser(await loadPuppeteer())

try {
  const page = await openEditor(browser)
  page.on('pageerror', (e) => console.error('PAGE ERROR:', e.message))
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  const ok = async (a, p = {}) => {
    const res = await exec(a, p)
    assert.equal(res.success, true, a + ' failed: ' + (res.message || JSON.stringify(res)))
    return res
  }
  const text = async () => (await ok('get_document_text')).paragraphs.map((p) => p.text).join('\n')

  console.log('--- 1. View options (ruler & formatting marks) ---')
  const initialUi = await ok('get_ui_state')
  assert.ok(initialUi.view, 'get_ui_state must include view options')
  assert.equal(typeof initialUi.view.formattingMarks, 'boolean')
  assert.equal(typeof initialUi.view.ruler, 'boolean')

  const setRes = await ok('set_view_options', { ruler: true, formattingMarks: true })
  assert.equal(setRes.ruler, true)
  assert.equal(setRes.formattingMarks, true)

  const uiAfter = await ok('get_ui_state')
  assert.equal(uiAfter.view.ruler, true)
  assert.equal(uiAfter.view.formattingMarks, true)

  const toggleRes = await ok('set_view_options', { ruler: false, formattingMarks: false })
  assert.equal(toggleRes.ruler, false)
  assert.equal(toggleRes.formattingMarks, false)

  console.log('--- 2. Clear doc and type text ---')
  await ok('ui_command', { name: 'select_all' })
  await ok('replace_selection', { text: 'Hello World' })
  assert.equal(await text(), 'Hello World')

  console.log('--- 3. Word parity: Type over selection (replaceSelection: true) ---')
  // Select "World"
  const selRes = await ok('find_navigate', { keyword: 'World' })
  assert.equal(selRes.found, true)

  // Replace selection with "Universe"
  const overtypeRes = await ok('insert_at_cursor', { text: 'Universe', replaceSelection: true })
  assert.equal(overtypeRes.replacedSelection, 5)
  assert.equal(await text(), 'Hello Universe')

  // One undo restores "Hello World"
  await ok('undo')
  assert.equal(await text(), 'Hello World', 'One single undo must restore the replaced selection')

  console.log('--- 4. Enter over selection (replaceSelection: true) ---')
  await ok('find_navigate', { keyword: 'World' })
  const enterRes = await ok('insert_paragraph', { replaceSelection: true })
  assert.equal(enterRes.replacedSelection, 5)
  assert.equal(await text(), 'Hello \n')

  await ok('undo')
  assert.equal(await text(), 'Hello World', 'One undo restores selection replaced by Enter')

  console.log('--- 5. AI contract: insert_at_cursor without replaceSelection appends ---')
  await ok('find_navigate', { keyword: 'World' })
  await ok('insert_at_cursor', { text: '!' })
  // With replaceSelection falsy, text is appended after selection (not replacing)
  assert.equal(await text(), 'Hello World!')

  console.log('--- 6. Format at cursor (Word parity for font size / font when collapsed) ---')
  // Collapse to end
  await ok('goto', { type: 'end' })

  // AI path without atCursor:true must fail with empty selection
  const aiFormat = await exec('format_selection', { fontSize: 24 })
  assert.equal(aiFormat.success, false, 'AI format_selection without selection must fail')
  assert.match(aiFormat.message, /nothing selected/)

  // Toolbar path with atCursor:true must succeed
  const cursorFormat = await ok('format_selection', { fontSize: 22, atCursor: true })
  assert.equal(cursorFormat.atCursor, true)
  assert.equal(cursorFormat.applied.fontSize, 22)

  // Type new text at cursor with new size
  await ok('insert_at_cursor', { text: ' Big' })
  assert.equal(await text(), 'Hello World! Big')

  console.log('--- 7. Real keyboard overlay commit over selection ---')
  // Select "Big"
  await ok('find_navigate', { keyword: 'Big' })
  const imeInput = 'input[data-lo-ime]'
  await page.focus(imeInput)

  // Dispatch composition through IME overlay
  await page.evaluate(() => {
    const el = document.querySelector('input[data-lo-ime]')
    el.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true }))
    el.dispatchEvent(new CompositionEvent('compositionend', { data: 'Huge', bubbles: true }))
    el.dispatchEvent(new InputEvent('input', { data: 'Huge', inputType: 'insertCompositionText', bubbles: true }))
  })
  // Give worker a moment to process the message
  await new Promise((r) => setTimeout(r, 500))
  assert.equal(await text(), 'Hello World! Huge', 'Typing through IME overlay over selection must replace it')

  await ok('undo')
  assert.equal(await text(), 'Hello World! Big', 'Single undo restores text replaced by IME overlay')

  console.log('ALL WORD-PARITY E2E CHECKS PASSED!')
} finally {
  await browser.close()
  server.close()
}
