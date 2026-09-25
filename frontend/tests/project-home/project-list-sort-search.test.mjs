// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 项目列表页的名称搜索与排序（v0.49.0 真机测试 BUG-09 / C1-05）。
 *
 * 真机现象：网格/列表两个视图都没有排序与搜索，列表视图表头点了没反应，
 * 20+ 个案卷只能肉眼翻。
 *
 * 取值规则全在纯函数 utils/projectListSort.js 里（前端过滤，不加请求）；
 * 页面只负责接线：搜索框 → searchQuery、列表表头可点 → toggleSort、
 * 网格视图用 AwdSelect 下拉选排序键；排序选择记在本机（uni storage）。
 *
 * 跑法：cd frontend && node --test tests/project-home/project-list-sort-search.test.mjs
 */
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  DEFAULT_SORT,
  SORT_STORAGE_KEY,
  normalizeSort,
  nextSort,
  filterAndSortProjects,
} from '../../src/utils/projectListSort.js'
import zh from '../../src/locales/zh-CN/projects.js'
import en from '../../src/locales/en-US/projects.js'

const SRC = readFileSync(new URL('../../src/pages/project-list/project-list.vue', import.meta.url), 'utf8')
const TEMPLATE = SRC.match(/<template>([\s\S]*)<\/template>/)[1]

const P = [
  { id: 1, name: '乙公司股权纠纷', createdAt: '2026-09-01T10:00:00', lastActivityAt: '2026-09-20T10:00:00' },
  { id: 2, name: 'Alpha 并购', createdAt: '2026-09-10T10:00:00', lastActivityAt: '2026-09-11T10:00:00' },
  { id: 3, name: '甲公司合同审查', createdAt: '2026-08-01T10:00:00', lastActivityAt: null },
  { id: 4, name: 'alpha 尽调', createdAt: '2026-09-05T10:00:00', lastActivityAt: '2026-09-24T10:00:00' },
]
const ids = (list) => list.map((p) => p.id)

// ---------------- 纯函数 ----------------

test('默认排序与后端现状一致：创建时间倒序（不改变老用户第一眼看到的顺序）', () => {
  assert.deepEqual(DEFAULT_SORT, { key: 'created', dir: 'desc' })
  assert.deepEqual(ids(filterAndSortProjects(P, '', DEFAULT_SORT)), [2, 4, 1, 3])
})

test('按最近修改倒序：没有修改时间的排最后', () => {
  assert.deepEqual(ids(filterAndSortProjects(P, '', { key: 'updated', dir: 'desc' })), [4, 1, 2, 3])
  assert.deepEqual(ids(filterAndSortProjects(P, '', { key: 'updated', dir: 'asc' })), [2, 1, 4, 3],
    '升序时空值也排最后，不能顶到最前面')
})

test('按名称排序：大小写不敏感，中文按本地化比较', () => {
  const asc = filterAndSortProjects(P, '', { key: 'name', dir: 'asc' })
  assert.equal(asc.length, 4)
  const desc = filterAndSortProjects(P, '', { key: 'name', dir: 'desc' })
  assert.deepEqual(ids(desc), ids(asc).reverse())
  // 两个 alpha 相邻（大小写不敏感）
  const pos = ids(asc)
  assert.equal(Math.abs(pos.indexOf(2) - pos.indexOf(4)), 1)
})

test('名称搜索：子串、大小写不敏感、两端空白忽略；不改原数组', () => {
  const before = ids(P)
  assert.deepEqual(ids(filterAndSortProjects(P, '  ALPHA ', DEFAULT_SORT)), [2, 4])
  assert.deepEqual(ids(filterAndSortProjects(P, '公司', DEFAULT_SORT)), [1, 3])
  assert.deepEqual(filterAndSortProjects(P, '不存在的名字', DEFAULT_SORT), [])
  assert.deepEqual(ids(P), before, '排序不许原地改 this.projects')
})

test('nextSort：点同一列翻转方向；换列时名称默认升序、时间默认倒序', () => {
  assert.deepEqual(nextSort({ key: 'created', dir: 'desc' }, 'created'), { key: 'created', dir: 'asc' })
  assert.deepEqual(nextSort({ key: 'created', dir: 'asc' }, 'created'), { key: 'created', dir: 'desc' })
  assert.deepEqual(nextSort({ key: 'created', dir: 'desc' }, 'name'), { key: 'name', dir: 'asc' })
  assert.deepEqual(nextSort({ key: 'name', dir: 'asc' }, 'updated'), { key: 'updated', dir: 'desc' })
})

test('normalizeSort：存储里的脏值回落默认值', () => {
  assert.deepEqual(normalizeSort(null), DEFAULT_SORT)
  assert.deepEqual(normalizeSort('garbage'), DEFAULT_SORT)
  assert.deepEqual(normalizeSort({ key: 'bogus', dir: 'asc' }), DEFAULT_SORT)
  assert.deepEqual(normalizeSort({ key: 'name', dir: 'sideways' }), { key: 'name', dir: 'asc' })
  assert.deepEqual(normalizeSort({ key: 'updated', dir: 'asc' }), { key: 'updated', dir: 'asc' })
  assert.deepEqual(normalizeSort(JSON.stringify({ key: 'name', dir: 'desc' })), { key: 'name', dir: 'desc' })
})

// ---------------- 页面接线 ----------------

test('两个视图都渲染过滤排序后的 visibleProjects，而不是原始 projects', () => {
  assert.ok(!/v-for="project in projects"/.test(TEMPLATE), '还有视图直接遍历 projects，搜索/排序对它无效')
  const n = (TEMPLATE.match(/v-for="project in visibleProjects"/g) || []).length
  assert.equal(n, 2, '网格与列表两个视图都要走 visibleProjects')
  assert.match(SRC, /visibleProjects\(\)\s*\{[\s\S]*?filterAndSortProjects\(this\.projects,\s*this\.searchQuery,\s*this\.sort\)/)
})

test('有搜索框（名称），搜不到时给提示而不是空白', () => {
  assert.match(TEMPLATE, /class="project-search-input"/)
  assert.match(TEMPLATE, /\$t\('projects\.searchPlaceholder'\)/)
  assert.match(TEMPLATE, /\$t\('projects\.noMatch'\)/)
})

test('列表视图表头可点：名称 / 创建时间 / 最近修改三列都挂 toggleSort', () => {
  const head = TEMPLATE.match(/<view class="ptable-head">([\s\S]*?)<\/view>\s*<!--/)[1]
  for (const key of ['name', 'created', 'updated']) {
    assert.ok(head.includes(`@tap="toggleSort('${key}')"`), `表头 ${key} 列点了没反应`)
  }
})

test('网格视图有排序下拉（AwdSelect）', () => {
  assert.match(TEMPLATE, /<AwdSelect[^>]*class="project-sort-select"/)
  assert.match(SRC, /import AwdSelect from '@\/components\/AwdSelect\.vue'/)
})

test('排序选择记在本机：setSort 写 uni storage，restoreSort 读回来', () => {
  const store = {}
  globalThis.uni = {
    setStorageSync: (k, v) => { store[k] = v },
    getStorageSync: (k) => store[k],
  }
  const body = (sig) => {
    const i = SRC.indexOf(sig)
    assert.ok(i > 0, '找不到 ' + sig)
    let depth = 0
    for (let j = i + sig.length - 1; j < SRC.length; j++) {
      if (SRC[j] === '{') depth++
      else if (SRC[j] === '}' && --depth === 0) return SRC.slice(i + sig.length - 1, j + 1)
    }
  }
  const mk = (sig, name) => new Function('SORT_STORAGE_KEY', 'normalizeSort', 'nextSort',
    `return (function ${name}${sig.slice(name.length, -1)}${body(sig)})`)(SORT_STORAGE_KEY, normalizeSort, nextSort)
  const vm = { sort: { ...DEFAULT_SORT } }
  vm.setSort = mk('setSort(sort) {', 'setSort').bind(vm)
  vm.toggleSort = mk('toggleSort(key) {', 'toggleSort').bind(vm)
  vm.restoreSort = mk('restoreSort() {', 'restoreSort').bind(vm)

  vm.toggleSort('name')
  assert.deepEqual(vm.sort, { key: 'name', dir: 'asc' })
  assert.ok(store[SORT_STORAGE_KEY], '没有写进本机存储')

  const vm2 = { sort: { ...DEFAULT_SORT } }
  vm2.restoreSort = mk('restoreSort() {', 'restoreSort').bind(vm2)
  vm2.restoreSort()
  assert.deepEqual(vm2.sort, { key: 'name', dir: 'asc' }, '下次打开要记得上次的排序')
  assert.match(SRC, /onLoad\(\)\s*\{[\s\S]*?this\.restoreSort\(\)/)
  delete globalThis.uni
})

test('新文案 zh/en 成对', () => {
  for (const k of ['searchPlaceholder', 'noMatch', 'sortLabel', 'sortByName', 'sortByCreated', 'sortByUpdated']) {
    assert.ok(zh[k], 'zh 缺 ' + k)
    assert.ok(en[k], 'en 缺 ' + k)
  }
})
