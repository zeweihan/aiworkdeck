// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 「把项目文件挂进 AI 上下文」的公共判据（dev-board#794 K15）。
// 四个入口（拖拽 / 右键 / @ 引用 / 从项目选择）共用这一份，各写一遍必然漂移。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  AI_CONTEXT_FOLDER_FILE_LIMIT,
  countDescendantFiles,
  dirLabelOf,
  excludeSystemFolders,
  matchProjectFiles,
} from '../../src/utils/aiContextFiles.js'

const src = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

test('前缀命中排在包含命中之前', () => {
  const files = [
    { id: 1, name: '补充协议-股份.docx' },
    { id: 2, name: '股份认购协议.docx' },
    { id: 3, name: '公司章程.docx' },
  ]
  assert.deepEqual(matchProjectFiles(files, '股份').map((f) => f.id), [2, 1])
})

test('空查询原样返回前若干条，不是空列表', () => {
  const files = Array.from({ length: 40 }, (_, i) => ({ id: i, name: `f${i}` }))
  assert.equal(matchProjectFiles(files, '').length, 30)
  assert.equal(matchProjectFiles(files, '   ').length, 30)
})

test('匹配忽略大小写，也容得下没有名字的脏数据', () => {
  const files = [{ id: 1, name: 'Term Sheet.docx' }, { id: 2 }]
  assert.deepEqual(matchProjectFiles(files, 'term').map((f) => f.id), [1])
  assert.doesNotThrow(() => matchProjectFiles(files, 'x'))
})

test('文件夹后代计数只数文件，且 parentId 数字/字符串混用也认', () => {
  const all = [
    { id: 1, name: '交易文件', isFolder: true, parentId: null },
    { id: 2, name: '附件', isFolder: true, parentId: 1 },
    { id: 3, name: 'a.docx', parentId: '1' },
    { id: 4, name: 'b.docx', parentId: 2 },
    { id: 5, name: 'c.docx', parentId: null },
  ]
  assert.equal(countDescendantFiles(all, 1), 2)
  assert.equal(countDescendantFiles(all, 2), 1)
  assert.equal(countDescendantFiles(all, 999), 0)
  assert.equal(countDescendantFiles(null, 1), 0)
})

test('目录结构成环时不会把计数卡死', () => {
  const all = [
    { id: 1, isFolder: true, parentId: 2 },
    { id: 2, isFolder: true, parentId: 1 },
    { id: 3, name: 'x.docx', parentId: 1 },
  ]
  assert.doesNotThrow(() => countDescendantFiles(all, 1))
})

test('面包屑逐级往上拼，父节点缺失就停在那里', () => {
  const byId = new Map([[1, { id: 1, name: '交易文件', parentId: null }], [2, { id: 2, name: '附件', parentId: 1 }]])
  assert.equal(dirLabelOf({ parentId: 2 }, byId), '交易文件 / 附件')
  assert.equal(dirLabelOf({ parentId: null }, byId), '')
  assert.equal(dirLabelOf({ parentId: 99 }, byId), '')
})

test('上限是 10（拖拽路径与 @ 引用路径读的是同一个常量）', () => {
  assert.equal(AI_CONTEXT_FOLDER_FILE_LIMIT, 10)
  const overview = src('../../src/pages/project-overview/project-overview.vue')
  assert.ok(overview.includes('AI_CONTEXT_FOLDER_FILE_LIMIT'), '工作台读常量而不是再写一个 10')
  assert.ok(overview.includes('countDescendantFiles('), '工作台用公共计数，不再自己递归一遍')
  const chat = src('../../src/components/ChatInterface.vue')
  assert.ok(chat.includes('AI_CONTEXT_FOLDER_FILE_LIMIT') && chat.includes('countDescendantFiles('),
    'ChatInterface 的三个入口也走同一份判据')
})

test('Cmd+P 快速打开与 @ 引用共用同一个检索实现', () => {
  const quickOpen = src('../../src/components/QuickOpenPanel.vue')
  assert.ok(quickOpen.includes("from '@/utils/aiContextFiles.js'"))
  assert.ok(!/const starts = \[\]/.test(quickOpen), '不许再留一份自己的排序实现')
})

test('Esc 只挂在输入框上，不进命令表', () => {
  const ai = src('../../src/config/commands/ai.js')
  assert.ok(!/accel:\s*'[^']*Esc/i.test(ai), 'Esc 一旦成为菜单加速键就会吞掉编辑器和所有输入框的 Esc')
  const chat = src('../../src/components/ChatInterface.vue')
  assert.ok(chat.includes('@keydown="handleInputKeydown"'), '键位统一从输入框自己的 keydown 进来')
  assert.ok(!chat.includes('@keydown.enter="handleEnterKey"'), '别留两个 keydown 绑定同时处理回车')
})

test('暂存区整棵子树都不出现在「挑一份文件」的清单里', () => {
  const all = [
    { id: 1, name: '__staging_area__', isFolder: true, parentId: null },
    { id: 2, name: '暂存的合同.docx', parentId: 1 },
    { id: 3, name: '子目录', isFolder: true, parentId: 1 },
    { id: 4, name: '更深一层.docx', parentId: 3 },
    { id: 5, name: '股份认购协议.docx', parentId: null },
    { id: 6, name: '.stagezone', isFolder: true, parentId: null },
    { id: 7, name: '老暂存区里的.docx', parentId: 6 },
  ]
  assert.deepEqual(excludeSystemFolders(all).map((f) => f.id), [5])
  // 没有隐藏文件夹时原样返回，不做无谓的重建
  const plain = [{ id: 1, name: 'a.docx' }]
  assert.deepEqual(excludeSystemFolders(plain).map((f) => f.id), [1])
  assert.deepEqual(excludeSystemFolders(null), [])
})

test('三个「挑一份文件」的入口都过同一道剔除', () => {
  const chat = src('../../src/components/ChatInterface.vue')
  const quickOpen = src('../../src/components/QuickOpenPanel.vue')
  assert.ok(chat.includes('excludeSystemFolders('), '@ 引用与「从项目选择」共用的候选集要剔除暂存区')
  assert.ok(quickOpen.includes('excludeSystemFolders('), 'Cmd+P 同理')
})
