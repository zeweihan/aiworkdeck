// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Resolve what the user reviewed even if a native view notification or an
// unrelated edit advanced the global generation. Changed targets stay fenced.
import assert from 'node:assert/strict'
import { fixture, deleted } from './_revision-fixture.mjs'
import { startServer, launchBrowser, loadPuppeteer, preflight, ORIGIN } from './_boot.mjs'

preflight()
const server = await startServer()
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await browser.newPage()
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  const exec = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const ok = async (action, params) => {
    const result = await exec(action, params)
    assert.equal(result.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const load = async layered => {
    await ok('load_document', { name: 'revision-snapshot.docx', bytes: await fixture(layered) })
    return ok('list_revisions')
  }
  const expected = (snapshot, target) => ({
    indices: [target.index], action: 'reject', revision: snapshot.revision,
    documentSeq: snapshot.documentSeq, expectedRevisions: [target],
  })
  const identity = rows => rows.map(({ identifier, type, author, text }) => ({ identifier, type, author, text }))
  const body = async () => (await ok('get_document_text', { __agent: true })).paragraphs.map(p => p.text).join('\n')

  let snapshot = await load(false)
  let target = snapshot.revisions.find(r => r.text === deleted)
  assert.ok(target.identifier, 'snapshots include native redline identity')
  assert.equal(typeof snapshot.documentSeq, 'number')
  assert.equal((await exec('resolve_revisions', { indices: [target.index], action: 'reject', revision: snapshot.revision - 1 })).success, false, 'legacy index-only calls retain the strict generation fence')
  const stale = { ...expected(snapshot, target), revision: snapshot.revision - 1 }
  assert.equal((await ok('resolve_revisions', stale)).resolved, 1, 'unchanged targets are usable despite an outdated global generation')
  assert.equal(await body(), '前文' + deleted + '后文\n独立插入\n尾文')
  snapshot = await load(false)
  target = snapshot.revisions.find(r => r.text === deleted)
  const { indices, ...single } = expected(snapshot, target)
  await ok('resolve_revision', { ...single, index: indices[0], revision: snapshot.revision - 1 })
  assert.equal(await body(), '前文' + deleted + '后文\n独立插入\n尾文', 'the single-revision API uses the same snapshot guard')
  console.log('PASS stale global generation with an unchanged target, and strict legacy fencing')

  snapshot = await load(false)
  target = snapshot.revisions.find(r => r.text === '独立删除')
  const untouched = snapshot.revisions.find(r => r.text === '独立插入')
  await ok('resolve_revision', { index: 0, action: 'accept' })
  const shifted = (await ok('list_revisions')).revisions.find(r => r.identifier === target.identifier)
  assert.notEqual(shifted.index, target.index, 'an unrelated resolution moves the target index')
  const located = await ok('goto_revision', { index: target.index, identifier: target.identifier, documentSeq: snapshot.documentSeq, revision: snapshot.revision - 1 })
  assert.equal(located.index, shifted.index, 'navigation resolves native identity after index movement')
  assert.equal(located.selected, target.text)
  assert.equal((await ok('resolve_revisions', expected(snapshot, target))).resolved, 1)
  assert.deepEqual(identity((await ok('list_revisions')).revisions), identity([untouched]), 'only the ID-matched target is resolved after the index shift')
  assert.equal(await body(), '前文后文\n独立插入\n尾独立删除文')
  console.log('PASS stable identity resolves a shifted target without touching its neighbor')

  snapshot = await load(true)
  target = snapshot.revisions.find(r => r.text === deleted)
  await ok('resolve_revision', { index: target.index, action: 'reject' })
  const exposed = await ok('list_revisions')
  assert.equal(exposed.revisions.find(r => r.identifier === target.identifier).type, 'Insert', 'rejection reveals an older layer with the same native ID')
  assert.equal((await exec('resolve_revisions', expected(snapshot, target))).success, false, 'the same ID with a changed revision layer cannot be resolved using the old card')
  assert.deepEqual(identity((await ok('list_revisions')).revisions), identity(exposed.revisions))
  console.log('PASS changed revision layers stay fenced even when native IDs are unchanged')

  snapshot = await load(false)
  target = snapshot.revisions.find(r => r.text === deleted)
  const replacement = await load(false)
  assert.notEqual(replacement.documentSeq, snapshot.documentSeq)
  assert.equal((await exec('goto_revision', { index: target.index, identifier: target.identifier, documentSeq: snapshot.documentSeq })).success, false, 'navigation cannot use a previous document identity')
  assert.equal((await exec('resolve_revisions', expected(snapshot, target))).success, false, 'a replacement document cannot consume an old card')
  assert.deepEqual(identity((await ok('list_revisions')).revisions), identity(replacement.revisions))
  const mismatch = { ...expected(replacement, replacement.revisions[0]), indices: [0, 1] }
  assert.equal((await exec('resolve_revisions', mismatch)).success, false, 'every requested target needs its own reviewed snapshot')
  assert.deepEqual(identity((await ok('list_revisions')).revisions), identity(replacement.revisions))
  console.log('PASS document replacement and unmatched target lists remain protected')
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
