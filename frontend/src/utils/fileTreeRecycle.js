// FileTree.vue 回收站相关的纯判定逻辑，抽出来是为了能在 node:test 里直接单测
// （FileTree.vue 体量太大又是组件，测不动整个文件）。

/**
 * 找出 item 的祖先链里，离它最远（最靠上）的那个仍在回收站里的文件夹。
 *
 * 病灶：后端 restoreFile 只向下递归子节点、从不向上恢复祖先。如果直接还原一个
 * 子文件而它的父文件夹（或更上层）仍是软删除状态，文件树建树时根层只收
 * parentId 为空的节点、子层只收「已展开文件夹」的子节点——那个父文件夹压根不在
 * 列表里，永不展开，还原出来的文件因此永久不可见（但后端数据其实已经是「未删除」）。
 *
 * 用法：还原前用这个函数查一下，有返回值就说明直接还原会让文件消失，
 * 应该先把返回的这个文件夹还原掉（其后端还原会向下级联，把 item 一并带出来）。
 *
 * @param {{parentId: number|string|null}} item
 * @param {Array<{id: number|string, parentId: number|string|null}>} recycleBinList 当前回收站列表（still-deleted 的清单）
 * @returns {object|null} 仍在回收站里的最上层祖先节点；链路已经干净则返回 null
 */
export function findTopmostDeletedAncestor(item, recycleBinList) {
  if (!item || !Array.isArray(recycleBinList) || recycleBinList.length === 0) return null
  const byId = new Map(recycleBinList.map((f) => [f.id, f]))
  const seen = new Set()
  let result = null
  let parentId = item.parentId
  while (parentId !== null && parentId !== undefined && parentId !== 0) {
    if (seen.has(parentId)) break // 环路保护：数据不应该出现，但别死循环
    seen.add(parentId)
    const parent = byId.get(parentId)
    if (!parent) break // 这一级父节点不在回收站里，说明没被删除，链路到此就安全了
    result = parent
    parentId = parent.parentId
  }
  return result
}

/**
 * 把「批量彻底删除」逐条调用的结果归并成成功/失败两组。
 *
 * 病灶：原先的实现循环里 catch 住每条的失败（非 404 只 console.error），循环结束后
 * 却无条件把全部 id 从本地回收站列表里 splice 掉、弹「删除成功」——某一条服务端
 * 真的失败时，界面显示全部删除成功且行全部消失，但服务端其实还留着那份文档。
 *
 * @param {Array<{id: number|string, ok: boolean}>} results 每条 id 的删除结果（ok=false 表示真失败，不含"服务端已经没有这条=视为成功"的 404）
 * @returns {{succeededIds: Array, failedIds: Array}}
 */
export function summarizeDeleteResults(results) {
  const succeededIds = []
  const failedIds = []
  for (const r of results || []) {
    if (!r) continue
    if (r.ok) succeededIds.push(r.id)
    else failedIds.push(r.id)
  }
  return { succeededIds, failedIds }
}
