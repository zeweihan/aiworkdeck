// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#1105: real Writer regression from the synthetic installed-app failure.
// The fixture retains tracked edits; short plain-text samples missed the bug.
// Optional: COMMENT_FAULT=point verifies failed range verification, and
// COMMENT_WORKER_SOURCE selects an older worker for a red/green comparison.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import JSZip from 'jszip'
import { preflight, startServer, launchBrowser, loadPuppeteer, ORIGIN } from './_boot.mjs'

const jobs = [
  {
    "comment": "原稿为126万元、交割日2026年10月15日，系2026年9月12日原协议第二条。双方已于2026年9月26日签署补充协议一，将总价款变更为168万元、交割日变更为2026年11月6日，且该变更与青岚股东会2026年9月25日决议批准的方案一致。据实更正。",
    "target": "168万元"
  },
  {
    "comment": "原稿剩余价款70万元系按原总价126万元计算。补充协议一已将总价款变更为168万元；根据双方2026年9月29日签署的价款收付确认，截至该日青岚实际累计支付56万元，故剩余价款为112万元（168万-56万）。据实更正。",
    "target": "剩余转让价款为112万元"
  },
  {
    "comment": "原稿称须取得澄屿银行书面同意且条件尚未满足，系原协议第四条。双方已于2026年9月28日签署补充协议二，将银行事项替换为「交割前送达通知即满足，无须银行书面同意或回复」；根据2026年9月29日银行通知签收记录，通知已于当日10时送达并经银行盖章签收，该条件已满足。据实更正。",
    "target": "根据2026年9月28日补充协议二"
  },
  {
    "comment": "结论同步更正：银行事项条件已因通知送达而满足，剩余交割前提为青岚付清剩余价款112万元；交割日按补充协议一为2026年11月6日。付款情况为截至2026年9月29日的时点状态，其后是否变化需以交割前最新付款凭证核实。",
    "target": "银行通知送达条件已满足"
  }
]
const fault = process.env.COMMENT_FAULT || ''
assert.ok(!fault || fault === 'point')
const rounds = Number(process.env.COMMENT_ROUNDS || 2)
const evidenceDir = process.env.COMMENT_EVIDENCE_DIR
if (evidenceDir) await fs.mkdir(evidenceDir, { recursive: true })
async function report(value) {
  const line = JSON.stringify(value)
  console.log(line)
  if (evidenceDir) await fs.appendFile(path.join(evidenceDir, `comment-batch${fault ? '-' + fault : ''}.jsonl`), line + '\n')
}
const fixturePath = fileURLToPath(new URL('./fixtures/comment-batch-revisions.docx', import.meta.url))
const fixture = await fs.readFile(fixturePath)
const fixtureZip = await JSZip.loadAsync(fixture)
const cleanDocument = await fixtureZip.file('word/document.xml').async('string')
assert.equal([...cleanDocument.matchAll(/<w:ins\b/g)].length, 33)
assert.equal([...cleanDocument.matchAll(/<w:del\b/g)].length, 16)
const workerPath = process.env.COMMENT_WORKER_SOURCE || fileURLToPath(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url))
let worker = await fs.readFile(workerPath, 'utf8')
await report({ workerPath, sourceSha256: createHash('sha256').update(worker).digest('hex'), fault, rounds })
// Test-only read/configuration of the real native view setting. No production
// commands are replaced for the positive case.
worker = worker.replace('const EXEC = {', `const EXEC = {
  get_replay_notes(p) {
    const view = ctrl.getViewSettings();
    if (typeof p.show === 'boolean') withViewOnlyChange(function () { view.setPropertyValue('ShowAnnotations', p.show); });
    return { success: true, shown: !!view.getPropertyValue('ShowAnnotations'), recordChanges: !!xModel.getPropertyValue('RecordChanges') };
  },`)
if (fault === 'point') {
  const from = worker.indexOf('  add_comment(p) {'), to = worker.indexOf('  add_comment_at_selection(p)', from)
  const action = worker.slice(from, to)
  assert.ok(action.includes('selectVisibly(range)'))
  worker = worker.slice(0, from) + action.replace('selectVisibly(range)', 'selectVisibly(range.getEnd())') + worker.slice(to)
}
preflight([['comment fixture', fixturePath]])
const extraFiles = { '/office_thread.js': workerPath }
// _boot serves fonts from dist by default. An external assets directory is useful
// when running source tests with an installed engine, without copying its files.
if (process.env.LOWA_FONT_DIR) for (const font of ['cjk.ttc', 'cjk-serif.otf', 'cjk-kai.ttf', 'cjk-fangsong.ttf']) {
  extraFiles['/' + font] = path.join(process.env.LOWA_FONT_DIR, font)
}
const server = await startServer({ extraFiles, patchServed(url, bytes) {
  if (url === '/office_thread.js') return Buffer.from(worker)
  if (url.startsWith('/assets/')) return Buffer.from(bytes.toString().replace('["update_comment",', '["get_replay_notes","update_comment",'))
  return bytes
} })
const browser = await launchBrowser(await loadPuppeteer())
// Parse with the browser's XML parser. Match each exact comment body to its id,
// then inspect exactly that id's range with deleted revision text excluded.
async function inspectXml(page, bytes) {
  const exported = await JSZip.loadAsync(bytes)
  const document = await exported.file('word/document.xml').async('string')
  const comments = await exported.file('word/comments.xml').async('string')
  return page.evaluate(({ document, comments, jobs, reference }) => {
    const W = 'http://schemas.openxmlformats.org/wordprocessingml/2006/main'
    const parse = xml => { const doc = new DOMParser().parseFromString(xml, 'application/xml'); if (doc.querySelector('parsererror')) throw new Error('Invalid XML'); return doc }
    const d = parse(document), c = parse(comments), active = new Set(), ranges = new Map()
    const textState = doc => [...doc.getElementsByTagNameNS(W, 'p')].map(p => {
      const runs = []
      for (const n of p.getElementsByTagName('*')) {
        if (n.namespaceURI !== W || !['t', 'delText'].includes(n.localName)) continue
        let parent = n.parentElement, kind = 'plain', author = ''
        while (parent && parent !== p) {
          if (parent.namespaceURI === W && ['ins', 'del', 'moveFrom', 'moveTo'].includes(parent.localName)) {
            kind = parent.localName; author = parent.getAttributeNS(W, 'author') || ''; break
          }
          parent = parent.parentElement
        }
        const last = runs[runs.length - 1]
        if (last && last.kind === kind && last.author === author) last.text += n.textContent
        else runs.push({ kind, author, text: n.textContent })
      }
      return runs.filter(r => r.text)
    })
    const trackedTextMatches = JSON.stringify(textState(d)) === JSON.stringify(textState(parse(reference)))
    const walk = (node, deleted = false) => {
      const w = node.namespaceURI === W
      deleted ||= w && ['del', 'moveFrom'].includes(node.localName)
      const id = w ? node.getAttributeNS(W, 'id') : null
      if (w && node.localName === 'commentRangeStart') { const r = ranges.get(id) || { starts: 0, ends: 0, text: '' }; r.starts++; ranges.set(id, r); active.add(id) }
      if (w && node.localName === 't' && !deleted) for (const id of active) ranges.get(id).text += node.textContent
      for (const child of node.children || []) walk(child, deleted)
      if (w && node.localName === 'commentRangeEnd') { const r = ranges.get(id) || { starts: 0, ends: 0, text: '' }; r.ends++; ranges.set(id, r); active.delete(id) }
    }
    walk(d.documentElement)
    const entries = [...c.getElementsByTagNameNS(W, 'comment')].map(n => ({
      id: n.getAttributeNS(W, 'id'), author: n.getAttributeNS(W, 'author'), content: [...n.getElementsByTagNameNS(W, 't')].map(t => t.textContent).join(''),
    }))
    return { count: entries.length, trackedTextMatches, rows: jobs.map(j => {
      const match = entries.filter(c => c.content === j.comment), range = match.length === 1 ? ranges.get(match[0].id) : null
      return { target: j.target, id: match[0]?.id ?? null, author: match[0]?.author ?? null, contentMatches: match.length, range: range || null,
        ok: match.length === 1 && match[0].author === 'AI WorkDeck' && range?.starts === 1 && range?.ends === 1 && range?.text === j.target }
    }), empty: entries.filter(e => !e.content).map(e => ({ id: e.id, range: ranges.get(e.id) || null })), revisions: {
      ins: d.getElementsByTagNameNS(W, 'ins').length, del: d.getElementsByTagNameNS(W, 'del').length,
    } }
  }, { document, comments, jobs, reference: cleanDocument })
}

function inspectLive(listed) {
  assert.equal(listed.success, true)
  return jobs.map(j => {
    const match = (listed.comments || []).filter(c => c.content === j.comment)
    return { target: j.target, id: match[0]?.id ?? null, author: match[0]?.author ?? null, contentMatches: match.length, anchor: match[0]?.anchorText ?? null,
      ok: match.length === 1 && match[0].author === 'AI WorkDeck' && match[0].anchorText === j.target }
  })
}

try {
  const page = await browser.newPage()
  await page.setViewport({ width: 1440, height: 1000 })
  await page.goto(ORIGIN + '/editor.html?verify=1&lowa=/lowa/', { waitUntil: 'domcontentloaded' })
  await page.waitForFunction('!!window.__loExecutor', { timeout: 240000 })
  const exec = (a, p = {}) => page.evaluate((a, p) => window.__loExecutor.executeCommand(a, p), a, p)
  let failures = 0
  for (let round = 1; round <= rounds; round++) {
    assert.equal((await exec('load_document', { name: 'installed-clean.docx', bytes: Array.from(fixture) })).success, true)
    assert.equal((await exec('list_comments')).count, 0, 'clean fixture has no annotations')
    assert.equal((await exec('set_revision_view', { mode: 'all' })).mode, 'all')
    const notesBefore = await exec('get_replay_notes', { show: round % 2 === 1 })
    const anchors = []
    for (const j of jobs) {
      const found = await exec('find_text_locations', { keyword: j.target, __agent: true })
      assert.equal(found.success, true)
      assert.equal(found.matches.length, 1, 'unique formal target: ' + j.target)
      anchors.push(found.matches[0].anchorId)
    }
    // No diagnostic reads or sleeps between insertions: preserve the production
    // command and its paragraph readback, view guard, and sequential timing.
    const receipts = []
    for (let i = 0; i < jobs.length; i++) receipts.push(await exec('add_comment', {
      anchor: anchors[i], comment: jobs[i].comment, __agent: true,
    }))
    const notesAfter = await exec('get_replay_notes')
    const live = inspectLive(await exec('list_comments', { __agent: true }))
    const output = await page.evaluate(async () => {
      const r = await window.__loExecutor.executeCommand('export_document', { name: 'replay.docx' })
      if (!r.success) throw new Error(JSON.stringify(r))
      return Array.from(r.bytes)
    })
    const bytes = Buffer.from(output.map(n => n & 255))
    if (evidenceDir) await fs.writeFile(path.join(evidenceDir, `comment-batch${fault ? '-' + fault : ''}-${round}.docx`), bytes)
    const xml = await inspectXml(page, bytes)
    assert.equal((await exec('load_document', { name: 'reopened.docx', bytes: Array.from(bytes) })).success, true)
    const reopened = inspectLive(await exec('list_comments', { __agent: true }))
    const stateOk = notesBefore.shown === notesAfter.shown && notesBefore.recordChanges && notesAfter.recordChanges
    const ok = fault ? receipts.every(r => r.success === false) && xml.trackedTextMatches && stateOk
      : receipts.every(r => r.success) && xml.count === 4 && xml.trackedTextMatches && stateOk && [...live, ...xml.rows, ...reopened].every(r => r.ok)
    await report({ fault, round, ok, notesBefore, notesAfter, receiptSuccess: receipts.map(r => r.success), failures: receipts.filter(r => !r.success).map(r => r.message), live, xml, reopened })
    if (!ok) failures++
  }
  await report({ fault, rounds, failures, status: failures ? 'FAILED' : fault ? 'FAIL_CLOSED_PASS' : 'PASS_THIS_RUN' })
  if (failures) process.exitCode = 1
} finally {
  await browser.close()
  await new Promise(resolve => server.close(resolve))
}
