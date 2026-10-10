// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Actual keyboard -> clipboard -> IME overlay -> worker, using synthetic text.
// PASTE_WORKER_SOURCE can replay the pre-fix worker. Default headless Chrome
// uses its virtual clipboard; the separate desktop smoke covers Electron.
import assert from 'node:assert/strict';
import { preflight, startServer, launchBrowser, loadPuppeteer, openEditor } from './_boot.mjs';
const root = process.env.LOWA_FONT_DIR || 'dist/zetaoffice';
const extraFiles = { '/office_thread.js': process.env.PASTE_WORKER_SOURCE || 'src/zetaoffice/public/office_thread.js' };
for (const f of ['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf']) extraFiles['/' + f] = root + '/' + f;
preflight();
const server = await startServer({ extraFiles });
const browser = await launchBrowser(await loadPuppeteer());
try {
  const page = await openEditor(browser);
  page.on('pageerror', e => console.error(e.message));
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p);
  const read = async () => {
    const paragraphs = [];
    let r;
    do {
      r = await exec('get_document_text', { startParagraph: paragraphs.length });
      assert.equal(r.success, true);
      paragraphs.push(...r.paragraphs.map(p => p.text));
    } while (r.truncated);
    return paragraphs;
  };
  const save = () => page.evaluate(async () => {
    const r = await window.__loExecutor.executeCommand('export_document', { name: 'paste.docx' });
    if (!r.success || !r.bytes?.length) throw new Error('export failed');
    return Array.from(r.bytes);
  });
  await exec('set_chrome', { menubar: false, statusbar: false, toolbars: false, rulers: true });
  const blank = await save();
  await page.evaluate(() => {
    const e = window.__loExecutor, run = e.executeCommand.bind(e);
    e.executeCommand = async (a, p, ...rest) => {
      const start = performance.now();
      const r = await run(a, p, ...rest);
      if (a === 'replace_selection') window.__paste = { ms: performance.now() - start, success: r.success };
      return r;
    };
  });
  const paste = async (text, rich = false) => {
    await page.evaluate(async (text, rich) => {
      window.__paste = null;
      if (rich) await navigator.clipboard.write([new ClipboardItem({
        'text/plain': new Blob([text], { type: 'text/plain' }),
        'text/html': new Blob(['<p><b>' + text.replaceAll('\n', '</b></p><p><b>') + '</b></p>'], { type: 'text/html' }),
      })]);
      else await navigator.clipboard.writeText(text);
    }, text, rich);
    await page.focus('input[data-lo-ime]');
    const modifier = await page.evaluate(() => /Mac/i.test(navigator.platform) ? 'Meta' : 'Control');
    await page.keyboard.down(modifier); await page.keyboard.press('v'); await page.keyboard.up(modifier);
    await page.waitForFunction('!!window.__paste', { timeout: 120000 });
    const receipt = await page.evaluate(() => window.__paste);
    console.log('paste', JSON.stringify({ chars: text.length, rich, ...receipt }));
    assert.equal(receipt.success, true);
    assert.ok(receipt.ms < 10000, `paste blocked for ${receipt.ms}ms`);
  };
  const multi = Array.from({ length: 180 }, (_, i) => `第${i + 1}段：` + '本段是合成粘贴测试内容，用于核对正文完整性和保存后的段落顺序。'.repeat(3));
  for (const [lines, rich] of [[multi, false], [multi, true], [['长单段开头' + '合成正文用于检查长段落输入性能。'.repeat(1100) + '长单段结尾'], false]]) {
    assert.equal((await exec('load_document', { name: 'blank.docx', bytes: blank })).success, true);
    await paste(lines.join('\n'), rich);
    assert.deepEqual(await read(), lines, 'all paragraphs, including the tail, survive paste');
    // A subsequent real keystroke must remain usable (no leaked action/controller lock).
    await page.keyboard.type('Z');
    await page.waitForFunction(() => !document.querySelector('input[data-lo-ime]').value);
    const expected = [...lines]; expected[expected.length - 1] += 'Z';
    assert.deepEqual(await read(), expected);
    const bytes = await save();
    assert.equal((await exec('load_document', { name: 'saved.docx', bytes })).success, true);
    assert.deepEqual(await read(), expected, 'export/reopen retains exact text and paragraphs');
    assert.ok((await exec('list_revisions', { locate: false })).revisions.length > 0, 'paste retains tracked changes');
  }
  console.log('PASS plain/rich clipboard, long paragraph, continued typing and DOCX round trips');
} finally { await browser.close(); await new Promise(r => server.close(r)); }
