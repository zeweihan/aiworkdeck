import test from 'node:test'
import assert from 'node:assert/strict'
import { classifyAnchorResults, applyReport, resolveKeepText, createAnchorChecker } from '../../src/composables/useEvidenceAnchors.js'

const hash = async (t) => 'h:' + t
const mk = (key, status, text) => ({ linkKey: key, status, anchorHash: 'h:' + text, anchorText: text, targets: [{ fileId: 1 }] })

test('classify: hash changed → stale report + hit; unchanged → nothing', async () => {
  const cache = [mk('A', 'active', 'old'), mk('B', 'active', 'same')]
  const r = await classifyAnchorResults(cache, [{ name: 'A', exists: true, text: 'new' }, { name: 'B', exists: true, text: 'same' }], hash)
  assert.deepEqual(r.reports, [{ linkKey: 'A', exists: true, text: 'new' }])
  assert.equal(r.staleHits.length, 1)
  assert.equal(r.staleHits[0].link, cache[0])
})

test('classify: missing bookmark or empty text → orphan report; existing orphan not re-reported and never revived', async () => {
  const cache = [mk('A', 'active', 'x'), mk('B', 'stale', 'x'), mk('C', 'orphan', 'x')]
  const r = await classifyAnchorResults(cache, [
    { name: 'A', exists: false, text: '' }, { name: 'B', exists: true, text: '' }, { name: 'C', exists: true, text: 'back' },
  ], hash)
  assert.deepEqual(r.reports, [{ linkKey: 'A', exists: false, text: '' }, { linkKey: 'B', exists: false, text: '' }])
  assert.equal(r.staleHits.length, 0)
})

test('classify: already-stale link with another hash change is not reported again (F4)', async () => {
  const cache = [mk('A', 'stale', 'v1')]
  const r = await classifyAnchorResults(cache, [{ name: 'A', exists: true, text: 'v2' }], hash)
  assert.deepEqual(r.reports, [])
})

test('applyReport: only keys in changed are applied', () => {
  const cache = [mk('A', 'active', 'x'), mk('B', 'active', 'x')]
  const applied = applyReport(cache, [{ linkKey: 'A', exists: true, text: 'y' }, { linkKey: 'B', exists: false, text: '' }], ['B'])
  assert.deepEqual([...applied], ['B'])
  assert.equal(cache[0].status, 'active')
  assert.equal(cache[1].status, 'orphan')
})

test('resolveKeepText: uses current worker text, falls back to cached text (F2)', async () => {
  const execOk = async () => ({ success: true, items: [{ name: 'A', exists: true, text: 'now' }] })
  assert.equal(await resolveKeepText(execOk, 'A', 'cached'), 'now')
  const execGone = async () => ({ success: true, items: [{ name: 'A', exists: false, text: '' }] })
  assert.equal(await resolveKeepText(execGone, 'A', 'cached'), 'cached')
  const execThrow = async () => { throw new Error('boom') }
  assert.equal(await resolveKeepText(execThrow, 'A', 'cached'), 'cached')
})

function harness(cache, opts = {}) {
  const timers = []
  const calls = { exec: 0, report: [], stale: [], changed: [] }
  let release = null
  const checker = createAnchorChecker({
    getCache: () => cache,
    exec: async (action, p) => {
      calls.exec++
      if (opts.block && calls.exec === 1) await new Promise((r) => { release = r })
      return { success: true, items: p.names.map((n) => ({ name: n, exists: true, text: opts.text || 'changed' })) }
    },
    report: async (reports) => { calls.report.push(reports); return { changed: reports.map((r) => r.linkKey) } },
    hash,
    isBusy: () => !!opts.busy,
    canRun: () => true,
    onStale: (hits) => calls.stale.push(hits.map((h) => h.linkKey)),
    onChanged: (set) => calls.changed.push([...set]),
    setTimeout: (fn, ms) => { timers.push({ fn, ms }); return timers.length },
    clearTimeout: (id) => { if (id) timers[id - 1] = null },
    debounceMs: 3000, retryMs: 1000,
  })
  return { checker, timers, calls, release: () => release && release() }
}

test('checker: run while running defers (recheck) instead of dropping, then re-arms a 0ms run', async () => {
  const cache = [mk('A', 'active', 'old')]
  const h = harness(cache, { block: true })
  const p1 = h.checker.run()
  await new Promise((r) => setImmediate(r))
  const r2 = await h.checker.run()
  assert.equal(r2, false)
  assert.equal(h.checker.state.recheck, true)
  h.release()
  assert.equal(await p1, true)
  assert.equal(h.checker.state.recheck, false)
  const armed = h.timers.filter(Boolean)
  assert.equal(armed.length, 1)
  assert.equal(armed[0].ms, 0)
  assert.deepEqual(h.calls.stale, [['A']])
  assert.equal(cache[0].status, 'stale')
})

test('checker: busy → retry armed at 1s, noRetry suppresses it', async () => {
  const h = harness([mk('A', 'active', 'x')], { busy: true })
  assert.equal(await h.checker.run(), false)
  assert.equal(h.timers.filter(Boolean)[0].ms, 1000)
  const h2 = harness([mk('A', 'active', 'x')], { busy: true })
  await h2.checker.run({ noRetry: true })
  assert.equal(h2.timers.filter(Boolean).length, 0)
})

test('checker: schedule debounces at 3s and flush runs a pending check immediately', async () => {
  const cache = [mk('A', 'active', 'old')]
  const h = harness(cache)
  h.checker.schedule(); h.checker.schedule()
  assert.equal(h.timers.filter(Boolean).length, 1)
  assert.equal(h.timers.filter(Boolean)[0].ms, 3000)
  assert.equal(await h.checker.flush(), true)
  assert.equal(h.calls.report.length, 1)
  assert.equal(await h.checker.flush(), false) // nothing pending
})

test('checker: flush also runs when a recheck was deferred', async () => {
  const cache = [mk('A', 'active', 'old')]
  const h = harness(cache)
  h.checker.state.recheck = true
  assert.equal(await h.checker.flush(), true)
})

test('checker: second run after stale does not report again (end-to-end F4)', async () => {
  const cache = [mk('A', 'active', 'old')]
  const h = harness(cache)
  await h.checker.run()
  await h.checker.run()
  assert.equal(h.calls.report.length, 1)
  assert.equal(h.calls.stale.length, 1)
})
