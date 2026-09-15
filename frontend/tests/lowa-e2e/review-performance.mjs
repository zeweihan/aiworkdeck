// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Real engine regression: review positioning must leave scrolling responsive,
// reuse unchanged metadata, and never reverse-hit-test the document.
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fixture } from './_revision-fixture.mjs'
import { startServer, launchBrowser, loadPuppeteer, preflight, ORIGIN } from './_boot.mjs'

preflight()
const server = await startServer({ patchServed(url, bytes) {
  if (url === '/office_thread.js') {
    let source = bytes.toString()
    // Each probe must land exactly once, or the measurements below read nothing.
    const patch = (from, to) => {
      if (source.split(from).length !== 2) throw new Error('review-performance probe anchor not unique: ' + from)
      source = source.replace(from, to)
    }
    patch('let reviewLayoutCache = null;', 'let reviewLayoutCache = null; const reviewPerf = { layouts: 0, metadata: 0, hits: 0, durations: [] };')
    patch('const comments = EXEC.list_comments({ limit: 500, locate: false });', 'reviewPerf.metadata++; const comments = EXEC.list_comments({ limit: 500, locate: false });')
    source = source.replaceAll('ctrl.createTextRangeByPixelPosition(', '((...args) => { reviewPerf.hits++; return ctrl.createTextRangeByPixelPosition(...args); })(')
    patch('get_review_layout(p) { return reviewLayout(p); },', 'get_review_layout(p) { const t = performance.now(); try { return reviewLayout(p); } finally { reviewPerf.layouts++; reviewPerf.durations.push(performance.now() - t); } },')
    patch('const EXEC = {', 'const EXEC = { debug_review_perf() { return { success: true, perf: reviewPerf, selection: ctrl.getViewCursor().getString(), revision: currentReviewRevision() }; },')
    return Buffer.from(source)
  }
  if (/^\/assets\/editor-.*\.js$/.test(url)) return Buffer.from(bytes.toString().replace(/(['"])get_hyperlink_at_cursor\1/, match => match + ',"debug_review_perf"'))
  return bytes
} })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1600, height: 1000, deviceScaleFactor: Number(process.env.LOWA_E2E_DPR || 1) })
  const bootStart = performance.now()
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  const bootMs = Math.round(performance.now() - bootStart)
  await page.addStyleTag({ content: '#verify,#vlog{display:none!important}' })
  const exec = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const ok = async (action, params) => {
    const result = await exec(action, params)
    assert.equal(result.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const bytes = process.env.REVIEW_FIXTURE ? Array.from(readFileSync(process.env.REVIEW_FIXTURE)) : await fixture(true)
  const loadStart = performance.now()
  await ok('load_document', { name: 'review-performance.docx', bytes })
  const loadMs = Math.round(performance.now() - loadStart)
  await ok('set_chrome', { all: false })
  await ok('set_zoom', { value: 100 })
  await ok('set_revision_view', { mode: 'balloons' })
  const firstStart = performance.now()
  await ok('get_cursor_rect')
  const firstInteractionMs = Math.round(performance.now() - firstStart)
  await page.waitForFunction(() => document.querySelector('.awd-rb-card'), { timeout: 30000 })
  // Let native deferred layout events settle, then measure actual steady scroll.
  await new Promise(resolve => setTimeout(resolve, 500))
  const before = await ok('debug_review_perf')
  const latencies = []
  for (let i = 0; i < 12; i++) {
    await page.mouse.move(550, 650)
    await page.mouse.wheel({ deltaY: i < 6 ? 180 : -180 })
    const start = performance.now()
    const layout = await ok('get_review_layout')
    assert.equal(layout.available, true, 'requires the rebuilt native geometry engine')
    latencies.push(Math.round(performance.now() - start))
  }
  const after = await ok('debug_review_perf')
  assert.equal(after.perf.hits, before.perf.hits, 'scrolling performs no inverse position searches')
  assert.equal(after.perf.metadata, before.perf.metadata, 'scrolling reuses unchanged revision/comment metadata')
  assert.equal(after.revision, before.revision, 'layout queries never change document revision')
  assert.equal(after.selection, before.selection, 'layout queries never steal the native selection')
  const durations = after.perf.durations.slice(before.perf.durations.length)
  assert.ok(durations.length >= 12)
  assert.ok(Math.max(...durations) < 250, 'review layout must finish within 250ms; measured ' + Math.max(...durations).toFixed(1))
  assert.ok(Math.max(...latencies) < 750, 'scroll interaction must avoid the previous multi-second stalls')
  console.log(JSON.stringify({ bootMs, loadMs, firstInteractionMs, layoutMaxMs: Math.max(...durations), scrollRoundTripMs: latencies, metadataReadsDuringScroll: after.perf.metadata - before.perf.metadata, inverseQueriesDuringScroll: after.perf.hits - before.perf.hits }))
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
