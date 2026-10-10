// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1177: real executor + cursor-targeted resolution in inline/balloon
// views. Each step must preserve the other revision across DOCX export/reload.
// Run after build:zetaoffice with LOWA_ENGINE_DIR and a unique LOWA_E2E_PORT.
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import path from 'node:path'
import fs from 'node:fs'
import JSZip from 'jszip'
import { preflight, loadPuppeteer, startServer, launchBrowser, openEditor, engineDir } from './_boot.mjs'

preflight()
const extraFiles = {
  '/office_thread.js': fileURLToPath(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url)),
}
// External packaged runtimes keep their CJK fonts next to lowa/.
for (const font of ['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf']) {
  const file = path.join(engineDir, '..', font)
  if (fs.existsSync(file)) extraFiles['/' + font] = file
}
const server = await startServer({ extraFiles })
const browser = await launchBrowser(await loadPuppeteer())
try {
  const page = await openEditor(browser, { clipboard: false })
  const command = (action, params = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), action, params)
  const ok = async (action, params) => {
    const result = await command(action, params)
    assert.equal(result?.success, true, action + ': ' + JSON.stringify(result))
    return result
  }
  const revisions = () => ok('list_revisions', { limit: 100 })
  const text = async () => (await ok('get_document_text', { __agent: true })).paragraphs.map(p => p.text).join('\n')
  const exportBytes = () => page.evaluate(async () => {
    const result = await window.__loExecutor.executeCommand('export_document', { name: 'cursor.docx' })
    if (!result?.success) throw Error(JSON.stringify(result))
    return Array.from(result.bytes)
  })
  await ok('set_track_changes', { on: false })
  await ok('ui_command', { name: 'select_all' })
  await ok('replace_selection', { text: '甲方应当付款。\n乙方应当交付。' })
  assert.equal((await revisions()).count, 0)
  const baseline = await exportBytes()

  for (const mode of ['all', 'balloons']) for (const kind of ['insert', 'delete']) for (const action of ['accept', 'reject']) {
    const label = `${mode}/${kind}/${action}`
    await ok('load_document', { name: 'cursor.docx', bytes: baseline })
    if (kind === 'insert') {
      await ok('find_replace', { findText: '付款', replaceText: '按期付款' })
      await ok('find_replace', { findText: '交付', replaceText: '如约交付' })
    } else await ok('find_replace', { findText: '应当', replaceText: '', replaceAll: true })
    assert.equal((await ok('set_revision_view', { mode })).mode, mode)
    const before = await revisions()
    assert.equal(before.count, 2, label)
    const first = before.revisions[0], other = before.revisions[1]
    assert.equal(first.type, kind === 'insert' ? 'Insert' : 'Delete')
    await ok('goto_revision', { index: first.index })
    await ok('collapse_selection', { to: 'start' })
    const result = await ok('resolve_revision_at_cursor', { action })
    assert.equal(result.remaining, 1, label)
    assert.equal((await ok('set_revision_view')).mode, mode, 'retain display mode')
    const remaining = await revisions()
    assert.deepEqual(remaining.revisions.map(r => r.identifier), [other.identifier], label + ': preserve unrelated revision identity')
    const firstText = kind === 'insert'
      ? (action === 'accept' ? '甲方应当按期付款。' : '甲方应当付款。')
      : (action === 'accept' ? '甲方付款。' : '甲方应当付款。')
    const otherPendingText = kind === 'insert' ? '乙方应当如约交付。' : '乙方交付。'
    assert.equal(await text(), firstText + '\n' + otherPendingText, label)
    const bytes = await exportBytes()
    const xml = await (await JSZip.loadAsync(Uint8Array.from(bytes))).file('word/document.xml').async('string')
    assert.equal((xml.match(kind === 'insert' ? /<w:ins[\s>]/g : /<w:del[\s>]/g) || []).length, 1, label + ': DOCX retains exactly the other revision')
    await ok('load_document', { name: 'reopened-cursor.docx', bytes })
    assert.equal(await text(), firstText + '\n' + otherPendingText, label + ': reopened text')
    const reopened = await revisions()
    assert.equal(reopened.count, 1, label + ': reopened pending revision')
    assert.equal(reopened.revisions[0].type, other.type)
    assert.equal(reopened.revisions[0].text, other.text)
    await ok('set_revision_view', { mode })
    // A cursor outside the remaining revision must fail, never resolve all.
    await ok('select_paragraph', { index: 0 })
    await ok('collapse_selection', { to: 'start' })
    const miss = await command('resolve_revision_at_cursor', { action })
    assert.equal(miss.success, false, label + ': no-hit must fail')
    assert.doesNotMatch(miss.message || '', /Unknown action/)
    assert.equal((await revisions()).count, 1)
    await ok('goto_revision', { index: 0 })
    await ok('resolve_revision_at_cursor', { action: 'reject' })
    assert.equal((await revisions()).count, 0)
    const finalText = firstText + '\n乙方应当交付。'
    assert.equal(await text(), finalText)
    await ok('load_document', { name: 'resolved-cursor.docx', bytes: await exportBytes() })
    assert.equal(await text(), finalText, label + ': final DOCX roundtrip')
    assert.equal((await revisions()).count, 0)
    console.log('PASS ' + label + ': selected only, unrelated revision retained, no-hit rejected, DOCX roundtrip')
  }
} finally {
  await browser.close()
  server.close()
}
