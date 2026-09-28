// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 付费转写的提交前确认（dev-board#968，BUG-72 / QA C9-03）的纯逻辑。
// **本文件刻意零依赖**：档位、余额、单价、弹窗、翻译都由调用方注入，好让 node:test 直接导入；
// 接线在同目录 paidTranscribeGate.js。
//
// 红线：平台档转写一提交就预扣 Credits，所以**每次都问**，不做「不再提示」记忆。
// 只有能确定不花 Credits 的情形才放行不问：本机 local、自备 Key byok、这台机器根本没有平台档，
// 或这份音频已经转写过 / 正在转写（register-file 幂等，不会再提交）。
// 档位读不到时照样问——读不到就当成可能扣费，宁可多一次点击，不替用户花钱。

/** 弹框前每一项远程取数的等待上限：取不到就不显示那一行，不能让用户点了没反应 */
export const FETCH_TIMEOUT_MS = 1500

// register-file 只在已有记录处于这三个状态时才提交（与 MeetingRecordingController.registerFile 逐字对齐）；
// 其它状态（TRANSCRIBED / TRANSCRIBING / RECORDING）不会提交，不必问
const SUBMITTABLE_STATUSES = new Set(['RECORDED', 'FAILED', 'EMPTY'])

function fmtDuration(ms) {
  const total = Math.max(0, Math.round(Number(ms) / 1000))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const s = total % 60
  const pad = (n) => String(n).padStart(2, '0')
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`
}

/** 「分」→ Credits 的显示（与设置页 platform.* 同口径：cents / 100，两位小数） */
export function centsToCreditsText(cents) {
  const n = Number(cents)
  return Number.isFinite(n) ? (n / 100).toFixed(2) : null
}

/**
 * 估算文案所需的数：单价 creditsPerUnit 与余额同为「分」，只认按分钟计价的行。
 * 时长已知 → ceil(带小数的分钟数 × 单价)，与网关结算同一算法（分钟数保留小数、最后才向上取整）；
 * 未知 → 每分钟单价。单位不是分钟就不估。
 * @returns {{kind:'total'|'perMinute', credits:string}|null}
 */
export function estimateAsrCost(price, durationMs) {
  if (!price || price.unit !== 'minute') return null
  const per = Number(price.creditsPerUnit)
  if (!(per > 0)) return null
  if (Number(durationMs) > 0) {
    return { kind: 'total', credits: centsToCreditsText(Math.ceil(Number(durationMs) / 60000 * per)) }
  }
  return { kind: 'perMinute', credits: centsToCreditsText(per) }
}

async function quiet(fn, timeoutMs) {
  if (typeof fn !== 'function') return null
  let timer = null
  const timeout = new Promise((resolve) => { timer = setTimeout(() => resolve(null), timeoutMs) })
  try {
    return await Promise.race([Promise.resolve().then(fn).catch(() => null), timeout])
  } finally {
    clearTimeout(timer)
  }
}

/**
 * @param {{durationMs?: number}} opts 音频时长（知道才给，右键转写的文件前端拿不到）
 * @param {{
 *   getContext: () => Promise<{provider: string|null, platformAvailable: boolean,
 *     existingStatus?: string|null, configured?: boolean}|null>,
 *   getBalanceCents: () => Promise<number|null>,
 *   getAsrPrice: () => Promise<{unit: string, creditsPerUnit: number}|null>,
 *   localPossible: () => boolean,
 *   showDialog: (opts: object) => Promise<{confirm: boolean}>,
 *   t: (key: string, params?: object) => string,
 *   timeoutMs?: number,
 * }} deps
 * @returns {Promise<boolean>} true = 可以提交
 */
export async function confirmPaidTranscription(opts, deps) {
  const o = opts || {}
  const timeoutMs = deps.timeoutMs || FETCH_TIMEOUT_MS
  const ctx = await quiet(deps.getContext, timeoutMs)
  if (ctx) {
    if (ctx.existingStatus && !SUBMITTABLE_STATUSES.has(ctx.existingStatus)) return true
    // 转写没配好（未连账户 / 本机模型未就绪）时后端不会提交，也就不会扣
    if (ctx.configured === false) return true
    if (ctx.provider === 'local' || ctx.provider === 'byok') return true
    if (ctx.platformAvailable === false) return true
  }
  const [balanceCents, price] = await Promise.all([
    quiet(deps.getBalanceCents, timeoutMs),
    quiet(deps.getAsrPrice, timeoutMs),
  ])
  const t = deps.t
  const lines = [t('meeting.paidConfirmBody')]
  if (Number(o.durationMs) > 0) {
    lines.push(t('meeting.paidConfirmDuration', { duration: fmtDuration(o.durationMs) }))
  }
  const est = estimateAsrCost(price, o.durationMs)
  if (est) {
    lines.push(t(est.kind === 'total' ? 'meeting.paidConfirmEstimate' : 'meeting.paidConfirmPerMinute',
      { credits: est.credits }))
  }
  const balance = balanceCents == null ? null : centsToCreditsText(balanceCents)
  if (balance) lines.push(t('meeting.paidConfirmBalance', { credits: balance }))
  let localPossible = false
  try { localPossible = !!(deps.localPossible && deps.localPossible()) } catch (e) { /* ignore */ }
  if (localPossible) lines.push(t('meeting.paidConfirmLocalHint'))
  let r = null
  try {
    r = await deps.showDialog({
      title: t('meeting.paidConfirmTitle'),
      content: lines.join('\n'),
      confirmText: t('meeting.paidConfirmOk'),
      cancelText: t('meeting.cancel'),
    })
  } catch (e) {
    return false
  }
  return !!(r && r.confirm)
}
