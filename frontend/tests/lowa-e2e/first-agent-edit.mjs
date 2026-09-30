// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1082: a clean document's first AI replacement must return final
// paragraph text, retain real revisions, and survive DOCX export/reload.
// Run: LOWA_E2E_PORT=8922 node tests/lowa-e2e/first-agent-edit.mjs
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor } from './_boot.mjs'

preflight()
// office_thread.js is a verbatim public asset. Serve the real source at its
// normal URL so this regression cannot accidentally test a stale dist copy.
const server = await startServer({ extraFiles: {
  '/office_thread.js': fileURLToPath(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url)),
} })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser)
  const ok = async (action, params = {}) => {
    const result = await page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
    assert.equal(result?.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const fixture = async text => {
    const zip = new JSZip()
    zip.file('[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>')
    zip.file('_rels/.rels', '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>')
    zip.file('word/document.xml', `<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body><w:p><w:r><w:t>${text}</w:t></w:r></w:p></w:body></w:document>`)
    return Array.from(await zip.generateAsync({ type: 'uint8array' }))
  }
  for (const [original, replacement] of [
    ['云杉项目法律意见书（草稿）', '云杉项目法律意见书'],
    ['股权转让价款为人民币100万元。', '股权转让价款为人民币126万元。'],
  ]) {
    await ok('load_document', { name: 'first-agent-edit.docx', bytes: await fixture(original) })
    assert.equal((await ok('set_revision_view', {})).mode, 'all')
    assert.equal((await ok('list_revisions', { limit: 100 })).count, 0, 'the first edit must start without any tracked changes')
    const found = await ok('find_text_locations', { keyword: original, __agent: true })
    assert.equal(found.count, 1)
    const edited = await ok('replace_at_position', { anchor: found.matches[0].anchorId, newText: replacement, __agent: true })
    assert.equal(edited.paragraphAfterEdit, replacement, 'the immediate first-write result must exclude deleted text')
    assert.equal((await ok('set_revision_view', {})).mode, 'all', 'restore the user display mode')
    const changes = await ok('list_revisions', { limit: 100 })
    assert.ok(changes.count > 0, 'retaining changes is required, accepting them is not a fix')
    assert.ok(changes.revisions.some(r => r.type === 'Delete'), 'the case must actually create a deletion')
    assert.equal((await ok('get_document_text', { __agent: true })).paragraphs[0].text, replacement)
    // Exercise the user's native UndoManager path without __agent: the AI
    // edit ran with hidden deletions, while the user is back in inline view.
    assert.equal((await ok('undo', { steps: 1 })).undone, 1)
    assert.equal((await ok('get_document_text', { __agent: true })).paragraphs[0].text, original,
      'one user undo must restore the original first-edit text')
    assert.equal((await ok('list_revisions', { limit: 100 })).count, 0,
      'undo removes the first edit revisions instead of retaining a display-only change')
    assert.equal((await ok('set_revision_view', {})).mode, 'all', 'undo retains the user display mode')
    assert.equal((await ok('redo', { steps: 1 })).redone, 1)
    assert.equal((await ok('get_document_text', { __agent: true })).paragraphs[0].text, replacement,
      'one user redo must restore the replacement')
    const redone = await ok('list_revisions', { limit: 100 })
    assert.equal(redone.count, changes.count, 'redo restores the tracked changes')
    assert.deepEqual(redone.revisions.map(r => ({ type: r.type, text: r.text })),
      changes.revisions.map(r => ({ type: r.type, text: r.text })),
      'redo restores the same insertion/deletion contents')
    assert.equal((await ok('set_revision_view', {})).mode, 'all', 'redo retains the user display mode')
    const bytes = await page.evaluate(async () => {
      const result = await window.__loExecutor.executeCommand('export_document', { name: 'first-agent-edit.docx' })
      if (!result?.success) throw Error(JSON.stringify(result))
      return Array.from(result.bytes)
    })
    const xml = await (await JSZip.loadAsync(Uint8Array.from(bytes))).file('word/document.xml').async('string')
    assert.match(xml, /<w:del[\s>]/, 'export preserves deletion markup')
    await ok('load_document', { name: 'reopened-first-agent-edit.docx', bytes })
    assert.equal((await ok('get_document_text', { __agent: true })).paragraphs[0].text, replacement, 'final text survives export/reload')
    assert.equal((await ok('list_revisions', { limit: 100 })).count, changes.count, 'tracked changes survive export/reload')
    assert.equal((await ok('set_revision_view', {})).mode, 'all')
    console.log('PASS first edit + final-text receipt + user undo/redo + revisions + export/reload: ' + original)
  }
} finally {
  await browser.close()
  server.close()
}
