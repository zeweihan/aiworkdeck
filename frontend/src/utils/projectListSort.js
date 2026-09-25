// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
/**
 * 项目列表页的名称搜索与排序（v0.49.0 真机测试 BUG-09）。纯前端过滤，不加请求。
 *
 * 默认「创建时间倒序」= 后端 ProjectService.getUserProjects 现在给的顺序，
 * 没动过排序的人看到的列表与以前一模一样。
 */

export const SORT_KEYS = ['name', 'created', 'updated']
export const DEFAULT_SORT = Object.freeze({ key: 'created', dir: 'desc' })
// 记在本机（同 checkba_project_list_view 视图模式）：这台机器上这个人的习惯，不是账户设置
export const SORT_STORAGE_KEY = 'checkba_project_list_sort'

// 换到一列时的默认方向：名称从 A 到 Z，时间从新到旧
const DEFAULT_DIR = { name: 'asc', created: 'desc', updated: 'desc' }

export function normalizeSort(raw) {
  let v = raw
  if (typeof v === 'string') {
    try { v = JSON.parse(v) } catch (e) { v = null }
  }
  if (!v || typeof v !== 'object' || !SORT_KEYS.includes(v.key)) return { ...DEFAULT_SORT }
  const dir = v.dir === 'asc' || v.dir === 'desc' ? v.dir : DEFAULT_DIR[v.key]
  return { key: v.key, dir }
}

/** 点表头 / 选下拉：同一列翻转方向，换列用该列的默认方向。 */
export function nextSort(current, key) {
  if (!SORT_KEYS.includes(key)) return normalizeSort(current)
  const cur = normalizeSort(current)
  if (cur.key === key) return { key, dir: cur.dir === 'asc' ? 'desc' : 'asc' }
  return { key, dir: DEFAULT_DIR[key] }
}

function timeOf(v) {
  if (!v) return NaN
  const t = new Date(v).getTime()
  return Number.isNaN(t) ? NaN : t
}

const collator = typeof Intl !== 'undefined' && Intl.Collator
  ? new Intl.Collator('zh-Hans-CN', { numeric: true, sensitivity: 'base' })
  : null

function compareName(a, b) {
  const x = String(a || '')
  const y = String(b || '')
  return collator ? collator.compare(x, y) : x.toLowerCase().localeCompare(y.toLowerCase())
}

/**
 * @param {Array} list      原始项目数组（不会被原地修改）
 * @param {string} query    名称关键词，子串匹配、大小写不敏感
 * @param {{key, dir}} sort
 */
export function filterAndSortProjects(list, query, sort) {
  const arr = Array.isArray(list) ? list : []
  const q = String(query || '').trim().toLowerCase()
  const filtered = q ? arr.filter((p) => String((p && p.name) || '').toLowerCase().includes(q)) : arr.slice()
  const { key, dir } = normalizeSort(sort)
  const sign = dir === 'asc' ? 1 : -1
  const field = key === 'created' ? 'createdAt' : 'lastActivityAt'
  return filtered.sort((a, b) => {
    if (key === 'name') return sign * compareName(a && a.name, b && b.name)
    const ta = timeOf(a && a[field])
    const tb = timeOf(b && b[field])
    // 没有时间的一律排最后，不管升序还是倒序
    if (Number.isNaN(ta) && Number.isNaN(tb)) return 0
    if (Number.isNaN(ta)) return 1
    if (Number.isNaN(tb)) return -1
    return sign * (ta - tb)
  })
}
