// Web 插件 evidence.* 宿主端纯函数（frontend/src/utils/pluginEvidence.js）
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolveAnchor, newLinkKey, toPluginLink, toTargetInputs } from '../../src/utils/pluginEvidence.js'

function fakeExec(table) {
  const calls = []
  const exec = async (action, params) => {
    calls.push({ action, params })
    const h = table[action]
    if (!h) throw new Error('unexpected action ' + action)
    return typeof h === 'function' ? h(params) : h
  }
  exec.calls = calls
  return exec
}

test('resolveAnchor selection: 有选区回 mode=selection 与去空白文字', async () => {
  const exec = fakeExec({ get_selection_hyperlink: { success: true, hasSelection: true, text: ' 甲方保证 ', url: '' } })
  const r = await resolveAnchor(exec, { selection: true })
  assert.deepEqual(r, { mode: 'selection', text: '甲方保证' })
})

test('resolveAnchor selection: 无选区 / 空白选区 / worker 失败 都是 no_selection', async () => {
  for (const ret of [
    { success: true, hasSelection: false, url: '' },
    { success: true, hasSelection: true, text: '   ' },
    { success: false, message: 'boom' }
  ]) {
    const r = await resolveAnchor(fakeExec({ get_selection_hyperlink: ret }), { selection: true })
    assert.equal(r.error && r.error.code, 'no_selection')
  }
})

test('resolveAnchor quote: 恰好 1 命中回 anchorId（find_text_locations 的 keyword 入参 / anchorId 出参）', async () => {
  const exec = fakeExec({
    find_text_locations: { success: true, count: 1, matches: [{ matchIndex: 0, anchorId: '__ai_anchor_7', text: '第三条' }] }
  })
  const r = await resolveAnchor(exec, { quote: ' 第三条 ' })
  assert.deepEqual(r, { mode: 'quote', anchorId: '__ai_anchor_7', text: '第三条' })
  assert.deepEqual(exec.calls[0], { action: 'find_text_locations', params: { keyword: '第三条' } })
})

test('resolveAnchor quote: 0 命中与多命中都是 anchor_ambiguous', async () => {
  const zero = await resolveAnchor(fakeExec({ find_text_locations: { success: true, count: 0, matches: [] } }), { quote: '不存在' })
  assert.equal(zero.error.code, 'anchor_ambiguous')
  assert.match(zero.error.message, /未命中/)
  const many = await resolveAnchor(fakeExec({
    find_text_locations: { success: true, count: 2, matches: [{ anchorId: 'a' }, { anchorId: 'b' }] }
  }), { quote: '甲方' })
  assert.equal(many.error.code, 'anchor_ambiguous')
  assert.match(many.error.message, /2 处/)
})

test('resolveAnchor quote: worker 返回 success:false 不折叠成 0 命中，message 带上 worker 的原因', async () => {
  const r = await resolveAnchor(fakeExec({ find_text_locations: { success: false, message: 'document not ready', error: 'document not ready' } }), { quote: '第三条' })
  assert.equal(r.error.code, 'anchor_ambiguous')
  assert.match(r.error.message, /document not ready/)
  assert.doesNotMatch(r.error.message, /未命中/)
  const r2 = await resolveAnchor(fakeExec({ find_text_locations: { success: false, error: 'NOT_TEXT_DOC' } }), { quote: '第三条' })
  assert.match(r2.error.message, /NOT_TEXT_DOC/)
})

test('resolveAnchor quote: 命中但没拿到 anchorId 也算 anchor_ambiguous（不能把没法定位的当成功）', async () => {
  const r = await resolveAnchor(fakeExec({ find_text_locations: { success: true, matches: [{ text: 'x' }] } }), { quote: 'x' })
  assert.equal(r.error.code, 'anchor_ambiguous')
})

test('resolveAnchor: anchor 形状不对不打 worker，直接 anchor_ambiguous', async () => {
  const exec = fakeExec({})
  for (const bad of [undefined, null, {}, { selection: false }, { quote: '' }, { quote: 42 }]) {
    const r = await resolveAnchor(exec, bad)
    assert.equal(r.error.code, 'anchor_ambiguous')
  }
  assert.equal(exec.calls.length, 0)
})

test('newLinkKey: EVID_ + 26 位 Crockford，时间部分单调、随机部分来自注入的 random', () => {
  const fixed = (n) => new Uint8Array(n).fill(31)
  const k = newLinkKey(1724198400000, fixed)
  assert.match(k, /^EVID_[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.equal(k.slice(-16), 'Z'.repeat(16))
  const earlier = newLinkKey(1724198400000 - 1, fixed)
  assert.ok(earlier < k)
  assert.notEqual(newLinkKey(), newLinkKey())
  assert.match(newLinkKey(), /^EVID_[0-9A-HJKMNP-TV-Z]{26}$/)
})

test('toPluginLink: fileId 反查 path，target id 改名 targetId，缺省字段补空串', () => {
  const pathById = new Map([[10, 'docs/报告.docx'], [20, '底稿/营业执照.pdf']])
  const out = toPluginLink({
    linkKey: 'EVID_X', docFileId: 10, anchorText: '注册资本 100 万', sectionPath: '2/2.1', status: 'active',
    targets: [{ id: 5, fileId: 20, locator: { page: 1 }, relation: 'supports', method: 'human' }, { id: 6, fileId: 99 }]
  }, pathById)
  assert.deepEqual(out, {
    linkKey: 'EVID_X', docPath: 'docs/报告.docx', anchorText: '注册资本 100 万', sectionPath: '2/2.1', status: 'active',
    targets: [
      { targetId: 5, path: '底稿/营业执照.pdf', locator: { page: 1 }, relation: 'supports', method: 'human' },
      { targetId: 6, path: '', locator: null, relation: '', method: '' }
    ]
  })
})

test('toTargetInputs: path -> fileId，locator 序列化成 locatorJson；缺路径 / 空数组是 not_found', () => {
  const idByPath = new Map([['底稿/营业执照.pdf', 20]])
  const ok = toTargetInputs([{ path: '底稿/营业执照.pdf', locator: { page: 2 }, relation: 'supports', note: 'n' }], idByPath)
  assert.deepEqual(ok, { targets: [{ fileId: 20, locatorJson: '{"page":2}', relation: 'supports', method: null, note: 'n' }] })
  assert.equal(toTargetInputs([{ path: 'nope.pdf' }], idByPath).error.code, 'not_found')
  assert.equal(toTargetInputs([], idByPath).error.code, 'not_found')
  assert.equal(toTargetInputs(undefined, idByPath).error.code, 'not_found')
})
