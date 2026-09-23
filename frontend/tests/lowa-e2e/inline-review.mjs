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
  const snapshot = () => ok('get_document_text', { __agent: true })
  const waitChip = async () => { try { await page.waitForSelector('.awd-ir-chip:not([hidden])', { timeout: 4000 }) } catch (e) { console.log('CHIP WAIT', await exec('get_review_context'), await page.evaluate(() => ({ state: window.__lastReviewState, input: document.activeElement?.outerHTML.slice(0,100), chip: document.querySelector('.awd-ir-chip')?.outerHTML, clicks: window.__reviewClicks }))); throw e } }
  const ballText = () => page.$eval('.awd-ir-ball', e => (e.hidden ? '' : e.textContent))
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
  await ok('set_revision_view', { mode: 'all' }); assert.equal((await exec('get_review_context')).success, true); await ok('set_revision_view', { mode: 'margin' })
  console.log('PASS atomic revision/range checks, export stability, duplicate paragraphs, tracked one-step undo/redo')

  await reset(); p = await params()
  await page.evaluate(() => { window.__reviewRequests = []; window.__reviewClicks=[]; document.getElementById('qtcanvas').addEventListener('mouseup',e=>window.__reviewClicks.push([e.clientX,e.clientY]),true); window.addEventListener('message', e => { if (e.data?.type === 'inline-review-request') window.__reviewRequests.push(e.data); if(e.data?.type === 'inline-review-state') window.__lastReviewState=e.data }) })
  // The real host checks after its 1.2s debounce, after the guest's 500ms modified relay.
  const state = async (patch = {}) => { await new Promise(resolve => setTimeout(resolve, 600)); p = { ...p, revision: (await snapshot()).revision }; return page.evaluate(async (p, patch) => { window.postMessage({ __lo: 'lo-relay', type: 'inline-review-state', session: 'review-test', ai: true, writable: true, revision: p.revision, status: 'ready', deepStatus: 'idle', findings: [{ id: 'term', kind: 'TEST_FIXTURE', title: '付款期限待核对', message: '测试提示：请核对两处约定。', severity: 'warning', ...p }], ...patch }, location.origin); await new Promise(resolve => setTimeout(resolve, 0)) }, p, patch) }
  await state()
  await clickCaret()
  try { await page.waitForSelector('.awd-ir-chip:not([hidden])', { timeout: 5000 }) } catch (e) { console.log('INITIAL CHIP', p, await exec('get_review_context'), await ballText()); await page.screenshot({ path: '/tmp/awd-547-inline-failure.png' }); throw e }
  // dev-board#723/#724：正文里只剩浮球 + 行旁标记，清单在宿主右栏的「审校」标签里。
  assert.equal(await page.$$eval('.awd-ir-panel', n => n.length), 0, 'no panel may cover the body text')
  assert.equal((await ballText()).includes('1'), true, 'the ball carries the unread count')
  await page.click('.awd-ir-chip')
  await page.waitForFunction(() => window.__reviewRequests.some(r => r.action === 'open-panel'))
  assert.equal(await text(), body, 'showing review does not change the document')
  assert.equal(await page.evaluate(() => window.__reviewRequests.filter(r => r.action !== 'open-panel').length), 0, 'no AI or external request without a click')
  await page.click('.awd-ir-open')
  await page.waitForFunction(() => window.__reviewRequests.filter(r => r.action === 'open-panel').length === 2)
  await page.screenshot({ path: '/tmp/awd-547-inline-review.png' })
  // 浮球拖动后靠边吸附，位置落 localStorage（客体页每开一份文档都是新 webview，
  // sessionStorage 每次重来，位置留不住）。
  const ballBox = await (await page.$('.awd-ir-ball')).boundingBox()
  await page.mouse.move(ballBox.x + ballBox.width / 2, ballBox.y + ballBox.height / 2)
  await page.mouse.down(); await page.mouse.move(ballBox.x + 180, Math.max(40, ballBox.y - 120), { steps: 10 }); await page.mouse.up()
  const movedBox = await (await page.$('.awd-ir-ball')).boundingBox()
  assert.ok(Math.abs(movedBox.y - ballBox.y) > 40, 'the ball follows a real drag')
  assert.ok(await page.evaluate(() => Object.keys(localStorage).some(k => k.startsWith('awd_inline_review_'))), 'ball position persists')
  // dev-board#749：AI 关掉之后浮球**不消失**（开关就在它身上，藏了就没地方再打开），
  // 只是弱化、并且不再自动调模型；规则检查照跑，所以计数还在。
  await state({ ai: false })
  assert.equal(await page.$eval('.awd-ir-ball', e => e.hidden), false, 'the ball survives turning AI off')
  assert.equal(await page.$eval('.awd-ir-ball', e => e.classList.contains('off')), true)
  assert.equal((await ballText()).includes('1'), true, 'rule findings still counted with AI off')
  // 浮球菜单：开/关、立即 AI 审校、隐藏浮球三项，且都只是给宿主发一条请求。
  await page.click('.awd-ir-more')
  assert.equal(await page.$eval('.awd-ir-menu', e => e.hidden), false)
  await page.click('.awd-ir-menu button')
  await page.waitForFunction(() => window.__reviewRequests.some(r => r.action === 'preferences' && r.data?.ai === true))
  // dev-board#866：「收起」不是消失——浮球贴到它那一侧的边缘，只留一截可点的把手；
  // 真鼠标点把手 = 请求展开。旧实现（真隐藏）下 .awd-ir-dock 不存在，这一段转红。
  await state()
  await page.screenshot({ path: '/tmp/awd-866-ball-expanded.png' })
  await page.click('.awd-ir-more')
  await page.click('.awd-ir-menu button:last-child')
  await page.waitForFunction(() => window.__reviewRequests.some(r => r.action === 'preferences' && r.data?.hidden === true))
  await state({ hidden: true })
  assert.equal(await page.$eval('.awd-ir-ball', e => e.hidden), true, 'collapsed: the ball body leaves the text')
  const dockBox = await (await page.$('.awd-ir-dock')).boundingBox()
  assert.ok(dockBox && dockBox.width >= 14 && dockBox.height >= 40, 'collapsed: a clickable handle stays on the edge ' + JSON.stringify(dockBox))
  const viewportWidth = await page.evaluate(() => window.innerWidth)
  assert.ok(dockBox.x <= 1 || dockBox.x + dockBox.width >= viewportWidth - 40, 'the handle hugs the edge the ball lived on ' + JSON.stringify(dockBox))
  assert.equal(await page.evaluate(() => { const r = document.querySelector('.awd-ir-dock').getBoundingClientRect(); return document.elementFromPoint(r.x + r.width / 2, r.y + r.height / 2)?.closest('.awd-ir-dock') != null }), true, 'nothing covers the handle')
  await page.screenshot({ path: '/tmp/awd-866-ball-docked.png' })
  await page.mouse.click(dockBox.x + dockBox.width / 2, dockBox.y + dockBox.height / 2)
  await page.waitForFunction(() => window.__reviewRequests.some(r => r.action === 'preferences' && r.data?.hidden === false))
  await state({ hidden: false })
  assert.equal(await page.$eval('.awd-ir-ball', e => e.hidden), false, 'clicking the handle brings the ball back')
  assert.equal(await page.$eval('.awd-ir-dock', e => e.hidden), true)
  const backBox = await (await page.$('.awd-ir-ball')).boundingBox()
  assert.ok(Math.abs(backBox.y - movedBox.y) < 2, 'the ball returns to where it was')
  await page.screenshot({ path: '/tmp/awd-866-ball-reexpanded.png' })
  console.log('PASS collapse-to-edge handle: visible, clickable, expands back in place')
  // 会话结束（宿主 destroy）才把正文里的东西全部摘掉。
  await state({ status: 'disabled' })
  assert.equal(await page.$eval('.awd-ir-ball', e => e.hidden), true, 'a finished session keeps the body text clean')
  assert.equal(await page.$eval('.awd-ir-chip', e => e.hidden), true)
  assert.equal(await page.$eval('.awd-ir-dock', e => e.hidden), true, 'a finished session leaves no handle either')
  await state(); await clickCaret()
  try { await page.waitForSelector('.awd-ir-chip:not([hidden])', { timeout: 4000 }) } catch (e) {
    console.log('AFTER RE-ENABLE', p, await exec('get_review_context'), await ballText()); await page.screenshot({ path: '/tmp/awd-547-inline-failure.png' }); throw e
  }
  // 采用建议这条链路在本文件前半段已用真引擎逐项验过（goto/apply/undo/redo/stale），
  // 按钮本身现在长在宿主的 Vue 面板里，不在客体页——那一半由 desktop-e2e/writing.mjs 走。
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
  console.log('PASS real guest ball/chip, host-side list only, guarded apply/undo, IME/scroll/stale hiding, no document anchors')
} finally { await browser.close(); await new Promise(resolve => server.close(resolve)) }
