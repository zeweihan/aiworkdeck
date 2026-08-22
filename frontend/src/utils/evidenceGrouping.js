// evidenceGrouping.js — 审阅面板「底稿」页的分组与过滤纯函数（spec §4.3；P2 补章节树/筛选/统计）。
// 输入是后端 LinkView 数组（见 .claude/agents/ai-doc-bridge.md「EvidenceLink 契约」）。

import { normalizeTagType, TAG_TYPE_PARTY } from './tagTypes.js'

export const GROUP_NONE = '__none__'

/** 状态枚举的展示顺序；统计条与筛选 chip 都按它排（'all' 单独在最前，不在这里）。 */
export const STATUS_KEYS = ['active', 'unverified', 'stale', 'orphan']

/** `一/（二）/3` → ['一','（二）','3']；空/undefined → []。 */
export function sectionSegments(path) {
  return String(path == null ? '' : path).split('/').map((s) => s.trim()).filter(Boolean)
}

/** 按 sectionPath 分组（平铺一层），保持首次出现顺序；无 sectionPath 归 GROUP_NONE。 */
export function groupBySection(links) {
  const map = new Map()
  for (const l of links || []) {
    const key = (l && l.sectionPath) || GROUP_NONE
    if (!map.has(key)) map.set(key, { key, title: key === GROUP_NONE ? '' : (l.sectionTitle || key), items: [] })
    map.get(key).items.push(l)
  }
  return [...map.values()]
}

/**
 * 按章节树分组：一级 = sectionPath 第一段，二级 = 前两段。三级及以下并入所属的二级组
 * （面板只有 288px 宽，再深一层读不出层次；筛选仍能按任意前缀收窄）。
 * 只有一段路径的 link 挂在一级组的 `items` 上（直属），空路径整组归 GROUP_NONE。
 * 组标题优先取「sectionPath 恰好等于该组 key」的那条 link 的 sectionTitle（worker 给的
 * 展示用标题，比路径段好读），取不到就用路径段本身。
 * 返回 [{ key, title, items[], children:[{ key, title, items[] }], count }]，顺序 = 首次出现顺序。
 */
export function groupBySectionTree(links) {
  const titleOf = new Map()
  for (const l of links || []) {
    if (l && l.sectionPath && l.sectionTitle && !titleOf.has(l.sectionPath)) titleOf.set(l.sectionPath, l.sectionTitle)
  }
  const roots = new Map()
  for (const l of links || []) {
    if (!l) continue
    const segs = sectionSegments(l.sectionPath)
    const rootKey = segs[0] || GROUP_NONE
    if (!roots.has(rootKey)) {
      roots.set(rootKey, { key: rootKey, title: rootKey === GROUP_NONE ? '' : (titleOf.get(rootKey) || rootKey), items: [], children: [] })
    }
    const root = roots.get(rootKey)
    if (segs.length <= 1) { root.items.push(l); continue }
    const childKey = segs.slice(0, 2).join('/')
    let child = root.children.find((c) => c.key === childKey)
    if (!child) { child = { key: childKey, title: titleOf.get(childKey) || segs[1], items: [] }; root.children.push(child) }
    child.items.push(l)
  }
  const out = [...roots.values()]
  for (const r of out) r.count = r.items.length + r.children.reduce((n, c) => n + c.items.length, 0)
  return out
}

/**
 * 一条 link 的 PARTY 归属（按 targets 文件的标签去重，保持首次出现顺序）；空数组 = 未归属。
 * fileTagsById: Map<fileId, tags[]>，tags 来自文件树（getProjectFiles(pid, null, true)）。
 */
export function partiesOf(link, fileTagsById) {
  const out = []
  const seen = new Set()
  for (const tg of (link && link.targets) || []) {
    for (const tag of (fileTagsById && fileTagsById.get(Number(tg.fileId))) || []) {
      if (normalizeTagType(tag) !== TAG_TYPE_PARTY) continue
      const key = 'party:' + tag.id
      if (seen.has(key)) continue
      seen.add(key)
      out.push({ key, label: tag.name || String(tag.id) })
    }
  }
  return out
}

/**
 * 按 targets 文件的 PARTY 标签分组：一条 link 可落多组（多个 target / 一个文件多个
 * PARTY 标签），同组内不重复；没有任何 PARTY → GROUP_NONE（未归属）。
 */
export function groupByParty(links, fileTagsById) {
  const map = new Map()
  const put = (key, title, l) => {
    if (!map.has(key)) map.set(key, { key, title, items: [] })
    const g = map.get(key)
    if (!g.items.includes(l)) g.items.push(l)
  }
  for (const l of links || []) {
    const parties = partiesOf(l, fileTagsById)
    if (!parties.length) { put(GROUP_NONE, '', l); continue }
    for (const p of parties) put(p.key, p.label, l)
  }
  return [...map.values()]
}

/** status: 'all' | 'active' | 'unverified' | 'stale' | 'orphan' */
export function filterByStatus(links, status) {
  if (!status || status === 'all') return (links || []).slice()
  return (links || []).filter((l) => l && l.status === status)
}

/**
 * 按章节筛选。key 取自 sectionOptions：'all' 全要，GROUP_NONE 只要没有 sectionPath 的，
 * 其余按**路径段前缀**匹配（选「一」连它下面的「一/（一）/3」一起要，不是字符串 startsWith——
 * 那样「一」会把「一〇」也吃进来）。
 */
export function filterBySection(links, key) {
  if (!key || key === 'all') return (links || []).slice()
  if (key === GROUP_NONE) return (links || []).filter((l) => l && !sectionSegments(l.sectionPath).length)
  const want = sectionSegments(key)
  if (!want.length) return (links || []).slice()
  return (links || []).filter((l) => {
    const segs = sectionSegments(l && l.sectionPath)
    return segs.length >= want.length && want.every((w, i) => segs[i] === w)
  })
}

/** 按主体筛选。key 取自 partyOptions：'all' 全要，GROUP_NONE 只要未归属的，其余 'party:<tagId>'。 */
export function filterByParty(links, fileTagsById, key) {
  if (!key || key === 'all') return (links || []).slice()
  return (links || []).filter((l) => {
    const parties = partiesOf(l, fileTagsById)
    if (key === GROUP_NONE) return !parties.length
    return parties.some((p) => p.key === key)
  })
}

/** 章节筛选下拉的选项（按章节树的顺序展开成两级），label 为空 = 让组件按 GROUP_NONE 取文案。 */
export function sectionOptions(links) {
  const out = []
  for (const r of groupBySectionTree(links)) {
    out.push({ key: r.key, label: r.key === GROUP_NONE ? '' : (r.title || r.key), depth: 0 })
    for (const c of r.children) out.push({ key: c.key, label: c.title || c.key, depth: 1 })
  }
  return out
}

/** 主体筛选下拉的选项；有未归属的 link 才追加 GROUP_NONE 一项。 */
export function partyOptions(links, fileTagsById) {
  const out = []
  const seen = new Set()
  let none = false
  for (const l of links || []) {
    const parties = partiesOf(l, fileTagsById)
    if (!parties.length) { none = true; continue }
    for (const p of parties) {
      if (seen.has(p.key)) continue
      seen.add(p.key)
      out.push({ key: p.key, label: p.label, depth: 0 })
    }
  }
  if (none) out.push({ key: GROUP_NONE, label: '', depth: 0 })
  return out
}

/**
 * 顶部统计：总数 + 各状态数。**不补齐、不猜**——后端给了枚举外的 status 时只计进 total，
 * 各桶之和小于 total 是如实呈现，不许硬塞进某个桶。
 */
export function statusCounts(links) {
  const out = { total: 0, active: 0, unverified: 0, stale: 0, orphan: 0 }
  for (const l of links || []) {
    if (!l) continue
    out.total++
    const s = l.status || 'active'
    if (Object.prototype.hasOwnProperty.call(out, s) && s !== 'total') out[s]++
  }
  return out
}

/** 文件树（tree=true）→ Map<fileId, tags[]>，递归子节点。 */
export function collectFileTags(nodes, out = new Map()) {
  for (const n of nodes || []) {
    if (!n) continue
    if (Array.isArray(n.tags) && n.tags.length) out.set(Number(n.id), n.tags)
    if (Array.isArray(n.children)) collectFileTags(n.children, out)
  }
  return out
}
