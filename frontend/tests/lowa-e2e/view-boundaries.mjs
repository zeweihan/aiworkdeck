// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import assert from 'node:assert/strict';
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs';
const root = process.env.LOWA_FONT_DIR || 'dist/zetaoffice';
const extraFiles = { '/office_thread.js': 'src/zetaoffice/public/office_thread.js' };
for (const f of ['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf']) extraFiles['/' + f] = root + '/' + f;
preflight();
const server = await startServer({ extraFiles, patchServed(url, bytes) {
  if (url !== '/office_thread.js') return bytes;
  const source = bytes.toString();
  const begin = source.indexOf('  set_view_options(p) {');
  const end = source.indexOf('  set_track_changes(p)', begin);
  const command = source.slice(begin, end).replace('const out = { success: true };', `if (req.__testClean) xModel.setModified(false);
    const out = { success: true, modified: xModel.isModified() };
    const style = xModel.getStyleFamilies().getByName('PageStyles').getByName(ctrl.getViewCursor().getPropertyValue('PageStyleName'));
    out.margins = ['LeftMargin','RightMargin','TopMargin','BottomMargin','Width','Height'].map(k => style.getPropertyValue(k));`);
  return Buffer.from(source.slice(0, begin) + command + source.slice(end));
} });
const browser = await launchBrowser(await loadPuppeteer(), { headed: true });
try {
  const page = await openEditor(browser, { clipboard: false, viewport: { width: 1100, height: 900 } });
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p);
  await exec('set_chrome', { menubar: false, statusbar: false, toolbars: false, rulers: true });
  const original = await exec('set_view_options', { formattingMarks: true, ruler: true, __testClean: true });
  assert.equal(original.modified, false);
  console.log('original', JSON.stringify(original));
  assert.equal(original.textBoundaries, true, 'reproduce the native default that showed the rectangle');
  await new Promise(r => setTimeout(r, 700));
  await page.screenshot({ path: '/tmp/awd-boundaries-before.png' });
  for (const formattingMarks of [true, false, true, true]) {
    const r = await exec('set_view_options', { formattingMarks, ruler: true, textBoundaries: false });
    assert.equal(r.textBoundaries, false);
    assert.equal(r.formattingMarks, formattingMarks);
    assert.equal(r.ruler, true);
    assert.equal(r.verticalRuler, true);
    assert.deepEqual(r.margins, original.margins);
    assert.equal(r.modified, original.modified);
  }
  await new Promise(r => setTimeout(r, 700));
  await page.screenshot({ path: '/tmp/awd-boundaries-after.png' });
  await exec('insert_at_cursor', { text: '合成正文：关闭辅助边界不影响文档。' });
  const dirty = await exec('set_view_options');
  assert.equal(dirty.modified, true);
  assert.equal((await exec('set_view_options', { textBoundaries: false })).modified, true);
  const bytes = await page.evaluate(async () => Array.from((await window.__loExecutor.executeCommand('export_document', { name: 'view.docx' })).bytes));
  await exec('load_document', { bytes, name: 'view.docx' });
  const loaded = await exec('set_view_options');
  const applied = await exec('set_view_options', { formattingMarks: true, ruler: true, textBoundaries: false });
  assert.equal(applied.textBoundaries, false);
  assert.equal(applied.modified, loaded.modified);
  assert.deepEqual(applied.margins, original.margins);
  assert.deepEqual((await exec('get_document_text')).paragraphs.map(p => p.text), ['合成正文：关闭辅助边界不影响文档。']);
  console.log('PASS view-only boundaries, marks/rulers, dirty state, margins and reload');
} finally { await browser.close(); await new Promise(r => server.close(r)); }
