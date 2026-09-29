// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 工作台标签持久化（dev-board#1049，spec 2026-09-29-defer-login-welcome-tab-design §8）。
//
// 跑法：cd frontend && node --test tests/project-home/tab-snapshot.test.mjs（test:project-home 一起跑）
import test from 'node:test'
import assert from 'node:assert/strict'

import {
  TAB_SNAPSHOT_VERSION,
  TAB_SNAPSHOT_THROTTLE_MS,
  SINGLETON_TAB_TYPES,
  serialize,
  serializeTab,
  restore,
  mergeRestored,
  snapshotNeedsFileCheck,
  tabSnapshotMethods,
} from '../../src/pages/project-overview/tabSnapshot.js'

const doc = (id, name = `f${id}.docx`) => ({ id, name, fileType: 'docx', wpsFileId: 'w' + id, filePath: '/p/' + name, pendingLocator: { page: 3 }, _libre: {} })
const web = { id: 'web_1_abc', tabType: 'web', name: 'example.com', url: 'https://example.com/a' }
const welcome = { id: 'welcome', tabType: 'welcome', name: '欢迎' }
const calendar = { id: 'calendar', tabType: 'calendar', name: '日程', calendarFocus: '12', calendarGroup: 'today' }
const settings = { id: 'admin-settings', tabType: 'admin-settings', name: '设置', adminNav: 'team', adminService: '' }
const market = { id: 'market-detail_skill_x', tabType: 'market-detail', name: 'X', marketSpec: { kind: 'skill', id: 'x', name: 'X' } }
const artifact = { id: 'artifact-3', artifactId: 3, tabType: 'markdown', fileType: 'md', name: '计划.md', content: '# 很长' }
const merge = { id: 'merge-review_1_a.docx', tabType: 'merge-review', fileType: 'merge-review', name: '合并', mergeSpec: { path: 'a.docx' } }
const history = { id: 'commit-history_1', tabType: 'commit-history', name: '历史', historyFocus: 'remote', historyFocusSha: 'abc', historyFocusToken: 4 }
const diff = { id: 'diff-1-2-99', tabType: 'diff', fileType: 'diff', name: 'a ↔ b', diffSource: { id: 1, name: 'a' }, diffTarget: { id: 2, name: 'b' } }
const dd = { id: 'dd-7', requestId: 7, name: '清单', type: 'dd-request', fileType: 'dd', isFolder: false }

// ---------------- 序列化 ----------------

test('serialize：白名单字段、一次性深链不存、不可恢复的标签丢掉；结果可 JSON 往返', () => {
  const snap = serialize([doc(1), web, artifact, merge, calendar, history], [settings], 1, 'admin-settings', { splitMode: true, focusedPane: 'right' })
  assert.equal(snap.v, TAB_SNAPSHOT_VERSION)
  assert.deepEqual(snap.left.map((t) => t.id), [1, 'web_1_abc', 'calendar', 'commit-history_1'], 'AI 产物与合并比对稿不进快照')
  assert.deepEqual(snap.left[0], { id: 1, name: 'f1.docx', fileType: 'docx', wpsFileId: 'w1', filePath: '/p/f1.docx' })
  assert.deepEqual(snap.left[2], { id: 'calendar', tabType: 'calendar', name: '日程' }, '日程深链不存')
  assert.deepEqual(snap.left[3], { id: 'commit-history_1', tabType: 'commit-history', name: '历史' }, '提交历史的定位不存')
  assert.deepEqual(snap.right[0], { id: 'admin-settings', tabType: 'admin-settings', name: '设置', adminNav: 'team', adminService: '' })
  assert.equal(snap.activeLeft, 1)
  assert.equal(snap.activeRight, 'admin-settings')
  assert.equal(snap.splitMode, true)
  assert.equal(snap.focusedPane, 'right')
  assert.deepEqual(JSON.parse(JSON.stringify(snap)), snap)
})

test('serialize：激活的是被丢掉的标签时记 null；未分屏焦点恒为 left', () => {
  const snap = serialize([doc(1), artifact], [], 'artifact-3', null, { splitMode: false, focusedPane: 'right' })
  assert.equal(snap.activeLeft, null)
  assert.equal(snap.focusedPane, 'left')
})

test('serializeTab：认不出来的 tabType、插件标签、没有 url 的网页、文件夹一律不存（向前兼容）', () => {
  assert.equal(serializeTab({ id: 'x', tabType: 'future-thing' }), null)
  assert.equal(serializeTab({ id: 'plugin-1', fileType: 'plugin' }), null)
  assert.equal(serializeTab({ id: 'web_2', tabType: 'web', url: '' }), null)
  assert.equal(serializeTab({ id: 9, isFolder: true }), null)
  assert.equal(serializeTab({ id: 'abc', name: '非数字 id 的无类型标签' }), null)
  assert.deepEqual(serializeTab(dd), { id: 'dd-7', requestId: 7, name: '清单', type: 'dd-request', fileType: 'dd', isFolder: false })
})

// ---------------- 恢复 ----------------

const exists = (ids) => (id) => ids.includes(Number(id))

test('restore：不存在的文件标签静默丢弃；对比标签两份都在才留', () => {
  const snap = serialize([doc(1), doc(2), doc(3), diff], [], 2, null, {})
  const r = restore(snap, { fileExists: exists([1, 3]) })
  assert.deepEqual(r.leftFiles.map((t) => t.id), [1, 3], 'id 2 已删：文件标签与引用它的对比标签都丢')
  assert.equal(r.activeLeft, 1, '激活的那份没了，落到第一个')
})

test('restore：resolveFile 刷新名字（改名之后不陈旧），id 形态保留；一次性字段补默认值', () => {
  const snap = serialize([doc(1), calendar, history], [], 1, null, {})
  const r = restore(snap, {
    fileExists: () => true,
    resolveFile: (id) => ({ id: Number(id), name: '改名后.docx', fileType: 'docx', wpsFileId: 'w1', filePath: '/p/改名后.docx' }),
  })
  assert.equal(r.leftFiles[0].name, '改名后.docx')
  assert.equal(r.leftFiles[0].id, 1)
  assert.equal(r.leftFiles[0].pendingLocator, null)
  assert.equal(r.leftFiles[1].calendarFocus, '')
  assert.equal(r.leftFiles[1].calendarGroup, '')
  assert.equal(r.leftFiles[2].historyFocusToken, 0)
})

test('restore：拿不到文件清单（fileExists 未传）时文件标签原样保留', () => {
  const r = restore(serialize([doc(1), doc(2)], [], 2, null, {}), {})
  assert.deepEqual(r.leftFiles.map((t) => t.id), [1, 2])
  assert.equal(r.activeLeft, 2)
})

test('restore：单例标签跨两窗格按 id 去重（先左后右）；同一窗格重复 id 只留第一个', () => {
  const snap = {
    v: TAB_SNAPSHOT_VERSION,
    left: [welcome, calendar, doc(1), doc(1), settings],
    right: [calendar, welcome, market, doc(1)],
    activeLeft: 'calendar',
    activeRight: 'calendar',
    splitMode: true,
    focusedPane: 'right',
  }
  const r = restore(snap, { fileExists: () => true })
  assert.deepEqual(r.leftFiles.map((t) => t.id), ['welcome', 'calendar', 1, 'admin-settings'])
  assert.deepEqual(r.rightFiles.map((t) => t.id), ['market-detail_skill_x', 1], '普通文件可以左右双开（Alt 拖拽），不去重')
  assert.equal(r.activeRight, 'market-detail_skill_x', '右侧激活的日程被去重掉，落到右侧第一个')
  for (const type of ['welcome', 'calendar', 'admin-settings', 'market-detail']) assert.ok(SINGLETON_TAB_TYPES.includes(type))
})

test('restore：无项目态只恢复全局标签（欢迎 / 日程 / 设置 / 插件详情 / 网页）', () => {
  const snap = serialize([doc(1), welcome, calendar, settings, market, web, history, diff, dd], [], 'calendar', null, {})
  const r = restore(snap, { hasProject: false, fileExists: () => true })
  assert.deepEqual(r.leftFiles.map((t) => t.tabType), ['welcome', 'calendar', 'admin-settings', 'market-detail', 'web'])
  assert.equal(r.activeLeft, 'calendar')
})

test('restore：未分屏时右窗格的标签并进左侧；分屏状态与焦点照恢复', () => {
  const snap = { v: TAB_SNAPSHOT_VERSION, left: [doc(1)], right: [doc(2)], activeLeft: null, activeRight: 2, splitMode: false, focusedPane: 'right' }
  const r = restore(snap, { fileExists: () => true })
  assert.deepEqual(r.leftFiles.map((t) => t.id), [1, 2])
  assert.deepEqual(r.rightFiles, [])
  assert.equal(r.activeLeft, 2)
  assert.equal(r.splitMode, false)
  assert.equal(r.focusedPane, 'left')
  const split = restore({ ...snap, splitMode: true }, { fileExists: () => true })
  assert.equal(split.splitMode, true)
  assert.equal(split.focusedPane, 'right')
  assert.equal(split.activeRight, 2)
})

test('restore：快照缺失、版本不认识、形状坏了一律返回 null / 空，不抛', () => {
  assert.equal(restore(null), null)
  assert.equal(restore('oops'), null)
  assert.equal(restore({ v: 999, left: [doc(1)] }), null)
  const r = restore({ v: TAB_SNAPSHOT_VERSION, left: 'x', right: null })
  assert.deepEqual(r.leftFiles, [])
  assert.equal(r.activeLeft, null)
})

test('restore：快照里的对象也过白名单（手改 storage / 旧版本多出来的字段不带进模板）', () => {
  const snap = { v: TAB_SNAPSHOT_VERSION, left: [{ ...doc(1), evil: 1, content: 'x' }], right: [] }
  const r = restore(snap, { fileExists: () => true })
  assert.equal('evil' in r.leftFiles[0], false)
  assert.equal('_libre' in r.leftFiles[0], false)
})

test('snapshotNeedsFileCheck：只有文件 / 对比标签才需要那一个 GET', () => {
  assert.equal(snapshotNeedsFileCheck(serialize([welcome, calendar, web], [], null, null, {})), false)
  assert.equal(snapshotNeedsFileCheck(serialize([welcome, doc(1)], [], null, null, {})), true)
  assert.equal(snapshotNeedsFileCheck(serialize([diff], [], null, null, {})), true)
  assert.equal(snapshotNeedsFileCheck(null), false)
})

test('mergeRestored：恢复期间已经开了的标签排在后面、保持激活；与恢复的重复只留一份', () => {
  const restored = restore(serialize([doc(1), welcome], [], 'welcome', null, {}), { fileExists: () => true })
  const merged = mergeRestored(restored, {
    leftFiles: [doc(5), { ...doc(1) }], rightFiles: [], activeLeft: 5, activeRight: null, splitMode: false, focusedPane: 'left',
  })
  assert.deepEqual(merged.leftFiles.map((t) => t.id), [1, 'welcome', 5])
  assert.equal(merged.activeLeft, 5)
  const idle = mergeRestored(restored, { leftFiles: [], rightFiles: [], activeLeft: null, activeRight: null })
  assert.equal(idle.activeLeft, 'welcome', '恢复期间什么都没开：用快照的激活标签（快照优先于 activeTabsByMode 的旧记忆）')
})

// ---------------- 写入：节流 / 同步写 / 恢复前不写 ----------------

function fakeVm(overrides = {}) {
  const store = new Map()
  const writes = []
  const vm = {
    projectId: 7,
    hasProject: true,
    leftFiles: [],
    rightFiles: [],
    activeFileIdLeft: null,
    activeFileIdRight: null,
    splitMode: false,
    focusedPane: 'left',
    $t: (k) => 'T:' + k,
    $tabStorage: {
      getStorageSync: (k) => store.get(k),
      setStorageSync: (k, v) => { writes.push(k); store.set(k, JSON.parse(JSON.stringify(v))) },
    },
    store,
    writes,
    ...overrides,
  }
  for (const [k, fn] of Object.entries(tabSnapshotMethods)) vm[k] = fn.bind(vm)
  return vm
}

test('键：有项目 project_${id}_tabs，无项目 global_tabs', () => {
  assert.equal(fakeVm().tabSnapshotKey(), 'project_7_tabs')
  assert.equal(fakeVm({ projectId: null, hasProject: false }).tabSnapshotKey(), 'global_tabs')
})

test('恢复完成前不写（否则恢复窗口里的空标签条先把快照覆盖掉）；flush 同步写、节流合并成一次', async () => {
  const vm = fakeVm()
  vm.scheduleTabSnapshotSave()
  vm.flushTabSnapshot()
  assert.deepEqual(vm.writes, [], '恢复之前一次都不写')
  await vm.restoreTabSnapshot()
  vm.flushTabSnapshot()
  assert.equal(vm.writes.length, 1, 'flush 同步写一次并撤掉节流中的那次')
  vm.leftFiles.push(doc(1))
  vm.scheduleTabSnapshotSave()
  vm.scheduleTabSnapshotSave()
  vm.scheduleTabSnapshotSave()
  await new Promise((r) => setTimeout(r, TAB_SNAPSHOT_THROTTLE_MS + 50))
  assert.equal(vm.writes.length, 2, '300ms 内三次变化合成一次写')
  assert.deepEqual(vm.store.get('project_7_tabs').left.map((t) => t.id), [1])
})

test('restoreTabSnapshot：文件核对走宿主的 fetchTabSnapshotFileIndex，单例名字按当前语言重取，激活标签以快照为准', async () => {
  const vm = fakeVm()
  vm.store.set('project_7_tabs', serialize([doc(1), doc(2), calendar], [], 1, null, {}))
  let fetched = 0
  vm.fetchTabSnapshotFileIndex = async () => { fetched++; return new Map([['1', { id: 1, name: 'f1.docx', fileType: 'docx' }]]) }
  vm.activeFileIdLeft = 99 // activeTabsByMode 的旧记忆（指向一个已经不在标签条里的 id）
  const restored = await vm.restoreTabSnapshot()
  assert.equal(restored, true)
  assert.equal(fetched, 1)
  assert.deepEqual(vm.leftFiles.map((t) => t.id), [1, 'calendar'])
  assert.equal(vm.leftFiles[1].name, 'T:calendar.tabName')
  assert.equal(vm.activeFileIdLeft, 1)
})

test('restoreTabSnapshot：清单拉失败不核对（文件标签原样保留），没有快照返回 false', async () => {
  const vm = fakeVm()
  vm.store.set('project_7_tabs', serialize([doc(1)], [], 1, null, {}))
  vm.fetchTabSnapshotFileIndex = async () => { throw new Error('offline') }
  assert.equal(await vm.restoreTabSnapshot(), true)
  assert.deepEqual(vm.leftFiles.map((t) => t.id), [1])
  const empty = fakeVm({ projectId: null, hasProject: false })
  assert.equal(await empty.restoreTabSnapshot(), false)
  assert.deepEqual(empty.leftFiles, [])
})

test('restoreTabSnapshot：只有全局标签的快照不发文件清单那个 GET', async () => {
  const vm = fakeVm()
  vm.store.set('project_7_tabs', serialize([welcome, web], [], 'welcome', null, {}))
  let fetched = 0
  vm.fetchTabSnapshotFileIndex = async () => { fetched++; return new Map() }
  await vm.restoreTabSnapshot()
  assert.equal(fetched, 0)
  assert.deepEqual(vm.leftFiles.map((t) => t.id), ['welcome', 'web_1_abc'])
})
