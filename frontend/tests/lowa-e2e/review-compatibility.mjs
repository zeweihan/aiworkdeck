// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Run against an older engine: no duplicate column and no inverse-search fallback.
import assert from 'node:assert/strict'
import { fixture } from './_revision-fixture.mjs'
import { startServer, launchBrowser, loadPuppeteer, preflight, openEditor } from './_boot.mjs'
preflight()
const server = await startServer(), browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser, { viewport: { width: 1360, height: 900 } })
  const exec = (action, params = {}) => page.evaluate((a,p) => window.__loExecutor.executeCommand(a,p), action, params)
  assert.equal((await exec('load_document', { name: 'review-compatibility.docx', bytes: await fixture(false) })).success, true)
  const layout = await exec('get_review_layout')
  assert.equal(layout.available, false, 'this test requires a legacy engine without native geometry')
  assert.deepEqual(layout.items, [])
  const state = await exec('get_ui_state')
  assert.equal(state.view.revisionBalloonsSupported, false)
  assert.equal((await exec('set_revision_view', { mode: 'balloons' })).success, false, 'cannot hide deletion text without a functioning balloon surface')
  assert.equal((await exec('set_revision_view')).mode, 'all')
  assert.equal((await exec('set_review_balloons', { enabled: true })).available, false)
  await new Promise(resolve => setTimeout(resolve, 250))
  assert.equal(await page.$eval('.awd-review-balloons', n => n.hidden), true)
  assert.equal(await page.$('.awd-rb-rail'), null)
  const width = await page.$eval('#qtcanvas', n => n.getBoundingClientRect().width)
  assert.ok(Math.abs(width - 1360) < 2)
  console.log('PASS older engine preserves readable revision display without reserving an empty column')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
