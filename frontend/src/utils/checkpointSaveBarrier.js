// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// File-level barrier: saves are registered before export, independently of Vue
// refs, so a checkpoint also waits for uploads from not-yet-registered editors.
const files = new Map()
const keyOf = (projectId, fileId) => String(projectId) + ':' + String(fileId)
function stateOf(projectId, fileId) {
  const key = keyOf(projectId, fileId)
  if (!files.has(key)) files.set(key, { saves: new Set(), barrier: null, generation: 0, uncertain: false })
  return files.get(key)
}
function prune(key, state) {
  if (!state.barrier && !state.saves.size && !state.generation && !state.uncertain && files.get(key) === state) files.delete(key)
}
export function documentCheckpointGeneration(projectId, fileId) {
  return files.get(keyOf(projectId, fileId))?.generation || 0
}
export function checkpointSaveBarrier(projectId, fileId) {
  return files.get(keyOf(projectId, fileId))?.barrier || null
}
export function beginDocumentSave(projectId, fileId) {
  const state = stateOf(projectId, fileId)
  if (state.barrier) return null
  let resolve, finished = false
  const done = new Promise(r => { resolve = r })
  state.saves.add(done)
  return { finish(safeToOverwrite) {
    if (finished) return
    finished = true
    if (!safeToOverwrite) state.uncertain = true
    resolve(!!safeToOverwrite)
    state.saves.delete(done)
    prune(keyOf(projectId, fileId), state)
  } }
}
export function beginCheckpointSaveBarrier(projectId, fileId, restoreId) {
  const state = stateOf(projectId, fileId)
  if (state.barrier) return null
  state.generation++
  const barrier = { projectId, fileId, restoreId, invalidated: false,
    drained: Promise.all([...state.saves]).then(results => !state.uncertain && results.every(Boolean)) }
  state.barrier = barrier
  return barrier
}
export function endCheckpointSaveBarrier(projectId, fileId, restoreId) {
  const key = keyOf(projectId, fileId), state = files.get(key)
  if (!state || state.barrier?.restoreId !== restoreId) return
  const entry = state.barrier.entry
  if (entry && entry.pending?.get(String(fileId)) === entry) entry.pending.delete(String(fileId))
  state.barrier = null
  prune(key, state)
}

// A terminal SSE may reach a replacement page (or a different project page).
// Match the server-generated operation, never the new page's mutable identity.
export function findCheckpointSaveBarrier(fileId, restoreId, conversationId) {
  for (const state of files.values()) {
    const b = state.barrier
    if (b && String(b.fileId) === String(fileId) && b.restoreId === restoreId && b.conversationId === conversationId) return b
  }
  return null
}
export async function reconcileCheckpointSaveBarrier(projectId, fileId, getWriteState, loadingInstance) {
  const barrier = checkpointSaveBarrier(projectId, fileId)
  if (!barrier) return true
  if (!barrier.conversationId) return false
  const state = await getWriteState(barrier.conversationId, fileId, barrier.restoreId)
  if (checkpointSaveBarrier(projectId, fileId) !== barrier) return !checkpointSaveBarrier(projectId, fileId)
  if (state?.mayWrite !== false) return false
  barrier.invalidated = true
  for (const inst of barrier.instances || []) {
    if (inst !== loadingInstance) inst.failCheckpointRestore?.(barrier.restoreId)
  }
  endCheckpointSaveBarrier(projectId, fileId, barrier.restoreId)
  return true
}
