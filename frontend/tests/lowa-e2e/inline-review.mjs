// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict'
import JSZip from 'jszip'
import { fileURLToPath } from 'node:url'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

preflight()
const server = await startServer({ extraFiles: { '/inline-review-host.js': fileURLToPath(new URL('../../src/composables/inlineReviewHost.js', import.meta.url)) } })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  page.on('pageerror', e => console.error('PAGE ERROR:', e.message))
  // Native browser timers require a Window receiver; Node's timers do not catch this.
  const hostCheck = await page.evaluate(async () => {
    const { createInlineReviewHost } = await import('/inline-review-host.js')
    let calls = 0
    const h = createInlineReviewHost({ projectId: 1, fileId: 1, userId: 'timer-test', delay: 10,
      send() {}, review: async (_id, body) => { if (body.deep) throw new Error('unexpected AI'); calls++; return { findings: [] } },
      execute: async a => a === 'set_revision_view' ? { mode: 'margin' } : a === 'get_document_text'
        ? { success: true, revision: 1, paragraphs: [{ index: 0, text: 'test' }] } : { revision: 1 } })
    h.start()
    await new Promise(resolve => setTimeout(resolve, 100))
    h.modified(); h.destroy()
    await new Promise(resolve => setTimeout(resolve, 100))
    return calls
  })
  assert.equal(hostCheck, 1, 'browser defaults run one local check and cancel after destroy')
  let caretClick = 0
  // Alternate positions so rapid automated clicks do not become a Qt double-click selection.
  const clickCaret = () => page.mouse.click(caretClick++ % 2 ? 260 : 130, 254)
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  const ok = async (a, p) => { const r = await exec(a, p); assert.equal(r?.success, true, a + ': ' + JSON.stringify(r)); return r }
  const zip = new JSZip()
  zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
  zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
  zip.file('word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p/></w:body></w:document>')
  const blank = Array.from(await zip.generateAsync({ type: 'uint8array' }))
  const body = '甲方应在30日内付款。'
  const reset = async () => { await ok('load_document', { name: 'inline-review.docx', bytes: blank }); await ok('insert_at_cursor', { text: body }) }
  const snapshot = () => ok('get_document_text')
  const waitChip = async () => { try { await page.waitForSelector('.awd-ir-chip:not([hidden])', { timeout: 4000 }) } catch (e) { console.log('CHIP WAIT', await exec('get_review_context'), await page.evaluate(() => ({ state: window.__lastReviewState, input: document.activeElement?.outerHTML.slice(0,100), chip: document.querySelector('.awd-ir-chip')?.outerHTML, clicks: window.__reviewClicks }))); throw e } }
  const text = async () => (await snapshot()).paragraphs.map(p => p.text).join('\n')
  const params = async (paragraphIndex = 0) => { const s = await snapshot(); const p = s.paragraphs[paragraphIndex]; const start = p.text.indexOf('30'); return { revision: s.revision, paragraphIndex, start, end: start + 2, expectedParagraph: p.text, quote: '30', replacement: '15' } }
  await reset()
  let p = await params(), ctx = await ok('get_review_context')
  assert.equal(ctx.paragraphIndex, 0); assert.equal(ctx.text, body); assert.equal(ctx.revision, p.revision)
  await ok('export_document', { name: 'review-readonly.docx' }); assert.equal((await snapshot()).revision, p.revision, 'export does not expire a review')
  await ok('goto_review_range', p); assert.equal((await snapshot()).revision, p.revision, 'locating only moves selection')
  assert.equal(await text(), body)
  assert.equal((await exec('apply_review_edit', { ...p, quote: 'wrong' })).success, false)
  await ok('apply_review_edit', p); assert.equal(await text(), body.replace('30', '15'))
  assert.equal((await exec('apply_review_edit', p)).reason, 'stale', 'replay is rejected')
  await ok('undo'); assert.equal(await text(), body, 'one undo reverts the whole correction')
  await ok('redo'); assert.equal(await text(), body.replace('30', '15'))
  await reset(); p = await params()
  await ok('insert_at_cursor', { text: '新增' })
  assert.equal((await exec('goto_review_range', p)).reason, 'stale'); assert.equal((await exec('apply_review_edit', p)).reason, 'stale')
  await reset(); p = await params(); await reset()
  assert.equal((await exec('apply_review_edit', p)).reason, 'stale', 'identical reload rejects old revision')
  await ok('insert_paragraph'); await ok('insert_at_cursor', { text: body })
  p = await params(1); await ok('apply_review_edit', p)
  assert.deepEqual((await snapshot()).paragraphs.map(p => p.text), [body, body.replace('30', '15')], 'duplicate quotes are confined to the verified paragraph')
  await ok('undo'); assert.deepEqual((await snapshot()).paragraphs.map(p => p.text), [body, body])
  await ok('set_revision_view', { mode: 'all' }); assert.equal((await exec('get_review_context')).reason, 'inline-revisions'); await ok('set_revision_view', { mode: 'margin' })
  console.log('PASS atomic revision/range checks, export stability, duplicate paragraphs, tracked one-step undo/redo')

  await reset(); p = await params()
  await page.evaluate(() => { window.__reviewRequests = []; window.__reviewClicks=[]; document.getElementById('qtcanvas').addEventListener('mouseup',e=>window.__reviewClicks.push([e.clientX,e.clientY]),true); window.addEventListener('message', e => { if (e.data?.type === 'inline-review-request') window.__reviewRequests.push(e.data); if(e.data?.type === 'inline-review-state') window.__lastReviewState=e.data }) })
  // The real host checks after its 1.2s debounce, after the guest's 500ms modified relay.
  const state = async (patch = {}) => { await new Promise(resolve => setTimeout(resolve, 600)); return page.evaluate(async (p, patch) => { window.postMessage({ __lo: 'lo-relay', type: 'inline-review-state', session: 'review-test', enabled: true, writable: true, revision: p.revision, status: 'ready', deepStatus: 'idle', findings: [{ id: 'term', kind: 'TEST_FIXTURE', title: '付款期限待核对', message: '测试提示：请核对两处约定。', severity: 'warning', ...p }], ...patch }, location.origin); await new Promise(resolve => setTimeout(resolve, 0)) }, p, patch) }
  await state()
  await clickCaret()
  try { await page.waitForSelector('.awd-ir-chip:not([hidden])', { timeout: 5000 }) } catch (e) { console.log('INITIAL CHIP', p, await exec('get_review_context'), await page.$eval('.awd-ir-status', e => e.textContent)); await page.screenshot({ path: '/tmp/awd-547-inline-failure.png' }); throw e }
  await page.click('.awd-ir-chip')
  await page.waitForFunction(() => document.querySelector('.awd-ir-panel')?.textContent.includes('付款期限待核对'))
  assert.equal(await text(), body, 'showing review does not change the document')
  assert.equal(await page.evaluate(() => window.__reviewRequests.length), 0, 'no AI or external request without a click')
  await page.screenshot({ path: '/tmp/awd-547-inline-review.png' })
  const clickButton = label => page.evaluate(label => [...document.querySelectorAll('.awd-ir-panel button')].find(b => b.textContent === label).click(), label)
  await clickButton('深入审校（AI）')
  await page.waitForFunction(() => window.__reviewRequests.length === 1)
  assert.equal(await page.evaluate(() => window.__reviewRequests[0].action), 'deep')
  assert.equal(await page.evaluate(() => [...document.querySelectorAll('.awd-ir-panel button')].find(b => b.textContent === '深入审校中…').disabled), true)
  await state(); await page.waitForFunction(() => !document.querySelector('.awd-ir-panel')?.hidden)
  await clickButton('采用建议'); await page.waitForFunction(() => document.querySelector('.awd-ir-panel')?.textContent.includes('已采用建议'))
  assert.equal(await text(), body.replace('30', '15')); await ok('undo'); assert.equal(await text(), body); await ok('collapse_selection', { to: 'end' })
  p = await params(); await state(); await clickCaret()
  try { await page.waitForSelector('.awd-ir-chip:not([hidden])', { timeout: 4000 }) } catch (e) {
    console.log('AFTER UNDO', p, await exec('get_review_context'), await page.$eval('.awd-ir-status', e => e.textContent)); await page.screenshot({ path: '/tmp/awd-547-inline-failure.png' }); throw e
  }
  await page.evaluate(() => document.querySelector('input[data-lo-ime]').dispatchEvent(new CompositionEvent('compositionstart', { bubbles: true })))
  assert.equal(await page.$eval('.awd-ir-chip', e => e.hidden), true, 'IME hides hints immediately')
  await page.evaluate(() => document.querySelector('input[data-lo-ime]').dispatchEvent(new CompositionEvent('compositionend', { data: '', bubbles: true })))
  await clickCaret(); await waitChip()
  await page.mouse.wheel({ deltaY: 70 }); assert.equal(await page.$eval('.awd-ir-chip', e => e.hidden), true, 'scroll hides hints')
  p = await params(); await state({ revision: p.revision - 1 }); await clickCaret()
  await new Promise(resolve => setTimeout(resolve, 300)); assert.equal(await page.$eval('.awd-ir-chip', e => e.hidden), true, 'stale host result never paints a hint')
  const exported = await ok('export_document', { name: 'review-clean.docx' })
  const docx = await JSZip.loadAsync(Uint8Array.from(Object.values(exported.bytes)))
  assert.equal((await docx.file('word/document.xml').async('string')).includes('__ai_anchor_'), false, 'review adds no persisted anchors')
  console.log('PASS real guest chip/detail, explicit AI only, guarded apply/undo, IME/scroll/stale hiding, no document anchors')
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)) }
