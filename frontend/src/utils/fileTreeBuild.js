// FileTree.vue 树形视图构建的纯函数部分（dev-board#107 单元 F3），抽出来是为了能在
// node:test 里直接单测（FileTree.vue 体量太大又是组件，测不动整个文件）。
//
// 此前 buildTreeView 每递归一层都对全量 allFiles 做一次 filter+sort（O(N) per
// 展开文件夹），千节点树、多层展开时是 O(N × 展开文件夹数)。这里改成先用一次 for 循环
// 按 parentId 分组建 Map（groupByParent，一次 O(N)），递归只在分组表里取子集
// （buildTreeFromGroups，不再 filter 全量数组）。

const HIDDEN_NAMES = new Set(['.stagezone', '__staging_area__'])

/** parentId 归一化：null/undefined/0 统一当「根」处理，与既有语义一致。 */
function normalizeParentId(parentId) {
  return (parentId === null || parentId === undefined || parentId === 0) ? null : parentId
}

/**
 * 按 parentId 分组，一次 O(N) 遍历；隐藏的系统文件夹（.stagezone/__staging_area__）
 * 在分组这一步就被剔除，递归时不用再逐层过滤。
 */
export function groupByParent(allFiles) {
  const byParent = new Map()
  for (const f of allFiles) {
    if (HIDDEN_NAMES.has(f.name)) continue
    const key = normalizeParentId(f.parentId)
    let bucket = byParent.get(key)
    if (!bucket) {
      bucket = []
      byParent.set(key, bucket)
    }
    bucket.push(f)
  }
  return byParent
}

/**
 * 递归构建展示列表。compareFn/isExpanded 由调用方注入（组件里依赖 sortMode/sortOrder/
 * expandedFolders 等状态），这里保持无副作用、不碰 allFiles 全量数组。
 */
export function buildTreeFromGroups(byParent, parentId, compareFn, isExpanded) {
  const key = normalizeParentId(parentId)
  const children = (byParent.get(key) || []).slice()
  children.sort(compareFn)

  const result = []
  for (const item of children) {
    result.push(item)
    if (item.isFolder && isExpanded(item.id)) {
      result.push(...buildTreeFromGroups(byParent, item.id, compareFn, isExpanded))
    }
  }
  return result
}
