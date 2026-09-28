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
import { collapseToTopmostSelected } from '../../src/utils/fileTreeRecycle.js'

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
  const calls = { batchDeleteFiles: [], batchCopyFiles: [], dialogs: [], emits: [], toasts: [], clipboard: [] }
  const queue = [...replies]
  const stubs = {
    batchDeleteFiles: async (...a) => { calls.batchDeleteFiles.push(a) },
    batchCopyFiles: async (...a) => { calls.batchCopyFiles.push(a) },
    deleteFile: async () => {},
    collapseToTopmostSelected,
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
