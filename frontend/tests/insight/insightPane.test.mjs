// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// InsightPane.vue 的组件级用例（dev-board#182）：把 <script> 抽出来跑 computed / methods
// （同 tests/evidence/panelFilters.test.mjs 的路子：剥掉 import 行，依赖当形参喂进去）。
//
// 锁的是三条真会花钱/改文档的不变式：
//   ① 打开窗格只读；只有显式在线核验、保存成功后才能调用 LLM 与外部库；
//   ② 一键修改**只在恰好唯一命中时**才动文档，非唯一一律不改并给可读提示；
//   ③ 轮询定时器在卸载时清掉（面板是 v-if 挂载的）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { matchEntityAt, fixSuggestions, fixBlockReason, findingLocateQuote } from '../../src/utils/insightMatch.js'
import {
  companyRows, companyShareholders, lawArticle, caseRecord, rawFallback,
  authoritative, caseRecognition, citationDetail, projectFile,
} from '../../src/utils/insightDetail.js'

function makeVm(overrides = {}) {
  const calls = { parse: [], latest: [], entity: [], refresh: [], exec: [], prepare: [] }
  const deps = {
    parseDocInsight: async (pid, did) => { calls.parse.push([pid, did]); return { code: 0, data: { runId: 1, status: 'RUNNING' } } },
    getDocInsight: async (pid, did) => { calls.latest.push([pid, did]); return { code: 0, data: overrides.latest || { run: null, entities: [], findings: [] } } },
    getDocInsightEntity: async (pid, id) => { calls.entity.push([pid, id]); return { code: 0, data: { detail: { basic: { 企业名称: 'X' } } } } },
    refreshDocInsightEntity: async (pid, id) => { calls.refresh.push([pid, id]); return { code: 0, data: { retrievalStatus: 'OK', hasDetail: true, detail: { basic: {} } } } },
    matchEntityAt, fixSuggestions, fixBlockReason, findingLocateQuote,
    companyRows, companyShareholders, lawArticle, caseRecord, rawFallback,
    authoritative, caseRecognition, citationDetail, projectFile,
  }
  const src = readFileSync(new URL('../../src/components/InsightPane.vue', import.meta.url), 'utf8')
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import [\s\S]*?from .*$/gm, '')
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  const component = new Function(...names, script.replace('export default', 'return'))(...names.map((n) => deps[n]))

  const emitted = []
  const vm = {
    $t: (k, p) => (p ? k + ':' + JSON.stringify(p) : k),
    $emit: (n, p) => emitted.push([n, p]),
    $nextTick: (fn) => (fn ? Promise.resolve().then(fn) : Promise.resolve()),
    projectId: 7,
    docFileId: 9,
    docName: 'a.docx',
    canWrite: true,
    parseRequest: overrides.parseRequest || null,
    cursorContext: null,
    prepareDocument: overrides.prepareDocument || (async (did) => { calls.prepare.push(did); return true }),
    getExecutor: overrides.getExecutor || (() => (action, params) => {
      calls.exec.push([action, params])
      return Promise.resolve(overrides.execResult ? overrides.execResult(action, params) : { success: true })
    }),
  }
  Object.assign(vm, component.data.call(vm), component.methods)
  for (const [k, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  return { vm, component, calls, emitted }
}

// ————————————————— ① 只有显式在线核验才能发起付费管线 —————————————————

for (const status of [null, 'DONE', 'FAILED']) {
  test(`打开窗格只加载既有结果，不发起在线核验（${status || '未核验'}）`, async () => {
    const latest = { run: status ? { id: 3, status } : null, entities: [], findings: [] }
    const { vm, component, calls } = makeVm({ latest, parseRequest: { fileId: 9, token: 1 } })
    component.mounted.call(vm)
    await vm._loading
    await new Promise((r) => setTimeout(r, 0))
    assert.deepEqual(calls.latest, [[7, 9]])
    assert.equal(calls.parse.length, 0, '旧入口令牌也不能在挂载时触发付费请求')
  })
}

test('写作辅助打开依据只切焦点与停靠窗格，不创建解析请求', () => {
  const src = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const body = src.match(/onOpenInsight\(payload, pane\) \{([\s\S]*?)\n    \},/)[1]
  const opened = []
  const vm = { focusedPane: 'left', insightDocFileId: 9, insightParseRequest: null, openPanelInItsDock: (key) => opened.push(key) }
  new Function('payload', 'pane', body).call(vm, { fileId: 9 }, 'right')
  assert.equal(vm.focusedPane, 'right')
  assert.deepEqual(opened, ['insight'])
  assert.equal(vm.insightParseRequest, null)
})

test('工具栏不再显示旧解析按钮；在线按钮明确标注费用', () => {
  const toolbar = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8').split('<script>')[0]
  assert.ok(!toolbar.includes("$emit('toggle-insight')"))
  for (const locale of ['zh-CN', 'en-US']) {
    const src = readFileSync(new URL(`../../src/locales/${locale}/insight.js`, import.meta.url), 'utf8')
    assert.match(src, locale === 'zh-CN' ? /全文在线核验（可能产生费用）/ : /Full-document online verification \(charges may apply\)/)
  }
})

test('显式点击全文在线核验：已有结论也重跑', async () => {
  const { vm, calls } = makeVm({ latest: { run: { id: 3, status: 'DONE' }, entities: [], findings: [] } })
  await vm.load()
  await vm.onParseTap()
  await new Promise((r) => setTimeout(r, 0))
  assert.deepEqual(calls.parse, [[7, 9]])
})

test('等待首拉时切文档，不把在线核验请求转移给另一份文档', async () => {
  const { vm, calls } = makeVm()
  let release
  vm._loading = new Promise((resolve) => { release = resolve })
  const pending = vm.requestParse()
  vm.docFileId = 10
  release()
  await pending
  assert.equal(calls.parse.length, 0)
})

test('等待首拉期间连续点击在线核验只发一次请求', async () => {
  const { vm, calls } = makeVm()
  let release
  vm._loading = new Promise((resolve) => { release = resolve })
  const first = vm.requestParse(), second = vm.requestParse()
  release()
  await Promise.all([first, second])
  assert.deepEqual(calls.parse, [[7, 9]])
})

test('只读成员没有写权限 → 按钮置灰且不发请求', async () => {
  const { vm, calls } = makeVm()
  vm.canWrite = false
  await vm.load()
  assert.equal(vm.canParse, false)
  await vm.onParseTap()
  await vm.requestParse()
  assert.equal(calls.parse.length, 0)
})

test('在线核验先保存当前文档，保存失败不发付费请求', async () => {
  const { vm, calls } = makeVm({ prepareDocument: async () => false })
  await vm.onParseTap()
  assert.equal(calls.parse.length, 0)
  assert.equal(vm.error, 'insight.saveRequired')
})

test('保存等待期间切换文档，取消原在线核验请求', async () => {
  const { vm, calls } = makeVm()
  vm.prepareDocument = async () => { vm.docFileId = 10; return true }
  await vm.onParseTap()
  assert.equal(calls.parse.length, 0)
})

test('显式核验成功时，先准备同一文档再发请求', async () => {
  const { vm, calls } = makeVm()
  vm.prepareDocument = async (did) => {
    assert.equal(did, 9)
    assert.equal(calls.parse.length, 0)
    calls.prepare.push(did)
    return true
  }
  await vm.onParseTap()
  assert.deepEqual(calls.prepare, [9])
  assert.deepEqual(calls.parse, [[7, 9]])
})

function prepareHostVm() {
  const src = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
  const methods = ['getInsightEditorKey', 'prepareInsightDocument'].map((name) => {
    const found = src.match(new RegExp('(?:async )?' + name + '\\([^)]*\\) \\{[\\s\\S]*?\\n    \\},'))
    assert.ok(found, name + ' method must exist')
    return found[0]
  })
  return { projectId: 7, focusedPane: 'right', insightDocFileId: 9, activeFileLeft: { id: 9 }, activeFileRight: { id: 9 }, _libreRefs: {}, ...new Function('return ({' + methods.join('\n') + '})')() }
}

test('保存精确选择依据文档的当前侧实例，并核对保存后的脏状态', async () => {
  const vm = prepareHostVm(), calls = []
  const inst = { ready: true, file: { id: 9 }, dirty: true, saving: false, docLoadFailed: false, async flushSave(options) { calls.push(options); this.dirty = false; return true } }
  vm._libreRefs = { 'right:9': inst, 'left:9': { ...inst, flushSave() { throw new Error('wrong side') } } }
  assert.equal(await vm.prepareInsightDocument(9), true)
  assert.deepEqual(calls, [{ timeoutMs: 10000 }])
  inst.flushSave = async () => { inst.dirty = true; return true }
  assert.equal(await vm.prepareInsightDocument(9), false)
})

test('加载失败、实例替换或切文档时不许可在线核验', async () => {
  const vm = prepareHostVm()
  let saves = 0
  const inst = { ready: true, file: { id: 9 }, docLoadFailed: true, async flushSave() { saves++; return true } }
  vm._libreRefs = { 'right:9': inst }
  assert.equal(await vm.prepareInsightDocument(9), false)
  assert.equal(saves, 0)
  inst.docLoadFailed = false
  inst.flushSave = async () => { vm._libreRefs['right:9'] = { ...inst }; return true }
  assert.equal(await vm.prepareInsightDocument(9), false)
  vm._libreRefs['right:9'] = inst
  inst.flushSave = async () => { vm.insightDocFileId = 10; return true }
  assert.equal(await vm.prepareInsightDocument(9), false)
})

test('卸载时首拉尚未完成，也不能重新开启轮询', async () => {
  const { vm, component } = makeVm({ latest: { run: { status: 'RUNNING' }, entities: [], findings: [] } })
  const pending = vm.load()
  component.beforeUnmount.call(vm)
  try {
    await pending
    assert.ok(!vm._poll)
  } finally { vm.clearPoll() }
})

test('等待保存时关闭窗格，不在卸载后发起在线核验', async () => {
  const { vm, component, calls } = makeVm()
  vm.prepareDocument = async () => { component.beforeUnmount.call(vm); return true }
  await vm.onParseTap()
  assert.equal(calls.parse.length, 0)
})

// ————————————————— ② 一键修改的唯一命中闸 —————————————————

const FINDING = {
  id: 41, kind: 'COUNT_MISMATCH', severity: 'warn', title: '房产前后不一致',
  detail: {
    subject: '标的', metric: '房产', unit: '项',
    claims: [
      { quote: '标的公司名下房产共 58 项', value: 58, unit: '项', numberText: '58', fixable: true },
      { quote: '附表二：房产明细共 39 项', value: 39, unit: '项', numberText: '39', fixable: true },
    ],
  },
}

function execScript(script) {
  const seen = []
  return {
    seen,
    getExecutor: () => (action, params) => {
      seen.push([action, params])
      return Promise.resolve(script(action, params))
    },
  }
}

test('一键修改：唯一命中才替换，且用的是 find_navigate 数、find_replace 改', async () => {
  const ex = execScript((action) => (action === 'find_navigate'
    ? { success: true, found: true, total: 1, index: 1 }
    : { success: true, replaced: 1, total: 1 }))
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [FINDING] }, getExecutor: ex.getExecutor })
  await vm.load()
  const sug = vm.suggestionsOf(FINDING).find((s) => s.numberText === '58')
  await vm.applyFix(FINDING, sug)
  assert.deepEqual(ex.seen.map((c) => c[0]), ['find_navigate', 'find_replace'])
  assert.equal(ex.seen[1][1].findText, '附表二：房产明细共 39 项')
  assert.equal(ex.seen[1][1].replaceText, '附表二：房产明细共 58 项')
  assert.equal(vm.fixed[FINDING.id], true)
  assert.ok(!vm.fixNotice[FINDING.id])
})

test('一键修改：命中两处 → 一个字都不改，给「未能唯一定位」', async () => {
  const ex = execScript((action) => (action === 'find_navigate'
    ? { success: true, found: true, total: 2 }
    : { success: true, replaced: 2 }))
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [FINDING] }, getExecutor: ex.getExecutor })
  await vm.load()
  await vm.applyFix(FINDING, vm.suggestionsOf(FINDING)[0])
  assert.deepEqual(ex.seen.map((c) => c[0]), ['find_navigate'], '非唯一命中绝不能走到 find_replace')
  assert.ok(!vm.fixed[FINDING.id])
  assert.match(vm.fixNotice[FINDING.id], /fixNotUnique/)
})

test('一键修改：一处都没命中（正文已被改过）→ 不改、给提示', async () => {
  const ex = execScript((action) => (action === 'find_navigate' ? { success: true, found: false, total: 0 } : { success: false }))
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [FINDING] }, getExecutor: ex.getExecutor })
  await vm.load()
  await vm.applyFix(FINDING, vm.suggestionsOf(FINDING)[0])
  assert.deepEqual(ex.seen.map((c) => c[0]), ['find_navigate'])
  assert.ok(!vm.fixed[FINDING.id])
})

test('一键修改：引擎报 replaced≠1 → 当作没改成（不谎报成功）', async () => {
  const ex = execScript((action) => (action === 'find_navigate'
    ? { success: true, found: true, total: 1 }
    : { success: true, replaced: 0, total: 0 }))
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [FINDING] }, getExecutor: ex.getExecutor })
  await vm.load()
  await vm.applyFix(FINDING, vm.suggestionsOf(FINDING)[0])
  assert.ok(!vm.fixed[FINDING.id])
  assert.match(vm.fixNotice[FINDING.id], /fixNotUnique/)
})

test('一键修改：没有活跃编辑器 → 明说，不静默', async () => {
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [FINDING] }, getExecutor: () => null })
  await vm.load()
  await vm.applyFix(FINDING, vm.suggestionsOf(FINDING)[0])
  assert.match(vm.fixNotice[FINDING.id], /noEditor/)
})

test('定位一律走 find_navigate（不许用会往文档写书签的 find_text_locations）', async () => {
  const ex = execScript(() => ({ success: true, found: true, total: 1 }))
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [FINDING] }, getExecutor: ex.getExecutor })
  await vm.load()
  await vm.onFindingTap(FINDING)
  await vm.locate('标的公司名下房产共 58 项')
  assert.deepEqual(new Set(ex.seen.map((c) => c[0])), new Set(['find_navigate']))
  assert.equal(ex.seen[0][1].keyword, '标的公司名下房产共 58 项')
})

test('空 quote 不发命令（别拿标题去全文查找）', async () => {
  const ex = execScript(() => ({ success: true }))
  const { vm } = makeVm({ getExecutor: ex.getExecutor })
  await vm.locate('')
  await vm.onFindingTap({ id: 1, detail: { claims: [] } })
  assert.equal(ex.seen.length, 0)
})

// ————————————————— 引用发现（法宝升级件） —————————————————

const CITE_MISMATCH = {
  id: 61, kind: 'CITATION_MISMATCH', severity: 'warn', title: '《公司法》第十五条的引用内容可能与条文不符',
  detail: {
    lawTitle: '中华人民共和国公司法', citedArticle: '第十五条', citedText: '公司股东应当遵守…',
    quote: '依据《公司法》第十五条，公司向其他企业投资',
    candidates: [{ title: '中华人民共和国公司法（2018 修正）', articleNumber: '16', snippet: '公司向其他企业投资…', url: 'https://x' }],
    note: '候选可能来自旧版法规（存在条文重编号），请人工核对现行版本',
    fixable: false,
  },
}

test('引用发现：一个修改建议都不给（条文重编号，机械改条号必错）', async () => {
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [CITE_MISMATCH] } })
  await vm.load()
  assert.deepEqual(vm.suggestionsOf(CITE_MISMATCH), [])
  assert.equal(vm.blockReason(CITE_MISMATCH), '')
  assert.equal(vm.uscOf(CITE_MISMATCH), null, '别被 USCC 那条支路吃掉')
  assert.deepEqual(vm.claimsOf(CITE_MISMATCH), [])
  const c = vm.citationOf(CITE_MISMATCH)
  assert.equal(c.candidates.length, 1)
  assert.match(vm.citeHead(CITE_MISMATCH), /《中华人民共和国公司法》第十五条/)
})

test('引用发现：点条目仍按 detail.quote 定位（走 find_navigate）', async () => {
  const ex = execScript(() => ({ success: true, found: true, total: 1 }))
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [CITE_MISMATCH] }, getExecutor: ex.getExecutor })
  await vm.load()
  await vm.onFindingTap(CITE_MISMATCH)
  assert.deepEqual(ex.seen.map((c) => c[0]), ['find_navigate'])
  assert.equal(ex.seen[0][1].keyword, '依据《公司法》第十五条，公司向其他企业投资')
})

test('法宝链接交给宿主开（面板自己不 window.open），空链接不发', async () => {
  const { vm, emitted } = makeVm()
  vm.openUrl('https://www.pkulaw.com/chl/x')
  vm.openUrl('')
  vm.openUrl(null)
  const urls = emitted.filter((e) => e[0] === 'open-url')
  assert.deepEqual(urls.map((e) => e[1]), ['https://www.pkulaw.com/chl/x'])
})

test('只有权威原文 / 案号识别时不亮原文兜底（那不是「什么都认不出来」）', async () => {
  const { vm } = makeVm()
  vm.details = {
    3: { authoritative: { title: '公司法', original_text: '正文' } },
    1: { recognition: { caseFlag: '（2021）京01民终1234号', court: '北京一中院' } },
  }
  assert.equal(vm.showRaw({ id: 3, kind: 'LAW' }), false)
  assert.equal(vm.showRaw({ id: 1, kind: 'CASE' }), false)
  assert.equal(vm.showRaw({ id: 9, kind: 'CASE' }), false, '没有详情时本来就不亮')
})

// ————————————————— ③ 列表 / 轮询 / 联动 —————————————————

const ENTS = [
  { id: 1, kind: 'CASE', name: '（2024）京0108民初1234号', normKey: '（2024）京0108民初1234号', hasDetail: true, mentions: [{ quote: 'x' }] },
  { id: 2, kind: 'COMPANY', name: '京微资易科技有限公司', normKey: '京微资易科技', hasDetail: true, mentions: [] },
  { id: 3, kind: 'LAW', name: '《公司法》第二十条', normKey: '公司法#第二十条', hasDetail: false, mentions: [] },
]

test('实体按 公司 → 法规 → 案例 分组（顺序固定，不跟后端返回顺序走）', async () => {
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [] } })
  await vm.load()
  assert.deepEqual(vm.entityGroups.map((g) => g.kind), ['COMPANY', 'LAW', 'CASE'])
})

// ————————————————— 第四类实体 DOC（dev-board#541） —————————————————

const DOC_HIT = {
  id: 4, kind: 'DOC', name: '房屋租赁合同.docx', normKey: 'file:21',
  retrievalStatus: 'OK', retrievalSource: 'project-file', hasDetail: true, mentions: [{ quote: '见《房屋租赁合同》' }],
}
const DOC_MISS = {
  id: 5, kind: 'DOC', name: '补充协议', normKey: '补充协议',
  retrievalStatus: 'NOT_FOUND', retrievalNote: '项目中未找到该文件', retrievalHint: null,
  hasDetail: false, mentions: [],
}

test('DOC 自成一组，且排在案例之后（KIND_ORDER 漏了它就会被兜底归进公司组）', async () => {
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [...ENTS, DOC_HIT], findings: [] } })
  await vm.load()
  assert.deepEqual(vm.entityGroups.map((g) => g.kind), ['COMPANY', 'LAW', 'CASE', 'DOC'])
  const doc = vm.entityGroups.find((g) => g.kind === 'DOC')
  assert.deepEqual(doc.items.map((e) => e.id), [4])
})

test('DOC 不给「重试」：它的检索是与项目文件树比对，重试一百次也不会多出一份文件', async () => {
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [DOC_HIT, DOC_MISS], findings: [] } })
  await vm.load()
  assert.equal(vm.canParse, true, '前提：写权限在、没在跑——其余实体这时是给「重试」的')
  assert.equal(vm.showRetry(vm.entities[0]), false)
  assert.equal(vm.showRetry(vm.entities[1]), false, '未命中同样不给——那是文档的线索，不是我们的故障')
  assert.equal(vm.noteAction(DOC_MISS), null, '未命中没有 hint，也不该指向设置页')
})

test('DOC 命中：「打开文件」上抛 open-doc-file{fileId,fileName}；未命中不给按钮', async () => {
  const { vm, emitted, component } = makeVm({ latest: { run: { status: 'DONE' }, entities: [DOC_HIT, DOC_MISS], findings: [] } })
  await vm.load()
  assert.ok(component.emits.includes('open-doc-file'), '没声明的话宿主的 @open-doc-file 收不到')

  vm.details = { 4: { source: 'project-file', fileId: 21, fileName: '房屋租赁合同.docx', filePath: '/p/1/房屋租赁合同.docx' }, 5: null }
  assert.deepEqual(vm.docOf(DOC_HIT), { fileId: 21, fileName: '房屋租赁合同.docx', filePath: '/p/1/房屋租赁合同.docx' })
  vm.openDocFile(DOC_HIT)
  assert.deepEqual(emitted.filter((e) => e[0] === 'open-doc-file'), [['open-doc-file', { fileId: 21, fileName: '房屋租赁合同.docx' }]])

  assert.equal(vm.docOf(DOC_MISS), null)
  vm.openDocFile(DOC_MISS)
  assert.equal(emitted.filter((e) => e[0] === 'open-doc-file').length, 1, '没命中的一条一个事件都不许发')
})

test('实体清单同步给宿主（宿主据此做正文点击匹配）', async () => {
  const { vm, emitted } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [] } })
  await vm.load()
  const last = emitted.filter((e) => e[0] === 'entities').pop()
  assert.equal(last[1].docFileId, 9)
  assert.deepEqual(last[1].entities.map((e) => e.id), [1, 2, 3])
  assert.ok(!('mentions' in last[1].entities[0]), '同步给宿主的是瘦身索引，别把出处也搬过去')
})

test('RUNNING 才轮询；DONE 不留定时器；卸载一定清干净', async () => {
  const { vm, component } = makeVm({ latest: { run: { status: 'RUNNING', phase: '读取文档' }, entities: [], findings: [] } })
  await vm.load()
  assert.ok(vm._poll, 'RUNNING 时应排下一次轮询')
  component.beforeUnmount.call(vm)
  assert.equal(vm._poll, null, '卸载没清定时器 = 对着销毁的实例继续 setData')

  const done = makeVm({ latest: { run: { status: 'DONE' }, entities: [], findings: [] } })
  await done.vm.load()
  assert.ok(!done.vm._poll, 'DONE 还在轮询 = 永远打后端')
})

test('详情懒加载：展开才拉，且同一条只拉一次；hasDetail=false 的不打后端', async () => {
  const { vm, calls } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [] } })
  await vm.load()
  assert.equal(calls.entity.length, 0, '列表期不该拉任何详情')
  await vm.toggleEntity(ENTS[1])
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.entity.length, 1)
  vm.toggleEntity(ENTS[1])   // 收起
  await vm.toggleEntity(ENTS[1])  // 再展开
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.entity.length, 1, '详情要缓存在组件内')
  await vm.toggleEntity(ENTS[2])  // hasDetail:false
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.entity.length, 1)
})

test('光标联动：Cmd/Ctrl 点击 → 上抛 open-hover；普通点击 → 只高亮（dev-board#541）', async () => {
  const { vm, calls, emitted } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [] } })
  await vm.load()
  vm.tab = 'checks'

  vm.onCursorContext({ before: '由京微资易', after: '科技有限公司持有', meta: { metaKey: false, ctrlKey: false } })
  assert.equal(vm.highlightId, 2)
  assert.equal(vm.expandedId, null, '普通点击不该抢展开')
  assert.equal(vm.tab, 'checks', '普通点击不该抢 tab')
  assert.equal(emitted.filter((e) => e[0] === 'open-hover').length, 0)

  vm.onCursorContext({ before: '由京微资易', after: '科技有限公司持有', meta: { metaKey: true, hostX: 420, hostY: 310 } })
  const hover = emitted.filter((e) => e[0] === 'open-hover').pop()
  assert.equal(hover[1].entity.id, 2)
  assert.deepEqual([hover[1].x, hover[1].y], [420, 310], '坐标要原样带上去，浮窗才贴得住点击处')
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(vm.expandedId, null, '详情改由浮窗就地给，面板不再抢展开')
  assert.equal(vm.tab, 'checks', '也不再抢 tab')
  assert.equal(calls.entity.length, 0, '面板不该再为这一次点击去打一遍详情接口')
})

test('光标联动：没命中就什么都不动（不清高亮，免得面板一直闪）', async () => {
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [] } })
  await vm.load()
  vm.highlightId = 2
  vm.onCursorContext({ before: '本次交易的对价为', after: '人民币一亿元', meta: { metaKey: true } })
  assert.equal(vm.highlightId, 2)
  vm.onCursorContext(null)
  assert.equal(vm.highlightId, 2)
})

test('换文档：旧结论、旧详情缓存、旧「已修改」标记全部清掉', async () => {
  const { vm, emitted } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [FINDING] } })
  await vm.load()
  vm.fixed = { 41: true }
  vm.details = { 2: {} }
  vm.docFileId = 10
  vm.resetAndLoad()
  assert.deepEqual(vm.fixed, {})
  assert.deepEqual(vm.details, {})
  const cleared = emitted.filter((e) => e[0] === 'entities').pop()
  assert.deepEqual(cleared[1], { docFileId: 10, entities: [] })
})

test('run=null 是「没解析过」而不是错误态', async () => {
  const { vm } = makeVm({ latest: { run: null, entities: [], findings: [] } })
  await vm.load()
  assert.equal(vm.run, null)
  assert.equal(vm.error, '')
  assert.equal(vm.runLine, '')
  assert.equal(vm.isRunning, false)
})


// ————————————————— 检索状态五态（dev-board#395） —————————————————

test('NOT_FOUND 是中性态：note 照常显示，且样式表里为它单列一条中性色', async () => {
  const notFound = {
    id: 5, kind: 'COMPANY', name: '不存在的公司', retrievalStatus: 'NOT_FOUND',
    retrievalNote: '未查询到该企业（企查查只认工商全称，文中可能写的是简称）',
    hasDetail: false, mentions: [{ quote: '不存在的公司' }],
  }
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: [notFound], findings: [] } })
  await vm.load()
  const e = vm.entityGroups.flatMap((g) => g.items).find((x) => x.id === 5)
  assert.equal(e.retrievalStatus, 'NOT_FOUND')
  assert.ok(e.retrievalNote, 'note 必须下发并显示——空白格子什么都说明不了')
  assert.ok(!/hi@aiworkdeck\.com/.test(e.retrievalNote), '查无此项不该指向客服')

  // 模板按 `st-<状态>` 出 class；样式表漏一条 = 未命中被渲染成默认告警色
  const scss = readFileSync(new URL('../../src/components/insight-pane.scss', import.meta.url), 'utf8')
  assert.ok(/\.ip-dot\.st-NOT_FOUND\s*\{/.test(scss), 'ip-dot 缺 NOT_FOUND 一档')
  assert.ok(/\.ip-note\.st-NOT_FOUND\s*\{/.test(scss), 'ip-note 缺 NOT_FOUND 一档')
  assert.ok(!/\.ip-note\.st-NOT_FOUND[^\n]*awd-warning/.test(scss), 'NOT_FOUND 不该用告警色')
})


// ————————————————— 配置类失败的引导（dev-board#458） —————————————————
//
// 「重试」对「没连账户 / 余额不足 / Key 失效 / 本机没凭据」一点用都没有：那四种是恒定状态，
// 再点一百次还是同一句话。判定必须走后端下发的结构化 retrievalHint，**不许拿 retrievalNote
// 做中文子串匹配**——note 是双语的，英文版一上线子串判定整条失效（api.js:285 的同款教训）。

function unavailableEntity(hint, note = '原因一句话') {
  return {
    id: 6, kind: 'LAW', name: '《中华人民共和国公司法》第二十条', retrievalStatus: 'UNAVAILABLE',
    retrievalHint: hint, retrievalNote: note, hasDetail: false, mentions: [],
  }
}

async function loadWith(entity) {
  const made = makeVm({ latest: { run: { status: 'DONE' }, entities: [entity], findings: [] } })
  await made.vm.load()
  made.entity = made.vm.entities[0]
  return made
}

test('未连接账户：给「去连接账户」按钮，且不给「重试」', async () => {
  const { vm, entity, emitted } = await loadWith(unavailableEntity('NOT_CONNECTED'))
  const act = vm.noteAction(entity)
  assert.ok(act, '配置类失败必须给一个可点的下一步')
  assert.equal(act.label, 'insight.goConnectAccount')
  assert.equal(vm.showRetry(entity), false, '重试改不了「没连账户」')
  vm.runNoteAction(entity)
  assert.deepEqual(emitted.filter((e) => e[0] === 'open-settings'), [['open-settings', { nav: 'account' }]])
})

test('账户 Key 失效：同样指向账户设置', async () => {
  const { vm, entity, emitted } = await loadWith(unavailableEntity('UNAUTHORIZED'))
  assert.equal(vm.noteAction(entity).label, 'insight.goConnectAccount')
  assert.equal(vm.showRetry(entity), false)
  vm.runNoteAction(entity)
  assert.deepEqual(emitted.filter((e) => e[0] === 'open-settings'), [['open-settings', { nav: 'account' }]])
})

test('余额不足：按钮是「去充值」，不是「重试」', async () => {
  const { vm, entity, emitted } = await loadWith(unavailableEntity('NO_CREDITS'))
  assert.equal(vm.noteAction(entity).label, 'insight.goRecharge')
  assert.equal(vm.showRetry(entity), false)
  vm.runNoteAction(entity)
  assert.deepEqual(emitted.filter((e) => e[0] === 'open-settings'), [['open-settings', { nav: 'account' }]])
})

test('本机没凭据（自建部署）：只给文案，既不给按钮也不给重试', async () => {
  const { vm, entity, emitted } = await loadWith(unavailableEntity('NO_CREDENTIAL'))
  assert.equal(vm.noteAction(entity), null, '官方版没有法宝凭据输入框，指一条不存在的路比不指更糟')
  assert.equal(vm.showRetry(entity), false)
  assert.equal(vm.noteHint(entity), 'insight.hint.NO_CREDENTIAL')
  vm.runNoteAction(entity)
  assert.equal(emitted.filter((e) => e[0] === 'open-settings').length, 0)
})

test('瞬时失败（没有原因码）：照旧只给「重试」', async () => {
  const { vm, entity } = await loadWith(unavailableEntity(null, '法规检索本次不可用：上游超时'))
  assert.equal(vm.noteAction(entity), null)
  assert.equal(vm.noteHint(entity), '')
  assert.equal(vm.showRetry(entity), true, '上游故障重试是真出路')
})

test('只读成员：配置类引导仍显示，重试仍然没有（权限与原因是两回事）', async () => {
  const { vm, entity } = await loadWith(unavailableEntity('NO_CREDITS'))
  vm.canWrite = false
  assert.ok(vm.noteAction(entity))
  assert.equal(vm.showRetry(entity), false)
})

test('open-settings 必须在 emits 里声明，否则宿主的 @open-settings 收不到', () => {
  const { component } = makeVm()
  assert.ok(component.emits.includes('open-settings'))
})
