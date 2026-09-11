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
    window.model = { name: 'A', revision: 1, writable: options.writable !== false, notes: true, nativeWidth: options.nativeWidth || 0 }
    if (options.insertOnly) window.reviewItems = [{key:'r0',kind:'revision',data:{index:0,identifier:'insert-1',type:'Insert',text:'Inserted body text'},x:3000,y:1500,page:1}]
    window.commands = []; window.resizes = 0
    // Count the component's explicit canvas resize, excluding browser setup.
    window.addEventListener('resize', e => { if (!e.isTrusted) window.resizes++ })
    window.balloons = attachReviewBalloons({
      canvas: document.querySelector('canvas'), locale: 'en', transport: { send() {} },
      execute: async (action, params) => {
        window.commands.push({ action, params, document: window.model.name })
        if (action === 'set_review_balloons') {
          window.model.notes = !params.enabled
          window.model.nativeWidth = params.enabled ? params.width : 0
          if (options.deferSetter) {
            options.deferSetter = false
            return new Promise(resolve => { window.releaseSetter = () => resolve({ success: true }) })
          }
          return { success: true }
        }
        if (action !== 'get_review_layout') return { success: true }
        const m = window.model
        const top = (window.viewTop || 0) * 15, left = (window.viewLeft || 0) * 15, zoom = window.zoom || 1
        return {
          success: true, sidebarWidth: m.nativeWidth || 0, available: options.available !== false, documentSeq: m.name === 'A' ? 1 : 2, revision: m.revision, mode: window.mode || 'balloons', writable: m.writable, notesVisible: m.notes,
          items: window.reviewItems || [
            { key: 'c0', kind: 'comment', data: { id: '0', index: 0, content: 'Same original comment', author: m.name }, x: 3000, y: 1500, page: 1 },
            { key: 'r0', kind: 'revision', data: { index: 0, identifier: 'revision-A', type: 'Delete', text: 'Deleted text', author: m.name }, x: 3000, y: 4500, page: 1 },
          ],
          pages: window.reviewPages || [{ number: 1, x: options.leftSidebar ? 4200 : 0, y: 0, width: 7200, height: 15000, sidebar: options.leftSidebar ? 'left' : 'right', gutterWidth: 4200 }],
          view: { left, right: left + 12000 / zoom, top, bottom: top + 9000 / zoom, caretY: 1500, frameWidth: 800, frameHeight: 600,
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
  assert.equal(await page.evaluate(() => window.resizes), 0, 'native gutter never resizes the HTML canvas')
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


test('cards use native page gutter, follow scrolling and zoom without an HTML rail', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  const before = await page.$eval('[data-key="c0"]', n => ({ top: n.getBoundingClientRect().top, left: n.getBoundingClientRect().left, width: n.getBoundingClientRect().width }))
  assert.equal(await page.$('.awd-rb-rail'), null)
  assert.equal(await page.$('.awd-rb-head'), null)
  assert.equal(await page.$eval('canvas', n => n.getBoundingClientRect().width), 800)
  assert.ok(before.left >= 488 && before.left < 510, 'card starts just outside the native page edge')
  assert.ok(before.width > 240 && before.width <= 280, 'card fits native gutter')
  await page.evaluate(() => { window.viewTop = 50; window.balloons.cursorMoved() })
  await page.waitForFunction(top => document.querySelector('[data-key="c0"]').getBoundingClientRect().top < top - 40, {}, before.top)
  const scrolled = await page.$eval('[data-key="c0"]', n => n.getBoundingClientRect().top)
  assert.equal(Math.round(before.top - scrolled), 50)
  await page.evaluate(() => { window.viewTop = 0; window.zoom = 0.75; window.balloons.cursorMoved() })
  await page.waitForFunction(width => document.querySelector('[data-key="c0"]').getBoundingClientRect().width < width - 40, {}, before.width)
  const zoomed = await page.$eval('[data-key="c0"]', n => ({ left: n.getBoundingClientRect().left, width: n.getBoundingClientRect().width }))
  assert.ok(Math.abs(zoomed.width / before.width - 0.75) < 0.01)
  assert.equal(await page.evaluate(() => window.resizes), 0)
  assert.ok(await page.$('.awd-rb-lines path'))
})

test('unavailable native geometry keeps the original comments and schedules no retry loop', async t => {
  const page = await mount(t, { available: false })
  await page.waitForFunction(() => window.commands.some(c => c.action === 'get_review_layout'))
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(await page.evaluate(() => document.querySelector('.awd-review-balloons').hidden), true)
  assert.equal(await page.evaluate(() => window.model.notes), true)
  assert.equal(await page.evaluate(() => window.commands.filter(c => c.action === 'set_review_balloons').length), 0)
  const reads = await page.evaluate(() => window.commands.filter(c => c.action === 'get_review_layout').length)
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(await page.evaluate(() => window.commands.filter(c => c.action === 'get_review_layout').length), reads, 'no polling after initial browser resize settles')
})

test('selection refresh preserves card nodes and a physical click survives a document refresh', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  await page.evaluate(() => { window.originalCard = document.querySelector('[data-key="c0"]'); window.balloons.cursorMoved() })
  await new Promise(resolve => setTimeout(resolve, 120))
  assert.equal(await page.evaluate(() => window.originalCard === document.querySelector('[data-key="c0"]')), true)
  const button = await page.$('[data-key="c0"] .awd-rb-actions button')
  const rect = await button.boundingBox()
  await page.mouse.move(rect.x + rect.width / 2, rect.y + rect.height / 2)
  await page.mouse.down()
  await page.evaluate(() => { window.model.revision++; window.balloons.documentChanged() })
  await new Promise(resolve => setTimeout(resolve, 550))
  assert.equal(await page.evaluate(() => window.originalCard === document.querySelector('[data-key="c0"]')), true)
  await page.mouse.up()
  await page.waitForSelector('[data-key="c0"] textarea')
})


test('left-side native gutters and horizontal scroll use the same viewport clipping', async t => {
  const page = await mount(t, { leftSidebar: true })
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  const bounds = await page.$eval('[data-key="c0"]', n => ({ left: n.getBoundingClientRect().left, right: n.getBoundingClientRect().right }))
  assert.equal(bounds.left, 16, 'left gutter starts at page edge minus native gutter plus padding')
  assert.ok(bounds.right < 288, 'card stays left of the paper')
  await page.evaluate(() => { window.viewLeft = 50; window.balloons.cursorMoved() })
  await page.waitForFunction(() => document.querySelector('[data-key="c0"]').getBoundingClientRect().left < 0)
  assert.equal(await page.$eval('[data-key="c0"]', n => n.getBoundingClientRect().left), -34)
  const viewport = await page.$eval('.awd-review-balloons', n => ({ width: n.getBoundingClientRect().width, height: n.getBoundingClientRect().height, overflow: getComputedStyle(n).overflow }))
  assert.deepEqual(viewport, { width: 800, height: 600, overflow: 'hidden' })
  assert.equal(await page.$eval('canvas', n => n.dispatchEvent(new WheelEvent('wheel', { deltaY: 100, bubbles: true, cancelable: true }))), true, 'document wheel is never cancelled by the overlay')
})

test('geometry refresh retains an open draft and its original mutation fence', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  await commentButton(page, 'Edit')
  await page.evaluate(() => {
    document.querySelector('textarea').value = 'Draft stays intact'
    window.viewTop = 50
    window.model.revision = 2
    window.balloons.cursorMoved()
  })
  await page.waitForFunction(() => document.querySelector('.awd-review-balloons').dataset.viewport.split(':')[1] === '750')
  assert.equal(await page.$eval('textarea', n => n.value), 'Draft stays intact')
  await commentButton(page, 'Save')
  assert.equal(await page.evaluate(() => window.commands.find(c => c.action === 'update_comment').params.revision), 1, 'a draft cannot silently gain a newer document token')
})


test('read-only transition removes an open editor and all mutation controls', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  await commentButton(page, 'Edit')
  await page.evaluate(() => { window.model.writable = false; window.balloons.cursorMoved() })
  await page.waitForFunction(() => !document.querySelector('textarea') && !document.querySelector('.awd-rb-actions button'))
  assert.equal(await page.evaluate(() => window.commands.some(c => c.action === 'update_comment')), false)
})

test('a steady native gutter does not poll or repeat its visibility command', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]', { visible: true })
  await new Promise(resolve => setTimeout(resolve, 150))
  const before = await page.evaluate(() => window.commands.length)
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(await page.evaluate(() => window.commands.length), before)
  await page.evaluate(() => { window.balloons.cursorMoved(); window.balloons.documentChanged() })
  await new Promise(resolve => setTimeout(resolve, 150))
  assert.deepEqual(await page.evaluate(() => window.commands.filter(c => c.action === 'set_review_balloons').map(c => c.params)), [{ enabled: true, width: 280 }])
})


test('balloons omit inserted text while keeping deletion and formatting cards', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="g0"]')
  await page.evaluate(() => {
    const revision = (index, type) => ({ key: 'r' + index, kind: 'revision', data: { index, identifier: String(index), type, text: type }, x: 3000, y: 1500 + index * 1500, page: 1 })
    window.reviewItems = [revision(0, 'Delete'), revision(1, 'Insert'), revision(2, 'Format')]
    window.balloons.documentChanged()
  })
  await page.waitForFunction(() => [...document.querySelectorAll('.awd-rb-content')].some(n => n.textContent === 'Format'))
  assert.deepEqual(await page.$$eval('.awd-rb-content', nodes => nodes.map(n => n.textContent)), ['Delete', 'Format'])
})

test('revision actions include the document and exact native target snapshot', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="g0"] .awd-rb-actions button')
  await page.$eval('[data-key="g0"]',n=>n.click())
  const navigation=await page.evaluate(()=>window.commands.find(c=>c.action==='goto_revision').params)
  assert.equal(navigation.identifier,'revision-A')
  assert.equal(navigation.documentSeq,1)
  await page.click('[data-key="g0"] .awd-rb-actions button')
  const params = await page.evaluate(() => window.commands.find(c => c.action === 'resolve_revisions').params)
  assert.equal(params.documentSeq, 1)
  assert.deepEqual(params.expectedRevisions.map(r => ({ identifier: r.identifier, type: r.type, text: r.text })), [{ identifier: 'revision-A', type: 'Delete', text: 'Deleted text' }])
})

test('ten long comments remain reachable inside their page without covering the following page', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]')
  await page.evaluate(() => {
    window.reviewItems = Array.from({ length: 10 }, (_, i) => ({ key: 'c' + i, kind: 'comment', data: { id: String(i), index: i, content: 'Long comment ' + i + ' ' + 'Complete text remains available. '.repeat(100), author: 'A' }, x: 3000, y: 1500 + i * 15, page: 1 }))
    window.reviewItems.push({ key: 'next', kind: 'comment', data: { id:'next', index:10, content:'Next page', author:'A' }, x:3000, y:10500, page:2 })
    window.reviewPages = [
      { number:1, x:0, y:0, width:7200, height:9000, sidebar:'right', gutterWidth:4200 },
      { number:2, x:0, y:9750, width:7200, height:9000, sidebar:'right', gutterWidth:4200 },
    ]
    window.nativeWheels = 0
    document.querySelector('canvas').addEventListener('wheel', () => window.nativeWheels++)
    window.balloons.documentChanged()
  })
  await page.waitForSelector('.awd-rb-page[data-page="1"] .awd-rb-overflow:not([hidden])')
  const nextTop = await page.$eval('[data-key="next"]', n => n.getBoundingClientRect().top)
  assert.equal(await page.$eval('.awd-rb-page[data-page="1"]', n => getComputedStyle(n).overflow), 'hidden')
  for (let index = 0; index < 10; index++) {
    await page.evaluate(index => {
      const slider = document.querySelector('.awd-rb-page[data-page="1"] .awd-rb-overflow')
      const card = document.querySelector('[data-key="c' + index + '"]')
      slider.value = Math.min(Number(slider.max), Number(slider.value) + parseFloat(card.style.top) * 15)
      slider.dispatchEvent(new Event('input', { bubbles:true }))
    }, index)
    const card = await page.$eval('[data-key="c' + index + '"]', n => ({ top:n.getBoundingClientRect().top, bottom:n.getBoundingClientRect().bottom, text:n.querySelector('.awd-rb-content').textContent }))
    assert.ok(card.top >= 7 && card.bottom <= 609, 'comment ' + index + ' is fully reachable within its page')
    assert.ok(card.text.endsWith('Complete text remains available. '), 'comment ' + index + ' retains all content')
  }
  await page.$eval('.awd-rb-page[data-page="1"] .awd-rb-overflow', n => { n.value = n.max; n.dispatchEvent(new Event('input', { bubbles:true })) })
  const final = await page.$eval('[data-key="c9"]', n => ({ top:n.getBoundingClientRect().top, bottom:n.getBoundingClientRect().bottom, visibility:getComputedStyle(n).visibility, content:n.querySelector('.awd-rb-content').textContent }))
  assert.ok(final.top >= 8 && final.bottom <= 609 && final.visibility === 'visible', 'last full card reaches the visible page gutter')
  assert.match(final.content, /Long comment 9/)
  assert.equal(await page.$eval('[data-key="next"]', n => n.getBoundingClientRect().top), nextTop)
  await page.$eval('[data-key="c9"] .awd-rb-meta', n => n.dispatchEvent(new WheelEvent('wheel', { deltaY:100, bubbles:true, cancelable:true })))
  assert.equal(await page.evaluate(() => window.nativeWheels), 1, 'bottom boundary hands scrolling back to the document')
  await page.$eval('[data-key="c9"] .awd-rb-meta', n => n.dispatchEvent(new WheelEvent('wheel', { deltaY:-100, bubbles:true, cancelable:true })))
  assert.equal(await page.evaluate(() => window.nativeWheels), 1, 'page overflow consumes movement while it can scroll')
  assert.ok(await page.$eval('.awd-rb-page[data-page="1"] .awd-rb-overflow', n => Number(n.value) < Number(n.max)))
})


test('an omitted insertion still separates unrelated deletion groups across pages', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="g0"]')
  await page.evaluate(() => {
    const item = (index, type, page, y, contiguous) => ({ key:'r'+index, kind:'revision', page, x:3000, y,
      data:{index,identifier:String(index),type,author:type==='Insert'?'B':'A',date:'2026-09-11 10:00',text:'Change '+index,contiguous} })
    window.reviewItems = [item(0,'Delete',1,1500,false),item(1,'Insert',2,18000,false),item(2,'Delete',2,18015,true)]
    window.reviewPages = [
      {number:1,x:0,y:0,width:7200,height:15000,sidebar:'right',gutterWidth:4200},
      {number:2,x:0,y:17000,width:7200,height:15000,sidebar:'right',gutterWidth:4200},
    ]
    window.balloons.documentChanged()
  })
  await page.waitForFunction(() => document.querySelector('[data-key="g0"] .awd-rb-content')?.textContent.startsWith('Change'))
  assert.deepEqual(await page.$$eval('.awd-rb-card', nodes=>nodes.map(n=>({key:n.dataset.key,page:n.parentElement.dataset.page,text:n.querySelector('.awd-rb-content').textContent}))), [
    {key:'g0',page:'1',text:'Change 0'},{key:'g2',page:'2',text:'Change 2'},
  ])
  await page.click('[data-key="g0"] .awd-rb-actions button')
  assert.deepEqual(await page.evaluate(()=>window.commands.find(c=>c.action==='resolve_revisions').params.indices),[0])
})


test('an insert-only document clears a native gutter pre-reserved by the revision mode', async t => {
  const page = await mount(t, { insertOnly:true, nativeWidth:280 })
  await page.waitForFunction(() => window.commands.some(c=>c.action==='get_review_layout'))
  await new Promise(resolve=>setTimeout(resolve,150))
  assert.equal(await page.evaluate(()=>window.model.nativeWidth),0)
  assert.equal(await page.$('.awd-rb-card'),null)
  assert.equal(await page.evaluate(()=>document.querySelector('.awd-review-balloons').hidden),true)
  assert.deepEqual(await page.evaluate(()=>window.commands.filter(c=>c.action==='set_review_balloons').map(c=>c.params)),[{enabled:false,width:280}])
})

test('comment navigation carries the document fence and mutations carry captured target snapshots', async t => {
  const page = await mount(t)
  await page.waitForSelector('[data-key="c0"]')
  await page.$eval('[data-key="c0"]',n=>n.click())
  await commentButton(page,'Resolve')
  await commentButton(page,'Delete')
  await commentButton(page,'Edit')
  await page.$eval('textarea',n=>{n.value='Edited comment'})
  await commentButton(page,'Save')
  const commands=await page.evaluate(()=>window.commands.filter(c=>['goto_comment','set_comment_resolved','delete_comment','update_comment'].includes(c.action)))
  assert.deepEqual(commands.map(c=>c.action),['goto_comment','set_comment_resolved','delete_comment','update_comment'])
  for(const command of commands) {
    assert.equal(command.params.documentSeq,1,command.action)
    assert.equal(command.params.id,'0',command.action)
    if(command.action !== 'goto_comment') {
      assert.equal(command.params.expectedComment.id,'0',command.action)
      assert.equal(command.params.expectedComment.content,'Same original comment',command.action)
    }
  }
})
