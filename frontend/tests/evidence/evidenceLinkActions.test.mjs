// 拖到编辑器建链的纯函数（evidenceLinkCore.js，exec/api 全部注入）：mock exec 按 action 返回，断言各条路径。
import test from 'node:test'
import assert from 'node:assert/strict'
import { createEvidenceLinkForDrop, createEvidenceLinkForSelection, pickEvidenceTarget } from '../../src/pages/project-overview/evidenceLinkCore.js'

const BASE = 'https://checkba-internal.local/open'
const file = { id: 77, name: '营业执照.pdf' }
const WRAPPED_OLD = BASE + '?u=' + encodeURIComponent('checkba://filelink?k=EVID_OLD&projectId=1')

function harness({ selection, bookmarkOk = true, hyperlinkOk = true, addTargetsError = null } = {}) {
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
    addEvidenceTargets: async (pid, key, targets) => {
      apiCalls.push(['add', pid, key, targets])
      if (addTargetsError) throw addTargetsError
      return { linkKey: key, targets: [{ id: 1, fileId: 3 }, { id: 9, fileId: 77 }, { id: 12, fileId: 77 }] }
    },
  }
  return { exec, api, calls, apiCalls, actions: () => calls.map((c) => c[0]) }
}
const run = (h) => createEvidenceLinkForDrop({ ...h, projectId: 1, docFileId: 10, file, internalBase: BASE })

test('无选区 → no_selection，不碰 worker 其它原语也不调 api', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: false, text: '' } })
  assert.deepEqual(await run(h), { ok: false, reason: 'no_selection' })
  assert.deepEqual(h.actions(), ['get_selection_hyperlink'])
  assert.equal(h.apiCalls.length, 0)
})

test('新建：bookmark_selection + set_selection_hyperlink + createEvidenceLink，URL 带 k/projectId 不带 t', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: true, text: ' 收购人成立于 2020 年 ' } })
  const r = await run(h)
  assert.equal(r.ok, true)
  assert.equal(r.created, true)
  assert.equal(r.recovered, false)
  assert.match(r.linkKey, /^EVID_[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.equal(r.targetId, 501)
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'bookmark_selection', 'set_selection_hyperlink', 'get_bookmark_context'])
  assert.equal(h.calls[1][1].name, r.linkKey)
  const url = h.calls[2][1].url
  assert.ok(url.startsWith(BASE + '?u='))
  assert.equal(decodeURIComponent(url.slice((BASE + '?u=').length)), `checkba://filelink?k=${r.linkKey}&projectId=1`)
  const [, pid, body] = h.apiCalls[0]
  assert.equal(h.apiCalls.length, 1)
  assert.equal(pid, 1)
  assert.equal(body.docFileId, 10)
  assert.equal(body.anchorText, '收购人成立于 2020 年')
  assert.equal(body.sectionPath, '1/2')
  assert.equal(body.sectionTitle, '二、主体资格')
  assert.equal(body.createdByKind, 'human')
  assert.deepEqual(body.targets, [{ fileId: 77, relation: 'supports', method: 'written_review' }])
})

test('选区已带 filelink?k= → 复用 linkKey，只 addEvidenceTargets，取该文件下最新 target', async () => {
  const h = harness({ selection: { success: true, url: WRAPPED_OLD, hasSelection: true, text: '已关联文字' } })
  const r = await run(h)
  assert.equal(r.ok, true)
  assert.equal(r.created, false)
  assert.equal(r.recovered, false)
  assert.equal(r.linkKey, 'EVID_OLD')
  assert.equal(r.targetId, 12)
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'get_bookmark_context'])
  assert.deepEqual(h.apiCalls, [['add', 1, 'EVID_OLD', [{ fileId: 77, relation: 'supports', method: 'written_review' }]]])
})

test('死锚点自愈：复用分支 addEvidenceTargets 404/「链接不存在」→ 回退 createEvidenceLink 带既有 linkKey，不再 bookmark_selection', async () => {
  for (const err of [Object.assign(new Error('HTTP 404'), { status: 404 }), new Error('链接不存在'), new Error('evidence link not found')]) {
    const h = harness({ selection: { success: true, url: WRAPPED_OLD, hasSelection: true, text: '已关联文字' }, addTargetsError: err })
    const r = await run(h)
    assert.equal(r.ok, true, err.message)
    assert.equal(r.recovered, true)
    assert.equal(r.created, false)
    assert.equal(r.linkKey, 'EVID_OLD')
    assert.equal(r.targetId, 501)
    assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'get_bookmark_context'])
    assert.equal(h.apiCalls.length, 2)
    assert.equal(h.apiCalls[0][0], 'add')
    assert.equal(h.apiCalls[1][0], 'create')
    assert.equal(h.apiCalls[1][2].linkKey, 'EVID_OLD')
    assert.equal(h.apiCalls[1][2].anchorText, '已关联文字')
  }
})

test('复用分支其它错误（非 not-found）原样抛出，不回退建链', async () => {
  const h = harness({ selection: { success: true, url: WRAPPED_OLD, hasSelection: true, text: '文字' }, addTargetsError: new Error('无权限访问该项目') })
  await assert.rejects(run(h), /无权限/)
  assert.equal(h.apiCalls.length, 1)
})

test('bookmark 失败（重名等）→ bookmark_failed，不写超链接、不调 api', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: true, text: '文字' }, bookmarkOk: false })
  const r = await run(h)
  assert.equal(r.ok, false)
  assert.equal(r.reason, 'bookmark_failed')
  assert.equal(r.message, 'dup')
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'bookmark_selection'])
  assert.equal(h.apiCalls.length, 0)
})

test('超链接写入失败 → hyperlink_failed，不调 api', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: true, text: '文字' }, hyperlinkOk: false })
  const r = await run(h)
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

// 复核 F2：SDK evidence.link 与拖放共用 createEvidenceLinkForSelection——多 target + createdByKind 透传，
// 复用分支把整组 targets 追加到既有 linkKey 上。
test('createEvidenceLinkForSelection：多 target / createdByKind=plugin 透传，新建走书签+超链接', async () => {
  const h = harness({ selection: { success: true, url: '', hasSelection: true, text: '注册资本 100 万' } })
  const targets = [{ fileId: 77, relation: 'supports' }, { fileId: 78, locatorJson: '{"page":2}' }]
  const r = await createEvidenceLinkForSelection({ ...h, projectId: 1, docFileId: 10, internalBase: BASE, targets, createdByKind: 'plugin' })
  assert.equal(r.ok, true)
  assert.equal(r.created, true)
  assert.equal(r.selText, '注册资本 100 万')
  assert.equal(r.targetId, undefined, 'targetId 是拖放层的派生字段，核心层不给')
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'bookmark_selection', 'set_selection_hyperlink', 'get_bookmark_context'])
  const [, , body] = h.apiCalls[0]
  assert.equal(body.createdByKind, 'plugin')
  assert.deepEqual(body.targets, targets)
})

test('createEvidenceLinkForSelection：选区已带 filelink?k= → 整组 targets 追加到既有 linkKey', async () => {
  const h = harness({ selection: { success: true, url: WRAPPED_OLD, hasSelection: true, text: 'x' } })
  const targets = [{ fileId: 77 }, { fileId: 78 }]
  const r = await createEvidenceLinkForSelection({ ...h, projectId: 1, docFileId: 10, internalBase: BASE, targets, createdByKind: 'plugin' })
  assert.equal(r.ok, true)
  assert.equal(r.created, false)
  assert.equal(r.linkKey, 'EVID_OLD')
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'get_bookmark_context'])
  assert.deepEqual(h.apiCalls, [['add', 1, 'EVID_OLD', targets]])
})
