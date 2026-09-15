// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real Writer + production vocabulary extraction. No preseeded candidate list.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createWritingAssistanceHost } from '../../src/composables/writingAssistanceHost.js'
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs'
preflight()
const server = await startServer(), browser = await launchBrowser(await loadPuppeteer())
let host, closing = false
try {
  const page = await openEditor(browser, { viewport: { width: 1280, height: 900, deviceScaleFactor: Number(process.env.LOWA_E2E_DPR || 1) } })
  await page.addStyleTag({ content: '#verify,#vlog{display:none!important}' })
  const exec = (action, params = {}) => page.evaluate((action, params) => window.__loExecutor.executeCommand(action, params), action, params)
  const ok = async (action, params) => { const r = await exec(action, params); assert.equal(r.success, true, action + ': ' + JSON.stringify(r)); return r }
  const body = async () => (await ok('get_document_text', { maxParagraphs: 500 })).paragraphs.map(p => p.text).join('\n')
  await ok('set_chrome', { menubar: false, toolbars: false })
  const fixture = '股东名册中青岛致衡贸易有限公司持股40%。\n《公司章程》记载韩明远持股60%。\n适用《民法典》第一百条。\n' + '以下继续撰写法律意见。\n'.repeat(50)
  await ok('insert_at_cursor', { text: fixture })
  const learned = [], messages = []
  const pending = []
  const send = message => { if (closing) return; messages.push(message); pending.push(page.evaluate(message => window.postMessage(message, location.origin), message)) }
  host = createWritingAssistanceHost({ projectId: 1, fileId: 1, userId: 'caret-test', writable: true,
    execute: exec, send, storage: { get: () => ({ hints: false }), set() {} },
    api: { list: async () => ({ items: [] }), learn: async (id, data) => { learned.push(data); return {} } },
  })
  await host.start(); await Promise.all(pending.splice(0))
  assert.ok(messages.at(-1).config.items.some(i => i.text === '青岛致衡贸易有限公司'), 'the imported prose must supply the company candidate')
  assert.ok(messages.at(-1).config.items.some(i => i.text === '韩明远'), 'the imported shareholding prose must supply the person candidate')
  const input = '[data-lo-ime]'
  const ime = async text => {
    await page.focus(input)
    await page.evaluate(text => { const e = document.querySelector('[data-lo-ime]'); e.dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })); e.dispatchEvent(new CompositionEvent('compositionend', { bubbles: true, data: text })) }, text)
  }
  const bounds = selector => page.$eval(selector, e => { const r = e.getBoundingClientRect(); return { x: r.x, y: r.y, width: r.width, height: r.height } })
  const evidence = []
  for (const zoom of [100, 194]) {
    await ok('set_zoom', { value: zoom }); await ok('goto', { type: 'end' })
    // Scroll to the final paragraph, then type without calibrating via a glyph click.
    await ime('青岛致')
    await page.waitForSelector('.awd-wa-option', { timeout: 15000, visible: true })
    const choices = await page.$$eval('.awd-wa-option', nodes => nodes.map(n => n.childNodes[0].textContent))
    assert.ok(choices.includes('青岛致衡贸易有限公司'))
    const caret = await bounds(input), menu = await bounds('.awd-wa-panel'), raw = await ok('get_cursor_rect')
    console.log(JSON.stringify({ zoom, caret, menu, native: raw.nativeCaret }));
    assert.ok(raw.nativeCaret, 'live native viewport geometry is required, never a last-click fallback')
    assert.ok(caret.width < 400 && caret.height < 100)
    assert.ok(caret.x >= 0 && caret.x < 1280 && caret.y >= 0 && caret.y < 900, 'caret remains visible after long-document auto-scroll')
    assert.ok(Math.abs(menu.x - caret.x) < 12, 'candidate menu starts beside the actual caret')
    assert.ok(menu.y >= caret.y + caret.height || menu.y + menu.height <= caret.y + 1, 'candidate list does not cover the writing line')
    await page.screenshot({ path: `/tmp/awd-ux-writing-caret-${zoom}.png` })
    const before = await body()
    await page.keyboard.press('Tab')
    await page.waitForFunction(() => document.querySelector('.awd-wa-panel')?.hidden)
    assert.equal(await body(), before + '衡贸易有限公司')
    await ok('undo'); assert.equal(await body(), before, 'one undo reverts only the suffix')
    await ok('undo')
    evidence.push({ zoom, caret, menu, native: raw.nativeCaret })
  }
  await ok('set_zoom', { value: 100 }); await ok('goto', { type: 'end' })
  await ime('韩明'); await page.waitForSelector('.awd-wa-option', { visible: true })
  assert.ok((await page.$eval('.awd-wa-option', e => e.textContent)).includes('韩明远'))
  await page.keyboard.press('Tab'); await page.waitForFunction(() => document.querySelector('.awd-wa-panel')?.hidden)
  assert.ok((await body()).endsWith('韩明远'))
  await ok('insert_paragraph')
  await ime('民法'); await page.waitForSelector('.awd-wa-option', { visible: true })
  assert.ok((await page.$eval('.awd-wa-option', e => e.textContent)).includes('《民法典》'))
  await page.keyboard.press('Tab'); await page.waitForFunction(() => document.querySelector('.awd-wa-panel')?.hidden)
  assert.ok((await body()).endsWith('民法典'), 'bare law prefix inserts no dangling book-title bracket')
  assert.ok(learned.every(x => x.scope === 'project'), 'imported names stay in the project')
  fs.writeFileSync('/tmp/awd-ux-writing-caret.json', JSON.stringify(evidence, null, 2))
  console.log('PASS imported legal prose → company/person/law suggestions; native caret at 100%/194% after scrolling; Tab and single undo')
} catch (error) { console.error(error); throw error } finally { closing = true; host?.destroy(); await browser.close(); await new Promise(r => server.close(r)) }
