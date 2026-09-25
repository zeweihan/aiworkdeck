// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 审计（dev-board#74）确认的 FileTree.vue 回收站两处缺陷的回归断言。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { findTopmostDeletedAncestor, summarizeDeleteResults, collapseToTopmostSelected } from '../../src/utils/fileTreeRecycle.js'
import { readFileSync } from 'node:fs'

// ---------- 1. 还原嵌套文件时，祖先仍被删除的判定 ----------
//
// 后端还原只向下递归子节点、从不向上恢复祖先。直接还原一个子文件而它的父文件夹
// 仍是软删除状态，还原出来的文件永久不可见（父文件夹不在「files」清单里，永不展开）。

test('祖先链干净（父文件夹已经不在回收站）时返回 null，允许直接还原', () => {
  const item = { id: 3, parentId: 1 }
  const recycleBin = [] // 父文件夹已经被还原过，回收站里已经没有它了
  assert.equal(findTopmostDeletedAncestor(item, recycleBin), null)
})

test('父文件夹仍在回收站里：返回这个父文件夹', () => {
  const folder = { id: 1, parentId: null, name: '合同' }
  const item = { id: 3, parentId: 1 }
  assert.equal(findTopmostDeletedAncestor(item, [folder, item]), folder)
})

test('祖父、父都还在回收站：返回最上层（祖父），不是直接父级', () => {
  const grandparent = { id: 1, parentId: null, name: '案卷' }
  const parent = { id: 2, parentId: 1, name: '合同' }
  const item = { id: 3, parentId: 2 }
  const result = findTopmostDeletedAncestor(item, [grandparent, parent, item])
  assert.equal(result, grandparent, '必须一路走到最上层仍在回收站里的那个祖先，中间随便还原一层都还是看不见')
})

test('祖父已经被还原（不在回收站里）、父仍在：链路到父级为止，返回父级', () => {
  const parent = { id: 2, parentId: 1, name: '合同' } // parentId=1 指向的祖父已经不在回收站列表里了
  const item = { id: 3, parentId: 2 }
  const result = findTopmostDeletedAncestor(item, [parent, item])
  assert.equal(result, parent)
})

test('item 本身就是根级（parentId 为 null/0）：没有祖先要检查', () => {
  assert.equal(findTopmostDeletedAncestor({ id: 1, parentId: null }, []), null)
  assert.equal(findTopmostDeletedAncestor({ id: 1, parentId: 0 }, []), null)
})

test('item 为空或 recycleBin 不是数组：不抛错，返回 null', () => {
  assert.equal(findTopmostDeletedAncestor(null, []), null)
  assert.equal(findTopmostDeletedAncestor({ id: 1, parentId: 1 }, null), null)
  assert.equal(findTopmostDeletedAncestor({ id: 1, parentId: 1 }, []), null)
})

// ---------- 2. 批量彻底删除的结果归并 ----------
//
// 原实现循环里 catch 住每条失败只 console.error，循环结束后无条件把全部 id 从本地
// recycleBin 里过滤掉、弹成功提示——某一条服务端真的失败时，界面显示全部删除成功
// 且行全部消失，但服务端其实还留着那份文档。

test('全部成功：succeededIds 是全量，failedIds 为空', () => {
  const { succeededIds, failedIds } = summarizeDeleteResults([
    { id: 1, ok: true }, { id: 2, ok: true }, { id: 3, ok: true }
  ])
  assert.deepEqual(succeededIds, [1, 2, 3])
  assert.deepEqual(failedIds, [])
})

test('部分失败：失败的 id 不许出现在 succeededIds 里', () => {
  const { succeededIds, failedIds } = summarizeDeleteResults([
    { id: 1, ok: true }, { id: 2, ok: false }, { id: 3, ok: true }
  ])
  assert.deepEqual(succeededIds, [1, 3], '失败的那条不能被当成功处理，否则本地列表会把它连同已删的一起摘掉')
  assert.deepEqual(failedIds, [2])
})

test('404（服务端已经没有这条）在调用方应按成功传入，本函数只认 ok 标记', () => {
  // 调用方负责把 404 映射成 ok:true，本函数不关心失败原因，只做归并
  const { succeededIds, failedIds } = summarizeDeleteResults([{ id: 9, ok: true }])
  assert.deepEqual(succeededIds, [9])
  assert.deepEqual(failedIds, [])
})

test('空输入不抛错', () => {
  assert.deepEqual(summarizeDeleteResults([]), { succeededIds: [], failedIds: [] })
  assert.deepEqual(summarizeDeleteResults(undefined), { succeededIds: [], failedIds: [] })
})

// ---------- 3. 批量彻底删除：勾选的文件夹会级联带走子孙（v0.49.0 BUG-03） ----------
//
// 真机：回收站里是 1 个文件夹 + 它的 100 个子文件，全选彻底删除。先删的文件夹在服务端
// 级联带走了 100 个子行，紧接着对子行逐条请求全部「文件不存在」→ toast「100/104 项彻底
// 删除失败」，本地列表不刷新，重试次次同样失败。已被勾选祖先覆盖的子孙不该再单独请求，
// 结果跟着祖先走。

test('子孙与祖先同时被勾选：只对最上层发请求，子孙记在祖先名下', () => {
  const bin = [
    { id: 1, parentId: null, name: 'perf100' },
    { id: 2, parentId: 1, name: 'a.txt' },
    { id: 3, parentId: 1, name: 'sub' },
    { id: 4, parentId: 3, name: 'b.txt' },
    { id: 9, parentId: null, name: '长文件名.txt' }
  ]
  const { roots, coveredBy } = collapseToTopmostSelected([1, 2, 3, 4, 9], bin)
  assert.deepEqual(roots, [1, 9])
  assert.equal(coveredBy.get(2), 1)
  assert.equal(coveredBy.get(4), 1, '孙辈要一路归到最上层被勾选的祖先，中间那层也被勾选了也一样')
  assert.equal(coveredBy.get(3), 1)
})

test('祖先在回收站里但没被勾选：子孙照常单独删', () => {
  const bin = [
    { id: 1, parentId: null, name: 'perf100' },
    { id: 2, parentId: 1, name: 'a.txt' }
  ]
  const { roots, coveredBy } = collapseToTopmostSelected([2], bin)
  assert.deepEqual(roots, [2])
  assert.equal(coveredBy.size, 0)
})

test('字符串与数字 id 混用、空输入不抛错', () => {
  const bin = [{ id: 1, parentId: null }, { id: 2, parentId: '1' }]
  const { roots, coveredBy } = collapseToTopmostSelected(['1', 2], bin)
  assert.deepEqual(roots, ['1'])
  assert.equal(coveredBy.get(2), '1')
  assert.deepEqual(collapseToTopmostSelected(undefined, null).roots, [])
})

test('FileTree 批量彻底删除接了 collapseToTopmostSelected，删完从服务端重拉回收站', () => {
  const src = readFileSync(new URL('../../src/components/FileTree.vue', import.meta.url), 'utf8')
  const start = src.indexOf('async executeBatchDelete()')
  assert.ok(start > 0, '找不到 executeBatchDelete')
  const body = src.slice(start, src.indexOf('// Soft Batch Delete', start))
  assert.match(body, /collapseToTopmostSelected\(ids,\s*this\.recycleBin\)/, '必须先把被勾选祖先覆盖的子孙折叠掉')
  assert.match(body, /for \(const id of roots\)/, '只对最上层逐条请求')
  assert.match(body, /await this\.loadFiles\(\)/, '删完要从服务端重拉回收站，不能让本地陈旧行永远卡着')
})
