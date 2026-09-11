// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Hold and release a real right mouse button: mouse.click alone can release
// before the async context menu opens and miss the focus/cursor race (#601).
// Exactly one menu may open per right-click: the HTML lookup menu for a short
// selection (Writer's popup suppressed by the worker), otherwise Writer's own.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

preflight()
const extraFiles = {}
if (process.env.LOWA_CONTEXT_DIST) {
  const dir = process.env.LOWA_CONTEXT_DIST
  for (const file of fs.readdirSync(dir, { recursive: true })) {
    if (fs.statSync(path.join(dir, file)).isFile()) extraFiles['/' + file] = path.join(dir, file)
  }
}
const server = await startServer({ extraFiles })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  const exec = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const ok = async (action, params) => {
    const result = await exec(action, params)
    assert.equal(result.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const selection = async () => (await ok('get_selection')).text
  const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
  const selected = '右键菜单测试文字'
  await ok('ui_command', { name: 'select_all' })
  await ok('replace_selection', { text: selected })
  await ok('resolve_all_revisions', { action: 'accept' })
  await ok('ui_command', { name: 'select_all' })
  await page.evaluate(() => {
    window.__menuRequests = []
    window.addEventListener('message', e => {
      const m = e.data
      if (m?.type !== 'writing-request') return
      window.__menuRequests.push(m)
      window.postMessage({ __lo: 'lo-relay', type: 'writing-response', id: m.id, session: m.session,
        result: m.action === 'lookup' ? { title: '测试查询结果', variants: [{ text: '可用的测试资料' }] } : {} }, location.origin)
    })
    window.postMessage({ __lo: 'lo-relay', type: 'writing-config', config: {
      session: 'context-menu-test', writable: true, enabled: false, learning: false, hints: true, items: [],
    } }, location.origin)
  })
  await pause(300)

  // A point inside the text line, left of the current caret.
  const caretPoint = async (dx = -20) => {
    const raw = await ok('get_cursor_rect')
    return page.evaluate((raw, dx) => {
      const surface = document.getElementById('qtcanvas').getBoundingClientRect(), c = raw.nativeCaret
      const scale = surface.width / c.frameWidth
      return { x: surface.left + c.x * scale + dx,
        y: surface.top + Math.max(0, surface.height - c.frameHeight * scale) + (c.y + c.height / 2) * scale }
    }, raw, dx)
  }
  const menu = () => page.$eval('.awd-wa-panel', n => ({ hidden: n.hidden, text: n.textContent }))
  // Writer draws its popup into the canvas; measure it with the HTML menu and
  // the verify log (it scrolls as lines arrive) hidden.
  const overlays = (visibility) => page.evaluate(v => {
    for (const n of document.querySelectorAll('.awd-writing-assistance, #vlog')) n.style.visibility = v
  }, visibility)
  // Only the band right of the click, where Writer opens its popup; toolbar and
  // status bar repaint on every selection change.
  const canvasShot = async (point) => {
    const clip = await page.$eval('#qtcanvas', (n, p) => {
      const r = n.getBoundingClientRect(), x = p.x + 10, y = Math.max(r.top, p.y - 100)
      return { x, y, width: Math.min(300, r.right - x), height: Math.min(p.y + 180, r.bottom) - y }
    }, point)
    await overlays('hidden')
    try { return PNG.sync.read(await page.screenshot({ clip })) }
    finally { await overlays('') }
  }
  const changedPixels = (a, b) => {
    let n = 0
    for (let i = 0; i < a.data.length; i += 4) {
      if (Math.abs(a.data[i] - b.data[i]) + Math.abs(a.data[i + 1] - b.data[i + 1]) + Math.abs(a.data[i + 2] - b.data[i + 2]) > 48) n++
    }
    return n
  }
  const NATIVE_POPUP_PIXELS = 1500
  // Press and hold, wait for whichever menu appears, then release.
  const rightClick = async (point, { expectHtml }) => {
    const before = await canvasShot(point)
    await page.mouse.move(point.x, point.y)
    await page.mouse.down({ button: 'right' })
    if (expectHtml) {
      await page.waitForFunction(() => !document.querySelector('.awd-wa-panel')?.hidden
        && document.querySelector('.awd-wa-panel')?.textContent.includes('查询机构工商信息'), { timeout: 10000 })
    }
    await pause(500)
    const heldNative = changedPixels(before, await canvasShot(point))
    const heldMenu = await menu()
    await page.mouse.up({ button: 'right' })
    await pause(400)
    const result = { heldNative, heldMenu, released: await menu(), releasedNative: changedPixels(before, await canvasShot(point)) }
    console.log('RIGHT_CLICK', JSON.stringify({ heldNative, releasedNative: result.releasedNative, html: !result.released.hidden }))
    return result
  }
  const expectHtmlOnly = (r, label) => {
    assert.equal(r.heldMenu.hidden, false, label + ': HTML menu opens while the right button is held')
    assert.equal(r.released.hidden, false, label + ': releasing the right button leaves the HTML menu open')
    assert.ok(r.heldNative < NATIVE_POPUP_PIXELS && r.releasedNative < NATIVE_POPUP_PIXELS,
      label + ': Writer popup must not open under the HTML menu ' + JSON.stringify(r))
  }
  const expectNativeOnly = (r, label) => {
    assert.equal(r.released.hidden, true, label + ': no HTML menu ' + JSON.stringify(r.released))
    assert.ok(r.releasedNative >= NATIVE_POPUP_PIXELS, label + ': Writer popup opens ' + JSON.stringify(r))
  }

  let point = await caretPoint()
  // 1. Held/released menu stays open and its command is usable.
  expectHtmlOnly(await rightClick(point, { expectHtml: true }), 'first open')
  assert.equal(await selection(), selected)
  for (const button of await page.$$('.awd-wa-panel button')) {
    if (await button.evaluate(n => n.textContent === '查询机构工商信息')) { await button.click(); break }
  }
  await page.waitForFunction(() => document.querySelector('.awd-wa-panel')?.textContent.includes('可用的测试资料'))
  assert.equal(await page.evaluate(() => window.__menuRequests.filter(m => m.action === 'lookup').length), 1)
  assert.equal(await selection(), selected, 'using a menu command preserves the selected document text')
  await page.keyboard.press('Escape')
  assert.equal((await menu()).hidden, true, 'Escape dismisses the menu after its action')
  assert.equal(await selection(), selected, 'dismissal of the query panel preserves the selection')
  console.log('PASS held/released right menu remains open and its command is usable')

  // 2. Reopening repeatedly keeps the selection (#601: the second open lost it).
  for (const round of [2, 3, 4]) {
    expectHtmlOnly(await rightClick(point, { expectHtml: true }), 'open ' + round)
    assert.equal(await selection(), selected, 'open ' + round + ' keeps the selection before dismissal')
    await page.keyboard.press('Escape')
    assert.equal((await menu()).hidden, true, 'Escape dismisses a released context menu')
    assert.equal(await selection(), selected, 'Escape dismisses the menu without clearing the selection')
  }
  console.log('PASS repeated opens and Escape keep the selection')

  // 3. Outside click dismisses; a later left click is an ordinary caret click.
  expectHtmlOnly(await rightClick(point, { expectHtml: true }), 'before outside click')
  await page.mouse.click(point.x + 150, point.y + 60)
  await pause(300)
  assert.equal((await menu()).hidden, true, 'outside click dismisses the menu')
  await page.mouse.click(point.x - 40, point.y)
  await pause(300)
  assert.equal(await selection(), '', 'a left click after the menu places the caret instead of extending a selection')
  console.log('PASS outside dismissal and ordinary left click afterwards')

  // 4. No selection: Writer's own menu only; a real Escape closes it; the HTML
  //    menu still works for the next selection.
  expectNativeOnly(await rightClick(point, { expectHtml: false }), 'no selection')
  await page.keyboard.press('Escape')
  await pause(300)
  await ok('ui_command', { name: 'select_all' })
  point = await caretPoint()
  expectHtmlOnly(await rightClick(point, { expectHtml: true }), 'after native popup')
  assert.equal(await selection(), selected, 'HTML menu after a dismissed native popup keeps the selection')
  await page.keyboard.press('Escape')
  console.log('PASS native-only menu for an empty selection, HTML menu afterwards')

  // 5. Right-click outside a partial selection: Writer moves the caret first,
  //    so only its own menu opens.
  await ok('ui_command', { name: 'line_start' })
  const lineStart = await caretPoint(4)
  await ok('ui_command', { name: 'line_end' })
  await ok('ui_command', { name: 'word_left_sel' })
  const partial = await selection()
  assert.ok(partial && partial.length < selected.length, 'partial selection at line end: ' + JSON.stringify(partial))
  expectNativeOnly(await rightClick(lineStart, { expectHtml: false }), 'outside the selection')
  await page.keyboard.press('Escape')
  await pause(300)
  console.log('PASS right-click outside a selection opens only the native menu')

  // 6. A selection longer than the lookup limit keeps Writer's menu.
  const long = '甲'.repeat(170)
  await ok('ui_command', { name: 'select_all' })
  await ok('replace_selection', { text: long })
  await ok('resolve_all_revisions', { action: 'accept' })
  await ok('ui_command', { name: 'select_all' })
  point = await caretPoint()
  expectNativeOnly(await rightClick(point, { expectHtml: false }), 'long selection')
  await page.keyboard.press('Escape')
  await pause(300)
  assert.equal((await selection()).length, long.length, 'Escape closes the native menu without clearing the selection')
  console.log('PASS long selection keeps the native menu')

  // 7. Inline markup: a selection touching a tracked deletion keeps Writer's
  //    accept/reject menu; the host would only have read its final text.
  await ok('ui_command', { name: 'select_all' })
  await ok('replace_selection', { text: selected + '删除' })
  await ok('resolve_all_revisions', { action: 'accept' })
  await ok('set_revision_view', { mode: 'all' })
  await ok('ui_command', { name: 'line_end' })
  await ok('ui_command', { name: 'word_left_sel' })
  await ok('replace_selection', { text: '' })
  assert.ok((await ok('list_revisions')).revisions?.some(r => r.type === 'Delete'), 'tracked deletion exists')
  await ok('ui_command', { name: 'select_all' })
  point = await caretPoint()
  expectNativeOnly(await rightClick(point, { expectHtml: false }), 'selection with inline deletion')
  await page.keyboard.press('Escape')
  await pause(300)
  console.log('PASS inline deletion keeps the native menu')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
