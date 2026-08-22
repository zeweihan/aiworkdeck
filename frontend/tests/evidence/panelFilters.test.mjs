// 审阅面板「底稿」页（P2，dev-board#120）：章节树两级分组、三重筛选、顶部统计的纯函数，
// 外加把 EvidencePanel.vue 的 <script> 抽出来跑一遍 computed 链（同
// tests/project-home/review-panel-double-tap.test.mjs 的路子：剥掉 import 行，
// 依赖当形参喂进去），确认「筛选 → 分组 → 拍平成行」这条链真的接上了。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  groupBySectionTree, filterBySection, filterByParty, filterByStatus,
  sectionOptions, partyOptions, statusCounts, partiesOf, sectionSegments,
  collectFileTags, GROUP_NONE, STATUS_KEYS,
} from '../../src/utils/evidenceGrouping.js'
import { locatorQuote, locatorSummary, buildFileLinkUrl } from '../../src/utils/evidenceLocator.js'

const link = (key, sectionPath, sectionTitle, status, targets) => ({
  linkKey: key, sectionPath, sectionTitle, status, anchorText: key, targets: targets || [],
})

// 一/（一）  一/（一）  一/（二）  一（直属）  二/（一）/3  （无章节）
const L1 = link('EVID_1', '一/（一）', '（一）主体资格', 'active', [{ id: 1, fileId: 10 }])
const L2 = link('EVID_2', '一/（一）', '（一）主体资格', 'stale', [{ id: 2, fileId: 11 }])
const L3 = link('EVID_3', '一/（二）', '（二）历史沿革', 'unverified', [{ id: 3, fileId: 12 }])
const L4 = link('EVID_4', '一', '一、基本情况', 'active', [{ id: 4, fileId: 10 }])
const L5 = link('EVID_5', '二/（一）/3', '3. 出资', 'orphan', [])
const L6 = link('EVID_6', '', '', 'active', [{ id: 6, fileId: 13 }])
const LINKS = [L1, L2, L3, L4, L5, L6]

const TAGS = new Map([
  [10, [{ id: 1, name: '目标公司', type: 'PARTY' }, { id: 9, name: '合同', type: null }]],
  [11, [{ id: 2, name: '收购方', type: 'PARTY' }]],
  [12, [{ id: 5, name: '争点A', type: 'ISSUE' }]],
])

test('sectionSegments 只按段拆，空段丢掉', () => {
  assert.deepEqual(sectionSegments('一/（二）/3'), ['一', '（二）', '3'])
  assert.deepEqual(sectionSegments(' 一 //（一） '), ['一', '（一）'])
  assert.deepEqual(sectionSegments(''), [])
  assert.deepEqual(sectionSegments(null), [])
})

test('groupBySectionTree：一级/二级两层，三级并进所属二级，直属挂一级，空路径归 GROUP_NONE', () => {
  const tree = groupBySectionTree(LINKS)
  assert.deepEqual(tree.map((r) => r.key), ['一', '二', GROUP_NONE], '顺序 = 首次出现顺序')

  const one = tree[0]
  assert.equal(one.title, '一、基本情况', '一级标题取 sectionPath 恰好等于该组的那条 link 的 sectionTitle')
  assert.deepEqual(one.items.map((l) => l.linkKey), ['EVID_4'], '只有一段路径的挂一级直属')
  assert.deepEqual(one.children.map((c) => c.key), ['一/（一）', '一/（二）'])
  assert.deepEqual(one.children[0].items.map((l) => l.linkKey), ['EVID_1', 'EVID_2'])
  assert.equal(one.children[0].title, '（一）主体资格')
  assert.equal(one.count, 4, 'count = 直属 1 + 二级 2 + 1')

  const two = tree[1]
  assert.equal(two.title, '二', '没有恰好等于该组的 link 时退回路径段本身')
  assert.deepEqual(two.children.map((c) => c.key), ['二/（一）'])
  assert.deepEqual(two.children[0].items.map((l) => l.linkKey), ['EVID_5'], '三级并进二级组')
  assert.equal(two.children[0].title, '（一）', '二级标题拿不到 sectionTitle 就用路径段')

  assert.deepEqual(tree[2].items.map((l) => l.linkKey), ['EVID_6'])
  assert.equal(tree[2].title, '')
})

test('filterBySection 按路径段前缀，不是字符串 startsWith', () => {
  assert.equal(filterBySection(LINKS, 'all').length, 6)
  assert.equal(filterBySection(LINKS).length, 6)
  assert.deepEqual(filterBySection(LINKS, '一').map((l) => l.linkKey), ['EVID_1', 'EVID_2', 'EVID_3', 'EVID_4'])
  assert.deepEqual(filterBySection(LINKS, '一/（一）').map((l) => l.linkKey), ['EVID_1', 'EVID_2'])
  assert.deepEqual(filterBySection(LINKS, '二/（一）').map((l) => l.linkKey), ['EVID_5'], '选二级要连它下面的三级一起要')
  assert.deepEqual(filterBySection(LINKS, GROUP_NONE).map((l) => l.linkKey), ['EVID_6'])
  // 「一」不许把「一〇」吃进来
  const wide = [link('EVID_X', '一〇/（一）', '', 'active', [])]
  assert.deepEqual(filterBySection(wide, '一'), [])
})

test('sectionOptions 展开成两级，GROUP_NONE 的 label 留空交给组件取文案', () => {
  assert.deepEqual(sectionOptions(LINKS).map((o) => [o.key, o.label, o.depth]), [
    ['一', '一、基本情况', 0],
    ['一/（一）', '（一）主体资格', 1],
    ['一/（二）', '（二）历史沿革', 1],
    ['二', '二', 0],
    ['二/（一）', '（一）', 1],
    [GROUP_NONE, '', 0],
  ])
})

test('partiesOf / filterByParty / partyOptions：一个文件多个 PARTY，非 PARTY 标签不算', () => {
  assert.deepEqual(partiesOf(L1, TAGS), [{ key: 'party:1', label: '目标公司' }])
  assert.deepEqual(partiesOf(L3, TAGS), [], 'ISSUE 标签不是主体')
  assert.deepEqual(partiesOf(L5, TAGS), [], '没有 target 就没有主体')

  assert.deepEqual(filterByParty(LINKS, TAGS, 'party:1').map((l) => l.linkKey), ['EVID_1', 'EVID_4'])
  assert.deepEqual(filterByParty(LINKS, TAGS, 'party:2').map((l) => l.linkKey), ['EVID_2'])
  assert.deepEqual(filterByParty(LINKS, TAGS, GROUP_NONE).map((l) => l.linkKey), ['EVID_3', 'EVID_5', 'EVID_6'])
  assert.equal(filterByParty(LINKS, TAGS, 'all').length, 6)
  assert.equal(filterByParty(LINKS, null, 'party:1').length, 0, '标签还没加载时不许瞎猜归属')

  assert.deepEqual(partyOptions(LINKS, TAGS).map((o) => [o.key, o.label]), [
    ['party:1', '目标公司'], ['party:2', '收购方'], [GROUP_NONE, ''],
  ])
})

test('statusCounts：各状态数如实计，枚举外的值只进 total 不硬塞进桶', () => {
  assert.deepEqual(statusCounts(LINKS), { total: 6, active: 3, unverified: 1, stale: 1, orphan: 1 })
  const weird = [link('EVID_W', '', '', 'brand_new_state', [])]
  assert.deepEqual(statusCounts(weird), { total: 1, active: 0, unverified: 0, stale: 0, orphan: 0 })
  assert.deepEqual(statusCounts([]), { total: 0, active: 0, unverified: 0, stale: 0, orphan: 0 })
  assert.deepEqual(STATUS_KEYS, ['active', 'unverified', 'stale', 'orphan'])
})

test('locatorQuote：有 quote 才给，截断加省略号；没有就留空不编造', () => {
  assert.equal(locatorQuote({ type: 'pdf', page: 3, quote: '统一社会信用代码 91…' }), '统一社会信用代码 91…')
  assert.equal(locatorQuote({ type: 'pdf', page: 3 }), '', 'pdf 没带引文就留空')
  assert.equal(locatorQuote(null), '')
  assert.equal(locatorQuote({ quote: '   ' }), '', '全空白视同没有')
  assert.equal(locatorQuote({ quote: 'x'.repeat(80) }, 60), 'x'.repeat(60) + '…')
  assert.equal(locatorQuote({ quote: 'x'.repeat(80) }, 0), 'x'.repeat(80), 'max<=0 不截断')
})

// ---------------------------------------------------------------- 组件层：computed 链

// EvidencePanel.vue 的 <script> 里 import 的东西当形参喂进去（@/ 别名进不来）。
const DEPS = {
  listEvidenceLinks: async () => [], keepEvidenceAnchor: async () => ({}), rebindEvidenceLink: async () => ({}),
  deleteEvidenceLink: async () => ({}), updateEvidenceTarget: async () => ({}), removeEvidenceTarget: async () => ({}),
  getProjectFiles: async () => [],
  groupBySectionTree, groupByParty: null, filterByStatus, filterBySection, filterByParty,
  sectionOptions, partyOptions, statusCounts, collectFileTags, GROUP_NONE, STATUS_KEYS,
  locatorSummary, locatorQuote, buildFileLinkUrl,
  ulid: () => 'X'.repeat(26), WPS_INTERNAL_HTTP_LINK_BASE: 'https://checkba-internal.local/open',
  EVIDENCE_CHANGED_EVENT: 'awd:evidence-changed', resolveKeepText: async () => ({ text: '', gone: false }),
}

async function makeVm(links, tags) {
  const { groupByParty } = await import('../../src/utils/evidenceGrouping.js')
  const deps = { ...DEPS, groupByParty }
  const src = readFileSync(new URL('../../src/components/EvidencePanel.vue', import.meta.url), 'utf8')
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1].replace(/^import [\s\S]*?from .*$/gm, '')
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  const component = new Function(...names, script.replace('export default', 'return'))(...names.map((n) => deps[n]))
  const vm = { $t: (k, p) => (p && p.count != null ? k + ':' + p.count : k), $emit: () => {}, projectId: 7, docFileId: 9 }
  Object.assign(vm, component.data.call(vm), component.methods)
  for (const [k, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  vm.links = links
  vm.fileTags = tags
  return vm
}

test('组件：按章节视图的 rows 是「一级组头 → 直属卡片 → 二级组头 → 二级卡片」', async () => {
  const vm = await makeVm(LINKS, TAGS)
  const shape = vm.rows.map((r) => r.kind + ':' + (r.kind === 'link' ? r.link.linkKey + (r.indent ? '+' : '') : r.key))
  assert.deepEqual(shape, [
    'group:一', 'link:EVID_4', 'sub:一/（一）', 'link:EVID_1+', 'link:EVID_2+', 'sub:一/（二）', 'link:EVID_3+',
    'group:二', 'sub:二/（一）', 'link:EVID_5+',
    'group:' + GROUP_NONE, 'link:EVID_6',
  ])
  assert.equal(new Set(vm.rows.map((r) => r.rowKey)).size, vm.rows.length, 'rowKey 必须唯一，否则 v-for 会串行')
})

test('组件：折叠一级组把它整棵子树都收掉，二级组可单独折叠', async () => {
  const vm = await makeVm(LINKS, TAGS)
  vm.toggleGroup('一')
  assert.deepEqual(vm.rows.filter((r) => r.kind === 'link').map((r) => r.link.linkKey), ['EVID_5', 'EVID_6'])
  vm.toggleGroup('一')
  vm.toggleGroup('一/（一）')
  assert.deepEqual(vm.rows.filter((r) => r.kind === 'link').map((r) => r.link.linkKey), ['EVID_4', 'EVID_3', 'EVID_5', 'EVID_6'])
})

test('组件：状态与章节两个筛选叠加；统计始终按未筛选的全量算', async () => {
  const vm = await makeVm(LINKS, TAGS)
  vm.setStatus('active')
  assert.deepEqual(vm.visibleLinks.map((l) => l.linkKey), ['EVID_1', 'EVID_4', 'EVID_6'])
  vm.sectionKey = '一'
  assert.deepEqual(vm.visibleLinks.map((l) => l.linkKey), ['EVID_1', 'EVID_4'])
  assert.deepEqual(vm.counts, { total: 6, active: 3, unverified: 1, stale: 1, orphan: 1 }, '筛选不许改统计')
})

test('组件：主体视图用主体筛选，分组按 PARTY 标签', async () => {
  const vm = await makeVm(LINKS, TAGS)
  vm.view = 'party'
  assert.deepEqual(vm.dimOptions.map((o) => o.key), ['all', 'party:1', 'party:2', GROUP_NONE])
  vm.partyKey = 'party:1'
  assert.deepEqual(vm.visibleLinks.map((l) => l.linkKey), ['EVID_1', 'EVID_4'])
  assert.deepEqual(vm.rows.map((r) => r.kind + ':' + (r.kind === 'link' ? r.link.linkKey : r.key)),
    ['group:party:1', 'link:EVID_1', 'link:EVID_4'])
})

test('组件：换文档后旧筛选键在新文档里不存在时落回「全部」，列表不会莫名全空', async () => {
  const vm = await makeVm(LINKS, TAGS)
  vm.sectionKey = '九/（九）'
  assert.equal(vm.dimKey, 'all')
  assert.equal(vm.dimIndex, 0)
  assert.equal(vm.visibleLinks.length, 6)
})

test('组件：视图/筛选按项目落盘并能读回（uni 存储不可用时静默用默认值）', async () => {
  const store = new Map()
  globalThis.uni = {
    setStorageSync: (k, v) => store.set(k, v),
    getStorageSync: (k) => store.get(k) || '',
  }
  try {
    const vm = await makeVm(LINKS, TAGS)
    await vm.setView('party')
    vm.setStatus('stale')
    vm.partyKey = 'party:2'
    vm.saveViewState()
    assert.deepEqual(JSON.parse(store.get('project_7_evidencePanelView')),
      { view: 'party', status: 'stale', sectionKey: 'all', partyKey: 'party:2' })

    const again = await makeVm(LINKS, TAGS)
    again.restoreViewState()
    assert.equal(again.view, 'party')
    assert.equal(again.status, 'stale')
    assert.equal(again.partyKey, 'party:2')

    // 存储抛异常（隐私模式/无 uni）不许把面板带崩
    globalThis.uni = { getStorageSync() { throw new Error('nope') }, setStorageSync() { throw new Error('nope') } }
    const third = await makeVm(LINKS, TAGS)
    third.restoreViewState()
    third.saveViewState()
    assert.equal(third.view, 'section')
    assert.equal(third.status, 'all')
  } finally {
    delete globalThis.uni
  }
})
