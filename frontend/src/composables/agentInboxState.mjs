// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export const createInboxState = () => ({
  items: [],
  runId: null,
  status: null,
  lastSequences: {},
  appliedMessageIds: {},
})

const finiteNumber = (value, fallback) => Number.isFinite(Number(value)) ? Number(value) : fallback

export const normalizeInboxItem = (value = {}) => ({
  id: value.id || value.messageId || null,
  message: value.message || '',
  displayText: value.displayText || '',
  submissionMode: value.submissionMode === 'queue' ? 'queue' : 'steer',
  state: String(value.state || 'pending').toLowerCase(),
  position: finiteNumber(value.position, 0),
  revision: finiteNumber(value.revision, 0),
  clientRequestId: value.clientRequestId || null,
  runId: value.runId || null,
  sequence: value.sequence == null ? null : finiteNumber(value.sequence, null),
  createdAt: value.createdAt || null,
  updatedAt: value.updatedAt || null,
})

const itemIndex = (state, id, clientRequestId) => {
  const idIndex = id ? state.items.findIndex((candidate) => candidate.id === id) : -1
  if (idIndex !== -1) return idIndex
  return clientRequestId
    ? state.items.findIndex((candidate) => candidate.clientRequestId === clientRequestId)
    : -1
}

export function replaceInboxItem(state, value) {
  const next = normalizeInboxItem(value)
  const index = itemIndex(state, next.id, next.clientRequestId)
  if (index === -1) state.items.push(next)
  else state.items.splice(index, 1, next)
  return next
}

export function removeInboxItem(state, messageId) {
  const index = itemIndex(state, messageId, null)
  if (index !== -1) state.items.splice(index, 1)
}

export function applyInboxReceipt(state, receipt = {}, draft = {}) {
  if (!receipt.messageId) return null
  if (String(receipt.state || '').toLowerCase() === 'deleted') {
    removeInboxItem(state, receipt.messageId)
    return null
  }
  if (receipt.runId) state.runId = receipt.runId
  const existingIndex = itemIndex(state, receipt.messageId, draft.clientRequestId)
  const existing = existingIndex === -1 ? {} : state.items[existingIndex]
  return replaceInboxItem(state, {
    ...existing,
    id: receipt.messageId,
    message: draft.message ?? existing.message,
    displayText: draft.displayText ?? existing.displayText,
    clientRequestId: draft.clientRequestId ?? existing.clientRequestId,
    submissionMode: receipt.submissionMode,
    state: receipt.state,
    runId: receipt.runId,
  })
}

export function applyInboxSnapshot(state, snapshot = {}) {
  state.runId = snapshot.runId || null
  state.status = snapshot.status || null
  state.items.splice(0, state.items.length,
    ...(Array.isArray(snapshot.items) ? snapshot.items.map(normalizeInboxItem) : []))

  return state
}

export function applyInputApplied(state, event = {}) {
  if (!event.messageId) return { accepted: false, item: null }
  if (state.runId && event.runId && state.runId !== event.runId) return { accepted: false, item: null }
  if (state.appliedMessageIds[event.messageId]) return { accepted: false, item: null }

  const sequence = event.sequence == null ? null : finiteNumber(event.sequence, null)
  const lastSequence = event.runId ? (state.lastSequences[event.runId] || 0) : 0
  if (sequence != null && sequence <= lastSequence) return { accepted: false, item: null }

  const existingIndex = itemIndex(state, event.messageId, null)
  const existing = existingIndex === -1 ? {} : state.items[existingIndex]
  const next = replaceInboxItem(state, {
    ...existing,
    id: event.messageId,
    message: event.message ?? existing.message,
    displayText: event.displayText ?? existing.displayText,
    state: 'applied',
    runId: event.runId ?? existing.runId,
    sequence,
  })
  state.appliedMessageIds[event.messageId] = true
  if (event.runId && sequence != null) state.lastSequences[event.runId] = sequence
  return { accepted: true, item: next }
}

export const pendingInboxItems = (state) => state.items
  .filter((entry) => entry.state === 'pending')
  .slice()
  .sort((a, b) => a.position - b.position || String(a.createdAt || '').localeCompare(String(b.createdAt || '')))
