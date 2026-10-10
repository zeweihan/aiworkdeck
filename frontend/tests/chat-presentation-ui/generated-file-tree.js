// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
await import('./main.js')
const { createApp, h, ref, nextTick } = await import('vue')
const { default: FileTree } = await import('../../src/components/FileTree.vue')
const { i18n } = await import('../../src/i18n/index.js')
window.projectFiles.push(
  { id: 90, name: '目标目录', isFolder: true, parentId: null },
  { id: 98, name: '新报告.docx', fileType: 'docx', parentId: 1, isFolder: false },
  { id: 99, name: '新报告.docx', fileType: 'docx', parentId: null, isFolder: false })
window.fileMoves = []
const request = window.uni.request
window.uni.request = opts => {
  const match = /\/api\/projects\/(\d+)\/files\/(\d+)\/move$/.exec(String(opts.url))
  if (match && opts.method === 'PUT') {
    const file = window.projectFiles.find(f => f.id === Number(match[2]))
    window.fileMoves.push({ projectId: Number(match[1]), fileId: file.id, ...opts.data })
    file.parentId = opts.data.parentId
    return opts.success({ statusCode: 200, data: { ...file } })
  }
  return request(opts)
}
const target = document.createElement('div'); target.id = 'file-tree'; document.body.append(target)
const listeners = new Map()
window.uni.$on = (name, fn) => { if (!listeners.has(name)) listeners.set(name, new Set()); listeners.get(name).add(fn) }
window.uni.$off = (name, fn) => listeners.get(name)?.delete(fn)
window.uni.$emit = (name, ...args) => { for (const fn of listeners.get(name) || []) fn(...args) }
const tree = ref()
createApp({ setup: () => () => h(FileTree, { ref: tree, projectId: 1, showTree: true }) }).use(i18n).mount(target)
await nextTick()
window.fileTree = tree.value
window.dragFixtureReady = true
