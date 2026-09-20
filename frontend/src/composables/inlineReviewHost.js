// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

const preferenceListeners = new Map()
const unwrap = (value) => value && typeof value === 'object' && 'code' in value && 'data' in value ? value.data : value
const hashKey = value => {
  let hash = 2166136261
  for (const ch of String(value ?? '')) { hash ^= ch.codePointAt(0); hash = Math.imul(hash, 16777619) }
  return (hash >>> 0).toString(36)
}
const snapshotHash = (snap) => hashKey(
  (snap.truncated ? 't|' : 'f|') + snap.paragraphs.map((p) => p.index + ':' + p.text).join(''))

/** Review the live Writer snapshot. Typing may invoke rules, never a model or external lookup. */
export function createInlineReviewHost({ projectId, fileId, userId, execute, send, review, storage, openInsight, openPanel, onState,
  writable = true, active = true, delay = 2500, timers = { set: (fn, ms) => setTimeout(fn, ms), clear: id => clearTimeout(id) } }) {
  const session = `review:${projectId}:${fileId}:${Date.now()}:${Math.random().toString(36).slice(2)}`
  const key = `awd_inline_review_${userId}`
  const layoutKey = `awd_inline_review_layout_${hashKey(userId)}`
  // 默认开启但安静（dev-board#723 拍板）：不关检查，只是结果不再压正文。
  // hidden 只管正文里的浮球，与 enabled 是两件事——不想被浮球打扰 ≠ 不想要检查。
  let enabled = true, hidden = false
  try {
    const saved = storage?.get(key)
    if (saved && typeof saved === 'object') {
      if (saved.enabled === false) enabled = false
      if (saved.hidden === true) hidden = true
    }
  } catch { /* default */ }
  let disposed = false, generation = 0, timer = null, localBusy = false, deepBusy = false, dirty = true
  let state = { session, layoutKey, revision: null, enabled, hidden, active, writable, status: 'stale', deepStatus: 'idle', findings: [], truncated: false }
  let deepRevision = null
  // 全文快照没变就不再重发 POST：律师改一处格式、滚一次页、切回标签都会让 revision 前进，
  // 但正文一个字没动时上一轮的结论逐条仍然成立（dev-board#724 降资源第二项）。
  let cachedHash = null, cachedResult = null
  const publish = (next = {}) => {
    if (disposed) return
    state = { ...state, ...next, session, layoutKey, enabled, hidden, active, writable }
    send({ __lo: 'lo-relay', type: 'inline-review-state', ...state })
    try { onState?.({ ...state }) } catch { /* 宿主渲染失败不能带塌检查 */ }
  }
  function schedule() {
    if (disposed || !enabled || !active || !dirty || localBusy) return
    if (timer != null) timers.clear(timer)
    timer = timers.set(() => { timer = null; return run(false) }, delay)
  }
  function invalidate() {
    if (disposed) return
    generation++; dirty = true; deepRevision = null
    publish({ revision: null, status: enabled ? 'stale' : 'disabled', deepStatus: deepBusy ? 'checking' : 'idle', findings: [], message: '', summary: '', deepReason: '', deepRetried: false })
    schedule()
  }
  function preferences(next) {
    if (!next || typeof next !== 'object') return
    const nextEnabled = typeof next.enabled === 'boolean' ? next.enabled : enabled
    const nextHidden = typeof next.hidden === 'boolean' ? next.hidden : hidden
    if (nextEnabled === enabled && nextHidden === hidden) return
    hidden = nextHidden
    if (nextEnabled === enabled) { publish({}); return }
    enabled = nextEnabled
    if (timer != null) { timers.clear(timer); timer = null }
    invalidate()
  }
  /** 偏好写盘 + 广播给同一用户的其它标签（同一把钥匙的监听器共享）。 */
  function writePreferences(next) {
    if (disposed || !next || typeof next !== 'object') return
    const merged = { enabled, hidden, ...next }
    if (merged.enabled === enabled && merged.hidden === hidden) return
    try { storage?.set(key, merged) } catch { /* 存不下也要让本会话立刻生效 */ }
    for (const listener of preferenceListeners.get(key) || []) listener(merged)
  }
  if (!preferenceListeners.has(key)) preferenceListeners.set(key, new Set())
  preferenceListeners.get(key).add(preferences)
  const current = (gen) => !disposed && enabled && gen === generation

  async function snapshot(gen) {
    const paragraphs = []
    let revision, start = 0, chars = 0, truncated = false, exhausted = false
    // Pagination has a revision fence: never splice paragraphs from different edits.
    for (let page = 0; page < 60; page++) {
      const result = await execute('get_document_text', { startParagraph: start, maxParagraphs: 500, __agent: true })
      if (!current(gen)) return null
      if (!result?.success || result.revision == null || !Array.isArray(result.paragraphs)) throw new Error('REVIEW_SNAPSHOT_FAILED')
      if (revision == null) revision = result.revision
      if (revision !== result.revision) { invalidate(); return null }
      for (const p of result.paragraphs) {
        if (!Number.isInteger(p.index) || p.index < 0 || typeof p.text !== 'string') throw new Error('REVIEW_SNAPSHOT_FAILED')
        // The worker can locate up to 15,000 UTF-16 units in one body paragraph.
        // Skip only an oversized paragraph, keep checking the rest, and disclose the gap.
        if (p.text.length > 15000) { truncated = true; continue }
        const size = p.text.length + (paragraphs.length ? 1 : 0)
        if (paragraphs.length >= 10000 || chars + size > 200000) { truncated = true; exhausted = true; break }
        paragraphs.push({ index: p.index, text: p.text }); chars += size
      }
      if (exhausted || !result.truncated) break
      if (!Number.isInteger(result.nextStartParagraph) || result.nextStartParagraph <= start) throw new Error('REVIEW_SNAPSHOT_FAILED')
      start = result.nextStartParagraph
      if (page === 59) truncated = true
    }
    return { paragraphs, revision, truncated }
  }
  async function run(deep) {
    if (disposed || !enabled || !active || (deep ? !writable || deepBusy : localBusy)) return false
    const gen = generation
    if (deep) { deepBusy = true; publish({ deepStatus: 'checking', message: '' }) }
    else { localBusy = true; dirty = false; publish({ status: 'checking', message: '' }) }
    try {
      const snap = await snapshot(gen)
      if (!snap || !current(gen)) return false
      const hash = snapshotHash(snap)
      const reuse = !deep && cachedHash === hash && cachedResult
      const result = reuse ? cachedResult : unwrap(await review(projectId, { docFileId: fileId, paragraphs: snap.paragraphs, truncated: snap.truncated, deep }))
      if (!current(gen)) return false
      // The worker revision also covers edits whose host modified relay has not arrived yet.
      const context = await execute('get_review_context', {})
      if (!current(gen)) return false
      if (context?.revision == null) throw new Error('REVIEW_SNAPSHOT_FAILED')
      if (context.reason === 'inline-revisions') throw new Error('REVIEW_INLINE_REVISIONS')
      if (context?.revision !== snap.revision) { invalidate(); return false }
      if (!result || !Array.isArray(result.findings)) throw new Error('REVIEW_FAILED')
      if (!deep) { cachedHash = hash; cachedResult = result }
      if (!deep && deepRevision === snap.revision) return true
      const deepComplete = !deep || result.summary?.deepComplete !== false
      if (deep && deepComplete) deepRevision = snap.revision
      const byIndex = new Map(snap.paragraphs.map((p) => [p.index, p.text]))
      const findings = result.findings.slice(0, 200).flatMap((f, i) => {
        const expectedParagraph = byIndex.get(f.paragraphIndex)
        if (typeof expectedParagraph !== 'string' || !f.quote || !expectedParagraph.includes(f.quote)) return []
        const start = Number.isInteger(f.start) ? f.start : expectedParagraph.indexOf(f.quote)
        if (start < 0 || expectedParagraph.slice(start, start + f.quote.length) !== f.quote) return []
        // Never infer a replaceable location for a repeated quote supplied without offsets.
        const ambiguous = !Number.isInteger(f.start) && expectedParagraph.indexOf(f.quote, start + 1) >= 0
        const related = (Array.isArray(f.related) ? f.related : []).flatMap((r) => {
          const text = byIndex.get(r.paragraphIndex)
          if (typeof text !== 'string' || !r.quote || !text.includes(r.quote)) return []
          const offset = text.indexOf(r.quote)
          return [{ ...r, start: offset, end: offset + r.quote.length, expectedParagraph: text }]
        })
        return [{ ...f, id: f.id || `${f.kind}:${f.paragraphIndex}:${start}:${i}`, start, end: start + f.quote.length,
          expectedParagraph, related, replacement: ambiguous ? undefined : f.replacement }]
      })
      publish({ revision: snap.revision, status: 'ready', findings, summary: result.summary || '',
        scope: result.scope || 'body', truncated: snap.truncated || !!result.truncated,
        ...(deep ? { deepStatus: deepComplete ? 'ready' : 'error' } : {}), message: deepComplete ? '' : 'REVIEW_DEEP_INCOMPLETE',
        deepReason: deepComplete ? '' : String(result.summary?.deepReason || ''),
        deepRetried: !deepComplete && result.summary?.deepRetried === true })
      return true
    } catch (error) {
      if (current(gen) && (deep || deepRevision == null)) publish(deep
        ? { deepStatus: 'error', message: String(error?.message || 'REVIEW_FAILED'), deepReason: '', deepRetried: false }
        : { status: 'error', findings: [], message: String(error?.message || 'REVIEW_FAILED') })
      return false
    } finally {
      if (deep) { deepBusy = false; if (!disposed && state.deepStatus === 'checking') publish({ deepStatus: 'idle' }) }
      else localBusy = false
      schedule()
    }
  }
  return {
    session,
    start() { publish({ status: enabled ? 'stale' : 'disabled' }); schedule() },
    modified: invalidate,
    /**
     * 这个编辑器实例此刻是不是用户正在看的那一个（dev-board#724 降资源第一项）。
     * 保活池里后台标签一律不发 worker 命令、不发 HTTP；切回来时正文若已变就补一轮。
     */
    setActive(next) {
      const value = !!next
      if (disposed || value === active) return
      active = value
      if (!active && timer != null) { timers.clear(timer); timer = null }
      publish({})
      if (active) schedule()
    },
    setEnabled(next) { writePreferences({ enabled: !!next }) },
    setBallHidden(next) { writePreferences({ hidden: !!next }) },
    refresh: invalidate,
    runDeep() { return run(true) },
    async handle(msg) {
      if (disposed || msg?.type !== 'inline-review-request' || msg.session !== session) return false
      if (msg.action === 'open-insight') { openInsight?.(); return true }
      // 正文里的浮球只是一个入口：清单在宿主右栏，客体页不再自己画面板。
      if (msg.action === 'open-panel') { openPanel?.(); return true }
      if (msg.action === 'preferences' && msg.data && typeof msg.data === 'object') {
        writePreferences(msg.data)
        return true
      }
      if (msg.action === 'refresh') { invalidate(); return true }
      // The only path with deep:true is an explicit button action.
      if (msg.action === 'deep') { await run(true); return true }
      return false
    },
    destroy() {
      if (disposed) return
      if (timer != null) timers.clear(timer)
      const listeners = preferenceListeners.get(key)
      listeners?.delete(preferences)
      if (!listeners?.size) preferenceListeners.delete(key)
      enabled = false
      publish({ status: 'disabled', findings: [], revision: null })
      disposed = true; generation++
    },
  }
}
