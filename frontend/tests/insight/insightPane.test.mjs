// InsightPane.vue 的组件级用例（dev-board#182）：把 <script> 抽出来跑 computed / methods
// （同 tests/evidence/panelFilters.test.mjs 的路子：剥掉 import 行，依赖当形参喂进去）。
//
// 锁的是三条真会花钱/改文档的不变式：
//   ① 工具栏「解析」对已经解析过的文档**不重跑**（一次解析 = 一次 LLM + 一串外部库调用）；
//   ② 一键修改**只在恰好唯一命中时**才动文档，非唯一一律不改并给可读提示；
//   ③ 轮询定时器在卸载时清掉（面板是 v-if 挂载的）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { matchEntityAt, fixSuggestions, fixBlockReason, findingLocateQuote } from '../../src/utils/insightMatch.js'
import {
  companyRows, companyShareholders, lawArticle, caseRecord, rawFallback,
  authoritative, caseRecognition, citationDetail,
} from '../../src/utils/insightDetail.js'

function makeVm(overrides = {}) {
  const calls = { parse: [], latest: [], entity: [], refresh: [], exec: [] }
  const deps = {
    parseDocInsight: async (pid, did) => { calls.parse.push([pid, did]); return { code: 0, data: { runId: 1, status: 'RUNNING' } } },
    getDocInsight: async (pid, did) => { calls.latest.push([pid, did]); return { code: 0, data: overrides.latest || { run: null, entities: [], findings: [] } } },
    getDocInsightEntity: async (pid, id) => { calls.entity.push([pid, id]); return { code: 0, data: { detail: { basic: { 企业名称: 'X' } } } } },
    refreshDocInsightEntity: async (pid, id) => { calls.refresh.push([pid, id]); return { code: 0, data: { retrievalStatus: 'OK', hasDetail: true, detail: { basic: {} } } } },
    matchEntityAt, fixSuggestions, fixBlockReason, findingLocateQuote,
    companyRows, companyShareholders, lawArticle, caseRecord, rawFallback,
    authoritative, caseRecognition, citationDetail,
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

// ————————————————— ① 解析请求的闸 —————————————————

test('工具栏解析：没解析过 → 真发 POST /parse', async () => {
  const { vm, calls } = makeVm({ latest: { run: null, entities: [], findings: [] } })
  vm.parseRequest = { fileId: 9, token: 1 }
  await vm.load()
  await vm.consumeParseRequest()
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.parse.length, 1)
  assert.deepEqual(calls.parse[0], [7, 9])
})

test('工具栏解析：已经解析过 → 只开面板，不再花一次额度', async () => {
  const { vm, calls } = makeVm({ latest: { run: { id: 3, status: 'DONE', phase: '完成' }, entities: [], findings: [] } })
  vm.parseRequest = { fileId: 9, token: 1 }
  await vm.load()
  await vm.consumeParseRequest()
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.parse.length, 0)
})

test('工具栏解析：上一次是 FAILED → 重跑（失败的那次不算结论）', async () => {
  const { vm, calls } = makeVm({ latest: { run: { id: 3, status: 'FAILED', error: '读不出文字' }, entities: [], findings: [] } })
  vm.parseRequest = { fileId: 9, token: 1 }
  await vm.load()
  await vm.consumeParseRequest()
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.parse.length, 1)
})

test('工具栏解析：请求打给别的文档 → 本面板一动不动', async () => {
  const { vm, calls } = makeVm({ latest: { run: null, entities: [], findings: [] } })
  vm.parseRequest = { fileId: 12345, token: 1 }
  await vm.load()
  await vm.consumeParseRequest()
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.parse.length, 0, '「在 A 文档点过解析、切到 B 开面板」不许把 B 也解析掉')
})

test('面板里的「重新解析」是用户明示的 → 已有结论也重跑', async () => {
  const { vm, calls } = makeVm({ latest: { run: { id: 3, status: 'DONE' }, entities: [], findings: [] } })
  await vm.load()
  await vm.requestParse(true)
  assert.equal(calls.parse.length, 1)
})

test('只读成员没有写权限 → 按钮置灰且不发请求', async () => {
  const { vm, calls } = makeVm({ latest: { run: null, entities: [], findings: [] } })
  vm.canWrite = false
  await vm.load()
  assert.equal(vm.canParse, false)
  vm.onParseTap()
  await vm.requestParse(true)
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

test('光标联动：Cmd/Ctrl 点击 → 选中并展开；普通点击 → 只高亮', async () => {
  const { vm, calls } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [] } })
  await vm.load()
  vm.tab = 'checks'

  vm.onCursorContext({ before: '由京微资易', after: '科技有限公司持有', meta: { metaKey: false, ctrlKey: false } })
  assert.equal(vm.highlightId, 2)
  assert.equal(vm.expandedId, null, '普通点击不该抢展开')
  assert.equal(vm.tab, 'checks', '普通点击不该抢 tab')

  vm.onCursorContext({ before: '由京微资易', after: '科技有限公司持有', meta: { metaKey: true } })
  assert.equal(vm.expandedId, 2)
  assert.equal(vm.tab, 'retrieval')
  await new Promise((r) => setTimeout(r, 0))
  assert.equal(calls.entity.length, 1)
})

test('光标联动：没命中就什么都不动（不清高亮，免得面板一直闪）', async () => {
  const { vm } = makeVm({ latest: { run: { status: 'DONE' }, entities: ENTS, findings: [] } })
  await vm.load()
  vm.highlightId = 2
  vm.onCursorContext({ before: '本次交易的对价为', after: '人民币一亿元', meta: { metaKey: true } })
  assert.equal(vm.highlightId, 2)
  assert.equal(vm.expandedId, null)
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
