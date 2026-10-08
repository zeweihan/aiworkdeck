// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1135: opening a revision-heavy file must not scan hover metadata.
// Use a real display: headless screenshots can themselves trigger Qt repaint.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import JSZip from 'jszip'
import { PNG } from 'pngjs'
import { hoverViewportMetrics, hitTestRevision } from '../../src/composables/zetaOfficeReviewHover.js'
import { fixture } from './_revision-fixture.mjs'
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs'

const zip = await JSZip.loadAsync(Uint8Array.from(await fixture(false)))
const paragraphs = Array.from({ length: 60 }, (_, p) => '<w:p>' + Array.from({ length: 10 }, (_, n) => {
  const id = p * 10 + n, type = n % 2 ? 'del' : 'ins', tag = type === 'del' ? 'delText' : 't'
  return `<w:${type} w:id="${id}" w:author="Reviewer ${id}" w:date="2026-10-08T08:00:00Z"><w:r><w:${tag}>合成测试条款${id}：按期核验并交付文件。</w:${tag}></w:r></w:${type}>`
}).join('') + '</w:p>').join('')
zip.file('word/document.xml', (await zip.file('word/document.xml').async('string')).replace(/<w:body>[\s\S]*?<w:sectPr>/, '<w:body>' + paragraphs + '<w:sectPr>'))
const bytes = Array.from(await zip.generateAsync({ type: 'uint8array' }))
const fontRoot = process.env.LOWA_FONT_DIR || 'dist/zetaoffice'
const extraFiles = Object.fromEntries(['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf'].map(f => ['/' + f, fontRoot + '/' + f]))
preflight()
const server = await startServer({ extraFiles, patchServed(url, bytes) {
  if (url !== '/office_thread.js') return bytes
  const source = process.env.OPEN_WORKER_SOURCE ? fs.readFileSync(process.env.OPEN_WORKER_SOURCE, 'utf8') : bytes.toString()
  const anchor = 'function execCommand(reqId, action, params) {'
  assert.equal(source.split(anchor).length, 2)
  return Buffer.from(source.replace(anchor, anchor + " log('open-probe ' + action);"))
} })
const browser = await launchBrowser(await loadPuppeteer(), { headed: true })
try {
  const page = await openEditor(browser, { viewport: { width: 1440, height: 900 } })
  // This test exercises opening/hover, not typing. A headed window can receive
  // host keyboard input while other work continues; keep it out of the fixture.
  await page.evaluate(() => {
    for (const type of ['keydown','keyup','beforeinput','input']) window.addEventListener(type, e => { e.preventDefault(); e.stopImmediatePropagation() }, true)
  })
  await page.addStyleTag({ content: '#verify,#vlog{display:none!important}' })
  const actions = []
  page.on('console', message => { const m = message.text().match(/open-probe (\w+)/); if (m) actions.push(m[1]) })
  const ok = async (action, params = {}) => {
    const r = await page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
    assert.equal(r.success, true, action)
    return r
  }
  const start = performance.now()
  await ok('load_document', { name: 'synthetic-revisions.docx', bytes })
  const loadMs = Math.round(performance.now() - start)
  // The real toolbar's bootstrap, including a resize after it becomes visible.
  await ok('get_ui_state')
  await Promise.all([ok('list_styles'), ok('list_fonts')])
  await ok('set_chrome', { all: false })
  await ok('set_view_options', { formattingMarks: false, ruler: false })
  await page.setViewport({ width: 1440, height: 840 })
  await new Promise(resolve => setTimeout(resolve, 600))
  assert.equal(actions.filter(a => a === 'list_revisions').length, 0, 'opening/resizing must not scan hover metadata before pointer intent')
  const pixels = PNG.sync.read(await page.screenshot({ captureBeyondViewport: false }))
  let paper = 0, ink = 0
  for (let y = 100; y < 700; y++) for (let x = 200; x < 1000; x++) {
    const i = (y * pixels.width + x) * 4, rgb = pixels.data.subarray(i, i + 3)
    if (Math.min(...rgb) > 240) paper++
    if (Math.max(...rgb) - Math.min(...rgb) > 60 || Math.max(...rgb) < 100) ink++
  }
  assert.ok(paper > 100000 && ink > 1000, 'real first screen must contain both paper and text')
  const openMs = Math.round(performance.now() - start)
  const layout = await ok('get_review_layout')
  const last = layout.items.filter(i => i.kind === 'revision').at(-1)
  assert.ok(last && last.data.index > 400, 'exercise a late revision, not just the first one')
  const hitStart = performance.now()
  const hit = await ok('list_revisions', { index: last.data.index, locate: false, documentSeq: layout.documentSeq, revision: layout.revision })
  assert.equal(hit.revisions.length, 1)
  assert.equal(hit.revisions[0].index, last.data.index)
  const hitMs = Math.round(performance.now() - hitStart)
  assert.ok(hitMs < 1000, 'one hover hit must not scan all 500 revisions: ' + hitMs + 'ms')
  const bounds = await page.$eval('#qtcanvas', e => { const r = e.getBoundingClientRect(); return {left:r.left,top:r.top,width:r.width,height:r.height} })
  const metrics = hoverViewportMetrics(bounds, layout.view)
  const pointOf = i => ({x:Math.round(metrics.originX + (i.x + i.w / 2 - layout.view.left) * metrics.scale),
    y:Math.round(metrics.originY + (i.y + i.h / 2 - layout.view.top) * metrics.scale)})
  const visible = layout.items.find(i => {
    const p=pointOf(i)
    return i.kind === 'revision' && p.x > 100 && p.x < 1300 && p.y > 100 && p.y < 700
      && hitTestRevision(p.x,p.y,layout.items,metrics) === i
  })
  assert.ok(visible, 'a visible revision is required for real mouse hover')
  const point=pointOf(visible)
  const target = await page.evaluate(p => document.elementFromPoint(p.x,p.y)?.id, point)
  assert.equal(target,'qtcanvas','hover point must hit the real canvas')
  await page.mouse.move(point.x,point.y)
  try {
    await page.waitForFunction(() => { const card = document.querySelector('.awd-review-hover'); return card && !card.hidden && card.textContent.includes('Reviewer') }, {timeout:3000})
  } catch (e) {
    console.log('hover probe', JSON.stringify({visible,metrics,actions:actions.slice(-12),card:await page.$eval('.awd-review-hover', e=>({hidden:e.hidden,text:e.textContent}))}))
    throw e
  }
  await page.mouse.move(0, 0)
  // Readiness must preserve every revision and both original/final text.
  const exported = await page.evaluate(async () => {
    const r = await window.__loExecutor.executeCommand('export_document', { name: 'roundtrip.docx' })
    return { success:r.success, bytes:Array.from(r.bytes || []) }
  })
  assert.equal(exported.success,true)
  const saved = await JSZip.loadAsync(Uint8Array.from(exported.bytes))
  const xml = await saved.file('word/document.xml').async('string')
  const texts = await page.evaluate(xml => {
    const W='http://schemas.openxmlformats.org/wordprocessingml/2006/main'
    const document=new DOMParser().parseFromString(xml,'application/xml')
    const paragraphs=[...document.getElementsByTagNameNS(W,'p')]
    const read=original=>paragraphs.map(p=>[...p.getElementsByTagName('*')].filter(n=>{
      if(n.namespaceURI!==W||!['t','delText'].includes(n.localName))return false
      for(let parent=n.parentElement;parent&&parent!==p;parent=parent.parentElement)
        if(parent.namespaceURI===W&&parent.localName===(original?'ins':'del'))return false
      return true
    }).map(n=>n.textContent).join(''))
    return {original:read(true),final:read(false)}
  },xml)
  const expected=parity=>Array.from({length:60},(_,p)=>Array.from({length:10},(_,n)=>n%2===parity?`合成测试条款${p*10+n}：按期核验并交付文件。`:'').join(''))
  assert.deepEqual(texts.original,expected(1),'rejecting all revisions restores every original paragraph')
  assert.deepEqual(texts.final,expected(0),'accepting all revisions preserves every target paragraph')
  assert.ok((xml.match(/<w:ins\b/g)||[]).length>=300 && (xml.match(/<w:del\b/g)||[]).length>=300)
  console.log(JSON.stringify({ loadMs, openMs, hitMs, paperPixels: paper, inkPixels: ink, revisionTextRoundTrip: true }))
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)) }
