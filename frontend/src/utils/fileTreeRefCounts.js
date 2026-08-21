// FileTree.vue「被引用 N 次」角标的拉取调度（dev-board#107，#550 复核 M2）。
// 纯逻辑抽出来为了能在 node:test 里直接单测；组件只负责把 fetch/apply 注进来。
//
// 四条规则：
// 1. 300ms 防抖：展开/收起/展开更多连点时只发一轮请求；
// 2. 已经拿到过计数、且没有整树 reload 的 id 不再重复请求；
// 3. generation 计数：reload 或切项目后，之前在途的响应一律丢弃；
// 4. 响应里缺失的 id 视为 0，覆盖旧值——角标要能从 3 变回 0，不能只增不减。

export function createRefCountsFetcher({ fetch, apply, delayMs = 300, batchSize = 200, timers = globalThis }) {
  let timer = null
  let generation = 0
  let pendingProjectId = null
  let pendingIds = new Set()
  const requested = new Set()

  function reset() {
    requested.clear()
    pendingIds = new Set()
    generation++
    if (timer !== null) {
      timers.clearTimeout(timer)
      timer = null
    }
  }

  async function flush() {
    timer = null
    const projectId = pendingProjectId
    const ids = Array.from(pendingIds)
    pendingIds = new Set()
    if (!projectId || ids.length === 0) return
    const gen = generation
    for (let i = 0; i < ids.length; i += batchSize) {
      const batch = ids.slice(i, i + batchSize)
      let res
      try {
        res = await fetch(projectId, batch)
      } catch (e) {
        // 端点未合并/出错：角标就不显示；放开这批 id，下次刷新再试
        batch.forEach(id => requested.delete(id))
        continue
      }
      if (gen !== generation) return // 过期响应：期间发生了 reload/切项目
      const counts = (res && res.data && typeof res.data === 'object') ? res.data : res
      const normalized = {}
      for (const id of batch) {
        const v = counts && typeof counts === 'object' ? Number(counts[id]) : 0
        normalized[id] = Number.isFinite(v) && v > 0 ? v : 0
      }
      apply(normalized)
    }
  }

  /**
   * @param {number|string} projectId
   * @param {Array<number|string>} ids 当前渲染出来的文件 id
   * @param {{reload?: boolean}} [opts] reload=true 表示整树重载/切项目：清掉「已请求」记忆并丢弃在途响应
   */
  function schedule(projectId, ids, { reload = false } = {}) {
    if (reload || (pendingProjectId !== null && pendingProjectId !== projectId)) reset()
    pendingProjectId = projectId
    if (!projectId) return
    for (const id of ids || []) {
      if (!requested.has(id)) {
        requested.add(id)
        pendingIds.add(id)
      }
    }
    if (pendingIds.size === 0) return
    if (timer !== null) timers.clearTimeout(timer)
    timer = timers.setTimeout(flush, delayMs)
  }

  return { schedule, reset }
}
