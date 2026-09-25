// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// BUG-40（v0.49.0 真机测试批次 P，report-C5.md::C5-08）：Plan 模式把计划落盘到项目根
// 「AI Assistant Files/conv-<id>/」英文文件夹，文件树里直接显示这个英文物理名。
// 低风险修法：不改物理文件夹名（后端契约，ProjectOverviewService.AI_ARTIFACT_FOLDER_NAME），
// 只在 FileTree.vue 的展示层加一个本地化别名——见 displayName(item)。
// 对话侧的可见提示见 backend AgentOrchestratorArtifactSavedNoticeTest。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')

function extractMethod(src, header) {
  const start = src.indexOf(header)
  assert.ok(start > 0, '找不到 ' + header)
  const braceStart = src.indexOf('{', start + header.length - 1)
  let depth = 0
  let i = braceStart
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break } }
  }
  return src.slice(start, i)
}

function loadDisplayName() {
  const method = extractMethod(SRC, '    displayName(item) {')
  // eslint-disable-next-line no-new-func
  const fn = new Function(`return ({ ${method} })`)().displayName
  const vm = { $t: (k) => (k === 'fileTree.aiAssistantFilesFolder' ? 'AI 助手文件' : k) }
  return (item) => fn.call(vm, item)
}

test('BUG-40：「AI Assistant Files」根文件夹展示为本地化别名，不是英文物理名', () => {
  const displayName = loadDisplayName()
  assert.equal(displayName({ isFolder: true, name: 'AI Assistant Files', parentId: null }), 'AI 助手文件')
})

test('BUG-40：其它文件/文件夹原样显示，不受影响', () => {
  const displayName = loadDisplayName()
  assert.equal(displayName({ isFolder: true, name: 'AI Assistant Files', parentId: undefined }), 'AI 助手文件',
    '根级节点的 parentId 可能缺省')
  assert.equal(displayName({ isFolder: true, name: 'AI Assistant Files', parentId: 42 }), 'AI Assistant Files',
    '只认项目根级那一个，任意深度的同名子文件夹原样显示')
  assert.equal(displayName({ isFolder: true, name: '合同' }), '合同')
  assert.equal(displayName({ isFolder: false, name: 'AI Assistant Files' }), 'AI Assistant Files',
    '同名但不是文件夹（理论上不会发生）时不误伤，只精确匹配文件夹')
  assert.equal(displayName({ isFolder: false, name: '备忘录.docx' }), '备忘录.docx')
  assert.equal(displayName(null), '')
})

test('BUG-40：模板里两处树节点渲染都走 displayName()，不是裸 item.name（左右两窗格都要统一）', () => {
  const occurrences = SRC.split('{{ displayName(item) }}').length - 1
  assert.equal(occurrences, 2, '左右两个窗格的树节点渲染都应该走 displayName()')
  assert.ok(!SRC.includes('{{ item.name }}'), '不该再有裸 item.name 渲染残留')
})

test('BUG-40：重命名输入框仍用原始物理名（不能把别名当成真名字改回去）', () => {
  assert.match(SRC, /this\.tempRenameValue = item\.name/, '重命名默认值应该是物理名 item.name，不是 displayName(item)')
})

test('BUG-40：本地化别名 zh/en 都存在', () => {
  const zh = readFileSync(new URL('../../src/locales/zh-CN/fileTree.js', import.meta.url), 'utf8')
  const en = readFileSync(new URL('../../src/locales/en-US/fileTree.js', import.meta.url), 'utf8')
  assert.match(zh, /aiAssistantFilesFolder:/)
  assert.match(en, /aiAssistantFilesFolder:/)
})
