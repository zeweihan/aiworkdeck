// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Synthetic text only. No system clipboard or user files.
import assert from 'node:assert/strict';
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs';
const fontRoot = process.env.LOWA_FONT_DIR || 'dist/zetaoffice';
const extraFiles = { '/office_thread.js': 'src/zetaoffice/public/office_thread.js' };
for (const f of ['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf']) extraFiles['/' + f] = fontRoot + '/' + f;
preflight();
const server = await startServer({ extraFiles, patchServed(url, bytes) {
  if (url !== '/office_thread.js') return bytes;
  // Observe model state without introducing a production command.
  return Buffer.from(bytes.toString().replace('  get_document_text(p) {', `  get_document_text(p) {
    if (p.__testState) return { modified: xModel.isModified(), undo: xModel.getUndoManager().getAllUndoActionTitles().length };`));
} });
const browser = await launchBrowser(await loadPuppeteer());
try {
  const page = await openEditor(browser, { clipboard: false });
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p);
  const lines = ['1. 合成正文😀', '2. 合成正文', '3. 合成正文', '4. 合成正文'];
  await exec('insert_at_cursor', { text: lines.join('\n') });
  const plain = await exec('get_document_text');
  assert.deepEqual(plain.paragraphs.map(p => p.text), lines);
  assert.deepEqual(plain.paragraphs[0].numbering, { available: true, label: '', listId: '', level: 0, hasLabel: false });
  assert.equal((await exec('ui_command', { name: 'select_all' })).success, true);
  assert.equal((await exec('set_numbering', { preset: 'decimal' })).success, true);
  const numbered = await exec('get_document_text');
  assert.deepEqual(numbered.paragraphs.map(p => p.numbering.label), ['1.', '2.', '3.', '4.']);
  assert.equal(new Set(numbered.paragraphs.map(p => p.numbering.listId)).size, 1);
  assert.ok(numbered.paragraphs[0].numbering.listId);
  assert.deepEqual(numbered.paragraphs.map(p => p.text), lines);
  await exec('select_paragraph', { index: 1 });
  await exec('collapse_selection', { to: 'start' });
  const state = await exec('get_document_text', { __testState: true });
  const before = await exec('get_review_context');
  const start = Date.now();
  for (let i = 0; i < 10; i++) {
    const p = await exec('get_paragraph', { index: 1 });
    const page = await exec('get_document_text', { startParagraph: 1, maxParagraphs: 1 });
    const review = await exec('get_review_context');
    assert.deepEqual(p.numbering, numbered.paragraphs[1].numbering);
    assert.deepEqual(page.paragraphs[0].numbering, p.numbering);
    assert.deepEqual(review.numbering, p.numbering);
    assert.equal(review.text, lines[1]); assert.equal(review.offset, before.offset);
    assert.equal(review.paragraphIndex, 1);
  }
  assert.deepEqual(await exec('get_document_text', { __testState: true }), state);
  console.log('30 cached reads ms:', Date.now() - start);
  assert.equal((await exec('set_numbering', { preset: 'bullet' })).success, true);
  const bullet = await exec('get_paragraph', { index: 1 });
  assert.equal(bullet.numbering.available, true); assert.equal(bullet.numbering.hasLabel, true);
  assert.ok(bullet.numbering.listId); // r5 returns an empty ListLabelString for bullets.
  const exported = await exec('export_document', { name: 'numbering.docx' });
  await exec('load_document', { bytes: Array.from(Object.values(exported.bytes)), name: 'numbering.docx' });
  const reloaded = await exec('get_document_text');
  assert.deepEqual(reloaded.paragraphs.map(p => p.text), lines);
  assert.equal(reloaded.paragraphs[0].numbering.label, '1.');
  assert.equal(reloaded.paragraphs[1].numbering.hasLabel, true);
  console.log('PASS numbering metadata, repeated raw prefix, bullets, three APIs, unchanged cursor/state, DOCX reload');
} finally { await browser.close(); await new Promise(r => server.close(r)); }
