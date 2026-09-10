// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { groupRevisions } from '../../src/utils/reviewGrouping.js'

const cell = (index, operationId, tableName = 'Table1') => ({ index, type: 'Insert', author: 'AI WorkDeck', date: '2026-09-10 10:00', text: 'cell ' + index, inTable: true, tableName, operationId, operationKind: 'table-insert', contiguous: false })
test('one table insertion stays one group across cell stories and clock minute boundaries', () => {
  const rows = [cell(0, 'insert-1'), { ...cell(1, 'insert-1'), date: '2026-09-10 10:01' }, cell(2, 'insert-1')]
  const groups = groupRevisions(rows)
  assert.equal(groups.length, 1)
  assert.deepEqual(groups[0].items.map(r => r.index), [0, 1, 2])
  assert.match(groups[0].text, /cell 0\ncell 1\ncell 2/)
})
test('separate table operations, authors, and untagged old cell changes never merge by minute', () => {
  assert.equal(groupRevisions([cell(0, 'a'), cell(1, 'b')]).length, 2)
  assert.equal(groupRevisions([cell(0, 'a'), {...cell(1, 'a'), author: 'Other'}]).length, 2)
  assert.equal(groupRevisions([cell(0, ''), cell(1, '')]).length, 2)
  assert.equal(groupRevisions([cell(0, 'a'), cell(1, 'a', 'Table2')]).length, 2)
})
test('full deleted text remains available in a review group', () => {
  const text = '被删除的完整条款。'.repeat(100)
  assert.equal(groupRevisions([{index:0,type:'Delete',text}])[0].text, text)
})

import { positionReviewCards } from '../../src/composables/zetaOfficeReviewBalloons.js'
test('nearby comments avoid collisions and retain document positions while scrolling', () => {
  const result = positionReviewCards([{ key:'a',x:10,y:100,height:80 },{key:'b',x:20,y:110,height:120},{key:'c',x:0,y:600,height:30}])
  assert.deepEqual(result.map(r=>r.top), [100,192,600])
  const screenBefore = result.map(r => r.top - 50)
  const screenAfter = result.map(r => r.top - 250)
  assert.deepEqual(screenAfter, screenBefore.map(y=>y-200))
})
