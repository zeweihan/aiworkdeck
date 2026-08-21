// 复核 F2：PluginPane.vue 的 SDK evidence.link 必须与拖放建链走同一份流程（evidenceLinkCore）：
// 书签 + 超链接成对写入、选区已带 filelink?k= 时复用 linkKey 只追加 target、quote 模式清掉查找锚点、
// 成功后发 awd:evidence-changed。把 <script> 抽出来真跑，依赖当参数注入（与 plugin-pane-stale-reply 同法）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createEvidenceLinkForSelection } from '../../src/pages/project-overview/evidenceLinkCore.js'
import { resolveAnchor, toPluginLink, toTargetInputs } from '../../src/utils/pluginEvidence.js'

const SRC = readFileSync(new URL('../../src/components/PluginPane.vue', import.meta.url), 'utf8')
const BASE = 'https://checkba-internal.local/open'

function loadOptions(deps) {
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export\s+default/, 'return')
  const factory = new Function(
    'getProjectFiles', 'getFileText', 'createEvidenceLink', 'addEvidenceTargets', 'getEvidenceLink', 'listEvidenceLinks',
    'getAppLanguage', 'resolveAnchor', 'toPluginLink', 'toTargetInputs',
    'createEvidenceLinkForSelection', 'WPS_INTERNAL_HTTP_LINK_BASE', 'uni', body)
  return factory(
    deps.getProjectFiles, deps.getFileText, deps.createEvidenceLink, deps.addEvidenceTargets, deps.getEvidenceLink, deps.listEvidenceLinks,
    () => 'zh-CN', resolveAnchor, toPluginLink, toTargetInputs,
    createEvidenceLinkForSelection, BASE, deps.uni)
}

function makeVm(options, props) {
  const vm = Object.assign({}, options.data ? options.data() : {}, props)
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  return vm
}

const FILES = [
  { id: 10, name: '报告.docx', isFolder: false, parentId: null },
  { id: 20, name: '营业执照.pdf', isFolder: false, parentId: null },
]

function harness({ selection, quoteMatches = null } = {}) {
  const calls = []
  const executor = async (action, params) => {
    calls.push([action, params || {}])
    switch (action) {
      case 'get_selection_hyperlink': return selection
      case 'find_text_locations': return { success: true, count: quoteMatches.length, matches: quoteMatches }
      case 'set_selection': return { success: true }
      case 'clear_anchors': return { success: true }
      case 'bookmark_selection': return { success: true, name: params.name, text: selection.text }
      case 'set_selection_hyperlink': return { success: true }
      case 'get_bookmark_context': return { success: true, exists: true, sectionPath: '1/2', sectionTitle: '二、主体资格' }
      default: throw new Error('unexpected ' + action)
    }
  }
  const apiCalls = []
  const emits = []
  const options = loadOptions({
    getProjectFiles: async () => ({ data: FILES }),
    getFileText: async () => '',
    createEvidenceLink: async (pid, body) => { apiCalls.push(['create', pid, body]); return { linkKey: body.linkKey, targets: [{ id: 501, fileId: 20 }] } },
    addEvidenceTargets: async (pid, key, targets) => { apiCalls.push(['add', pid, key, targets]); return { linkKey: key, targets: [{ id: 1, fileId: 3 }, { id: 9, fileId: 20 }] } },
    getEvidenceLink: async () => null,
    listEvidenceLinks: async () => [],
    uni: { $emit: (name, p) => emits.push([name, p]) },
  })
  const vm = makeVm(options, {
    projectId: 1, pluginId: 'dd', permissions: ['file_read', 'editor'],
    getActiveEditor: () => ({ fileId: 10, executor }),
  })
  return { vm, calls, apiCalls, emits, actions: () => calls.map((c) => c[0]) }
}

test('selection 模式新建：bookmark_selection 与 set_selection_hyperlink 成对、createdByKind=plugin、发 awd:evidence-changed', async () => {
  const h = harness({ selection: { success: true, hasSelection: true, text: ' 收购人成立于 2020 年 ', url: '' } })
  const r = await h.vm.evidenceLink({ anchor: { selection: true }, targets: [{ path: '营业执照.pdf', relation: 'supports' }] })
  assert.equal(r.ok, true, JSON.stringify(r))
  assert.match(r.result.linkKey, /^EVID_[0-9A-HJKMNP-TV-Z]{26}$/)
  assert.deepEqual(r.result.targetIds, [501])
  // resolveAnchor 读一次选区，core 再读一次（同一份流程的入口），随后书签 + 超链接 + 上下文
  assert.deepEqual(h.actions(), ['get_selection_hyperlink', 'get_selection_hyperlink', 'bookmark_selection', 'set_selection_hyperlink', 'get_bookmark_context'])
  assert.equal(h.calls[2][1].name, r.result.linkKey)
  const url = h.calls[3][1].url
  assert.equal(decodeURIComponent(url.slice((BASE + '?u=').length)), `checkba://filelink?k=${r.result.linkKey}&projectId=1`)
  assert.equal(h.apiCalls.length, 1)
  const [, pid, body] = h.apiCalls[0]
  assert.equal(pid, 1)
  assert.equal(body.docFileId, 10)
  assert.equal(body.createdByKind, 'plugin')
  assert.equal(body.anchorText, '收购人成立于 2020 年')
  assert.equal(body.sectionPath, '1/2')
  assert.deepEqual(body.targets, [{ fileId: 20, locatorJson: null, relation: 'supports', method: null, note: null }])
  assert.deepEqual(h.emits, [['awd:evidence-changed', { docFileId: 10, linkKey: r.result.linkKey, source: 'plugin' }]])
})

test('选区已带 filelink?k=：复用 linkKey 只 addEvidenceTargets，不再打书签/超链接', async () => {
  const wrapped = BASE + '?u=' + encodeURIComponent('checkba://filelink?k=EVID_OLD&projectId=1')
  const h = harness({ selection: { success: true, hasSelection: true, text: '注册资本', url: wrapped } })
  const r = await h.vm.evidenceLink({ anchor: { selection: true }, targets: [{ path: '营业执照.pdf' }] })
  assert.equal(r.ok, true, JSON.stringify(r))
  assert.equal(r.result.linkKey, 'EVID_OLD')
  assert.deepEqual(r.result.targetIds, [1, 9])
  assert.ok(!h.actions().includes('bookmark_selection'))
  assert.ok(!h.actions().includes('set_selection_hyperlink'))
  assert.equal(h.apiCalls[0][0], 'add')
  assert.equal(h.apiCalls[0][2], 'EVID_OLD')
  assert.equal(h.emits.length, 1)
})

test('quote 模式：find → set_selection → 建链 → clear_anchors 收尾，不留 __ai_anchor_* 书签', async () => {
  const h = harness({
    selection: { success: true, hasSelection: true, text: '第三条', url: '' },
    quoteMatches: [{ matchIndex: 0, anchorId: '__ai_anchor_7', text: '第三条' }],
  })
  const r = await h.vm.evidenceLink({ anchor: { quote: '第三条' }, targets: [{ path: '营业执照.pdf' }] })
  assert.equal(r.ok, true, JSON.stringify(r))
  const a = h.actions()
  assert.equal(a[0], 'find_text_locations')
  assert.equal(a[1], 'set_selection')
  assert.ok(a.includes('bookmark_selection') && a.includes('set_selection_hyperlink'))
  assert.equal(a[a.length - 1], 'clear_anchors')
  assert.ok(a.indexOf('clear_anchors') > a.indexOf('set_selection'))
})

test('无选区：no_selection，不碰书签、不调 api、不发事件', async () => {
  const h = harness({ selection: { success: true, hasSelection: false, text: '', url: '' } })
  const r = await h.vm.evidenceLink({ anchor: { selection: true }, targets: [{ path: '营业执照.pdf' }] })
  assert.equal(r.ok, false)
  assert.equal(r.error.code, 'no_selection')
  assert.ok(!h.actions().includes('bookmark_selection'))
  assert.equal(h.apiCalls.length, 0)
  assert.equal(h.emits.length, 0)
})
