// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';
import { readFileSync } from 'node:fs';
import { loadWorkerFunctions } from './_workerFns.mjs';
const { paragraphNumberingOf } = loadWorkerFunctions(['paragraphNumberingOf']);
const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8');
const native = { ListLabelString: '2.', ListId: 'list-1', NumberingLevel: 0, NumberingIsNumber: true };
function paragraph(props = native, text = '2. 正文😀') {
  return { getString: () => text, getPropertyValue(key) {
    assert.notEqual(key, 'NumberingRules', 'reads must not enumerate numbering rules');
    if (!(key in props)) throw new Error('unsupported');
    return props[key];
  } };
}
test('native label and list identity are separate from body text, bullets are not numeric', () => {
  assert.deepEqual(paragraphNumberingOf(paragraph()), { available: true, label: '2.', listId: 'list-1', level: 0, hasLabel: true });
  assert.equal(paragraphNumberingOf(paragraph({ ...native, ListLabelString: '•' })).hasLabel, true);
  assert.deepEqual(paragraphNumberingOf(paragraph({ ListLabelString: '', ListId: '', NumberingLevel: 0, NumberingIsNumber: false })),
    { available: true, label: '', listId: '', level: 0, hasLabel: false });
  assert.deepEqual(paragraphNumberingOf(paragraph({ ListLabelString: '', ListId: '', NumberingLevel: 0, NumberingIsNumber: null })),
    { available: true, label: '', listId: '', level: 0, hasLabel: null });
});
test('missing or malformed properties are unknown, never a confirmed unnumbered paragraph', () => {
  for (const key of Object.keys(native)) {
    const props = { ...native }; delete props[key];
    assert.deepEqual(paragraphNumberingOf(paragraph(props)), { available: false, label: null, listId: null, level: null, hasLabel: null });
  }
  assert.equal(paragraphNumberingOf(paragraph({ ...native, NumberingLevel: '0' })).available, false);
  assert.equal(paragraphNumberingOf(null).available, false);
});
function methods(paragraphs) {
  const cursor = { getStart() {}, getEnd() {}, getString: () => '😀', isCollapsed: () => false };
  const realm = vm.createContext({ paragraphNumberingOf, paraAt: i => paragraphs[i],
    withParaIndex: f => f({ total: paragraphs.length, ranges: paragraphs }), currentReviewRevision: () => 7,
    completionGuard: () => null, ctrl: { getViewCursor: () => cursor }, rangeLocator: () => ({ paraKey: 0, start: 5 }),
    paragraphTextOf: () => paragraphs[0].getString(), EXEC: { get_cursor_rect: () => ({}) },
  });
  const slices = [['get_paragraph(p)', '  // [verified-extend] modify'], ['get_document_text(p)', '  // [感知] what'],
    ['get_review_context()', '  goto_review_range(p)']].map(([start, end]) => source.slice(source.indexOf('  ' + start + ' {'), source.indexOf(end, source.indexOf('  ' + start + ' {'))));
  return vm.runInContext('({' + slices.join('\n') + '})', realm);
}
test('all three read APIs return identical metadata without changing text, indexes, offsets or paging', () => {
  const worker = methods([paragraph(), paragraph(native, '下一段')]);
  const all = worker.get_document_text({ maxParagraphs: 1 });
  const single = worker.get_paragraph({ index: 0 });
  const review = worker.get_review_context();
  for (const result of [all.paragraphs[0], single, review]) {
    assert.equal(result.text, '2. 正文😀');
    assert.deepEqual(result.numbering, paragraphNumberingOf(paragraph()));
  }
  assert.equal(single.index, 0); assert.equal(review.paragraphIndex, 0); assert.equal(review.offset, 5);
  assert.equal(review.selectedText, '😀'); assert.equal(all.nextStartParagraph, 1); assert.equal(all.totalParagraphs, 2);
  assert.equal(worker.get_document_text({ startParagraph: 1 }).paragraphs[0].index, 1);
});
