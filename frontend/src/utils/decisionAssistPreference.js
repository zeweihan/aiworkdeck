// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

// Consent belongs to a signed-in identity on this server, never the whole device.
export function decisionAssistPreferenceKey(user, apiBaseUrl = '') {
  if (!user || user.id == null || String(user.id).trim() === '') return null
  return `awd_decision_assist:${encodeURIComponent(apiBaseUrl)}:${encodeURIComponent(String(user.id))}`
}

export function readDecisionAssistPreference(storage, key) {
  if (!key) return false
  try { return storage.getStorageSync(key) === true } catch { return false }
}

export function writeDecisionAssistPreference(storage, key, enabled) {
  if (!key) return
  try { storage.setStorageSync(key, enabled === true) } catch { /* Session choice remains visible. */ }
}
