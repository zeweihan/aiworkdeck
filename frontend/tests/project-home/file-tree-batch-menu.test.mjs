// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// v0.49.0 真机批次 T（report-C9）：资源管理器四条缺陷的护栏。跑法：npm run test:project-home
//
// BUG-73（dev-board#969，C9-04）批量选择勾两份后右键仍是单文件菜单；⌘+点击打开文件而不是加选；
//   菜单里找不到「对比文档」。根因两条：
//   ① uni-h5 把 <view> 上的 click 重建成普通对象，只补坐标，metaKey/ctrlKey 全丢
//      （@dcloudio/uni-h5 的 normalizeClickEvent）——handleItemClick 读 event.metaKey 恒为
//      undefined，⌘+点击一路走单选分支 emit file-select 打开文件；
//   ② 批量选择态的勾选存在 checkedMap，右键只看 multiSelectedIds，于是把它重置成
//      [当前项]，菜单与「对比文档」判定都只剩一份。
// BUG-74（dev-board#970，C9-05）「管理标签」「标签管理」按 Esc 不关。
// BUG-75（dev-board#971，C9-06）新建标签表单 .color-row 17 个色块不换行越出表单。
// BUG-80（dev-board#976，C9-11）右键「复制」其实是建副本，还写剪贴板，uni.setClipboardData
//   自带的「Content copied」toast 与我们自己的「已复制并创建副本」叠成两条。
//
// 复用 file-tree-delete-dialog-esc.test.mjs 的抠脚本套路：把 FileTree.vue 的 <script> 抠出来真跑。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { collapseToTopmostSelected, summarizeDeleteResults } from '../../src/utils/fileTreeRecycle.js'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')
const TAG_SELECTOR = readFileSync(new URL('../../src/components/TagSelector.vue', import.meta.url), 'utf8')
const ZH = readFileSync(new URL('../../src/locales/zh-CN/fileTree.js', import.meta.url), 'utf8')
const EN = readFileSync(new URL('../../src/locales/en-US/fileTree.js', import.meta.url), 'utf8')

const IMPORT_NAMES = [
  'getProjectFiles', 'createFolder', 'createFile', 'renameFile', 'deleteFile', 'deleteFilePerm',
  'restoreFileApi', 'getRecycleBinFiles', 'moveFile', 'batchDeleteFiles', 'batchMoveFiles',
  'batchCopyFiles', 'getApiBaseUrl', 'getContributedTemplates', 'createFileFromContributedTemplate',
  'getSessionId', 'host', 'findTopmostDeletedAncestor', 'summarizeDeleteResults', 'collapseToTopmostSelected',
  'groupByParent', 'buildTreeFromGroups', 'evidenceRefCounts', 'createRefCountsFetcher',
  'warmDragImage', 'applyDragImage', 'FileTypeIcon', 'TagChip', 'TagSelector',
  'TagManager', 'AwdDatePicker', 'ICONS', 'getProjectTags', 'addTagToFile', 'removeTagFromFile',
  'createTag', 'createTask', 'importLocalFile',
  'nativeDataTransfer', 'isExternalFileDrag', 'claimExternalDrop', 'isAudioFileName',
  'showDialog',
]

const FILES = [
  { id: 1, name: '合同', isFolder: true, parentId: null },
  { id: 11, name: '复杂版式样稿.docx', fileType: 'docx', isFolder: false, parentId: 1 },
  { id: 12, name: 'QA-合同样稿.docx', fileType: 'docx', isFolder: false, parentId: 1 },
  { id: 13, name: 'README.txt', fileType: 'txt', isFolder: false, parentId: null },
]

function makeVm({ replies = [] } = {}) {
  const calls = { batchDeleteFiles: [], batchCopyFiles: [], batchMoveFiles: [], deleteFilePerm: [], dialogs: [], emits: [], toasts: [], clipboard: [] }
  const queue = [...replies]
  const stubs = {
    batchDeleteFiles: async (...a) => { calls.batchDeleteFiles.push(a) },
    batchCopyFiles: async (...a) => { calls.batchCopyFiles.push(a) },
    batchMoveFiles: async (...a) => { calls.batchMoveFiles.push(a) },
    deleteFile: async () => {},
    deleteFilePerm: async (...a) => { calls.deleteFilePerm.push(a) },
    collapseToTopmostSelected,
    summarizeDeleteResults,
    showDialog: async (opts) => {
      calls.dialogs.push(opts)
      return queue.length ? queue.shift() : { confirm: false, cancel: true }
    },
    ICONS: {},
    host: {},
  }
  const body = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+['"][^'"]+['"]\s*$/gm, '')
    .replace(/export default \{/, 'return {')
  const options = new Function(...IMPORT_NAMES, body)(...IMPORT_NAMES.map(n => (n in stubs ? stubs[n] : (() => {}))))
  const vm = Object.assign({}, options.data.call({}))
  for (const [k, fn] of Object.entries(options.methods)) vm[k] = fn.bind(vm)
  vm.projectId = 42
  vm.showTree = true
  vm.selectionMode = false
  vm.viewMode = 'files'
  vm.allFiles = FILES
  vm.files = FILES
  vm.expandedFolders = new Set([1])
  vm.recycleBin = []
  Object.defineProperty(vm, 'checkedIds', {
    get() { return Object.keys(vm.checkedMap).filter(k => vm.checkedMap[k]).map(Number) },
  })
  vm.$t = (k, params) => (params ? `${k}:${JSON.stringify(params)}` : k)
  vm.$emit = (...a) => { calls.emits.push(a) }
  vm.loadFiles = async () => {}
  vm.clearChecked = () => { vm.checkedMap = {} }
  vm.showErrorModal = (msg) => { throw new Error('unexpected error modal: ' + msg) }
  vm.calls = calls
  globalThis.uni = {
    showToast(o) { calls.toasts.push(o) },
    setClipboardData(o) { calls.clipboard.push(o) },
    $emit() {}, $on() {}, $off() {},
  }
  return vm
}

const rightClick = () => ({ preventDefault() {}, stopPropagation() {}, clientX: 10, clientY: 20 })
const tick = () => new Promise((r) => setTimeout(r, 0))

// ---------------- BUG-73 ----------------

test('BUG-73 ⌘+点击：uni 重建的事件对象没有 metaKey，要回退读 window.event，加选而不是打开文件', () => {
  const vm = makeVm()
  vm.handleItemClick(FILES[1], {}) // 普通点击：单选
  vm.lastClickTime = 0
  globalThis.window = { event: { type: 'click', metaKey: true, ctrlKey: false } }
  try {
    vm.handleItemClick(FILES[2], { preventDefault() {}, stopPropagation() {} })
  } finally {
    delete globalThis.window
  }
  assert.deepEqual(vm.multiSelectedIds, [11, 12], '⌘+点击应把第二份加进多选')
  const opens = vm.calls.emits.filter(e => e[0] === 'file-select')
  assert.equal(opens.length, 1, '只有第一次普通点击打开文件，⌘+点击不能再打开')
})

test('BUG-73 复核：按住 ⌘ 按 ArrowDown 仍是单选移动，不加选（window.event 是键盘事件，不能当 ⌘+点击）', () => {
  const vm = makeVm()
  vm.files = [FILES[1], FILES[2]]
  vm.handleItemClick(FILES[1], {})
  vm.lastClickTime = 0
  globalThis.window = { event: { type: 'keydown', key: 'ArrowDown', metaKey: true, ctrlKey: false } }
  try {
    vm.handleKeyDown({ key: 'ArrowDown', preventDefault() {} })
  } finally {
    delete globalThis.window
  }
  assert.deepEqual(vm.multiSelectedIds, [12], '键盘移动是单选')
  const opens = vm.calls.emits.filter(e => e[0] === 'file-select').map(e => e[1].id)
  assert.deepEqual(opens, [11, 12], '方向键移动照常 file-select')
})

test('BUG-73 批量选择态勾两份 docx 后右键：菜单进入多选态（已选 2 项），对比文档可用', () => {
  const vm = makeVm()
  vm.selectionMode = true
  vm.checkedMap = { 11: true, 12: true }
  vm.handleContextMenu(FILES[1], rightClick())
  assert.equal(vm.isContextMulti(), true)
  assert.deepEqual(vm.contextMenu.selectionIds.map(Number), [11, 12])
  assert.equal(vm.canCompareDocuments(), true, '恰好两份 docx 要给「对比文档」')
  vm.startDocumentCompare()
  const cmp = vm.calls.emits.find(e => e[0] === 'compare-documents')
  assert.ok(cmp, '对比文档接到既有的 compare-documents 事件')
  assert.deepEqual(cmp[1].map(f => f.id), [11, 12])
})

test('BUG-73 在未勾选的那一项上右键：仍是单文件菜单', () => {
  const vm = makeVm()
  vm.selectionMode = true
  vm.checkedMap = { 11: true, 12: true }
  vm.handleContextMenu(FILES[3], rightClick())
  assert.equal(vm.isContextMulti(), false)
})

test('BUG-73 ⌘ 多选两份后右键其中一份：多选菜单', () => {
  const vm = makeVm()
  vm.multiSelectedIds = [11, 12]
  vm.handleContextMenu(FILES[2], rightClick())
  assert.equal(vm.isContextMulti(), true)
  assert.equal(vm.canCompareDocuments(), true)
})

test('BUG-73 多选菜单隐藏单文件项（重命名/历史/在访达中显示/管理标签/发送/下载/转写/事项），有「已选 N 项」标题', () => {
  const tpl = SRC.slice(SRC.indexOf('class="context-menu"'), SRC.indexOf('class="context-menu-item context-menu-item-danger"') + 400)
  for (const key of ['fileTree.rename', 'fileTree.fileHistory', 'fileTree.revealInFinder', 'fileTree.manageTags',
    'fileTree.sendFile', 'fileTree.download', 'fileTree.transcribe', 'calendar.fileAddTask', 'calendar.fileViewTasks']) {
    const at = tpl.indexOf(`$t('${key}'`)
    assert.ok(at > 0, '菜单里找不到 ' + key)
    const itemStart = tpl.lastIndexOf('<view v-if="', at)
    const vif = tpl.slice(itemStart, tpl.indexOf('"', itemStart + 12))
    assert.match(vif, /!isContextMulti\(\)/, key + ' 是单文件项，多选时必须隐藏')
  }
  assert.match(tpl, /v-if="isContextMulti\(\)"[^>]*class="context-menu-header"/, '多选时要有标题行')
  assert.match(tpl, /fileTree\.selectedCount/, '标题行文案 fileTree.selectedCount')
  assert.match(ZH, /selectedCount: '已选 \{count\} 项'/)
  assert.match(EN, /selectedCount: '\{count\} selected'/)
})

test('BUG-73 多选删除：确认框写明数量，取消不删；确认后一次删除全部选中项', async () => {
  const vm = makeVm()
  vm.multiSelectedIds = [11, 12]
  vm.handleContextMenu(FILES[1], rightClick())
  await vm.handleContextDelete()
  assert.equal(vm.calls.dialogs.length, 1)
  assert.equal(vm.calls.dialogs[0].danger, true)
  assert.match(vm.calls.dialogs[0].content, /"count":2/)
  assert.equal(vm.calls.batchDeleteFiles.length, 0, '取消（Esc）不能删')

  const vm2 = makeVm({ replies: [{ confirm: true, cancel: false }] })
  vm2.multiSelectedIds = [11, 12]
  vm2.handleContextMenu(FILES[1], rightClick())
  await vm2.handleContextDelete()
  assert.equal(vm2.calls.batchDeleteFiles.length, 1)
  assert.deepEqual(vm2.calls.batchDeleteFiles[0][1].map(Number), [11, 12])
})

test('BUG-73 多选「加入 AI 对话」「创建副本」作用于全部选中项（文件夹连子孙勾选时只算文件夹本身）', async () => {
  const vm = makeVm()
  vm.selectionMode = true
  vm.checkedMap = { 1: true, 11: true, 12: true, 13: true } // 勾了文件夹「合同」= 连子孙
  vm.handleContextMenu(FILES[3], rightClick())
  vm.handleContextAddToAi()
  const addEmits = vm.calls.emits.filter(e => e[0] === 'add-to-ai')
  assert.equal(addEmits.length, 1, '多选时整组一次交给宿主，宿主只弹一条汇总提示')
  const added = addEmits[0][1].map(f => f.id)
  assert.deepEqual(added.sort(), [1, 13], '文件夹带子孙时只加文件夹本身，不重复加子文件')

  vm.handleContextMenu(FILES[3], rightClick())
  await vm.handleContextDuplicate()
  const copied = vm.calls.batchCopyFiles.flatMap(c => c[1]).map(Number).sort()
  assert.deepEqual(copied, [1, 13])
  assert.equal(vm.calls.toasts.length, 1, '批量建副本只弹一条')
  assert.equal(vm.calls.clipboard.length, 0, '建副本不写剪贴板')
})

// ---------------- BUG-80 ----------------

test('BUG-80 右键「创建副本」：只建副本，不写剪贴板，只弹一条 i18n toast', async () => {
  const vm = makeVm()
  vm.handleContextMenu(FILES[3], rightClick())
  await vm.handleContextDuplicate()
  assert.equal(vm.calls.batchCopyFiles.length, 1)
  assert.deepEqual(vm.calls.batchCopyFiles[0].slice(1), [[13], null], '副本建在原文件同目录')
  assert.equal(vm.calls.clipboard.length, 0, '不能再调 uni.setClipboardData（它自带英文「Content copied」toast）')
  assert.equal(vm.calls.toasts.length, 1)
  assert.equal(vm.calls.toasts[0].title, 'fileTree.duplicateCreated')
})

test('BUG-80 菜单项叫「创建副本」，两个语言包都有键，旧的「复制/已复制并创建副本」不再使用', () => {
  assert.match(SRC, /\$t\('fileTree\.duplicate'\)/)
  assert.doesNotMatch(SRC, /\$t\('fileTree\.copy'\)/)
  assert.doesNotMatch(SRC, /copiedAndDuplicated/)
  assert.doesNotMatch(SRC, /uni\.setClipboardData\(/)
  assert.match(ZH, /duplicate: '创建副本'/)
  assert.match(EN, /duplicate: 'Duplicate'/)
  assert.match(ZH, /duplicateCreated: '已创建副本'/)
  assert.match(EN, /duplicateCreated: 'Duplicate created'/)
})

// ---------------- BUG-74 ----------------

function esc() {
  const e = { key: 'Escape', defaultPrevented: false, isComposing: false, preventDefault() { this.defaultPrevented = true }, stopPropagation() {} }
  return e
}

test('BUG-74「管理标签」弹窗按 Esc 关闭（输入框里有字也一样）', () => {
  const vm = makeVm()
  vm.showTagEditDialog = true
  vm.$refs = { tagSelector: { isCreatingTag: false, searchText: '甲方', cancelCreate() {} } }
  globalThis.document = { querySelector: () => null }
  try { vm.onTagDialogKeydown(esc()) } finally { delete globalThis.document }
  assert.equal(vm.showTagEditDialog, false)
})

test('BUG-74「标签管理」叠在「管理标签」上时 Esc 先关上层', () => {
  const vm = makeVm()
  vm.showTagEditDialog = true
  vm.showTagManager = true
  globalThis.document = { querySelector: () => null }
  try { vm.onTagDialogKeydown(esc()) } finally { delete globalThis.document }
  assert.equal(vm.showTagManager, false)
  assert.equal(vm.showTagEditDialog, true)
})

test('BUG-74 新建标签子表单开着时 Esc 先退回（等于点取消），再按一次才关弹窗', () => {
  const vm = makeVm()
  vm.showTagEditDialog = true
  let cancelled = 0
  const sel = { isCreatingTag: true, cancelCreate() { cancelled++; this.isCreatingTag = false } }
  vm.$refs = { tagSelector: sel }
  globalThis.document = { querySelector: () => null }
  try {
    vm.onTagDialogKeydown(esc())
    assert.equal(cancelled, 1)
    assert.equal(vm.showTagEditDialog, true)
    vm.onTagDialogKeydown(esc())
    assert.equal(vm.showTagEditDialog, false)
  } finally { delete globalThis.document }
})

test('BUG-74 删除标签的确认框（AwdDialog）开着时让它先处理 Esc，不连带关掉标签管理', () => {
  const vm = makeVm()
  vm.showTagManager = true
  globalThis.document = { querySelector: (s) => (s === '.awd-dlg-mask' ? {} : null) }
  try { vm.onTagDialogKeydown(esc()) } finally { delete globalThis.document }
  assert.equal(vm.showTagManager, true)
})

test('BUG-74 键盘监听在 mounted 挂上、beforeUnmount 摘掉；TagSelector 带 ref', () => {
  const mounted = SRC.slice(SRC.indexOf('  mounted() {'), SRC.indexOf('  beforeUnmount() {'))
  const unmount = SRC.slice(SRC.indexOf('  beforeUnmount() {'), SRC.indexOf('  methods: {'))
  assert.match(mounted, /window\.addEventListener\('keydown', this\._onTagDialogKeydown, true\)/)
  assert.match(unmount, /window\.removeEventListener\('keydown', this\._onTagDialogKeydown, true\)/)
  assert.match(SRC, /<TagSelector\s+ref="tagSelector"/)
})

// ---------------- BUG-75 ----------------

test('BUG-75 新建标签表单 .color-row 换行，不越出表单', () => {
  const css = TAG_SELECTOR.slice(TAG_SELECTOR.indexOf('<style'))
  const m = css.match(/\n\.color-row \{([^}]*)\}/)
  assert.ok(m, '找不到 .color-row 规则')
  assert.match(m[1], /flex-wrap: wrap;/)
})

// ---------------- BUG-73 复核：多选「加入 AI 对话」宿主只弹一条汇总 ----------------

const OVERVIEW = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const ZH_WB = readFileSync(new URL('../../src/locales/zh-CN/workbench.js', import.meta.url), 'utf8')
const EN_WB = readFileSync(new URL('../../src/locales/en-US/workbench.js', import.meta.url), 'utf8')

function makeHost() {
  const start = OVERVIEW.indexOf('    addDraggedFileToAiContext(file')
  const end = OVERVIEW.indexOf('    /** 已打开的标签（两侧窗格）按 id 查一条')
  assert.ok(start > 0 && end > start, '找不到宿主的两个方法')
  const methods = new Function('countDescendantFiles', 'AI_CONTEXT_FOLDER_FILE_LIMIT',
    'return {' + OVERVIEW.slice(start, end) + '}')(() => 0, 10)
  const toasts = []
  const added = []
  const host = { $t: (k, p) => (p ? `${k}:${JSON.stringify(p)}` : k), $refs: {} }
  const chat = { addFile(f) { added.push(f.id) } }
  host.$refs.chatInterface = chat
  host.resolveChatInterface = async () => chat
  for (const [k, fn] of Object.entries(methods)) host[k] = fn.bind(host)
  globalThis.uni = { showToast(o) { toasts.push(o) } }
  return { host, toasts, added }
}

test('BUG-73 复核：多选加入 AI 对话，宿主逐个挂进上下文但只弹一条汇总提示', async () => {
  const { host, toasts, added } = makeHost()
  await host.onAddFileToAiContext([{ id: 11, name: 'a.docx' }, { id: 12, name: 'b.docx' }])
  assert.deepEqual(added, [11, 12])
  assert.equal(toasts.length, 1, '多选时 showToast 只调一次')
  assert.equal(toasts[0].title, 'workbench.filesAdded:{"count":2}')
  assert.match(ZH_WB, /filesAdded: '已加入 \{count\} 个文件'/)
  assert.match(EN_WB, /filesAdded: 'Added \{count\} files'/)
})

test('BUG-73 复核：单项加入 AI 对话保持原提示', async () => {
  const { host, toasts, added } = makeHost()
  await host.onAddFileToAiContext({ id: 13, name: 'README.txt' })
  assert.deepEqual(added, [13])
  assert.equal(toasts.length, 1)
  assert.equal(toasts[0].title, 'workbench.fileAdded:{"name":"README.txt"}')
})

// ---------------- dev-board#988：勾文件夹后取消其中一个子文件 ----------------
// 勾文件夹会把自身连子孙一起写进 checkedMap；以前取消其中一个子文件只删那一项，文件夹自己的 id
// 还留着——界面是半选，批量删除 / 创建副本 / 加入 AI 对话却按「整个文件夹」作用（软删与副本都是
// 后端按文件夹级联），把用户特意取消的那份也带上了。复核探针：batchDelete ids=[[1,12]]。

const DEEP = [
  { id: 1, name: '合同', isFolder: true, parentId: null },
  { id: 11, name: 'a.docx', fileType: 'docx', isFolder: false, parentId: 1 },
  { id: 12, name: 'b.docx', fileType: 'docx', isFolder: false, parentId: 1 },
  { id: 2, name: '附件', isFolder: true, parentId: 1 },
  { id: 21, name: 'c.docx', fileType: 'docx', isFolder: false, parentId: 2 },
  { id: 22, name: 'd.docx', fileType: 'docx', isFolder: false, parentId: 2 },
  { id: 13, name: 'README.txt', fileType: 'txt', isFolder: false, parentId: null },
]

function selectionVm(files = FILES, replies = []) {
  const vm = makeVm({ replies })
  vm.allFiles = files
  vm.files = files
  vm.selectionMode = true
  return vm
}

test('#988 勾文件夹再取消一个子文件：文件夹半选且不再作为操作根，批量删除只删剩下的子文件', async () => {
  const vm = selectionVm(FILES, [{ confirm: true, cancel: false }])
  vm.toggleChecked(FILES[0]) // 勾「合同」= 1, 11, 12
  vm.toggleChecked(FILES[1]) // 取消 11
  assert.equal(vm.getCheckState(FILES[0]), 'indeterminate')
  assert.deepEqual(vm.checkedIds.sort(), [12], '半选的文件夹自己不能留在勾选集合里')

  vm.openBatchAction('delete')
  await tick(); await tick()
  assert.equal(vm.calls.dialogs.length, 1)
  assert.match(vm.calls.dialogs[0].content, /"count":1/, '确认框数量 = 实际作用数')
  assert.equal(vm.calls.batchDeleteFiles.length, 1)
  assert.deepEqual(vm.calls.batchDeleteFiles[0][1].map(Number), [12], '不能带上文件夹 1（后端级联会删掉被取消的 11）')
})

test('#988 多级：取消孙文件后，所有祖先都退出勾选；右键创建副本 / 加入 AI 对话不含被取消项与半选祖先，计数一致', async () => {
  const vm = selectionVm(DEEP)
  vm.toggleChecked(DEEP[0]) // 勾「合同」= 1, 11, 12, 2, 21, 22
  vm.toggleChecked(DEEP[5]) // 取消 22
  assert.equal(vm.getCheckState(DEEP[0]), 'indeterminate')
  assert.equal(vm.getCheckState(DEEP[3]), 'indeterminate')
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [11, 12, 21])

  vm.handleContextMenu(DEEP[1], rightClick())
  assert.equal(vm.isContextMulti(), true)
  assert.equal(vm.contextMenu.selectionIds.length, vm.checkedIds.length, '「已选 N 项」= 底部工具栏计数')
  vm.handleContextAddToAi()
  const added = vm.calls.emits.find(e => e[0] === 'add-to-ai')[1].map(f => f.id).sort((a, b) => a - b)
  assert.deepEqual(added, [11, 12, 21])

  vm.handleContextMenu(DEEP[1], rightClick())
  await vm.handleContextDuplicate()
  const copied = vm.calls.batchCopyFiles.flatMap(c => c[1]).map(Number).sort((a, b) => a - b)
  assert.deepEqual(copied, [11, 12, 21], '副本不能按整个文件夹建')
})

test('#988 取消子文件夹时，勾选中的父文件夹同样退出；子文件夹之外的兄弟仍勾着', () => {
  const vm = selectionVm(DEEP)
  vm.toggleChecked(DEEP[0])
  vm.toggleChecked(DEEP[3]) // 取消「附件」整棵
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [11, 12])
  assert.equal(vm.getCheckState(DEEP[0]), 'indeterminate')
})

test('#988 整个文件夹勾着（未取消任何子项）时仍只按文件夹本身作用', () => {
  const vm = selectionVm(DEEP)
  vm.toggleChecked(DEEP[0])
  vm.toggleChecked(DEEP[6]) // 再勾根下的 README
  vm.handleContextMenu(DEEP[6], rightClick())
  vm.handleContextAddToAi()
  const added = vm.calls.emits.find(e => e[0] === 'add-to-ai')[1].map(f => f.id).sort((a, b) => a - b)
  assert.deepEqual(added, [1, 13])
})

test('#988 子项取消后再勾回：文件夹显示已勾，但集合不含文件夹 id，操作只作用于子项', async () => {
  const vm = selectionVm(FILES)
  vm.toggleChecked(FILES[0])
  vm.toggleChecked(FILES[1])
  assert.equal(vm.getCheckState(FILES[0]), 'indeterminate')
  vm.toggleChecked(FILES[1])
  assert.equal(vm.getCheckState(FILES[0]), 'checked', '子项勾满时显示已勾（三态树惯例）')
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [11, 12], '不自动把文件夹补进集合')

  vm.handleContextMenu(FILES[1], rightClick())
  await vm.handleContextDuplicate()
  assert.deepEqual(vm.calls.batchCopyFiles.flatMap(c => c[1]).map(Number).sort((a, b) => a - b), [11, 12])
})

test('#988 逐个勾满子项（多级）：各级文件夹显示已勾；工具栏复制 / 移动只发子项，不多出「文件夹副本」也不拔出子项', async () => {
  const vm = selectionVm(DEEP)
  for (const f of [DEEP[1], DEEP[2], DEEP[4]]) vm.toggleChecked(f)
  assert.equal(vm.getCheckState(DEEP[0]), 'indeterminate')
  vm.toggleChecked(DEEP[5])
  assert.equal(vm.getCheckState(DEEP[3]), 'checked')
  assert.equal(vm.getCheckState(DEEP[0]), 'checked')
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [11, 12, 21, 22])

  const snapshot = { ...vm.checkedMap }
  vm.pendingBatchAction = 'copy'
  vm.batchTargetParentId = null
  await vm.executeBatchAction()
  assert.deepEqual(vm.calls.batchCopyFiles[0][1].map(Number).sort((a, b) => a - b), [11, 12, 21, 22])

  vm.checkedMap = snapshot
  vm.pendingBatchAction = 'move'
  await vm.executeBatchAction()
  assert.deepEqual(vm.calls.batchMoveFiles[0][1].map(Number).sort((a, b) => a - b), [11, 12, 21, 22])
})

test('#988 勾文件夹本身后工具栏批量复制 / 移动只发文件夹 [F]，不再 [F,a,b] 重复（既有问题）', async () => {
  const vm = selectionVm(FILES)
  vm.toggleChecked(FILES[0])
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [1, 11, 12])
  const snapshot = { ...vm.checkedMap }
  vm.pendingBatchAction = 'copy'
  vm.batchTargetParentId = null
  await vm.executeBatchAction()
  assert.deepEqual(vm.calls.batchCopyFiles[0][1].map(Number), [1])

  vm.checkedMap = snapshot
  vm.pendingBatchAction = 'move'
  await vm.executeBatchAction()
  assert.deepEqual(vm.calls.batchMoveFiles[0][1].map(Number), [1])
})

// 回收站的节点只在 recycleBin 里（allFiles 来自 getFileTree，后端只给未删除的）
const BIN = [
  { id: 1, name: 'A', isFolder: true, parentId: null },
  { id: 2, name: 'B', isFolder: true, parentId: 1 },
  { id: 3, name: 'c.docx', fileType: 'docx', isFolder: false, parentId: 2 },
  { id: 4, name: 'd.docx', fileType: 'docx', isFolder: false, parentId: 2 },
]

test('#988 回收站：勾文件夹连子孙一起勾；取消孙文件后祖先全部退出，彻底删除不带走被取消项', async () => {
  const vm = selectionVm([], [{ confirm: true, cancel: false }])
  vm.allFiles = [] // 回收站里的节点不在 allFiles
  vm.files = []
  vm.viewMode = 'recycle'
  vm.recycleBin = BIN.slice()
  vm.toggleChecked(BIN[0])
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [1, 2, 3, 4], '勾 A 时子孙也要进集合，界面与级联一致')
  assert.equal(vm.getCheckState(BIN[2]), 'checked')

  vm.toggleChecked(BIN[2]) // 取消 c
  assert.equal(vm.getCheckState(BIN[0]), 'indeterminate')
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [4])

  vm.openBatchAction('delete')
  await tick(); await tick()
  assert.equal(vm.deleteMode, 'hard')
  assert.match(vm.calls.dialogs[0].content, /"count":1/)
  assert.deepEqual(vm.calls.deleteFilePerm.map(c => Number(c[1])), [4], '彻底删除只删 d，不能按 A 或 B 级联带走 c')
})

test('#988 回收站：逐个勾 c、d 后文件夹显示已勾但集合只有 c、d，彻底删除不带走没勾的 A、B；多选右键能找到节点', async () => {
  const vm = selectionVm([], [{ confirm: true, cancel: false }])
  vm.allFiles = []
  vm.files = []
  vm.viewMode = 'recycle'
  vm.recycleBin = BIN.slice()
  vm.toggleChecked(BIN[2])
  vm.toggleChecked(BIN[3])
  assert.equal(vm.getCheckState(BIN[0]), 'checked')
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [3, 4])

  vm.handleContextMenu(BIN[2], rightClick())
  vm.handleContextAddToAi()
  const added = vm.calls.emits.find(e => e[0] === 'add-to-ai')[1].map(f => f.id).sort((a, b) => a - b)
  assert.deepEqual(added, [3, 4], '回收站节点不在 allFiles，右键作用项要从 recycleBin 找')

  vm.openBatchAction('delete')
  await tick(); await tick()
  assert.match(vm.calls.dialogs[0].content, /"count":2/, '确认框数量 = 用户勾的 2 项')
  assert.deepEqual(vm.calls.deleteFilePerm.map(c => Number(c[1])).sort((a, b) => a - b), [3, 4])
})

function marqueeOver(vm, hitIds) {
  const els = hitIds.map((id, i) => ({
    getAttribute: () => String(id),
    getBoundingClientRect: () => ({ left: 0, right: 100, top: i * 20, bottom: i * 20 + 18 }),
  }))
  globalThis.document = { querySelectorAll: () => els }
  vm.marquee = { ...vm.marquee, active: true, startX: 0, startY: 0 }
  try { vm.onMarqueeMove({ clientX: 100, clientY: hitIds.length * 20 }) } finally { delete globalThis.document }
}

test('#988 框选到折叠的文件夹：连子孙一起勾上（与 selectAll 一致）', () => {
  const vm = selectionVm(DEEP)
  vm.expandedFolders = new Set()
  marqueeOver(vm, [1, 13])
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [1, 2, 11, 12, 13, 21, 22])
  assert.equal(vm.getCheckState(DEEP[0]), 'checked')
})

test('#988 框选只框到部分子项：文件夹不进集合，批量操作不按整个文件夹作用', () => {
  const vm = selectionVm(DEEP)
  marqueeOver(vm, [11, 21])
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [11, 21])
  assert.equal(vm.getCheckState(DEEP[0]), 'indeterminate')
})

test('#988 框选框住展开文件夹的全部子行但没框住文件夹行：集合只含子项，文件夹显示已勾', () => {
  const vm = selectionVm(FILES)
  marqueeOver(vm, [11, 12])
  assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [11, 12])
  assert.equal(vm.getCheckState(FILES[0]), 'checked')
})

test('#988 框选 rAF 合并：一帧只算一次，松手时补算最后一次', () => {
  const vm = selectionVm(FILES)
  const frames = []
  globalThis.requestAnimationFrame = (fn) => { frames.push(fn); return frames.length }
  globalThis.cancelAnimationFrame = () => {}
  let rows = [11]
  const els = () => rows.map((id, i) => ({
    getAttribute: () => String(id),
    getBoundingClientRect: () => ({ left: 0, right: 100, top: i * 20, bottom: i * 20 + 18 }),
  }))
  globalThis.document = { querySelectorAll: () => els() }
  try {
    vm.marquee = { ...vm.marquee, active: true, startX: 0, startY: 0 }
    vm.onMarqueeMove({ clientX: 100, clientY: 20 })
    vm.onMarqueeMove({ clientX: 100, clientY: 30 })
    assert.equal(frames.length, 1, '同一帧里多次 mousemove 只排一次计算')
    frames.shift()()
    assert.deepEqual(vm.checkedIds, [11])
    rows = [11, 12]
    vm.onMarqueeMove({ clientX: 100, clientY: 40 })
    assert.equal(frames.length, 1)
    vm.onMarqueeEnd() // 帧还没到就松手
    assert.deepEqual(vm.checkedIds.sort((a, b) => a - b), [11, 12], '松手时补算')
    assert.equal(vm.marquee.active, false)
  } finally {
    delete globalThis.document
    delete globalThis.requestAnimationFrame
    delete globalThis.cancelAnimationFrame
  }
})

test('#988 rAF 回调执行前已退出选择模式：这一帧不写勾选、不 emit', () => {
  const vm = selectionVm(FILES)
  const frames = []
  globalThis.requestAnimationFrame = (fn) => { frames.push(fn); return frames.length }
  globalThis.cancelAnimationFrame = () => {}
  const els = [11, 12].map((id, i) => ({
    getAttribute: () => String(id),
    getBoundingClientRect: () => ({ left: 0, right: 100, top: i * 20, bottom: i * 20 + 18 }),
  }))
  globalThis.document = { querySelectorAll: () => els }
  try {
    vm.marquee = { ...vm.marquee, active: true, startX: 0, startY: 0 }
    vm.onMarqueeMove({ clientX: 100, clientY: 40 })
    vm.selectionMode = false
    vm.checkedMap = {}
    frames.shift()()
    assert.deepEqual(vm.checkedIds, [])
    assert.equal(vm.calls.emits.filter(e => e[0] === 'checked-change').length, 0)
  } finally {
    delete globalThis.document
    delete globalThis.requestAnimationFrame
    delete globalThis.cancelAnimationFrame
  }
})

test('#988 整个文件夹勾着后文件树新增了文件：批量删除不带上文件夹（否则级联删掉没勾的新文件）', async () => {
  const vm = selectionVm(FILES.slice(), [{ confirm: true, cancel: false }])
  vm.toggleChecked(FILES[0]) // [1, 11, 12]
  const grown = [...FILES, { id: 14, name: 'new.docx', fileType: 'docx', isFolder: false, parentId: 1 }]
  vm.allFiles = grown // loadFiles / 外部同步带来的新文件
  vm.files = grown
  assert.equal(vm.getCheckState(FILES[0]), 'indeterminate')
  vm.openBatchAction('delete')
  await tick(); await tick()
  assert.match(vm.calls.dialogs[0].content, /"count":2/)
  assert.deepEqual(vm.calls.batchDeleteFiles[0][1].map(Number).sort((a, b) => a - b), [11, 12])
})

test('#988 整个文件夹勾着后文件树新增了文件：工具栏复制 / 移动发子项而不是整个文件夹', async () => {
  const vm = selectionVm(FILES.slice())
  vm.toggleChecked(FILES[0])
  const snapshot = { ...vm.checkedMap }
  const grown = [...FILES, { id: 14, name: 'new.docx', fileType: 'docx', isFolder: false, parentId: 1 }]
  vm.allFiles = grown
  vm.files = grown
  vm.pendingBatchAction = 'copy'
  vm.batchTargetParentId = null
  await vm.executeBatchAction()
  assert.deepEqual(vm.calls.batchCopyFiles[0][1].map(Number).sort((a, b) => a - b), [11, 12])
  vm.checkedMap = snapshot
  vm.pendingBatchAction = 'move'
  await vm.executeBatchAction()
  assert.deepEqual(vm.calls.batchMoveFiles[0][1].map(Number).sort((a, b) => a - b), [11, 12])
})
