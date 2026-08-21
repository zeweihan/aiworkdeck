// FileTree.vue 树构建的分组/递归纯函数（dev-board#107 单元 F3）：
//   cd frontend && npm run test:evidence
//
// 此前 buildTreeView 每递归一层都对全量 allFiles 做一次 filter+sort，千节点树、
// 多层展开时是 O(N × 展开文件夹数)。groupByParent 先一次 O(N) 分组，
// buildTreeFromGroups 递归只在分组表里取子集——本用例直接锁死「不再 filter 全量数组」
// 与「1000 节点构建足够快」两条，回归就会立刻变红。
import test from 'node:test'
import assert from 'node:assert/strict'
import { groupByParent, buildTreeFromGroups } from '../../src/utils/fileTreeBuild.js'

function folder(id, parentId, name) {
  return { id, parentId, isFolder: true, name }
}
function file(id, parentId, name) {
  return { id, parentId, isFolder: false, name }
}

function defaultCompare(a, b) {
  if (a.isFolder && !b.isFolder) return -1
  if (!a.isFolder && b.isFolder) return 1
  return (a.name || '').localeCompare(b.name || '', 'zh-CN', { numeric: true })
}

test('隐藏系统文件夹在分组阶段就被剔除', () => {
  const files = [
    folder(1, null, '.stagezone'),
    folder(2, null, '__staging_area__'),
    folder(3, null, '正常文件夹'),
  ]
  const byParent = groupByParent(files)
  const root = byParent.get(null)
  assert.equal(root.length, 1)
  assert.equal(root[0].id, 3)
})

test('文件夹优先、按中文名排序，parentId=0 与 null/undefined 一样当根', () => {
  const files = [
    file(1, null, 'b.txt'),
    folder(2, undefined, 'A文件夹'),
    file(3, 0, 'a.txt'),
  ]
  const byParent = groupByParent(files)
  const tree = buildTreeFromGroups(byParent, null, defaultCompare, () => false)
  assert.deepEqual(tree.map(f => f.id), [2, 3, 1], '文件夹置顶，文件按名称排序')
})

test('展开的文件夹递归拼入子项，未展开的不递归', () => {
  const files = [
    folder(1, null, '文件夹A'),
    file(2, 1, '子文件.txt'),
    folder(3, null, '文件夹B'),
    file(4, 3, '不该出现.txt'),
  ]
  const byParent = groupByParent(files)
  const expanded = new Set([1])
  const tree = buildTreeFromGroups(byParent, null, defaultCompare, id => expanded.has(id))
  assert.deepEqual(tree.map(f => f.id), [1, 2, 3], '只有展开的文件夹 1 的子项被拼入')
})

test('buildTreeFromGroups 不调用 Array.prototype.filter（分组表取子集，不再扫全量数组）', () => {
  const files = []
  // 文件夹 id 从 1 开始：0 是 parentId 的「根」哨兵值（与 FileTree.vue 既有语义一致），
  // 用它当真实文件夹 id 会和「顶层」混淆。
  for (let i = 1; i <= 200; i++) {
    files.push(folder(i, null, `folder-${i}`))
    for (let j = 0; j < 5; j++) {
      files.push(file(1000 + i * 5 + j, i, `file-${i}-${j}.txt`))
    }
  }
  const byParent = groupByParent(files)
  const expanded = new Set(files.filter(f => f.isFolder).map(f => f.id))

  const originalFilter = Array.prototype.filter
  let filterCalls = 0
  Array.prototype.filter = function (...args) {
    filterCalls++
    return originalFilter.apply(this, args)
  }
  try {
    buildTreeFromGroups(byParent, null, defaultCompare, id => expanded.has(id))
  } finally {
    Array.prototype.filter = originalFilter
  }
  assert.equal(filterCalls, 0, 'buildTreeFromGroups 不该再对数组做 filter')
})

test('1000 节点（含多层展开）构建耗时在数量级上应远低于 50ms', () => {
  const files = []
  // 100 个顶层文件夹，每个 9 个子文件，构成约 1000 个节点（id 从 1 开始，理由同上）
  for (let i = 1; i <= 100; i++) {
    files.push(folder(i, null, `folder-${i}`))
    for (let j = 0; j < 9; j++) {
      files.push(file(1000 + i * 9 + j, i, `file-${i}-${j}.txt`))
    }
  }
  assert.equal(files.length, 1000)
  const byParent = groupByParent(files)
  const expanded = new Set(files.filter(f => f.isFolder).map(f => f.id))

  const start = performance.now()
  const tree = buildTreeFromGroups(byParent, null, defaultCompare, id => expanded.has(id))
  const elapsed = performance.now() - start

  assert.equal(tree.length, 1000)
  assert.ok(elapsed < 50, `构建 1000 节点耗时 ${elapsed}ms，应 < 50ms`)
})
