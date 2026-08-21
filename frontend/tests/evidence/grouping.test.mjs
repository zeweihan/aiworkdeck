import test from 'node:test'
import assert from 'node:assert/strict'
import { groupBySection, groupByParty, filterByStatus, collectFileTags, GROUP_NONE } from '../../src/utils/evidenceGrouping.js'

const L1 = { linkKey: 'EVID_1', sectionPath: '一/（一）', sectionTitle: '（一）主体资格', status: 'active', targets: [{ fileId: 10 }] }
const L2 = { linkKey: 'EVID_2', sectionPath: '一/（一）', sectionTitle: '（一）主体资格', status: 'stale', targets: [{ fileId: 11 }, { fileId: 12 }] }
const L3 = { linkKey: 'EVID_3', sectionPath: '', sectionTitle: '', status: 'orphan', targets: [] }
const links = [L1, L2, L3]

test('groupBySection: same sectionPath merges, empty goes to GROUP_NONE, order preserved', () => {
  const g = groupBySection(links)
  assert.equal(g.length, 2)
  assert.equal(g[0].key, '一/（一）')
  assert.equal(g[0].title, '（一）主体资格')
  assert.deepEqual(g[0].items.map((l) => l.linkKey), ['EVID_1', 'EVID_2'])
  assert.equal(g[1].key, GROUP_NONE)
  assert.deepEqual(g[1].items, [L3])
})

test('groupByParty: file with two PARTY tags lands link in two groups; null type = NORMAL; no party = none', () => {
  const tags = new Map([
    [10, [{ id: 1, name: '收购方', type: 'PARTY' }, { id: 2, name: '目标公司', type: 'PARTY' }, { id: 9, name: '合同', type: null }]],
    [11, [{ id: 2, name: '目标公司', type: 'PARTY' }]],
    [12, [{ id: 5, name: '争点A', type: 'ISSUE' }]],
  ])
  const g = groupByParty(links, tags)
  const byKey = Object.fromEntries(g.map((x) => [x.key, x]))
  assert.deepEqual(byKey['party:1'].items, [L1])
  assert.equal(byKey['party:1'].title, '收购方')
  assert.deepEqual(byKey['party:2'].items.map((l) => l.linkKey), ['EVID_1', 'EVID_2'])
  assert.deepEqual(byKey[GROUP_NONE].items, [L3])
  assert.equal(Object.keys(byKey).length, 3)
})

test('groupByParty: same link with two targets under one party is not duplicated', () => {
  const tags = new Map([[11, [{ id: 2, type: 'PARTY', name: 'X' }]], [12, [{ id: 2, type: 'PARTY', name: 'X' }]]])
  const g = groupByParty([L2], tags)
  assert.equal(g.length, 1)
  assert.equal(g[0].items.length, 1)
})

test('filterByStatus', () => {
  assert.equal(filterByStatus(links, 'all').length, 3)
  assert.equal(filterByStatus(links).length, 3)
  assert.deepEqual(filterByStatus(links, 'stale'), [L2])
  assert.deepEqual(filterByStatus(links, 'orphan'), [L3])
  assert.deepEqual(filterByStatus(links, 'unverified'), [])
})

test('collectFileTags walks tree and skips tagless nodes', () => {
  const tree = [
    { id: 1, tags: [], children: [{ id: 2, tags: [{ id: 7, type: 'PARTY' }] }, { id: 3 }] },
    { id: 4, tags: [{ id: 8 }] },
  ]
  const m = collectFileTags(tree)
  assert.deepEqual([...m.keys()].sort(), [2, 4])
})
