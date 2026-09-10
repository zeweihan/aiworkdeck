// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createWritingAssistanceHost } from '../../src/composables/writingAssistanceHost.js'
import { matchCompletionItems } from '../../src/utils/completionLexicon.js'

function fixture(preferences = {}) {
  const messages = [], calls = []
  const api = Object.fromEntries(['list', 'learn', 'remove', 'clear', 'lookup', 'detail'].map((name) => [name, async (...args) => { calls.push({ name, args }); return name === 'list' ? { items: [{ text: '北京当红晴天律师事务所', kind: 'COMPANY', scope: 'project' }] } : {} }]))
  const host = createWritingAssistanceHost({ projectId: 11, fileId: 22, userId: 33, writable: true,
    send: (m) => messages.push(m), api,
    storage: { get: () => preferences, set: (...args) => calls.push({ name: 'store', args }) },
    execute: async (action) => action === 'set_revision_view' ? { mode: 'margin' } : { success: true, revision: 1, paragraphs: [{ index: 0, text: '姓名：张三。北京当红晴天律师事务所。' }] },
  })
  const request = (action, data = {}, session = host.session) => host.handle({ type: 'writing-request', session, id: 1, action, data })
  return { host, api, calls, messages, request }
}

test('initial vocabulary stays local; imported document entities only enter project scope', async () => {
  const f = fixture(); await f.host.start()
  assert.equal(f.calls.some((c) => ['lookup', 'detail'].includes(c.name)), false)
  const learned = f.calls.filter((c) => c.name === 'learn')
  assert.ok(learned.length > 0)
  assert.ok(learned.every((c) => c.args[0] === 11 && c.args[1].scope === 'project'))
  assert.ok(f.messages.some((m) => m.type === 'writing-config' && m.config.items.length))
})

test('disabled learning neither seeds document vocabulary nor accepts learn requests', async () => {
  const f = fixture({ learning: false }); await f.host.start()
  await f.request('learn', { scope: 'user', entries: [{ text: '张三', kind: 'PERSON' }] })
  assert.equal(f.calls.filter((c) => c.name === 'learn').length, 0)
})

test('old document session cannot learn, look up, delete, or clear data', async () => {
  const f = fixture()
  for (const action of ['learn', 'lookup', 'delete', 'clear']) assert.equal(await f.request(action, {}, 'old-file-session'), false)
  assert.equal(f.calls.length, 0)
})

test('lookup occurs only on explicit action and retains fixed project scope', async () => {
  const f = fixture(); await f.host.start()
  assert.equal(f.calls.filter((c) => c.name === 'lookup').length, 0)
  await f.request('lookup', { kind: 'LAW', text: '中华人民共和国民法典', projectId: 99 })
  assert.deepEqual(f.calls.find((c) => c.name === 'lookup').args, [11, { kind: 'LAW', text: '中华人民共和国民法典' }])
})

test('destruction suppresses late snapshot and lookup responses', async () => {
  const f = fixture(); let resolve
  f.api.lookup = () => new Promise((r) => { resolve = r })
  const work = f.request('lookup', { kind: 'CASE', text: '测试案例' })
  f.host.destroy(); resolve({}); await work
  assert.equal(f.messages.some((m) => m.type === 'writing-response'), false)
  assert.equal(f.messages.at(-1).config.writable, false)
})

test('preference storage is keyed by user and only accepts known boolean values', async () => {
  const f = fixture()
  await f.request('preferences', { enabled: false, learning: 'yes', arbitrary: true })
  assert.deepEqual(f.calls.find((c) => c.name === 'store').args, ['awd_writing_preferences_33', { enabled: false, learning: true, hints: true }])
})

test('document candidates retain saved details for acceptance, completed-name hints and right-click', async () => {
  const f = fixture()
  const text = '北京当红晴天律师事务所'
  f.api.list = async () => ({ items: [
    { text, kind: 'WORD', source: 'learned', scope: 'project', id: 'learned:5', uses: 2 },
    { text, kind: 'COMPANY', source: 'insight', scope: 'project', entityId: 17, hasDetail: true, uses: 0 },
  ] })
  await f.host.start()
  const items = f.messages.at(-1).config.items
  const accepted = matchCompletionItems('北京当红', items)[0]
  assert.equal(accepted.entityId, 17)
  assert.equal(accepted.kind, 'COMPANY')
  const known = items.find((item) => item.text === text)
  assert.equal(known.entityId, 17)
  assert.equal(known.hasDetail, true)
  assert.equal(known.source, 'document')
  assert.ok(items.some((item) => item.id === 'learned:5' && item.source === 'learned'), 'retain the original record for deletion in management')
})

const editorScript = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
  .match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '').replace(/export default \{/, 'return {')
const editorMethods = new Function('ReviewPanel', 'EditorToolbar', 'EvidenceStaleBar', editorScript)(null, null, null).methods

test('engine remount disposes vocabulary before detaching its old transport', async () => {
  const events = []
  const vm = {
    _writingHost: { destroy: () => events.push('vocabulary') },
    _eventUnsub: () => { assert.equal(vm._writingHost, null); events.push('transport') },
    executor: { dispose: () => events.push('executor') },
    appendLog() {}, startBootTrickle() {},
  }
  await editorMethods.remountEditor.call(vm)
  assert.deepEqual(events, ['vocabulary', 'transport', 'executor'])
  assert.equal(vm.ready, false)
})

test('in-place Writer reload destroys the old vocabulary before loading and rebuilds after success', async () => {
  const events = []
  const vm = { file: { id: 22 }, executor: {}, ready: true, saving: false, statusKey: 'ready', docLoadFailed: false,
    _writingHost: { destroy: () => events.push('destroy') },
    loadDocument: async () => { assert.equal(vm._writingHost, null); assert.equal(vm._reloading, true); events.push('load'); return true },
    initWritingAssistance: () => { assert.equal(vm._reloading, false); events.push('init') },
    appendLog() {}, scheduleAnchorCheck() {},
  }
  assert.equal(await editorMethods.reloadFromBackend.call(vm), true)
  assert.deepEqual(events, ['destroy', 'load', 'init'])
})

test('failed or in-flight reload cannot reactivate assistance against old document contents', async () => {
  const vm = { file: { id: 22 }, executor: {}, ready: true, saving: false, statusKey: 'ready', docLoadFailed: false,
    docKind: 'writer', projectId: 11, _transportSend() {},
    _writingHost: { destroy() {} },
    loadDocument: async () => { editorMethods.initWritingAssistance.call(vm); assert.equal(vm._writingHost, null); throw new Error('download failed') },
    initWritingAssistance: () => editorMethods.initWritingAssistance.call(vm),
    appendLog() {}, scheduleAnchorCheck() {},
  }
  assert.equal(await editorMethods.reloadFromBackend.call(vm), false)
  assert.equal(vm.docLoadFailed, true)
  assert.equal(vm._writingHost, null)
})

test('user preference changes reach all open documents without replacing their current vocabulary', async (t) => {
  const stored = new Map()
  const storage = { get: (key) => stored.get(key), set: (key, value) => stored.set(key, value) }
  const open = (projectId, userId = 91) => {
    const messages = [], learned = []
    const host = createWritingAssistanceHost({ projectId, fileId: projectId, userId, writable: true, storage,
      send: (message) => messages.push(message),
      execute: async (action) => action === 'set_revision_view' ? { mode: 'margin' } : { success: true, paragraphs: [] },
      api: { list: async () => ({ items: [{ text: `项目${projectId}的常用表述`, kind: 'PHRASE', scope: 'project' }] }), learn: async (...args) => { learned.push(args); return { learned: 1 } } },
    })
    t.after(() => host.destroy())
    const request = (action, data) => host.handle({ type: 'writing-request', session: host.session, id: 1, action, data })
    return { host, messages, learned, request }
  }
  const a = open(1), b = open(2), otherUser = open(3, 92)
  await Promise.all([a.host.start(), b.host.start(), otherUser.host.start()])
  const otherCount = otherUser.messages.length
  await a.request('preferences', { enabled: false, learning: false, hints: false })
  const update = b.messages.at(-1)
  assert.equal(update.type, 'writing-config')
  assert.equal(update.config.session, b.host.session)
  assert.equal(update.config.enabled, false)
  assert.equal(update.config.learning, false)
  assert.equal(update.config.hints, false)
  assert.equal(Object.hasOwn(update.config, 'items'), false, 'preference-only updates must preserve guest-side newly learned candidates')
  for (const scope of ['project', 'user']) await b.request('learn', { scope, entries: [{ text: '不再学习', kind: 'PHRASE' }] })
  assert.equal(b.learned.length, 0)
  assert.equal(otherUser.messages.length, otherCount, 'one user must not change another user preferences')
  await b.request('preferences', { enabled: true })
  const reverseUpdate = a.messages.at(-1).config
  assert.equal(reverseUpdate.enabled, true)
  assert.equal(reverseUpdate.learning, false, 'changing another flag cannot restore stale learning settings')
  b.host.destroy()
  const destroyedCount = b.messages.length
  await a.request('preferences', { learning: true })
  assert.equal(b.messages.length, destroyedCount, 'destroyed hosts unsubscribe from user preference updates')
})

function pagedFixture(t, read) {
  const messages = [], calls = [], learned = []
  const host = createWritingAssistanceHost({ projectId: 11, fileId: 22, userId: Math.random(), writable: true,
    send: m => messages.push(m), storage: { get() {}, set() {} },
    execute: async (action, params) => {
      calls.push({ action, params })
      if (action === 'set_revision_view') return { mode: 'margin' }
      if (action === 'get_review_context') return { success: true, revision: 1 }
      return read(params)
    },
    api: { list: async () => ({ items: [] }), learn: async (_pid, body) => { learned.push(body); return {} } },
  })
  t.after(() => host.destroy())
  const request = action => host.handle({ type: 'writing-request', session: host.session, id: 1, action })
  return { host, messages, calls, learned, request, items: () => messages.at(-1).config?.items || [] }
}

test('document seeding follows pagination and learns later entities without any paid call', async t => {
  const f = pagedFixture(t, p => p.startParagraph
    ? { success: true, revision: 1, paragraphs: [{ index: 200, text: '股东名册中青岛致衡贸易有限公司持股40%。' }] }
    : { success: true, revision: 1, paragraphs: [{ index: 0, text: '项目概况。' }], truncated: true, nextStartParagraph: 200 })
  await f.host.start()
  assert.equal(matchCompletionItems('青岛致', f.items())[0]?.text, '青岛致衡贸易有限公司')
  assert.ok(f.learned.some(x => x.entries.some(e => e.text === '青岛致衡贸易有限公司')))
  assert.ok(f.learned.every(x => x.scope === 'project'))
})

test('mixed-revision document pages are never published or learned', async t => {
  const f = pagedFixture(t, p => p.startParagraph
    ? { success: true, revision: 2, paragraphs: [{ index: 1, text: '青岛新正文有限公司。' }] }
    : { success: true, revision: 1, paragraphs: [{ index: 0, text: '青岛旧正文有限公司。' }], truncated: true, nextStartParagraph: 1 })
  await f.host.start()
  assert.deepEqual(f.items(), [])
  assert.deepEqual(f.learned, [])
})

test('manual document refresh retries a failed seed; focus refresh does not scan the document', async t => {
  let available = false
  const f = pagedFixture(t, () => available
    ? { success: true, revision: 1, paragraphs: [{ index: 0, text: '青岛致衡贸易有限公司。' }] }
    : { success: false })
  await f.host.start(); available = true
  await f.request('refresh')
  assert.equal(f.calls.filter(x => x.action === 'get_document_text').length, 1)
  await f.request('refreshDocument')
  const config = f.messages.filter(m => m.type === 'writing-config').at(-1).config
  assert.equal(matchCompletionItems('青岛致', config.items)[0]?.text, '青岛致衡贸易有限公司')
})

test('document candidates exceed a learn batch but project learning stays capped and batched', async t => {
  const f = pagedFixture(t, () => ({ success: true, revision: 1, paragraphs: Array.from({ length: 250 }, (_, i) => ({ index: i, text: `北京示例${i}有限公司。` })) }))
  await f.host.start()
  assert.ok(f.items().filter(x => x.kind === 'COMPANY').length >= 250)
  assert.equal(f.learned.reduce((sum, x) => sum + x.entries.length, 0), 200)
  assert.ok(f.learned.every(x => x.entries.length <= 50))
})

test('document collection stops at paragraph and text budgets', async t => {
  const f = pagedFixture(t, p => ({ success: true, revision: 1,
    paragraphs: Array.from({ length: 200 }, (_, i) => ({ index: (p.startParagraph || 0) + i, text: `北京主体${(p.startParagraph || 0) + i}有限公司。` })),
    truncated: true, nextStartParagraph: (p.startParagraph || 0) + 200 }))
  await f.host.start()
  assert.equal(f.calls.filter(x => x.action === 'get_document_text').length, 5)
  const g = pagedFixture(t, p => ({ success: true, revision: 1,
    paragraphs: [{ index: p.startParagraph || 0, text: '甲'.repeat(14999) + '。' }], truncated: true, nextStartParagraph: (p.startParagraph || 0) + 1 }))
  await g.host.start()
  assert.ok(g.calls.filter(x => x.action === 'get_document_text').length <= 14)
})

test('a document edit before publication or destruction while reading cannot seed stale text', async t => {
  let release
  const f = pagedFixture(t, () => new Promise(resolve => { release = resolve }))
  const pending = f.host.start()
  await new Promise(setImmediate)
  f.host.destroy()
  release({ success: true, revision: 1, paragraphs: [{ index: 0, text: '青岛旧正文有限公司。' }] })
  await pending
  assert.deepEqual(f.learned, [])
  assert.deepEqual(f.items(), [])
  const g = pagedFixture(t, () => ({ success: true, revision: 2, paragraphs: [{ index: 0, text: '青岛已变化有限公司。' }] }))
  await g.host.start()
  assert.deepEqual(g.learned, [])
  assert.deepEqual(g.items(), [])
})

test('manual rescans do not count the same imported entities as repeated personal use', async t => {
  const f = pagedFixture(t, () => ({ success: true, revision: 1, paragraphs: [{ index: 0, text: '青岛致衡贸易有限公司。' }] }))
  await f.host.start()
  await f.request('refreshDocument')
  assert.equal(f.learned.length, 1)
  assert.deepEqual(f.learned[0], { scope: 'project', entries: [{ text: '青岛致衡贸易有限公司', kind: 'COMPANY' }] })
})
