// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 试用计量 v0.1.1：把 GET /api/trial/balance 的数据格式化成账户菜单里的一行。
// 纯函数（t 由调用方注入），只展示后端给的数，不做任何本地扣减或推算。
// 返回 '' 表示不渲染：未连接账户、请求失败、已转付费（converted）。

export function trialBalanceText(data, t) {
  if (!data || data.connected !== true) return ''
  const status = data.status
  if (!status || status === 'converted') return ''
  const days = Number.isFinite(data.remainingDays) ? data.remainingDays : null
  const calls = Number.isFinite(data.remainingCalls) ? data.remainingCalls : null
  let text = ''
  if (status === 'exhausted_calls') text = t('account.trialExhaustedCalls')
  else if (status === 'expired_days') text = t('account.trialExpiredDays')
  else if (days === null || calls === null) return ''
  else if (status === 'none') text = t('account.trialNotStarted', { days, calls })
  else if (status === 'active') text = t('account.trialActive', { days, calls })
  else return ''
  if (data.available === false || data.stale === true) text += ' ' + t('account.trialStale')
  return text
}
