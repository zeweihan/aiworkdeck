// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board#882：重命名后文件树已经是新名，但已开的标签页、底栏、窗口标题、AI 面板
// 「当前文档」都还停在旧名——根因是 FileTree.vue 的重命名成功之后只 `loadFiles()`
// 刷新自己，从不通知工作台；AI 工具重命名走的 `refresh_files` 通用信号同样只刷了树、
// 没有回头把已开标签的名字对齐。
//
// 两条入口都要走同一条同步：
// 1) FileTree 右键重命名（commitRename / 已弃用的对话框 handleConfirmRename）成功后
//    emit('file-renamed', { id, name })，工作台监听后调用 handleFileRenamed 精确同步。
// 2) AI/WPS 等不知道具体改了哪个文件的路径，靠 refresh_files 把树重新拉完之后，
//    调用 syncOpenTabsFromFileTree() 拿树的最新清单把已开标签整批对齐一遍。
//
// leftFiles/rightFiles 里的 tab 对象是 activeFileLeft/activeFileRight 计算属性、
// currentActiveTab、以及模板里 `{{ file.name }}` / `{{ activeFileLeft.name }}` 共享的
// 同一个响应式对象引用，直接改它的 name 就足够让标签、状态条、AI「当前文档」chip
// 一起跟上；只有 document.title 是命令式写入，改名不换 id 不会触发现有的
// activeFileIdLeft watcher，因此两条同步方法都要显式调一次 updateWindowTitle()。
//
// FileTree.vue / ChatInterface.vue 体量太大、@/ 别名太多，本仓约定对它们只做源码
// 文本断言（见 audit-rE-source-assertions.test.mjs 的说明与 extractBlock 写法），
// 不整份 new Function；project-overview.vue 与 agentClientActions.js 里我们新增的
// 方法不依赖任何 @/ 导入，可以真实求值执行。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

// 抠出 name(...) { ... } 形式的一个方法体（按大括号配平截断），照抄
// audit-rE-source-assertions.test.mjs 的写法。
function extractBlock(src, marker, braceOpenOffset) {
  const start = src.indexOf(marker)
  assert.ok(start > 0, '找不到 ' + JSON.stringify(marker))
  let i = start + (braceOpenOffset != null ? braceOpenOffset : marker.length)
  while (src[i] !== '{') i++
  let depth = 0
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break } }
  }
  return src.slice(start, i)
}

// ======================================================================
// 1. project-overview.vue：handleFileRenamed / syncOpenTabsFromFileTree
//    真实求值执行（不依赖任何 @/ 导入）。
// ======================================================================

const page = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const syncStart = page.indexOf('    handleFileRenamed({ id, name } = {}) {')
const syncEnd = page.indexOf('    // 处理文件重命名（历史：WPS 时代由文件信息轮询触发；现无调用方，保留为通用逻辑）')
assert.ok(syncStart > 0 && syncEnd > syncStart, '定位不到 handleFileRenamed/syncOpenTabsFromFileTree 这一段，源码可能已经改动')
const syncMethods = new Function('return ({' + page.slice(syncStart, syncEnd) + '})')()

function workspace() {
  const vm = {
    leftFiles: [],
    rightFiles: [],
    $refs: {},
    _titleUpdates: 0,
    updateWindowTitle() { this._titleUpdates++ },
  }
  for (const [name, method] of Object.entries(syncMethods)) vm[name] = method.bind(vm)
  return vm
}

test('handleFileRenamed：按 id 把新名同步进左右两侧标签，且左右各开一份时两份都要改', () => {
  const vm = workspace()
  const leftTab = { id: 42, name: 'old.docx' }
  const rightTab = { id: 42, name: 'old.docx' } // 左右分屏同一份文件各自一个 tab 对象
  vm.leftFiles.push(leftTab)
  vm.rightFiles.push(rightTab)
  vm.handleFileRenamed({ id: 42, name: 'new.docx' })
  assert.equal(leftTab.name, 'new.docx')
  assert.equal(rightTab.name, 'new.docx')
  assert.equal(vm._titleUpdates, 1, '改了名要重算一次窗口标题（activeFileIdLeft 没变，watch 不会自己触发)')
})

test('handleFileRenamed：id 按字符串比较，数字 id 与字符串 id 视为同一份文件', () => {
  const vm = workspace()
  const tab = { id: 42, name: 'old.docx' }
  vm.leftFiles.push(tab)
  vm.handleFileRenamed({ id: '42', name: 'new.docx' })
  assert.equal(tab.name, 'new.docx')
})

test('handleFileRenamed：没有匹配的标签（文件没开）时什么都不做，不误刷标题', () => {
  const vm = workspace()
  vm.leftFiles.push({ id: 1, name: 'a.docx' })
  vm.handleFileRenamed({ id: 999, name: 'b.docx' })
  assert.equal(vm.leftFiles[0].name, 'a.docx')
  assert.equal(vm._titleUpdates, 0)
})

test('handleFileRenamed：缺 id 或缺 name 的载荷直接忽略', () => {
  const vm = workspace()
  vm.leftFiles.push({ id: 1, name: 'a.docx' })
  vm.handleFileRenamed({})
  vm.handleFileRenamed({ id: 1 })
  vm.handleFileRenamed({ name: 'x' })
  assert.equal(vm.leftFiles[0].name, 'a.docx')
  assert.equal(vm._titleUpdates, 0)
})

test('syncOpenTabsFromFileTree：拿文件树的最新清单，把已开标签名字整批对齐（AI/WPS 改名走这条兜底）', () => {
  const vm = workspace()
  const leftTab = { id: 7, name: 'pdf-converted.docx' } // AI 转换时给的临时名
  vm.leftFiles.push(leftTab)
  vm.rightFiles.push({ id: 8, name: 'untouched.docx' })
  vm.$refs.fileTree = {
    allFiles: [
      { id: 7, name: 'AI 改过的名字.docx' }, // 树已经是权威新名
      { id: 8, name: 'untouched.docx' },
    ],
  }
  vm.syncOpenTabsFromFileTree()
  assert.equal(leftTab.name, 'AI 改过的名字.docx')
  assert.equal(vm.rightFiles[0].name, 'untouched.docx', '名字没变的不应该被当成"有变化"去刷标题')
  assert.equal(vm._titleUpdates, 1)
})

test('syncOpenTabsFromFileTree：文件树 ref 还没挂上或没数据时安全跳过，不抛错', () => {
  const vm = workspace()
  vm.leftFiles.push({ id: 1, name: 'a.docx' })
  assert.doesNotThrow(() => vm.syncOpenTabsFromFileTree())
  vm.$refs.fileTree = { allFiles: [] }
  assert.doesNotThrow(() => vm.syncOpenTabsFromFileTree())
  assert.equal(vm._titleUpdates, 0)
})

// ======================================================================
// 2. agentClientActions.js：refresh_files 在树刷新落地之后才去同步标签
//    （不能在 loadFiles() 的 promise 落地之前就同步——那时候树的数据还是旧的）。
//    真实求值执行：文件只有三个具体值导入，按 fileOpenTabs.js 既有测试的手法注入。
// ======================================================================

const acaSource = readFileSync(new URL('../../src/pages/project-overview/agentClientActions.js', import.meta.url), 'utf8')
const acaBody = acaSource.replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
  // 文件里还有一个独立导出的 export function shouldFlushDocStream(...)，与
  // fileOpenTabs.js 的既有测试手法一样只替换目标声明的话会留下裸 `export`
  // 关键字（在 Function 构造器里不是模块作用域，是语法错误），一并摘掉。
  .replace(/^export\s+/gm, '')
  .replace('const agentClientActionMethods = {', 'return {')
const acaMethods = new Function(
  'sendEditorResult', 'getFileDetail', 'createSerialQueue', 'DOC_MUTATED_EVENT', 'DOC_MUTATED_DEBOUNCE_MS', 'isDocMutatingAction',
  'uni',
  acaBody
)(
  () => {}, () => {}, () => ({ enqueue: (fn) => fn() }), 'awd:doc-mutated', 300, () => true,
  { showToast() {} }
)

function agentWorkspace() {
  const vm = {
    $refs: {},
    $t: (k) => k,
    focusedPane: 'left',
    syncCalls: 0,
    syncOpenTabsFromFileTree() { this.syncCalls++ },
  }
  for (const [name, method] of Object.entries(acaMethods)) {
    vm[name] = typeof method === 'function' ? method.bind(vm) : method
  }
  return vm
}

test('refresh_files：先刷文件树，树刷完（promise resolve）之后才同步已开标签的名字', async () => {
  const vm = agentWorkspace()
  let loadFilesCalled = false
  let resolveLoad
  vm.$refs.fileTree = {
    loadFiles: () => {
      loadFilesCalled = true
      return new Promise((r) => { resolveLoad = r })
    },
  }
  vm.handleClientAction({ action: 'refresh_files' })
  assert.equal(loadFilesCalled, true)
  assert.equal(vm.syncCalls, 0, '树还没刷完，不该提前同步（会拿到刷新前的旧清单）')
  resolveLoad()
  await Promise.resolve()
  await Promise.resolve()
  assert.equal(vm.syncCalls, 1)
})

test('refresh_files：文件树还没挂上 ref 时不报错、也不调用同步', () => {
  const vm = agentWorkspace()
  assert.doesNotThrow(() => vm.handleClientAction({ action: 'refresh_files' }))
  assert.equal(vm.syncCalls, 0)
})

// ======================================================================
// 3. FileTree.vue：右键重命名成功后必须广播 file-renamed（源码文本断言——
//    体量与 @/ 别名太多，不整份 new Function，理由同 audit-rE-source-assertions）。
// ======================================================================

test('FileTree.commitRename（右键重命名，唯一可达的入口）成功后广播 file-renamed', () => {
  const src = stripComments(read('components/FileTree.vue'))
  const body = extractBlock(src, 'async commitRename()')
  assert.match(body, /await this\.loadFiles\(\)/, '必须先落库刷新完树')
  assert.match(body, /\$emit\(\s*'file-renamed'\s*,\s*\{\s*id:\s*fileId\s*,\s*name:\s*finalName\s*\}\s*\)/,
    '重命名成功后要把新名连同 fileId 广播出去，工作台才能同步标签/标题/AI 上下文')
})

test('FileTree.handleConfirmRename（已弃用的对话框兜底路径）同样广播 file-renamed', () => {
  const src = stripComments(read('components/FileTree.vue'))
  const body = extractBlock(src, 'async handleConfirmRename()')
  assert.match(body, /\$emit\(\s*'file-renamed'\s*,\s*\{\s*id:\s*fileId\s*,\s*name:\s*finalName\s*\}\s*\)/)
})

test('project-overview.vue 的 <FileTree> 接了 @file-renamed="handleFileRenamed"', () => {
  const src = read('pages/project-overview/project-overview.vue')
  const start = src.indexOf('<FileTree')
  const end = src.indexOf('<DdFilesPanel')
  assert.ok(start > 0 && end > start, '找不到 <FileTree>...<DdFilesPanel> 这一段')
  const block = src.slice(start, end)
  assert.match(block, /@file-renamed="handleFileRenamed"/)
})
