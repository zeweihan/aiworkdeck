// evidenceGrouping.js — 审阅面板「证据」页的分组与过滤纯函数（spec §4.3）。
// 输入是后端 LinkView 数组（见 .claude/agents/ai-doc-bridge.md「EvidenceLink 契约」）。

import { normalizeTagType, TAG_TYPE_PARTY } from './tagTypes.js'

export const GROUP_NONE = '__none__'

/** 按 sectionPath 分组，保持首次出现顺序；无 sectionPath 归 GROUP_NONE。 */
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
 * 按 targets 文件的 PARTY 标签分组：一条 link 可落多组（多个 target / 一个文件多个
 * PARTY 标签），同组内不重复；没有任何 PARTY → GROUP_NONE（未归属）。
 * fileTagsById: Map<fileId, tags[]>，tags 来自文件树（getProjectFiles(pid, null, true)）。
 */
export function groupByParty(links, fileTagsById) {
  const map = new Map()
  const put = (key, title, l) => {
    if (!map.has(key)) map.set(key, { key, title, items: [] })
    const g = map.get(key)
    if (!g.items.includes(l)) g.items.push(l)
  }
  for (const l of links || []) {
    let hit = false
    for (const tg of (l && l.targets) || []) {
      const tags = (fileTagsById && fileTagsById.get(Number(tg.fileId))) || []
      for (const tag of tags) {
        if (normalizeTagType(tag) !== TAG_TYPE_PARTY) continue
        hit = true
        put('party:' + tag.id, tag.name || String(tag.id), l)
      }
    }
    if (!hit) put(GROUP_NONE, '', l)
  }
  return [...map.values()]
}

/** status: 'all' | 'active' | 'unverified' | 'stale' | 'orphan' */
export function filterByStatus(links, status) {
  if (!status || status === 'all') return (links || []).slice()
  return (links || []).filter((l) => l && l.status === status)
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
