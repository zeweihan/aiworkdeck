// useEvidenceAnchors.js — EvidenceLink 锚点核对状态机（spec §4.4，dev-board#105）。
// LibreOfficeEditor 只做接线：把 executor / API / 缓存读写喂进来，这里负责
// 判定（classifyAnchorResults）、回写本地状态（applyReport）与调度（createAnchorChecker）。
// 全部可在 node 下跑单测（tests/evidence/anchorCheck.test.mjs）。

/**
 * 把 worker check_link_anchors 的结果对照缓存分类。
 * - `!exists || text===''` → orphan（已是 orphan 不重复上报）
 * - orphan 不因 exists=true 复活（状态机：只有用户「重新指定」能救）
 * - hash 变 → stale；已是 stale 的不重复上报（F4），也不再弹条
 * @returns {{reports: Array<{linkKey,exists,text}>, staleHits: Array<{linkKey,text,link}>}}
 */
export async function classifyAnchorResults(cache, items, hashFn) {
  const byKey = new Map((cache || []).map((l) => [l.linkKey, l]))
  const reports = []
  const staleHits = []
  for (const it of items || []) {
    const link = byKey.get(it && it.name)
    if (!link) continue
    const text = String(it.text || '')
    if (!it.exists || text === '') {
      if (link.status !== 'orphan') reports.push({ linkKey: it.name, exists: false, text: '' })
      continue
    }
    if (link.status === 'orphan') continue
    if (link.status === 'stale') continue
    const h = await hashFn(text)
    if (h !== link.anchorHash) {
      reports.push({ linkKey: it.name, exists: true, text })
      staleHits.push({ linkKey: it.name, text, link })
    }
  }
  return { reports, staleHits }
}

/** 按后端返回的 changed 改本地缓存状态；返回真正变了的 linkKey 集合。 */
export function applyReport(cache, reports, changed) {
  const ok = new Set(Array.isArray(changed) ? changed : (reports || []).map((r) => r.linkKey))
  const byKey = new Map((cache || []).map((l) => [l.linkKey, l]))
  const applied = new Set()
  for (const rep of reports || []) {
    const link = byKey.get(rep.linkKey)
    if (!link || !ok.has(rep.linkKey)) continue
    link.status = rep.exists ? 'stale' : 'orphan'
    applied.add(rep.linkKey)
  }
  return applied
}

/** 「保留关联」用文档里现在的文字：先问 worker，问不到才回退缓存文字（F2）。 */
export async function resolveKeepText(exec, linkKey, fallback) {
  try {
    const r = await exec('check_link_anchors', { names: [linkKey] })
    const item = r && Array.isArray(r.items) ? r.items[0] : null
    if (item && item.exists && String(item.text || '') !== '') return String(item.text)
  } catch (e) { /* 回退 */ }
  return fallback == null ? null : String(fallback)
}

/**
 * 调度器：防抖 / 忙时让路重试 / 重入时 defer 而非丢弃（F3）/ 分批 ≤ batch。
 * deps: { getCache, exec(action, params), report(reports)→{changed}, hash(text),
 *         isBusy(), canRun(), onChanged(appliedSet), onStale(staleHits), log?,
 *         setTimeout?, clearTimeout? }
 */
export function createAnchorChecker(deps) {
  const setT = deps.setTimeout || ((fn, ms) => setTimeout(fn, ms))
  const clearT = deps.clearTimeout || ((t) => clearTimeout(t))
  const batch = deps.batch || 200
  const debounceMs = deps.debounceMs == null ? 3000 : deps.debounceMs
  const retryMs = deps.retryMs == null ? 1000 : deps.retryMs
  const log = deps.log || (() => {})
  const st = { timer: null, checking: false, recheck: false, disposed: false }

  const arm = (ms) => {
    clearT(st.timer)
    st.timer = setT(() => { st.timer = null; run() }, ms)
  }

  async function run(opts) {
    if (st.disposed) return false
    const cache = deps.getCache()
    if (!cache || !cache.length || !deps.canRun()) return false
    if (st.checking) { st.recheck = true; return false }
    if (deps.isBusy()) {
      if (!(opts && opts.noRetry)) arm(retryMs)
      return false
    }
    st.checking = true
    try {
      const names = cache.map((l) => l.linkKey)
      const items = []
      for (let i = 0; i < names.length; i += batch) {
        const r = await deps.exec('check_link_anchors', { names: names.slice(i, i + batch) })
        if (r && r.success && Array.isArray(r.items)) items.push(...r.items)
      }
      const { reports, staleHits } = await classifyAnchorResults(cache, items, deps.hash)
      if (!reports.length) return true
      const res = await deps.report(reports)
      const applied = applyReport(cache, reports, res && Array.isArray(res.changed) ? res.changed : null)
      if (!applied.size) return true
      const hits = staleHits.filter((s) => applied.has(s.linkKey))
      if (hits.length && deps.onStale) deps.onStale(hits)
      if (deps.onChanged) deps.onChanged(applied)
      return true
    } catch (e) {
      log('anchor check failed: ' + (e && e.message ? e.message : e))
      return false
    } finally {
      st.checking = false
      if (st.recheck && !st.disposed) { st.recheck = false; arm(0) }
    }
  }

  return {
    schedule() { if (st.disposed) return; const c = deps.getCache(); if (!c || !c.length) return; arm(debounceMs) },
    run,
    /** 关闭/保存前结账：有待跑的防抖或被 defer 的重查就立刻跑一次。 */
    async flush() {
      const pending = !!st.timer || st.recheck
      clearT(st.timer); st.timer = null
      if (!pending) return false
      st.recheck = false
      return run({ noRetry: true })
    },
    state: st,
    dispose() { st.disposed = true; clearT(st.timer); st.timer = null },
  }
}
