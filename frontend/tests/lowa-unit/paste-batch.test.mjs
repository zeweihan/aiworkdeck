// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import fs from 'node:fs';
const source = fs.readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8');
const method = source.slice(source.indexOf('  replace_selection(p) {'), source.indexOf('  // [verified] model-native search'));
for (const selected of [false, true]) for (const fail of [false, true]) {
  test(`paste batches notifications/layout and releases locks: selection=${selected}, failure=${fail}`, () => {
    let locked = false, notifications = 0, writes = 0, text = selected ? '旧文' : '';
    const write = () => { assert.equal(locked, true); writes++; if (fail) throw Error('write failed'); };
    const cursor = { getString: () => text, setString: () => { write(); text = ''; }, collapseToEnd() {} };
    const run = vm.runInNewContext(`({${method}}).replace_selection`, {
      ctrl: { getViewCursor: () => cursor }, xModel: { getPropertyValue: () => true },
      lockModel() { assert.equal(locked, false); locked = true; },
      unlockModel() { assert.equal(locked, true); locked = false; notifications++; },
      applyMinimalRedline() { write(); return true; },
      insertTextAtCursor() { for (let i = 0; i < 180; i++) write(); },
      verifySnapshot: () => ({}),
    });
    if (fail) assert.throws(() => run({ text: '新文' }), /write failed/);
    else assert.equal(run({ text: '新文' }).success, true);
    assert.ok(writes > 0);
    assert.equal(locked, false);
    assert.equal(notifications, 1);
  });
}
