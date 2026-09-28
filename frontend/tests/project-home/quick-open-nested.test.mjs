// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#977（BUG-81 / QA C9-12）：快速打开（⌘P）只搜得到项目根目录的文件。
//
// 病灶：QuickOpenPanel 取候选调的是 getProjectFiles(projectId)——不带 parentId、不带 tree，
// 后端 ProjectFileController.getFiles 走 getFilesByParent(projectId, null)，只回根目录那一层。
// 要全项目文件得带 tree=true（后端 getFileTree：同一张表按 projectId 全取，扁平、带 parentId）。
//
// 这里把组件 <script> 剥出来跑真实 mounted，getProjectFiles 换成按后端两条分支如实回数据的桩，
// 匹配/面包屑/系统目录过滤喂的是 utils/aiContextFiles.js 的真实现。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as ctx from '../../src/utils/aiContextFiles.js'

const SRC = readFileSync(new URL('../../src/components/QuickOpenPanel.vue', import.meta.url), 'utf8')

// 项目：根下 3 个文件 + 「证据材料」（含子文件夹「录音」）+ 「合同」+ 暂存区
const PROJECT = [
  { id: 1, parentId: null, name: 'README.txt', isFolder: false },
  { id: 2, parentId: null, name: 'QA-C3-Word.docx', isFolder: false },
  { id: 3, parentId: null, name: 'QA-F-表格.xlsx', isFolder: false },
  { id: 10, parentId: null, name: '证据材料', isFolder: true },
  { id: 11, parentId: 10, name: 'synthetic_chat.txt', isFolder: false },
  { id: 12, parentId: 10, name: 'synthetic_photo.png', isFolder: false },
  { id: 13, parentId: 10, name: '起诉状.pdf', isFolder: false },
  { id: 14, parentId: 10, name: '录音', isFolder: true },
  { id: 15, parentId: 14, name: '录音样本.mp3', isFolder: false },
  { id: 20, parentId: null, name: '合同', isFolder: true },
  { id: 21, parentId: 20, name: '股权转让协议.docx', isFolder: false },
  { id: 22, parentId: 20, name: 'README.txt', isFolder: false },
  { id: 23, parentId: 20, name: '旧版.pdf', isFolder: false, isDeleted: true },
  { id: 30, parentId: null, name: '__staging_area__', isFolder: true },
  { id: 31, parentId: 30, name: '暂存.pdf', isFolder: false },
]

/** 与 ProjectFileController.getFiles 同语义：tree=true 全取，否则只取 parentId 那一层 */
function backendGetProjectFiles(projectId, parentId = null, tree = false) {
  if (tree) return Promise.resolve(PROJECT.filter((f) => !f.isDeleted))
  // eslint-disable-next-line eqeqeq
  return Promise.resolve(PROJECT.filter((f) => !f.isDeleted && f.parentId == parentId))
}

async function mountPanel() {
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const names = ['getProjectFiles', ...Object.keys(ctx)]
  // eslint-disable-next-line no-new-func
  const component = new Function(...names, script.replace('export default', 'return'))(
    backendGetProjectFiles, ...Object.keys(ctx).map((n) => ctx[n]),
  )
  const vm = Object.assign({ projectId: 7, $emit() {} }, component.data(), component.methods)
  for (const [k, fn] of Object.entries(component.computed)) {
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  const listeners = []
  globalThis.document = { addEventListener: (...a) => listeners.push(a), removeEventListener() {} }
  await component.mounted.call(vm)
  return vm
}

const search = (vm, q) => { vm.query = q; return vm.matches.map((m) => (m.dirLabel ? m.dirLabel + ' / ' : '') + m.name) }

test('空输入列出全项目文件（含子文件夹、孙文件夹），不只根目录三个', async () => {
  const vm = await mountPanel()
  const all = search(vm, '')
  assert.ok(all.includes('证据材料 / 录音 / 录音样本.mp3'), JSON.stringify(all))
  assert.ok(all.includes('合同 / 股权转让协议.docx'), JSON.stringify(all))
  assert.equal(all.length, 9, '9 个文件：根 3 + 证据材料 3 + 录音 1 + 合同 2；不含文件夹、已删、暂存区')
})

test('子文件夹里的文件能按名字搜到（QA 复现的四个词）', async () => {
  const vm = await mountPanel()
  assert.deepEqual(search(vm, 'synthetic'), ['证据材料 / synthetic_chat.txt', '证据材料 / synthetic_photo.png'])
  assert.deepEqual(search(vm, '录音'), ['证据材料 / 录音 / 录音样本.mp3'])
  assert.deepEqual(search(vm, 'pdf'), ['证据材料 / 起诉状.pdf'])
})

test('同名文件靠目录区分，暂存区文件不出现', async () => {
  const vm = await mountPanel()
  assert.deepEqual(search(vm, 'readme'), ['README.txt', '合同 / README.txt'])
  assert.deepEqual(search(vm, '暂存'), [])
})
