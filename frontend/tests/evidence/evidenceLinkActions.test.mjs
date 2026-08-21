// 拖到编辑器建链的纯函数：mock exec 按 action 返回，断言四条路径。
// 这里把 '@/...' 别名换成相对路径再 import（node --test 不认 vite 别名）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, writeFileSync, mkdirSync, rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const HERE = dirname(fileURLToPath(import.meta.url))
const SRC = resolve(HERE, '../../src/pages/project-overview/evidenceLinkActions.js')
const TMP = resolve(HERE, '.tmp-evidenceLinkActions.mjs')
{
  const code = readFileSync(SRC, 'utf8')
    .replace(/from '@\/services\/api\.js'/, "from './_api-stub.mjs'")
    .replace(/from '@\/utils\//g, "from '../../src/utils/")
  mkdirSync(HERE, { recursive: true })
  writeFileSync(resolve(HERE, '_api-stub.mjs'), ['createEvidenceLink', 'addEvidenceTargets', 'updateEvidenceTarget', 'getEvidenceLink', 'getFileDetail']
    .map((n) => `export function ${n}() { throw new Error('not stubbed: ${n}') }`).join('\n') + '\n')
  writeFileSync(TMP, code)
}
const { createEvidenceLinkForDrop, pickEvidenceTarget } = await import(pathToFileURL(TMP).href)
process.on('exit', () => {
  for (const f of [TMP, resolve(HERE, '_api-stub.mjs')]) { try { rmSync(f) } catch (e) { /* ignore */ } }
})

const BASE = 'https://checkba-internal.local/open'
const file = { id: 77, name: '营业执照.pdf' }

function harness({ selection, bookmarkOk = true, hyperlinkOk = true } = {}) {
  const calls = []
  const exec = async (action, params) => {
    calls.push([action, params])
    switch (action) {
      case 'get_selection_hyperlink': return selection
      case 'bookmark_selection': return bookmarkOk ? { success: true, name: params.name, text: 'x' } : { success: false, error: 'dup', message: 'dup' }
      case 'set_selection_hyperlink': return hyperlinkOk ? { success: true } : { success: false, message: 'no selection' }
      case 'get_bookmark_context': return { success: true, exists: true, sectionPath: '1/2', sectionTitle: '二、主体资格' }
      default: throw new Error('unexpected ' + action)
    }
  }
  const apiCalls = []
  const api = {
    createEvidenceLink: async (pid, body) => { apiCalls.push(['create', pid, body]); return { linkKey: body.linkKey, targets: [{ id: 501, fileId: 77 }] } },
    addEvidenceTargets: async (pid, key, targets) => { apiCalls.push(['add', pid, key, targets]); return { linkKey: key, targets: [{ id: 1, fileId: 3 }, { id: 9, fileId: 77 }, { id: 12, fileId: 77 }] } },
  }
  return { exec, api, calls, apiCalls, actions: () => calls.map((c) => c[0]) }
}

test('无选区 → no_selection，不碰 worker 其它原语也不调 api', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: false, text: '' } })
  const r = await createEvidenceLinkForDrop({ ...h, projectId: 1, docFileId: 10, file, internalBase: BASE })
  assert.deepEqual(r, { ok: false, reason: 'no_selection' })
  assert.deepEqual(h.actions(), ['get_selection_hyperlink'])
  assert.equal(h.apiCalls.length, 0)
})

test('新建：bookmark_selection + set_selection_hyperlink + createEvidenceLink，URL 带 k/projectId 不带 t', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: true, text: ' 收购人成立于 2020 年 ' } })
  const r = await createEvidenceLinkForDrop({ ...h, projectId: 1, docFileId: 10, file, internalBase: BASE })
  assert.equal(r.ok, true)
  assert.equal(r.created, true)
  assert.match(r.linkKey, /^EVID_[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.equal(r.targetId, 501)
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'bookmark_selection', 'set_selection_hyperlink', 'get_bookmark_context'])
  assert.equal(h.calls[1][1].name, r.linkKey)
  const url = h.calls[2][1].url
  assert.ok(url.startsWith(BASE + '?u='))
  const inner = decodeURIComponent(url.slice((BASE + '?u=').length))
  assert.equal(inner, `checkba://filelink?k=${r.linkKey}&projectId=1`)
  assert.equal(h.apiCalls.length, 1)
  const [, pid, body] = h.apiCalls[0]
  assert.equal(pid, 1)
  assert.equal(body.docFileId, 10)
  assert.equal(body.anchorText, '收购人成立于 2020 年')
  assert.equal(body.sectionPath, '1/2')
  assert.equal(body.sectionTitle, '二、主体资格')
  assert.equal(body.createdByKind, 'human')
  assert.deepEqual(body.targets, [{ fileId: 77, relation: 'supports', method: 'written_review' }])
})

test('选区已带 filelink?k= → 复用 linkKey，只 addEvidenceTargets，取该文件下最新 target', async () => {
  const existing = BASE + '?u=' + encodeURIComponent('checkba://filelink?k=EVID_OLD&projectId=1')
  const h = harness({ selection: { success: true, url: existing, hasSelection: true, text: '已关联文字' } })
  const r = await createEvidenceLinkForDrop({ ...h, projectId: 1, docFileId: 10, file, internalBase: BASE })
  assert.equal(r.ok, true)
  assert.equal(r.created, false)
  assert.equal(r.linkKey, 'EVID_OLD')
  assert.equal(r.targetId, 12)
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'get_bookmark_context'])
  assert.deepEqual(h.apiCalls, [['add', 1, 'EVID_OLD', [{ fileId: 77, relation: 'supports', method: 'written_review' }]]])
})

test('bookmark 失败（重名等）→ bookmark_failed，不写超链接、不调 api', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: true, text: '文字' }, bookmarkOk: false })
  const r = await createEvidenceLinkForDrop({ ...h, projectId: 1, docFileId: 10, file, internalBase: BASE })
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'bookmark_failed')
  assert.equal(r.message, 'dup')
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'bookmark_selection'])
  assert.equal(h.apiCalls.length, 0)
})

test('超链接写入失败 → hyperlink_failed，不调 api', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: true, text: '文字' }, hyperlinkOk: false })
  const r = await createEvidenceLinkForDrop({ ...h, projectId: 1, docFileId: 10, file, internalBase: BASE })
  assert.equal(r.reason, 'hyperlink_failed')
  assert.equal(h.apiCalls.length, 0)
})

test('pickEvidenceTarget：t 命中优先；单条直接给；多条无 t → null', () => {
  const view = { targets: [{ id: 1, fileId: 3 }, { id: 2, fileId: 4 }] }
  assert.equal(pickEvidenceTarget(view, 2).id, 2)
  assert.equal(pickEvidenceTarget(view, '2').id, 2)
  assert.equal(pickEvidenceTarget(view, 99), null)
  assert.equal(pickEvidenceTarget(view, null), null)
  assert.equal(pickEvidenceTarget({ targets: [{ id: 5 }] }, null).id, 5)
  assert.equal(pickEvidenceTarget(null, null), null)
})
