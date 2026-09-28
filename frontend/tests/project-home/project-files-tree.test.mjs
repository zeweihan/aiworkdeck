// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#985（BUG-82）：与快速打开（#977）同一病灶的另外四处——
// getProjectFiles(pid) 不带 tree=true，后端 getFilesByParent(pid, null) 只回根目录一层，
// 于是子文件夹里的文件：事项对话框挑不到、对话卡片点开报「未找到文件」、
// 新建项目带 openFileId 落地打不开、版本记录的「按文件筛」下拉里没有。
//
// 各处源码的 <script> / 模块体剥出来跑真实方法；getProjectFiles 换成按后端两条分支如实回数据的桩。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import * as ctx from '../../src/utils/aiContextFiles.js'
import * as chatFileChange from '../../src/utils/chatFileChange.js'

// tree=true 的真实形状：扁平行 + parentId（不是嵌套 children）
const PROJECT = [
  { id: 1, parentId: null, name: 'README.txt', isFolder: false, fileType: 'txt' },
  { id: 10, parentId: null, name: '证据材料', isFolder: true },
  { id: 14, parentId: 10, name: '录音', isFolder: true },
  { id: 15, parentId: 14, name: '录音样本.mp3', isFolder: false, fileType: 'mp3' },
  { id: 20, parentId: null, name: '合同', isFolder: true },
  { id: 21, parentId: 20, name: '股权转让协议.docx', isFolder: false, fileType: 'docx' },
  { id: 30, parentId: null, name: '__staging_area__', isFolder: true },
  { id: 31, parentId: 30, name: '暂存.pdf', isFolder: false, fileType: 'pdf' },
]

/** 与 ProjectFileController.getFiles 同语义 */
function backendGetProjectFiles(projectId, parentId = null, tree = false) {
  // eslint-disable-next-line eqeqeq
  return Promise.resolve(tree ? PROJECT.slice() : PROJECT.filter((f) => f.parentId == parentId))
}

/**
 * 剥掉 import，把被导入的名字作为参数注入（overrides 里有的给真实现/桩，其余给空桩），
 * 执行后按 tail 返回想要的对象。
 */
function loadModule(code, tail, overrides) {
  const names = []
  const body = code.replace(/^import\s([\s\S]*?)\sfrom\s+'[^']+'\s*;?\s*$/gm, (_, spec) => {
    const braces = spec.match(/\{([\s\S]*)\}/)
    const def = spec.replace(/\{[\s\S]*\}/, '').replace(/,/g, '').trim()
    if (def) names.push(def)
    if (braces) for (const part of braces[1].split(',')) {
      const n = part.trim().split(/\s+as\s+/).pop()
      if (n) names.push(n)
    }
    return ''
  })
  const stub = () => undefined
  // eslint-disable-next-line no-new-func
  return new Function(...names, body + '\n' + tail)(...names.map((n) => (n in overrides ? overrides[n] : stub)))
}

const read = (p) => readFileSync(new URL('../../src/' + p, import.meta.url), 'utf8')
const vueScript = (p) => read(p).match(/<script>([\s\S]*?)<\/script>/)[1].replace('export default', 'const __component =')

test('handleOpenFileFromChat：对话卡片点开子文件夹里的文件，不再「未找到文件」', async () => {
  const methods = loadModule(read('pages/project-overview/fileOpenTabs.js').replace('export const fileOpenTabsMethods', 'const fileOpenTabsMethods'),
    'return fileOpenTabsMethods', { getProjectFiles: backendGetProjectFiles, ...chatFileChange })
  const toasts = []
  globalThis.uni = { showToast: (o) => toasts.push(o.title) }
  const opened = []
  const vm = { ...methods, projectId: 7, currentActiveTab: null, rightFiles: [], $t: (k) => k, openFile: (f) => opened.push(f) }
  // 按 fileId（dev-board#852 起卡片带 id）与只有名字两条路都要能打开
  await vm.handleOpenFileFromChat({ name: '录音样本.mp3', fileId: 15 })
  await vm.handleOpenFileFromChat({ name: '股权转让协议.docx' })
  assert.deepEqual(opened.map((f) => f.id), [15, 21])
  assert.deepEqual(toasts, [], '不该弹「未找到文件」')
})

test('openPendingLocalFile：openFileId 指向子文件夹里的文件也能打开', async () => {
  const methods = loadModule(read('pages/project-overview/fileOpenTabs.js').replace('export const fileOpenTabsMethods', 'const fileOpenTabsMethods'),
    'return fileOpenTabsMethods', { getProjectFiles: backendGetProjectFiles, ...chatFileChange })
  const toasts = []
  globalThis.uni = { showToast: (o) => toasts.push(o.title) }
  const opened = []
  const vm = { ...methods, projectId: 7, $t: (k) => k, openFile: (f) => opened.push(f) }
  await vm.openPendingLocalFile(21)
  assert.deepEqual(opened.map((f) => f.id), [21])
  assert.deepEqual(toasts, [])
})

test('事项对话框的文件候选包含子文件夹文件（带目录面包屑），不含暂存区', async () => {
  const comp = loadModule(vueScript('components/calendar/TaskDialog.vue'), 'return __component', {
    getProjectFiles: backendGetProjectFiles,
    getProjectMembers: () => Promise.resolve({ data: [] }),
    roleLabel: (r) => r,
    ...ctx,
  })
  const vm = { loadedProjectId: null, files: [], members: [], knownNames: {}, filesLoading: false, ...comp.methods }
  await vm.loadProjectData(7)
  assert.deepEqual(vm.files.map((f) => (f.dirLabel ? f.dirLabel + ' / ' : '') + f.name).sort(),
    ['README.txt', '合同 / 股权转让协议.docx', '证据材料 / 录音 / 录音样本.mp3'].sort())
})

test('版本记录「按文件筛」下拉包含子文件夹文件，不含暂存区', async () => {
  const comp = loadModule(vueScript('components/version/CommitHistoryTab.vue'), 'return __component', {
    getProjectFiles: backendGetProjectFiles,
    ...ctx,
  })
  const vm = { projectId: 7, files: [], ...comp.methods }
  await vm.loadFileOptions()
  assert.deepEqual(vm.files.map((f) => f.id).sort((a, b) => a - b), [1, 15, 21])
})

test('按名字打开：根目录与子文件夹同名时打开根目录那份；暂存区里的同名文件不参与', async () => {
  // 子文件夹 / 暂存区的同名行排在前面（sortOrder 是各目录内的序，全量拉回来会交错），
  // 逼出「谁先出现就开谁」的旧行为
  const rows = [
    { id: 30, parentId: null, name: '__staging_area__', isFolder: true },
    { id: 31, parentId: 30, name: '起诉状.docx', isFolder: false, fileType: 'docx' },
    { id: 32, parentId: 30, name: '关系图.drawio', isFolder: false, fileType: 'drawio' },
    { id: 20, parentId: null, name: '合同', isFolder: true },
    { id: 22, parentId: 20, name: '起诉状.docx', isFolder: false, fileType: 'docx' },
    { id: 23, parentId: 20, name: '关系图.svg', isFolder: false, fileType: 'svg' },
    { id: 5, parentId: null, name: '起诉状.docx', isFolder: false, fileType: 'docx' },
    { id: 6, parentId: null, name: '关系图.svg', isFolder: false, fileType: 'svg' },
  ]
  const methods = loadModule(read('pages/project-overview/fileOpenTabs.js').replace('export const fileOpenTabsMethods', 'const fileOpenTabsMethods'),
    'return fileOpenTabsMethods', { getProjectFiles: (pid, parentId, tree) => Promise.resolve(tree ? rows : rows.filter((f) => f.parentId == null)), ...chatFileChange })
  globalThis.uni = { showToast() {} }
  const opened = []
  const vm = { ...methods, projectId: 7, currentActiveTab: null, rightFiles: [], $t: (k) => k, openFile: (f) => opened.push(f) }
  await vm.handleOpenFileFromChat({ name: '起诉状.docx' })
  // 基名兜底（诉讼可视化报图名）：暂存区的 .drawio 排序更靠前也不许命中；同档里根目录优先
  await vm.handleOpenFileFromChat({ name: '关系图' })
  assert.deepEqual(opened.map((f) => f.id), [5, 6])
  // id 精确命中不受过滤影响：卡片明确指着暂存区那份时照开
  await vm.handleOpenFileFromChat({ name: '起诉状.docx', fileId: 31 })
  assert.equal(opened[2].id, 31)
})

test('股东大会核查：子文件夹里的材料能解析出名字（不再显示「文件 #id」兜底）', async () => {
  const comp = loadModule(vueScript('components/ShareholderMeetingPanel.vue'), 'return __component', {
    api: { getProjectFiles: backendGetProjectFiles },
    t: (k) => k,
  })
  const vm = { projectId: 7, fileNames: {}, ...comp.methods }
  await vm.resolveFileNames()
  assert.equal(vm.fileNames[21] && vm.fileNames[21].name, '股权转让协议.docx')
  assert.equal(vm.fileNames[15] && vm.fileNames[15].name, '录音样本.mp3')
  assert.equal(vm.fileNames[1] && vm.fileNames[1].name, 'README.txt')
})
