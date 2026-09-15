// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Revision resolution on engines with AwdReviewGeometry targets Writer's native
// redline id. The worker's real resolve_revision / resolve_revisions and helpers
// run in a vm realm against a mock redline table whose dispatch behaviour each
// test chooses (a correct by-id engine, or one that resolves by cursor).
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
function declaration(name) {
  const start = source.indexOf('\nfunction ' + name + '(')
  assert.ok(start >= 0, 'worker declares ' + name)
  return source.slice(start + 1, source.indexOf('\n}', start) + 2)
}
const HELPERS = ['mkProp', 'uint32Any', 'redlineAt', 'countRedlines', 'selectRedlineRange', 'redlineLayerState', 'redlineWasResolved',
  'redlineTop', 'redlineSnapshot', 'sameRedlineFamily', 'unexplainedRedlineChange', 'nativeRedlineTargets',
  'expectedRedlineIdentifiers', 'resolveRedlinesByNativeId']
const begin = source.indexOf('  resolve_revision(p) {'), end = source.indexOf('\n  resolve_all_revisions(p) {')

const at = (minute, second = 0) => ({ Year: 2026, Month: 9, Day: 10, Hours: 8, Minutes: minute, Seconds: second, NanoSeconds: 0, IsUTC: false })
let serial = 0
function redline(type, author, { date = at(0), pos = 5, end = pos, below = null } = {}) {
  serial += 1
  return { id: 1000 + serial, identifier: 'ptr' + serial, type, author, date, comment: '', pos, end, below }
}
const successorOf = r => r.below && [{ Name: 'RedlineAuthor', Value: r.below.author }, { Name: 'RedlineDateTime', Value: r.below.date },
  { Name: 'RedlineComment', Value: '' }, { Name: 'RedlineType', Value: r.below.type }]

function world(redlines, { geometry = true, engine = 'id' } = {}) {
  const table = redlines.slice(), dispatches = [], cursor = []
  const wrap = r => ({ getPropertyValue(name) {
    return { RedlineType: r.type, RedlineAuthor: r.author, RedlineDateTime: r.date, RedlineComment: r.comment,
      RedlineSuccessorData: successorOf(r) || undefined, RedlineIdentifier: r.identifier,
      RedlineStart: { pos: r.pos }, RedlineEnd: { pos: r.end } }[name]
  } })
  function Any(type, val) { this.type = type; this.val = val }
  const realm = vm.createContext({
    context: {}, zetajs: { Any, type: { unsigned_long: 'unsigned long', short: 'short' }, fromAny: v => v instanceof Any ? v.val : v },
    xModel: { getRedlines: () => ({ createEnumeration() { const items = table.map(wrap); let i = 0
      return { hasMoreElements: () => i < items.length, nextElement: () => items[i++] } } }) },
    ctrl: {
      getFrame: () => 'frame',
      getViewCursor: () => ({ gotoRange(range, extend) { cursor.push([range.pos, extend]) } }),
      getPropertyValue(name) {
        assert.equal(name, 'AwdReviewGeometry')
        return JSON.stringify({ version: 1, unit: 'twip', revisions: table.map((r, index) => ({ index, id: r.id })) })
      },
    },
    css: {
      beans: { PropertyValue: function (values) { Object.assign(this, values) } },
      frame: { DispatchHelper: { create: () => ({ executeDispatch(frame, url, target, flags, args) {
        dispatches.push({ url, args: Array.from(args) })
        const reject = url === '.uno:RejectTrackedChange'
        const hit = engine === 'id'
          ? table.find(r => r.id === args[0]?.Value?.val)
          : table.find(r => r.pos === cursor.at(-1)?.[0])   // the first redline at the collapsed cursor
        if (!hit) return
        if (typeof engine === 'function') return engine(table, hit, reject)
        if (reject && hit.type === 'Delete' && hit.below) { Object.assign(hit, hit.below, { below: null }); return }
        table.splice(table.indexOf(hit), 1)
      } }) } },
    },
    isWriterDoc: () => true, supportsReviewGeometry: () => geometry,
    readShowChanges: () => true, readShowChangesInMargin: () => true,   // balloons: deletions hidden
    tableFail: message => ({ success: false, error: message, message }), errStr: e => String(e && e.message || e),
  })
  vm.runInContext(HELPERS.map(declaration).join('\n') + '\nconst actions = ({' + source.slice(begin, end) + '});', realm)
  const run = (action, params) => JSON.parse(JSON.stringify(vm.runInContext('actions.' + action + '(' + JSON.stringify(params) + ')', realm)))
  return { table, dispatches, cursor, run }
}

test('balloons: an ungrouped neighbour at the same body position is not resolved in place of the target', () => {
  const first = redline('Delete', '甲'), second = redline('Delete', '乙')   // both hidden, both collapsed at 5
  const w = world([first, second])
  const result = w.run('resolve_revisions', { indices: [1], action: 'reject', expectedRevisions: [{ identifier: second.identifier }] })
  assert.equal(result.success, true); assert.equal(result.via, 'native-id')
  assert.deepEqual(result.results, [{ index: 1, success: true }])
  assert.equal(w.dispatches.length, 1)
  assert.equal(w.dispatches[0].url, '.uno:RejectTrackedChange')
  assert.deepEqual(w.dispatches[0].args.map(a => [a.Name, a.Value.type, a.Value.val]), [['RejectTrackedChange', 'unsigned long', second.id]])
  // The cursor only enables the slot (collapsed, never extended); the id picks the redline.
  assert.deepEqual(w.cursor, [[5, false]])
  assert.deepEqual(w.table.map(r => r.identifier), [first.identifier])
})

test('accept by id removes exactly the target deletion through resolve_revision', () => {
  const first = redline('Delete', '甲'), second = redline('Delete', '乙')
  const w = world([first, second])
  const result = w.run('resolve_revision', { index: 1, action: 'accept' })
  assert.deepEqual(result, { success: true, index: 1, action: 'accept', remaining: 1, via: 'native-id' })
  assert.deepEqual(w.dispatches[0].args.map(a => [a.Name, a.Value.val]), [['AcceptTrackedChange', second.id]])
  assert.deepEqual(w.table, [first])
})

test('a dispatch that leaves the target and removes another redline is reported as failure', () => {
  const first = redline('Delete', '甲'), second = redline('Delete', '乙')
  const w = world([first, second], { engine: 'cursor' })
  const batch = w.run('resolve_revisions', { indices: [1], action: 'reject' })
  assert.deepEqual(batch.results, [{ index: 1, success: false }]); assert.equal(batch.resolved, 0)
  const again = world([redline('Delete', '甲'), redline('Delete', '乙')], { engine: 'cursor' })
  const single = again.run('resolve_revision', { index: 1, action: 'accept' })
  assert.equal(single.success, false); assert.equal(single.remaining, 1)
  assert.equal(again.dispatches.length, 1, 'never retried')
})

test('the target resolved together with an unrelated redline is still a failure', () => {
  const target = redline('Delete', '乙', { pos: 5 }), unrelated = redline('Insert', '丙', { pos: 40, end: 44 })
  const w = world([target, unrelated], { engine: table => { table.length = 0 } })
  assert.deepEqual(w.run('resolve_revisions', { indices: [0], action: 'reject' }).results, [{ index: 0, success: false }])
})

test('one reject of a deletion over an older insertion succeeds when only its layer changes', () => {
  const stacked = redline('Delete', '删除者', { below: { type: 'Insert', author: '原插入者', date: at(0) }, date: at(5) })
  const other = redline('Insert', '其他审阅人', { pos: 20, end: 24 })
  const w = world([stacked, other])
  const result = w.run('resolve_revision', { index: 0, action: 'reject', expectedRevisions: [{ identifier: stacked.identifier }] })
  assert.equal(result.success, true, JSON.stringify(result))
  assert.equal(result.remaining, 2, 'count and identifier unchanged')
  assert.equal(w.dispatches.length, 1)
  assert.deepEqual([w.table[0].identifier, w.table[0].type, w.table[0].author], [stacked.identifier, 'Insert', '原插入者'])
})

for (const action of ['reject', 'accept']) {
  test(`${action} of a deletion inside an insertion tolerates Writer merging the split insertion`, () => {
    const ai = { type: 'Insert', author: 'AI WorkDeck', date: at(0) }
    const head = redline('Insert', ai.author, { pos: 0, end: 2 })
    const middle = redline('Delete', '用户', { pos: 2, end: 4, below: ai, date: at(3) })
    const tail = redline('Insert', ai.author, { pos: 4, end: 6 })
    // CompressRedlines(): the now-adjacent identical insertions merge into the first.
    const w = world([head, middle, tail], { engine: table => table.splice(1, 2) })
    const result = w.run('resolve_revisions', { indices: [1], action, expectedRevisions: [{ identifier: middle.identifier }] })
    assert.deepEqual(result.results, [{ index: 1, success: true }])
  })
}

test('the connected area Writer resolves with the target does not fail the command', () => {
  const a = redline('Delete', '甲', { date: at(0, 50) }), b = redline('Delete', '甲', { date: at(1, 20) }), c = redline('Delete', '乙')
  const w = world([a, b, c], { engine: table => table.splice(0, 2) })
  assert.deepEqual(w.run('resolve_revisions', { indices: [0], action: 'accept' }).results, [{ index: 0, success: true }])
})

test('a group is resolved by captured ids, each dispatched at most once', () => {
  const a = redline('Delete', '甲'), b = redline('Delete', '乙', { pos: 9 }), c = redline('Delete', '丙', { pos: 14 })
  const w = world([a, b, c])
  const result = w.run('resolve_revisions', { indices: [0, 2], action: 'reject',
    expectedRevisions: [{ identifier: a.identifier }, { identifier: c.identifier }] })
  assert.deepEqual(result.results, [{ index: 2, success: true }, { index: 0, success: true }])
  assert.deepEqual(w.dispatches.map(d => d.args[0].Value.val), [c.id, a.id])
  assert.deepEqual(w.table, [b])

  // Writer resolves both connected targets on the first dispatch: no second dispatch.
  const x = redline('Delete', '甲'), y = redline('Delete', '甲')
  const joined = world([x, y], { engine: table => table.splice(0, 2) })
  const both = joined.run('resolve_revisions', { indices: [1, 0], action: 'accept' })
  assert.deepEqual(both.results, [{ index: 1, success: true }, { index: 0, success: true }])
  assert.equal(joined.dispatches.length, 1)
})

test('a failed target stops the group without dispatching the rest', () => {
  const a = redline('Delete', '甲'), b = redline('Delete', '乙', { pos: 9 })
  const w = world([a, b], { engine: () => {} })
  const result = w.run('resolve_revisions', { indices: [0, 1], action: 'reject' })
  assert.deepEqual(result.results, [{ index: 1, success: false }, { index: 0, success: false }])
  assert.equal(w.dispatches.length, 1)
})

test('unverifiable targets are refused before any dispatch', () => {
  const a = redline('Delete', '甲'), b = redline('Delete', '乙')
  const stale = world([a, b])
  assert.equal(stale.run('resolve_revisions', { indices: [1], action: 'reject', expectedRevisions: [{ identifier: a.identifier }] }).success, false)
  assert.equal(stale.run('resolve_revision', { index: 7, action: 'reject' }).message, 'no revision at index 7')
  assert.equal(stale.dispatches.length, 0)
  assert.equal(stale.cursor.length, 0)
})

test('engines without AwdReviewGeometry keep the legacy cursor dispatch', () => {
  const first = redline('Delete', '甲'), second = redline('Delete', '乙', { pos: 9 })
  const w = world([first, second], { geometry: false, engine: 'cursor' })
  const result = w.run('resolve_revision', { index: 1, action: 'reject' })
  assert.equal(result.success, true); assert.equal(result.via, undefined)
  assert.deepEqual(w.dispatches, [{ url: '.uno:RejectTrackedChange', args: [] }])
  assert.deepEqual(w.cursor, [[9, false]])
})
