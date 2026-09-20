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
  (snap.truncated ? 't|' : 'f|') + snap.paragraphs.map((p) => p.index + ':' + p.text).join(''))

// 停笔多久才自动跑一次 AI 审校（dev-board#749）。
// 2.5 秒是规则检查的防抖——那一层不花钱，停一下就该给结果；AI 那一层要扣 Credits，
// 20 秒是「真的停下来在读」而不是「在想下一句怎么写」的分界。
const AUTO_AI_IDLE_MS = 20000
// 两次**自动** AI 审校之间的最小间隔。只有上面那条空闲判据的话，边写边停的律师每
// 20 秒就会触发一次整篇 AI 调用；三分钟把它压到一小时至多 20 次。面板上的「立即
// AI 审校」是用户自己按的，不受这条限制。
const AUTO_AI_MIN_GAP_MS = 180000
// 这些原因码不是「这一次不巧失败」，自动重试只会再花一次钱／再撞一次同一堵墙：
// 本会话不再自动跑，手动按钮仍留着（充完值、换完模型即可立刻重试）。
const AUTO_AI_BLOCKING = new Set(['DEEP_QUOTA', 'DEEP_RATE_LIMITED', 'DEEP_MODEL_UNAVAILABLE', 'DEEP_REGION'])

/**
 * Review the live Writer snapshot.
 *
 * 两层，开关只管上面那一层（dev-board#749 维护者拍板）：
 *   - 规则检查（结构、编号、交叉引用、算式、证件号码）始终静默跑，不调模型、不查外库；
 *   - AI 审校在停笔 20 秒后自动跑一次，扣 Credits，用户可以关。
 */
export function createInlineReviewHost({ projectId, fileId, userId, execute, send, review, storage, openInsight, openPanel, onState,
  writable = true, active = true, delay = 2500, now = () => Date.now(),
  timers = { set: (fn, ms) => setTimeout(fn, ms), clear: id => clearTimeout(id) } }) {
  const session = `review:${projectId}:${fileId}:${Date.now()}:${Math.random().toString(36).slice(2)}`
  const key = `awd_ai_review_${userId}`
  // 旧键（dev-board#723）的 enabled 是「连规则检查一起关」，与新语义对不上，所以换键。
  // 迁移只认一个方向：旧值 enabled:false 的人把 AI 也关着开机——**绝不**替一个明确
  // 关过这个功能的人重新打开一条要花钱的链路。关着这件事在浮球与面板上都写着，
  // 不是偷偷关的。
  const legacyKey = `awd_inline_review_${userId}`
  const layoutKey = `awd_inline_review_layout_${hashKey(userId)}`
  // 默认开启但安静（dev-board#723 拍板，#749 收窄到只管 AI 那一层）。
  // hidden 只管正文里的浮球，与 ai 是两件事——不想被浮球打扰 ≠ 不想要 AI 审校。
  let ai = true, hidden = false
  try {
    const saved = storage?.get(key)
    const legacy = saved && typeof saved === 'object' ? null : storage?.get(legacyKey)
    if (saved && typeof saved === 'object') {
      if (saved.ai === false) ai = false
      if (saved.hidden === true) hidden = true
    } else if (legacy && typeof legacy === 'object') {
      if (legacy.enabled === false) ai = false
      if (legacy.hidden === true) hidden = true
    }
  } catch { /* default */ }
  let disposed = false, generation = 0, timer = null, aiTimer = null, localBusy = false, deepBusy = false, dirty = true
  let state = { session, layoutKey, revision: null, ai, hidden, active, writable, status: 'stale', deepStatus: 'idle', findings: [], truncated: false, autoBlocked: '' }
  let deepRevision = null, autoBlocked = '', lastAutoAt = 0
  // 全文快照没变就不再发 POST：律师改一处格式、滚一次页、切回标签都会让 revision 前进，
  // 但正文一个字没动时上一轮的结论逐条仍然成立（dev-board#724 降资源第二项）。
  // deepHash 是同一把尺子量 AI 那一层：正文没动就没有新东西值得再花一次钱。
  let cachedHash = null, cachedResult = null, deepHash = null
  const publish = (next = {}) => {
    if (disposed) return
    state = { ...state, ...next, session, layoutKey, ai, hidden, active, writable, autoBlocked }
    send({ __lo: 'lo-relay', type: 'inline-review-state', ...state })
    try { onState?.({ ...state }) } catch { /* 宿主渲染失败不能带塌检查 */ }
  }
  function schedule() {
    if (disposed || !active || !dirty || localBusy) return
    if (timer != null) timers.clear(timer)
    timer = timers.set(() => { timer = null; return run(false) }, delay)
  }
  function clearAiTimer() {
    if (aiTimer == null) return
    timers.clear(aiTimer); aiTimer = null
  }
  /**
   * 排一次自动 AI 审校。只在规则检查刚跑完（cachedHash 就是此刻的正文）时调，
   * 所以这里能直接用哈希判「上次 AI 之后正文有没有动过」。
   */
  function scheduleAi() {
    clearAiTimer()
    if (disposed || !ai || !active || !writable || deepBusy || autoBlocked) return
    if (!cachedHash || cachedHash === deepHash) return
    const wait = Math.max(AUTO_AI_IDLE_MS, lastAutoAt + AUTO_AI_MIN_GAP_MS - now())
    aiTimer = timers.set(() => { aiTimer = null; lastAutoAt = now(); return run(true) }, wait)
  }
  function invalidate() {
    if (disposed) return
    generation++; dirty = true; deepRevision = null
    clearAiTimer()
    publish({ revision: null, status: 'stale', deepStatus: deepBusy ? 'checking' : 'idle', findings: [], message: '', summary: '', deepReason: '', deepRetried: false })
    schedule()
  }
  function preferences(next) {
    if (!next || typeof next !== 'object') return
    const nextAi = typeof next.ai === 'boolean' ? next.ai : ai
    const nextHidden = typeof next.hidden === 'boolean' ? next.hidden : hidden
    if (nextAi === ai && nextHidden === hidden) return
    hidden = nextHidden
    ai = nextAi
    if (!ai) clearAiTimer()
    publish({})
    if (ai) scheduleAi()
  }
  /** 偏好写盘 + 广播给同一用户的其它标签（同一把钥匙的监听器共享）。 */
  function writePreferences(next) {
    if (disposed || !next || typeof next !== 'object') return
    const merged = { ai, hidden, ...next }
    if (merged.ai === ai && merged.hidden === hidden) return
    try { storage?.set(key, merged) } catch { /* 存不下也要让本会话立刻生效 */ }
    for (const listener of preferenceListeners.get(key) || []) listener(merged)
  }
  if (!preferenceListeners.has(key)) preferenceListeners.set(key, new Set())
  preferenceListeners.get(key).add(preferences)
  const current = (gen) => !disposed && gen === generation

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
    if (disposed || !active || (deep ? !writable || deepBusy : localBusy)) return false
    const gen = generation
    if (deep) { deepBusy = true; clearAiTimer(); publish({ deepStatus: 'checking', message: '' }) }
    else { localBusy = true; dirty = false; publish({ status: 'checking', message: '' }) }
    try {
      const snap = await snapshot(gen)
      if (!snap || !current(gen)) return false
      const hash = snapshotHash(snap)
      const reuse = !deep && cachedHash === hash && cachedResult
      // 整篇送：这一条 POST 里的规则检查是跨段落的（交叉引用、前后数量、算式），
      // 只送改动段会让它们算错，而返回的 findings 是整份清单、不是增量补丁。
      // 增量要动后端的检查口径，不在本卡范围。
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
      if (deep && deepComplete) { deepRevision = snap.revision; deepHash = hash }
      const reason = deep && !deepComplete ? String(result.summary?.deepReason || '') : ''
      if (AUTO_AI_BLOCKING.has(reason)) autoBlocked = reason
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
        deepReason: reason,
        deepRetried: !deepComplete && result.summary?.deepRetried === true })
      // 规则检查刚跑完：cachedHash 就是此刻的正文，这里是唯一排自动 AI 的地方。
      // 一次失败的 AI 不在这里自动重排——要等正文真的又变了、规则再跑一轮。
      if (!deep) scheduleAi()
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
    start() { publish({ status: 'stale' }); schedule() },
    modified: invalidate,
    /**
     * 这个编辑器实例此刻是不是用户正在看的那一个（dev-board#724 降资源第一项）。
     * 保活池里后台标签一律不发 worker 命令、不发 HTTP；切回来时正文若已变就补一轮。
     */
    setActive(next) {
      const value = !!next
      if (disposed || value === active) return
      active = value
      if (!active) { clearAiTimer(); if (timer != null) { timers.clear(timer); timer = null } }
      publish({})
      // 正文变过就让规则那一轮去排自动 AI（它跑完才知道该不该跑）；正文没变就
      // 把切走时撤掉的那一次排回来。
      if (active) { schedule(); if (!dirty) scheduleAi() }
    },
    setAiEnabled(next) { writePreferences({ ai: !!next }) },
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
      // 浮球菜单里的「立即 AI 审校」：用户自己按的，不受自动那条最小间隔限制。
      if (msg.action === 'deep') { await run(true); return true }
      return false
    },
    destroy() {
      if (disposed) return
      if (timer != null) timers.clear(timer)
      clearAiTimer()
      const listeners = preferenceListeners.get(key)
      listeners?.delete(preferences)
      if (!listeners?.size) preferenceListeners.delete(key)
      publish({ status: 'disabled', findings: [], revision: null })
      disposed = true; generation++
    },
  }
}
