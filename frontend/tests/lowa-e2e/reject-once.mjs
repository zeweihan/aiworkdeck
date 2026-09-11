// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// A deletion may cover another author's insertion. Rejecting the deletion
// restores its text while exposing the older insertion, without reducing the
// redline count. One real button click must succeed and preserve that insertion.
import assert from 'node:assert/strict'
import { fixture, deleted, adjacentDeletions, adjacent } from './_revision-fixture.mjs'
import { startServer, launchBrowser, loadPuppeteer, preflight, ORIGIN } from './_boot.mjs'


preflight()
const server = await startServer({ extraFiles: Object.fromEntries(['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf'].map(f => ['/' + f, '/Applications/AI WorkDeck.app/Contents/Resources/frontend/dist/zetaoffice/' + f])) })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1360, height: 900 })
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  await page.addStyleTag({ content: '#verify,#vlog{display:none!important}' })
  const exec = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const ok = async (action, params) => {
    const result = await exec(action, params)
    assert.equal(result.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const revisions = async () => (await ok('list_revisions')).revisions
  const body = async () => (await ok('get_document_text', { __agent: true })).paragraphs.map(p => p.text).join('\n')
  const identity = rows => rows.map(({ type, author, text }) => ({ type, author, text }))

  for (const layered of [false, true]) {
    await ok('load_document', { name: 'reject-once.docx', bytes: await fixture(layered) })
    await ok('set_chrome', { all: false })
    await ok('set_revision_view', { mode: 'balloons' })
    const snapshot = await ok('list_revisions')
    const before = snapshot.revisions
    const unrelated = identity(before.filter(r => r.text !== deleted))
    assert.equal(await body(), '前文后文\n独立插入\n尾文')
    await page.waitForFunction(text => [...document.querySelectorAll('.awd-rb-content')].some(n => n.textContent === text), { timeout: 30000 }, deleted)
    await page.evaluate(() => {
      window.rejectCalls = []
      const executor = window.__loExecutor
      window.originalReviewExecute = executor.executeCommand.bind(executor)
      executor.executeCommand = async (action, params) => {
        const result = await window.originalReviewExecute(action, params)
        if (action === 'resolve_revisions') window.rejectCalls.push({ params, result })
        return result
      }
    })
    // Use an actual pointer click on the production control, without first
    // locating/selecting the revision or making a second resolution call.
    let button
    for (const candidate of await page.$$('.awd-rb-card')) {
      if (!(await candidate.$eval('.awd-rb-content', (n, text) => n.textContent === text, deleted))) continue
      for (const candidateButton of await candidate.$$('button')) {
        if (await candidateButton.evaluate(n => n.textContent === '拒绝')) button = candidateButton
      }
    }
    assert.ok(button, 'the target deletion has a visible reject control')
    await button.click()
    await page.waitForFunction(() => window.rejectCalls.length > 0, { timeout: 30000 })
    const calls = await page.evaluate(() => window.rejectCalls)
    assert.equal(calls.length, 1, 'one pointer click sends one resolution command')
    assert.equal(calls[0].params.documentSeq, snapshot.documentSeq, 'the button retains its document snapshot')
    assert.deepEqual(calls[0].params.expectedRevisions.map(r => r.identifier), [before.find(r => r.text === deleted).identifier], 'the button identifies the actual reviewed revision')
    assert.equal(calls[0].result.resolved, 1, 'rejecting the top revision succeeds even if an older revision remains: ' + JSON.stringify(calls))
    assert.equal(calls[0].result.results[0].success, true)
    assert.equal(await body(), '前文' + deleted + '后文\n独立插入\n尾文', 'the first click fully restores the deleted content')
    const after = await revisions()
    assert.deepEqual(identity(after.filter(r => r.text !== deleted)), unrelated, 'unrelated revisions remain unchanged')
    assert.deepEqual(identity(after.filter(r => r.text === deleted)), layered ? [{ type: 'Insert', author: '原插入者', text: deleted }] : [], 'the earlier insertion remains pending')
    assert.equal((await ok('set_revision_view')).mode, 'balloons')
    await ok('undo')
    assert.deepEqual(identity(await revisions()), identity(before), 'one undo restores the original revision layers')
    assert.equal(await body(), '前文后文\n独立插入\n尾文')
    await page.evaluate(() => { window.__loExecutor.executeCommand = window.originalReviewExecute })

    const target = (await revisions()).find(r => r.text === deleted)
    await ok('resolve_revision', { index: target.index, action: 'reject' })
    assert.equal(await body(), '前文' + deleted + '后文\n独立插入\n尾文', 'single-revision API shares the one-layer success semantics')
    await ok('undo')
    assert.deepEqual(identity(await revisions()), identity(before))
    console.log('PASS one-click rejection, unrelated revisions and undo: ' + (layered ? 'deletion over insertion' : 'ordinary deletion'))
  }

  // Balloons mode on an engine with AwdReviewGeometry: two touching deletions by
  // different authors are hidden at one body position. The card's revision is
  // resolved by its native id, never the neighbour found at the cursor.
  await ok('load_document', { name: 'adjacent-deletions.docx', bytes: await adjacentDeletions() })
  await ok('set_chrome', { all: false })
  await ok('set_revision_view', { mode: 'balloons' })   // fails on engines without the native review contract
  const pair = (await ok('list_revisions')).revisions
  assert.deepEqual(identity(pair), [{ type: 'Delete', author: '甲审阅人', text: adjacent[0] }, { type: 'Delete', author: '乙审阅人', text: adjacent[1] }])
  assert.ok(typeof pair[0].start === 'number' && pair[0].start === pair[1].start && pair[0].paraKey === pair[1].paraKey,
    'precondition: both hidden deletions sit at one body position: ' + JSON.stringify(pair))
  assert.equal(await body(), '前后')
  await page.waitForFunction(text => [...document.querySelectorAll('.awd-rb-content')].some(n => n.textContent === text), { timeout: 30000 }, adjacent[1])
  await page.evaluate(() => {
    window.rejectCalls = []
    const executor = window.__loExecutor
    window.originalReviewExecute = executor.executeCommand.bind(executor)
    executor.executeCommand = async (action, params) => {
      const result = await window.originalReviewExecute(action, params)
      if (action === 'resolve_revisions') window.rejectCalls.push({ params, result })
      return result
    }
  })
  let secondReject
  for (const card of await page.$$('.awd-rb-card')) {
    if (!(await card.$eval('.awd-rb-content', (n, text) => n.textContent === text, adjacent[1]))) continue
    for (const candidate of await card.$$('button')) if (await candidate.evaluate(n => n.textContent === '拒绝')) secondReject = candidate
  }
  assert.ok(secondReject, 'the second deletion has its own card with a reject control')
  await secondReject.click()
  await page.waitForFunction(() => window.rejectCalls.length > 0, { timeout: 30000 })
  const pairCalls = await page.evaluate(() => window.rejectCalls)
  await page.evaluate(() => { window.__loExecutor.executeCommand = window.originalReviewExecute })
  assert.equal(pairCalls.length, 1)
  assert.deepEqual(pairCalls[0].params.expectedRevisions.map(r => r.identifier), [pair[1].identifier])
  assert.equal(pairCalls[0].result.via, 'native-id', JSON.stringify(pairCalls[0].result))
  assert.deepEqual(pairCalls[0].result.results.map(r => r.success), [true])
  assert.equal(await body(), '前' + adjacent[1] + '后', 'exactly the second text is restored')
  assert.deepEqual(identity(await revisions()), [{ type: 'Delete', author: '甲审阅人', text: adjacent[0] }], 'the first deletion is intact')
  await ok('undo')
  assert.deepEqual(identity(await revisions()), identity(pair))

  // Accept is the destructive direction: it must drop only the second text.
  const snap = await ok('list_revisions')
  const accepted = await ok('resolve_revision', { index: 1, action: 'accept', revision: snap.revision, documentSeq: snap.documentSeq, expectedRevisions: [snap.revisions[1]] })
  assert.equal(accepted.via, 'native-id')
  assert.equal(await body(), '前后')
  const left = await revisions()
  assert.deepEqual(identity(left), [{ type: 'Delete', author: '甲审阅人', text: adjacent[0] }], 'accepting the second card keeps the first deletion pending')
  await ok('resolve_revision', { index: left[0].index, action: 'reject' })
  assert.equal(await body(), '前' + adjacent[0] + '后', 'the first deleted text was never removed')
  console.log('PASS balloons: adjacent ungrouped hidden deletions resolve by native id (reject and accept)')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
