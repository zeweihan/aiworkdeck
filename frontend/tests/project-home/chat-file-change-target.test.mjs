// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#852：对话里的「改动 (1)」卡片点下去弹「未找到文件: Current Document」。
//
// 病灶：sheet_* / doc_* / slide_* 改的是编辑器里当前打开的那份文档，后端 file_change
// 把文件名兜底成字面量 "Current Document"；handleOpenFileFromChat 只会按名字去项目里找，
// 找不到就弹「未找到文件」——而那份文件就开在用户眼前。
//
// 修法：后端带上 fileId 并报真名；前端按 id 优先、名字其次；卡片指的就是当前活跃标签时
// 只切过去；历史会话里的 "Current Document" 当「当前文档」处理，没有打开的文档时提示先打开。
//
// fileOpenTabs.js 带 @/ 别名 import，照本仓一贯套路抠出来 new Function 求值，
// 纯函数经参数注入（同 pending-local-file-silent-fail.test.mjs 的做法）。
// FILE_OPEN_TABS_SRC 可指向另一份源码（用来在改动前的版本上复现红灯）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  findChatFile, isCurrentDocSentinel, isSameFileChange, matchesActiveTab
} from '../../src/utils/chatFileChange.js'

const SRC = readFileSync(process.env.FILE_OPEN_TABS_SRC
  || new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')

function loadMethods(getProjectFilesImpl, uniStub) {
  const body = SRC
    .replace(/^\s*import[\s\S]*?from\s*'[^']*'\s*$/gm, '')
    .replace(/export const fileOpenTabsMethods = \{/, 'return {')
  const factory = new Function('getProjectFiles', 'activityTracker', 'GLYPHS', 'fileGlyph', 'uni',
    'findChatFile', 'isCurrentDocSentinel', 'matchesActiveTab', body)
  return factory(getProjectFilesImpl, { track: () => {} }, {}, () => '', uniStub,
    findChatFile, isCurrentDocSentinel, matchesActiveTab)
}

const SHEET = { id: 42, name: '重大合同清单.xlsx', fileType: 'xlsx' }
const OTHER = { id: 7, name: '股权转让协议.docx', fileType: 'docx' }
const FILES = [SHEET, OTHER, { id: 99, name: '重大合同清单.xlsx', isFolder: true }]

function makeVm({ activeTab = null, rightFiles = [] } = {}) {
  const toasts = []
  const fetches = []
  const methods = loadMethods(async (pid) => { fetches.push(pid); return { data: FILES } },
    { showToast: (opts) => toasts.push(opts) })
  const vm = {
    ...methods,
    projectId: 1,
    currentActiveTab: activeTab,
    rightFiles,
    opened: [],
    activated: [],
    $t: (k, p) => (p ? `${k}|${JSON.stringify(p)}` : k),
    openFile(f) { vm.opened.push(f) },
    // 两个桩必须写在 ...methods 之后，否则被真实现盖掉
    activateTab(f, pane) { vm.activated.push({ id: f.id, pane }) }
  }
  return { vm, toasts, fetches }
}

test('fileId 命中当前活跃标签：只切过去，不拉列表、不弹提示', async () => {
  const { vm, toasts, fetches } = makeVm({ activeTab: SHEET })
  await vm.handleOpenFileFromChat({ name: '重大合同清单.xlsx', fileId: 42 })
  assert.deepEqual(vm.activated, [{ id: 42, pane: 'left' }])
  assert.equal(vm.opened.length, 0)
  assert.equal(fetches.length, 0)
  assert.equal(toasts.length, 0)
})

test('fileId 优先于名字：名字对不上也按 id 打开', async () => {
  const { vm, toasts } = makeVm({ activeTab: OTHER })
  await vm.handleOpenFileFromChat({ name: '改了名的旧文件名.xlsx', fileId: 42 })
  assert.equal(vm.opened.length, 1)
  assert.equal(vm.opened[0].id, 42)
  assert.equal(toasts.length, 0)
})

test('旧后端没有 fileId：仍按名字找（忽略文件夹）', async () => {
  const { vm } = makeVm()
  await vm.handleOpenFileFromChat({ name: '重大合同清单.xlsx' })
  assert.equal(vm.opened.length, 1)
  assert.equal(vm.opened[0].id, 42)
})

test('历史会话里的 "Current Document" + 有活跃标签：切到当前标签，不报未找到', async () => {
  const { vm, toasts } = makeVm({ activeTab: SHEET, rightFiles: [SHEET] })
  await vm.handleOpenFileFromChat({ name: 'Current Document' })
  assert.deepEqual(vm.activated, [{ id: 42, pane: 'right' }])
  assert.equal(toasts.length, 0, '不应弹任何提示：' + JSON.stringify(toasts))
})

test('"Current Document" + 没有打开的文档：提示先打开文档，而不是「未找到文件」', async () => {
  const { vm, toasts } = makeVm({ activeTab: null })
  await vm.handleOpenFileFromChat({ name: 'Current Document' })
  assert.equal(toasts.length, 1)
  assert.ok(!toasts[0].title.startsWith('workbenchOps.fileNotFoundNamed'), toasts[0].title)
  assert.ok(toasts[0].title.startsWith('workbenchOps.openDocumentFirst'), toasts[0].title)
  assert.ok(toasts[0].title.includes('chat.activeDocChipLabel'), toasts[0].title)
  assert.equal(vm.opened.length, 0)
})

test('后端现行占位「当前文档」与旧字面量同等处理', async () => {
  const { vm, toasts } = makeVm({ activeTab: SHEET })
  await vm.handleOpenFileFromChat({ name: '当前文档', fileId: null })
  assert.deepEqual(vm.activated, [{ id: 42, pane: 'left' }])
  assert.equal(toasts.length, 0)
})

test('真找不到的普通文件名照旧提示未找到', async () => {
  const { vm, toasts } = makeVm()
  await vm.handleOpenFileFromChat({ name: '不存在.docx' })
  assert.equal(toasts.length, 1)
  assert.ok(toasts[0].title.startsWith('workbenchOps.fileNotFoundNamed'), toasts[0].title)
})

test('纯函数：findChatFile id 优先、占位名不按名字找、基名兜底仍在', () => {
  assert.equal(findChatFile(FILES, { name: '股权转让协议.docx', fileId: 42 }).id, 42)
  assert.equal(findChatFile(FILES, { name: '股权转让协议.docx', fileId: 12345 }).id, 7)
  assert.equal(findChatFile(FILES, { name: 'Current Document' }), null)
  const diag = [{ id: 1, name: '关系图.png', fileType: 'png' }, { id: 2, name: '关系图-draft.drawio', fileType: 'drawio' }]
  assert.equal(findChatFile(diag, { name: '关系图' }).id, 2)
})

test('纯函数：matchesActiveTab 有 id 只认 id，占位名认任意活跃标签', () => {
  assert.equal(matchesActiveTab({ name: '重大合同清单.xlsx', fileId: 7 }, SHEET), false)
  assert.equal(matchesActiveTab({ name: 'x', fileId: '42' }, SHEET), true)
  assert.equal(matchesActiveTab({ name: 'Current Document' }, SHEET), true)
  assert.equal(matchesActiveTab({ name: 'Current Document' }, null), false)
})

test('纯函数：file_change 去重按 id（同名不同文件不合并），无 id 时按名字', () => {
  const a = { fileName: '合同.docx', changeType: 'MODIFIED', fileId: 1 }
  assert.equal(isSameFileChange(a, { fileName: '合同.docx', changeType: 'MODIFIED', fileId: 2 }), false)
  assert.equal(isSameFileChange(a, { fileName: '合同(改名).docx', changeType: 'MODIFIED', fileId: 1 }), true)
  assert.equal(isSameFileChange({ fileName: 'a', changeType: 'ADDED', fileId: null },
    { fileName: 'a', changeType: 'ADDED' }), true)
  assert.equal(isSameFileChange(a, { ...a, changeType: 'ADDED' }), false)
})
