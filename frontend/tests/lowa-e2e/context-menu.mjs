// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Hold and release a real right mouse button: mouse.click alone can release
// before the async context menu opens and miss the focus/cursor race (#601).
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
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
  const selected = '右键菜单测试文字'
  await ok('ui_command', { name: 'select_all' })
  await ok('replace_selection', { text: selected })
  await ok('ui_command', { name: 'select_all' })
  await page.evaluate(() => {
    window.__menuRequests = []
    window.__menuEvents = []
    for (const name of ['mousedown', 'mouseup', 'contextmenu', 'focusin', 'focusout', 'keydown', 'keyup']) document.addEventListener(name, e => {
      window.__menuEvents.push({ type: name, button: e.button, key: e.key, trusted: e.isTrusted, target: e.target.tagName, active: document.activeElement?.tagName,
        hidden: document.querySelector('.awd-wa-panel')?.hidden })
    }, true)
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
  await new Promise(resolve => setTimeout(resolve, 100))
  const raw = await ok('get_cursor_rect')
  const point = await page.evaluate(raw => {
    const surface = document.getElementById('qtcanvas').getBoundingClientRect(), c = raw.nativeCaret
    const scale = surface.width / c.frameWidth
    return { x: surface.left + c.x * scale - 20,
      y: surface.top + Math.max(0, surface.height - c.frameHeight * scale) + (c.y + c.height / 2) * scale }
  }, raw)
  const menu = () => page.$eval('.awd-wa-panel', n => ({ hidden: n.hidden, text: n.textContent }))
  const openHeld = async () => {
    await page.mouse.move(point.x, point.y)
    await page.mouse.down({ button: 'right' })
    await page.waitForFunction(() => !document.querySelector('.awd-wa-panel')?.hidden
      && document.querySelector('.awd-wa-panel')?.textContent.includes('查询机构工商信息'), { timeout: 10000 })
    await new Promise(resolve => setTimeout(resolve, 200))
  }
  await openHeld()
  assert.equal((await menu()).hidden, false, 'menu opens while the right button is held')
  await page.mouse.up({ button: 'right' })
  await new Promise(resolve => setTimeout(resolve, 500))
  console.log('RIGHT_RELEASE_STATE', JSON.stringify({ menu: await menu(), events: await page.evaluate(() => window.__menuEvents) }))
  assert.equal((await menu()).hidden, false, 'releasing the right button over the document must leave the menu open')
  assert.equal((await ok('get_selection')).text, selected)
  const queryButton = await page.$$('.awd-wa-panel button')
  for (const button of queryButton) {
    if (await button.evaluate(n => n.textContent === '查询机构工商信息')) { await button.click(); break }
  }
  await page.waitForFunction(() => document.querySelector('.awd-wa-panel')?.textContent.includes('可用的测试资料'))
  assert.equal(await page.evaluate(() => window.__menuRequests.filter(m => m.action === 'lookup').length), 1)
  assert.equal((await ok('get_selection')).text, selected, 'using a menu command preserves the selected document text')
  console.log('PASS held/released right menu remains open and its command is usable')
  await page.keyboard.press('Escape')
  assert.equal((await menu()).hidden, true, 'Escape dismisses the menu after its action')
  assert.equal((await ok('get_selection')).text, selected, 'dismissal of the query panel preserves the selection')
  await openHeld()
  await page.mouse.up({ button: 'right' })
  await new Promise(resolve => setTimeout(resolve, 300))
  console.log('BEFORE_SECOND_ESCAPE', JSON.stringify({ menu: await menu(), selection: await ok('get_selection'), events: await page.evaluate(() => window.__menuEvents) }))
  await page.keyboard.press('Escape')
  assert.equal((await menu()).hidden, true, 'Escape dismisses a released context menu')
  assert.equal((await ok('get_selection')).text, selected, 'Escape dismisses the menu without clearing the selection')
  await openHeld()
  await page.mouse.up({ button: 'right' })
  await new Promise(resolve => setTimeout(resolve, 300))
  await page.mouse.click(point.x + 150, point.y + 60)
  assert.equal((await menu()).hidden, true, 'outside click dismisses the menu')
  console.log('PASS Escape/outside dismissal and selection preservation')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
