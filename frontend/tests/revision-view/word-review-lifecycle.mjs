// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real DOM, mocked worker transport: no WASM build or engine download needed.
// Run: node --test tests/revision-view/word-review-lifecycle.mjs
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import { after, before, test } from 'node:test'
import puppeteer from 'puppeteer-core'

const dataUrl = source => 'data:text/javascript;base64,' + Buffer.from(source).toString('base64')
const grouping = dataUrl(await readFile(new URL('../../src/utils/reviewGrouping.js', import.meta.url), 'utf8'))
const moduleUrl = dataUrl((await readFile(new URL('../../src/composables/zetaOfficeReviewBalloons.js', import.meta.url), 'utf8'))
  .replace("'../utils/reviewGrouping.js'", JSON.stringify(grouping)))
let browser
before(async () => {
  browser = await puppeteer.launch({
    executablePath: process.env.PUPPETEER_EXECUTABLE_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
    args: ['--no-sandbox'],
  })
})
after(async () => { await browser?.close() })

async function mount(t, options = {}) {
  const page = await browser.newPage()
  t.after(() => page.close())
  await page.setViewport({ width: 1200, height: 800 })
  await page.setContent('<style>canvas{width:800px;height:600px}</style><canvas id="qtcanvas"></canvas>')
  await page.evaluate(async (url, options) => {
    const { attachReviewBalloons } = await import(url)
    window.model = { name: 'A', revision: 1, writable: options.writable !== false, notes: true }
    window.commands = []; window.resizes = 0
    // Count the component's explicit canvas resize, excluding browser setup.
    window.addEventListener('resize', e => { if (!e.isTrusted) window.resizes++ })
    window.balloons = attachReviewBalloons({
      canvas: document.querySelector('canvas'), locale: 'en', transport: { send() {} },
      execute: async (action, params) => {
        window.commands.push({ action, params, document: window.model.name })
        if (action === 'set_review_balloons') {
          window.model.notes = !params.enabled
          if (options.deferSetter) {
            options.deferSetter = false
            return new Promise(resolve => { window.releaseSetter = () => resolve({ success: true }) })
          }
          return { success: true }
        }
        if (action !== 'get_review_layout') return { success: true }
        const m = window.model
        return {
          success: true, revision: m.revision, mode: window.mode || 'balloons', writable: m.writable, notesVisible: m.notes,
          items: [
            { key: 'c0', kind: 'comment', data: { id: '0', index: 0, content: 'Same original comment', author: m.name }, x: 200, y: 100 },
            { key: 'r0', kind: 'revision', data: { index: 0, type: 'Delete', text: 'Deleted text', author: m.name }, x: 200, y: 300 },
          ],
          view: { left: 0, right: 800, top: 0, bottom: 600, caretY: 100, frameWidth: 800, frameHeight: 600,
            viewport: { x: 0, y: 0, width: 800, height: 600 } },
        }
      },
    })
  }, moduleUrl, options)
  return page
}

const commentButton = (page, label) => page.evaluate(label => {
  const button = [...document.querySelectorAll('[data-key="c0"] button')].find(b => b.textContent === label)
  if (!button) throw new Error('Missing comment button: ' + label)
  button.click()
}, label)

test('loading a document cancels the old draft even when comment IDs and original content match', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  await commentButton(page, 'Edit')
  await page.evaluate(() => {
    document.querySelector('textarea').value = 'Draft intended for A'
    window.balloons.suspend('load_document')
    window.model = { name: 'B', revision: 2, writable: true, notes: true }
  })
  assert.deepEqual(await page.evaluate(() => ({
    drafts: document.querySelectorAll('textarea').length,
    cards: document.querySelectorAll('.awd-rb-card').length,
    hidden: document.querySelector('.awd-review-balloons').hidden,
  })), { drafts: 0, cards: 0, hidden: true })
  await page.evaluate(() => window.balloons.resume())
  await page.waitForFunction(() => document.querySelector('[data-key="c0"] .awd-rb-meta')?.textContent === 'CommentB')
  assert.equal(await page.evaluate(() => document.querySelector('.awd-review-balloons').hidden), false)
  assert.equal(await page.evaluate(() => window.commands.filter(c => c.action === 'update_comment').length), 0)
  await commentButton(page, 'Edit')
  await page.evaluate(() => { document.querySelector('textarea').value = 'New draft for B' })
  await commentButton(page, 'Save')
  assert.deepEqual(await page.evaluate(() => window.commands.filter(c => c.action === 'update_comment').map(c => ({
    document: c.document, content: c.params.content, revision: c.params.revision,
  }))), [{ document: 'B', content: 'New draft for B', revision: 2 }])
})

test('a stale note-visibility response cannot resize or reveal balloons during document import', async t => {
  const page = await mount(t, { deferSetter: true })
  await page.waitForFunction(() => !!window.releaseSetter)
  await page.evaluate(async () => {
    window.balloons.suspend('load_document')
    window.model = { name: 'B', revision: 2, writable: true, notes: true }
    window.releaseSetter()
    await new Promise(resolve => setTimeout(resolve, 0))
  })
  assert.deepEqual(await page.evaluate(() => ({
    margin: document.documentElement.classList.contains('awd-review-margin'),
    resizes: window.resizes, hidden: document.querySelector('.awd-review-balloons').hidden,
  })), { margin: false, resizes: 0, hidden: true })
  await page.evaluate(() => window.balloons.resume())
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  assert.ok(await page.evaluate(() => window.resizes > 0), 'fresh document may resize after import finishes')
})

test('read-only comments and revisions remain locatable without exposing mutation controls', async t => {
  const page = await mount(t, { writable: false })
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  assert.equal(await page.$$eval('.awd-rb-card', nodes => nodes.length), 2)
  assert.equal(await page.$$eval('.awd-rb-actions button', nodes => nodes.length), 0)
  await page.evaluate(() => {
    document.querySelector('[data-key="c0"]').click()
    document.querySelector('[data-key="g0"]').click()
  })
  assert.deepEqual(await page.evaluate(() => window.commands.filter(c => !['get_review_layout', 'set_review_balloons'].includes(c.action))
    .map(c => ({ action: c.action, revision: c.params.revision }))), [
    { action: 'goto_comment', revision: 1 }, { action: 'goto_revision', revision: 1 },
  ])
})

test('changing only writability removes previously available mutation controls', async t => {
  const page = await mount(t)
  await page.waitForSelector('.awd-rb-actions button', { visible: true })
  await page.evaluate(() => { window.model.writable = false; window.balloons.cursorMoved() })
  await page.waitForFunction(() => document.querySelectorAll('.awd-rb-card').length === 2
    && document.querySelectorAll('.awd-rb-actions button').length === 0)
})


test('inline mode shows only comments; balloon mode displays the complete deleted revision', async t => {
  const page = await mount(t)
  await page.waitForSelector('.awd-rb-card.deletion')
  assert.equal(await page.$eval('.awd-rb-card.deletion .awd-rb-content',n=>n.textContent),'Deleted text')
  await page.evaluate(()=>{window.mode='all';window.balloons.documentChanged()})
  await page.waitForFunction(()=>!document.querySelector('.awd-rb-card.deletion'))
  assert.ok(await page.$('[data-key="c0"]'))
  await page.evaluate(()=>{window.mode='balloons';window.balloons.documentChanged()})
  await page.waitForSelector('.awd-rb-card.deletion')
})
