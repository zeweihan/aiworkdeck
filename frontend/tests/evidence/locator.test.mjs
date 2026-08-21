import test from 'node:test'
import assert from 'node:assert/strict'
import { locatorSummary, parseFileLinkUrl, buildFileLinkUrl, EVIDENCE_METHODS } from '../../src/utils/evidenceLocator.js'
import { ulid } from '../../src/utils/ulid.js'

const t = (k, p) => k + (p ? JSON.stringify(p) : '')

test('locatorSummary 按 type 分派', () => {
  assert.equal(locatorSummary({ type: 'pdf', page: 3 }, (k, p) => k + JSON.stringify(p || {})), 'evidence.loc.page{"page":3}')
  assert.equal(locatorSummary(null, (k) => k), 'evidence.loc.wholeFile')
  assert.equal(locatorSummary({}, (k) => k), 'evidence.loc.wholeFile')
  assert.equal(locatorSummary({ type: 'pdf' }, (k) => k), 'evidence.loc.wholeFile')
  assert.equal(locatorSummary({ type: 'docx', quote: 'x'.repeat(30) }, t), 'evidence.loc.quote{"quote":"' + 'x'.repeat(20) + '"}')
  assert.equal(locatorSummary({ type: 'image', rect: { x: 0, y: 0, w: 1, h: 1 } }, t), 'evidence.loc.region')
  assert.equal(locatorSummary({ type: 'media', startMs: 125000 }, t), 'evidence.loc.time{"time":"2:05"}')
  assert.equal(locatorSummary({ type: 'media', startMs: 3725000 }, t), 'evidence.loc.time{"time":"1:02:05"}')
  assert.equal(locatorSummary({ type: 'web', url: 'https://www.gsxt.gov.cn/a?b=1' }, t), 'evidence.loc.web{"host":"www.gsxt.gov.cn"}')
  assert.equal(locatorSummary({ type: 'sheet', sheet: 'S', cell: 'C12' }, t), 'evidence.loc.cell{"sheet":"S","cell":"C12"}')
  assert.equal(locatorSummary({ type: 'unknown' }, (k) => k), 'evidence.loc.wholeFile')
})

test('parseFileLinkUrl 解包包装链接并读 t', () => {
  const u = 'https://checkba-internal.local/open?u=' + encodeURIComponent('checkba://filelink?k=EVID_X&projectId=1&t=42')
  assert.deepEqual(parseFileLinkUrl(u), { linkKey: 'EVID_X', projectId: '1', targetId: 42 })
  assert.equal(parseFileLinkUrl('https://example.com'), null)
  assert.equal(parseFileLinkUrl(''), null)
  assert.equal(parseFileLinkUrl(null), null)
})

test('parseFileLinkUrl 接受裸 checkba:// 与单斜杠写法；无 t → null；非 filelink → null', () => {
  assert.deepEqual(parseFileLinkUrl('checkba://filelink?k=lk_1&projectId=7'), { linkKey: 'lk_1', projectId: '7', targetId: null })
  assert.deepEqual(parseFileLinkUrl('checkba:/filelink?k=lk_1&projectId=7&t=abc'), { linkKey: 'lk_1', projectId: '7', targetId: null })
  assert.equal(parseFileLinkUrl('checkba://webfav?id=3'), null)
  assert.equal(parseFileLinkUrl('checkba://filelink?projectId=7'), null)
})

test('buildFileLinkUrl 与 parseFileLinkUrl 互逆', () => {
  const base = 'https://checkba-internal.local/open'
  const u = buildFileLinkUrl(base, 'EVID_ABC', 12, 99)
  assert.ok(u.startsWith(base + '?u='))
  assert.deepEqual(parseFileLinkUrl(u), { linkKey: 'EVID_ABC', projectId: '12', targetId: 99 })
  assert.deepEqual(parseFileLinkUrl(buildFileLinkUrl(base, 'EVID_ABC', 12)), { linkKey: 'EVID_ABC', projectId: '12', targetId: null })
  assert.equal(buildFileLinkUrl('', 'K', 1), 'checkba://filelink?k=K&projectId=1')
})

test('ulid 26 位 Crockford、时间前缀单调、随机后缀不重复', () => {
  const a = ulid(1000)
  const b = ulid(1000)
  assert.match(a, /^[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.equal(a.slice(0, 10), b.slice(0, 10))
  assert.notEqual(a.slice(10), b.slice(10))
  assert.ok(ulid(2000).slice(0, 10) > ulid(1000).slice(0, 10))
  assert.equal(('EVID_' + ulid()).length, 31)
})

test('五种核查方法清单固定', () => {
  assert.deepEqual(EVIDENCE_METHODS, ['written_review', 'written_statement', 'web_check', 'third_party', 'interview'])
})
