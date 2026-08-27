// insightMatch.js — 「依据」窗格的两组纯函数（dev-board#182）：
//   ① 光标邻域 → 命中哪个实体（正文点击/光标移动联动）；
//   ② 一致性发现 → 「统一为 X」的机械替换串（一键修改）。
//
// 放在 utils 里是为了能被 node --test 直接导入（tests/insight/），
// **不许 import Vue / uni / @/i18n**——照 config/panelRegistry.js 的先例。
//
// 契约见 .claude/agents/doc-insight.md 的「前端」一节。

/** 光标邻域窗口的默认半径（字符）。get_cursor_context 的 radius 也按它要。 */
export const CURSOR_RADIUS = 60

/** 实体名短于这个长度不参与匹配——一两个字的命中全是噪声。 */
const MIN_NAME = 2

/**
 * 把 get_cursor_context 的 {before, after} 拼成一个「跨光标切点」的窗口。
 * 返回 { text, cut }：cut 是切点在 text 里的下标（= before 尾部截取后的长度）。
 */
export function cursorWindow(context, radius = CURSOR_RADIUS) {
  const before = String((context && context.before) || '')
  const after = String((context && context.after) || '')
  const r = Number(radius)
  const head = r > 0 ? before.slice(-r) : before
  const tail = r > 0 ? after.slice(0, r) : after
  return { text: head + tail, cut: head.length }
}

/** 一个实体可以用来在正文里找它的写法：展示名 + 归一键（企业简称常常正好等于 normKey）。 */
function candidateNames(entity) {
  const out = []
  for (const v of [entity && entity.name, entity && entity.normKey]) {
    const s = v == null ? '' : String(v)
    if (s.length >= MIN_NAME && out.indexOf(s) === -1) out.push(s)
  }
  return out
}

/**
 * name 在 text 里有没有一处**覆盖切点**的出现。
 * 端点算命中（start <= cut <= end）：用户点在实体名的头/尾也该算点中它。
 */
function coversCut(text, name, cut) {
  let from = 0
  for (;;) {
    const i = text.indexOf(name, from)
    if (i === -1) return false
    if (i <= cut && i + name.length >= cut) return true
    from = i + 1
  }
}

/**
 * 光标此刻落在哪个实体上。命中多个时**取最长的那个写法**
 * （「京微资易科技有限公司」优先于它的简称「京微资易科技」），同长取实体清单里靠前的。
 *
 * @param {{before?:string, after?:string}} context get_cursor_context 的返回
 * @param {Array<{id?:number, name?:string, normKey?:string}>} entities 依据窗格的实体清单
 * @returns 命中的实体对象本身（未命中返回 null）
 */
export function matchEntityAt(context, entities, opts) {
  const list = Array.isArray(entities) ? entities : []
  if (!list.length) return null
  const radius = opts && opts.radius != null ? opts.radius : CURSOR_RADIUS
  const win = cursorWindow(context, radius)
  if (!win.text) return null
  let best = null
  let bestLen = 0
  for (const e of list) {
    if (!e) continue
    for (const name of candidateNames(e)) {
      // 已经有更长的命中了，短写法不必再扫（同长时先到者胜）
      if (name.length <= bestLen) continue
      if (!coversCut(win.text, name, win.cut)) continue
      best = e
      bestLen = name.length
    }
  }
  return best
}

/**
 * 一键修改的替换串：把 quote 里的 numberText 换成 targetText。
 *
 * 保守到底——**quote 里 numberText 出现不止一次就拒绝**（换第一处还是第二处没有依据，
 * 静默换错地方比不改更贵）。返回 { ok, text, reason }，reason ∈
 * 'empty' | 'missing'（numberText 不在 quote 里）| 'ambiguous' | 'same'。
 */
export function buildFixedQuote(quote, numberText, targetText) {
  const q = quote == null ? '' : String(quote)
  const n = numberText == null ? '' : String(numberText)
  const t = targetText == null ? '' : String(targetText)
  if (!q || !n || !t) return { ok: false, text: '', reason: 'empty' }
  if (n === t) return { ok: false, text: '', reason: 'same' }
  const first = q.indexOf(n)
  if (first === -1) return { ok: false, text: '', reason: 'missing' }
  if (q.indexOf(n, first + 1) !== -1) return { ok: false, text: '', reason: 'ambiguous' }
  return {
    ok: true,
    text: q.slice(0, first) + t + q.slice(first + n.length),
    reason: '',
  }
}

/**
 * 一条 COUNT_MISMATCH 发现能给出的「统一为 X」建议。
 *
 * 每个**可修改**（后端 fixable，即 numberText 确认是 quote 的逐字子串、quote 确认是
 * 正文逐字片段、组内单位字面量一致）的候选值各生成一条建议；一条建议里要改的是
 * **其余** claim（值已经等于 X 的那些不动）。生成不出任何一处替换的候选直接不出现——
 * 按钮点了什么都不会发生比给个假按钮好。
 *
 * @param {{claims?:Array, unit?:string}} detail finding.detail（后端全量下发）
 * @returns {Array<{numberText:string, value:*, unit:string, edits:Array<{quote:string,replacement:string,numberText:string}>}>}
 */
export function fixSuggestions(detail) {
  const claims = detail && Array.isArray(detail.claims) ? detail.claims : []
  const usable = claims.filter((c) => c && c.fixable === true && c.numberText != null && c.numberText !== '')
  if (!usable.length) return []
  const out = []
  const seen = Object.create(null)
  for (const c of usable) {
    const target = String(c.numberText)
    if (seen[target]) continue
    seen[target] = true
    const edits = []
    for (const other of usable) {
      if (other === c) continue
      if (String(other.numberText) === target) continue
      const built = buildFixedQuote(other.quote, other.numberText, target)
      if (!built.ok) continue
      edits.push({ quote: String(other.quote), replacement: built.text, numberText: String(other.numberText) })
    }
    if (!edits.length) continue
    out.push({
      numberText: target,
      value: c.value != null ? c.value : null,
      unit: c.unit || (detail && detail.unit) || '',
      edits,
    })
  }
  return out
}

/**
 * 这条发现有没有可点的建议按钮；没有时给出理由（取第一条不可修改 claim 的 fixableReason）。
 * USCC_INVALID 那类 detail 没有 claims，永远落在「没有建议」这一侧。
 */
export function fixBlockReason(detail) {
  const claims = detail && Array.isArray(detail.claims) ? detail.claims : []
  for (const c of claims) {
    if (c && c.fixable === false && c.fixableReason) return String(c.fixableReason)
  }
  return ''
}

/**
 * 点条目要定位到哪一段文字：第一条带 quote 的 claim（COUNT_MISMATCH）
 * 或 detail.quote（USCC_INVALID）。拿不到返回 ''（宿主据此不做定位，而不是拿标题去查找）。
 */
export function findingLocateQuote(detail) {
  if (!detail) return ''
  const claims = Array.isArray(detail.claims) ? detail.claims : []
  for (const c of claims) {
    if (c && c.quote) return String(c.quote)
  }
  return detail.quote ? String(detail.quote) : ''
}
