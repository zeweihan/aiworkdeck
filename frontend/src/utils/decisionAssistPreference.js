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

// The browser login cache (checkba_user) wins; the server-resolved user (GET /api/auth/me)
// covers desktop local-mode, where that cache is always empty (dev-board#877).
export function decisionAssistUser(cachedUser, resolvedUser) {
  const hasId = u => u && u.id != null && String(u.id).trim() !== ''
  if (hasId(cachedUser)) return cachedUser
  return hasId(resolvedUser) ? resolvedUser : null
}

// Switch state for one chat surface. A click always flips the visible state; when the
// identity is not known yet the choice stays in memory and is adopted (then persisted) by
// the first identity that resolves. Any later identity change re-reads that identity's own
// stored value, so switching accounts never inherits consent.
export function createDecisionAssistState({ storage, identity, onChange = () => {} }) {
  let owner = identity()
  let enabled = readDecisionAssistPreference(storage, owner)
  let pending = null
  let adoptedOwner = null
  const set = value => { enabled = value; onChange(value) }
  const sync = (reload = false) => {
    const next = identity()
    if (next !== owner) {
      const adopt = owner == null && next != null && pending !== null
      owner = next
      if (adopt) {
        writeDecisionAssistPreference(storage, owner, pending)
        adoptedOwner = owner
        pending = null
        set(enabled)
      } else {
        pending = null
        set(readDecisionAssistPreference(storage, owner))
      }
    } else if (reload === true && owner != null) {
      set(readDecisionAssistPreference(storage, owner))
    }
    return enabled
  }
  return {
    sync,
    get enabled() { return enabled },
    get owner() { return owner },
    toggle() {
      sync()
      set(!enabled)
      if (owner == null) pending = enabled
      else writeDecisionAssistPreference(storage, owner, enabled)
      return enabled
    },
    // Whether a snapshot taken under snapshotOwner may still be sent now.
    ownerStillCurrent(snapshotOwner) {
      const current = identity()
      return snapshotOwner === current || (snapshotOwner == null && current != null && adoptedOwner === current)
    },
  }
}
