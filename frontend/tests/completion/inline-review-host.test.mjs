// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { createInlineReviewHost } from '../../src/composables/inlineReviewHost.js'

function fixture(t, opts = {}) {
  let revision = 1, timerId = 0
  const tasks = new Map(), calls = [], messages = [], stored = new Map()
  const paragraph = '第一条 价款：【待填写】。'
  const dependencies = {
    execute: async (action) => action === 'set_revision_view' ? { mode: 'margin' }
      : action === 'get_review_context' ? { success: true, revision }
        : { success: true, revision, paragraphs: [{ index: 0, text: paragraph }] },
    review: async (pid, body) => { calls.push({ pid, body }); return { findings: [{ kind: 'BLANK', paragraphIndex: 0, quote: '【待填写】', title: '尚有待填写内容' }] } },
  }
  const host = createInlineReviewHost({ projectId: 7, fileId: 8, userId: Math.random(),
    execute: (...args) => dependencies.execute(...args), review: (...args) => dependencies.review(...args),
    send: (m) => messages.push(m), storage: { get: (k) => stored.get(k), set: (k, v) => stored.set(k, v) },
    openInsight: () => calls.push({ open: true }),
    timers: { set: (fn) => { tasks.set(++timerId, fn); return timerId }, clear: (id) => tasks.delete(id) }, ...opts,
  })
  t.after(() => host.destroy())
  const tick = async () => { const entries = [...tasks]; tasks.clear(); await Promise.all(entries.map(([, fn]) => fn())) }
  const request = (action, data, session = host.session) => host.handle({ type: 'inline-review-request', session, action, data })
  return { host, dependencies, calls, messages, tasks, stored, tick, request, paragraph, edit() { revision++; host.modified() } }
}

test('opening and rapid typing coalesce into rules-only checks of the live document', async (t) => {
  const f = fixture(t); f.host.start()
  for (let i = 0; i < 20; i++) f.edit()
  assert.equal(f.tasks.size, 1)
  await f.tick()
  assert.equal(f.calls.length, 1)
  assert.equal(f.calls[0].body.deep, false)
  assert.equal(f.calls[0].body.docFileId, 8)
  const state = f.messages.at(-1)
  assert.equal(state.status, 'ready')
  assert.equal(state.revision, 21)
  assert.equal(state.findings[0].expectedParagraph, f.paragraph)
  assert.equal(state.findings[0].start, f.paragraph.indexOf('【待填写】'))
})

test('deep review requires an explicit action; repeated clicks cannot duplicate the paid request', async (t) => {
  const f = fixture(t); f.host.start(); await f.tick()
  let finish
  f.dependencies.review = async (pid, body) => { f.calls.push({ pid, body }); return new Promise((r) => { finish = r }) }
  const pending = f.request('deep')
  await new Promise(setImmediate)
  await f.request('deep')
  assert.equal(f.calls.filter((c) => c.body?.deep).length, 1)
  finish({ findings: [] }); await pending
  assert.equal(f.messages.at(-1).deepStatus, 'ready')
})

test('typing invalidates a slow AI result and schedules only a local recheck', async (t) => {
  const f = fixture(t); let finish
  f.dependencies.review = async (pid, body) => { f.calls.push({ pid, body }); return new Promise((r) => { finish = r }) }
  const pending = f.request('deep'); await new Promise(setImmediate)
  f.edit(); finish({ findings: [{ paragraphIndex: 0, quote: '【待填写】' }] }); await pending
  assert.equal(f.messages.at(-1).findings.length, 0)
  f.dependencies.review = async (pid, body) => { f.calls.push({ pid, body }); return { findings: [] } }
  await f.tick()
  assert.deepEqual(f.calls.map((c) => c.body.deep), [true, false])
})

test('worker revision fence rejects a result before a delayed modified relay arrives', async (t) => {
  const f = fixture(t)
  f.dependencies.execute = async (action) => action === 'set_revision_view' ? { mode: 'margin' }
    : action === 'get_review_context' ? { success: true, revision: 2 }
      : { success: true, revision: 1, paragraphs: [{ index: 0, text: f.paragraph }] }
  f.host.start(); await f.tick()
  assert.equal(f.messages.at(-1).status, 'stale')
  assert.equal(f.messages.at(-1).findings.length, 0)
})

test('pagination reaches later paragraphs but never mixes document revisions', async (t) => {
  const f = fixture(t); const reads = []
  f.dependencies.execute = async (action, p) => {
    if (action === 'set_revision_view') return { mode: 'margin' }
    if (action === 'get_review_context') return { success: true, revision: 1 }
    reads.push(p.startParagraph)
    return { success: true, revision: p.startParagraph ? 2 : 1,
      paragraphs: [{ index: p.startParagraph, text: '测试正文' }], truncated: !p.startParagraph, nextStartParagraph: 500 }
  }
  f.host.start(); await f.tick()
  assert.deepEqual(reads, [0, 500]); assert.equal(f.calls.length, 0)
  assert.equal(f.messages.at(-1).status, 'stale')
})

test('read-only members can use local checks but cannot call a model', async (t) => {
  const f = fixture(t, { writable: false }); f.host.start(); await f.tick(); await f.request('deep')
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].body.deep, false)
})

test('opening evidence is read-only and stale guest sessions have no effects', async (t) => {
  const f = fixture(t)
  assert.equal(await f.request('deep', {}, 'old-document'), false)
  await f.request('open-insight')
  assert.deepEqual(f.calls, [{ open: true }])
})

test('disabled preference cancels pending checks and resumes only after explicit enable', async (t) => {
  const f = fixture(t); f.host.start(); await f.request('preferences', { enabled: false })
  await f.tick(); f.edit(); await f.tick()
  assert.equal(f.calls.length, 0); assert.equal(f.messages.at(-1).status, 'disabled')
  await f.request('preferences', { enabled: true }); await f.tick()
  assert.equal(f.calls.length, 1)
})

test('all-revisions view is explicitly unavailable and does not feed deleted text into checks', async (t) => {
  const f = fixture(t); f.dependencies.execute = async () => ({ mode: 'all' })
  f.host.start(); await f.tick()
  assert.equal(f.calls.length, 0)
  assert.equal(f.messages.at(-1).message, 'REVIEW_INLINE_REVISIONS')
})

test('destroy prevents late responses, timers and all future actions', async (t) => {
  const f = fixture(t); let finish
  f.dependencies.review = () => new Promise((r) => { finish = r })
  const pending = f.request('deep'); await new Promise(setImmediate)
  f.host.destroy(); const count = f.messages.length; finish({ findings: [] }); await pending
  assert.equal(f.messages.length, count); assert.equal(f.tasks.size, 0)
  assert.equal(f.messages.at(-1).enabled, false)
  assert.equal(await f.request('deep'), false)
})

test('unlocatable model quotes are excluded; repeated quotes cannot acquire an inferred edit', async (t) => {
  const f = fixture(t)
  f.dependencies.execute = async (action) => action === 'set_revision_view' ? { mode: 'margin' }
    : action === 'get_review_context' ? { revision: 1 }
      : { success: true, revision: 1, paragraphs: [{ index: 0, text: '甲乙甲乙' }] }
  f.dependencies.review = async () => ({ findings: [
    { paragraphIndex: 0, quote: '并不存在', replacement: '编造的结论' },
    { paragraphIndex: 0, quote: '甲乙', replacement: '丙丁' },
  ] })
  await f.request('deep')
  assert.equal(f.messages.at(-1).findings.length, 1)
  assert.equal(f.messages.at(-1).findings[0].replacement, undefined)
})

test('a late local failure cannot erase completed deep review of the same revision', async (t) => {
  const f = fixture(t); let failLocal
  f.dependencies.review = async (pid, body) => body.deep
    ? { findings: [{ paragraphIndex: 0, quote: '【待填写】' }], summary: { deepComplete: true } }
    : new Promise((resolve, reject) => { failLocal = reject })
  f.host.start(); const local = f.tick(); await new Promise(setImmediate)
  await f.request('deep'); failLocal(new Error('local timeout')); await local
  assert.equal(f.messages.at(-1).status, 'ready')
  assert.equal(f.messages.at(-1).deepStatus, 'ready')
  assert.equal(f.messages.at(-1).findings.length, 1)
})

test('an oversized paragraph does not prevent checking subsequent paragraphs and is disclosed', async (t) => {
  const f = fixture(t)
  f.dependencies.execute = async (action, p) => action === 'set_revision_view' ? { mode: 'margin' }
    : action === 'get_review_context' ? { revision: 1 }
      : p.startParagraph === 0
        ? { success: true, revision: 1, paragraphs: [{ index: 0, text: '甲'.repeat(20001) }], truncated: true, nextStartParagraph: 1 }
        : { success: true, revision: 1, paragraphs: [{ index: 1, text: f.paragraph }] }
  f.host.start(); await f.tick()
  assert.deepEqual(f.calls[0].body.paragraphs, [{ index: 1, text: f.paragraph }])
  assert.equal(f.calls[0].body.truncated, true)
  assert.equal(f.messages.at(-1).truncated, true)
})

test('malformed deep output is explicitly incomplete even when rule results are available', async (t) => {
  const f = fixture(t)
  f.dependencies.review = async () => ({ findings: [], summary: { deepComplete: false } })
  await f.request('deep')
  assert.equal(f.messages.at(-1).deepStatus, 'error')
  assert.equal(f.messages.at(-1).message, 'REVIEW_DEEP_INCOMPLETE')
})
