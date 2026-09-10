// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { extractCompletionEntries } from '../utils/completionLexicon.js'
import { completionDetails } from '../utils/completionDetails.js'

const preferenceListeners = new Map()

/** Host-scoped vocabulary and explicit lookup. Dependencies stay injectable for isolation tests. */
export function createWritingAssistanceHost({ projectId, fileId, userId, execute, send, api, storage, writable, language }) {
  const session = `${projectId}:${fileId}:${Date.now()}:${Math.random().toString(36).slice(2)}`
  const key = `awd_writing_preferences_${userId}`
  let disposed = false, items = [], documentItems = [], loadSequence = 0, seedSequence = 0
  const seededTexts = new Set()
  let preferences = { enabled: true, learning: true, hints: true }
  try { const stored = storage.get(key); for (const name of Object.keys(preferences)) if (typeof stored?.[name] === 'boolean') preferences[name] = stored[name] } catch { /* defaults */ }
  const receivePreferences = (next) => {
    if (disposed) return
    preferences = { ...next }
    // Only flags change: retain this guest's vocabulary, including recent typing.
    send({ __lo: 'lo-relay', type: 'writing-config', config: { ...preferences, session } })
  }
  if (!preferenceListeners.has(key)) preferenceListeners.set(key, new Set())
  preferenceListeners.get(key).add(receivePreferences)
  function publish() {
    // Document locality must not hide an existing entity's details. Keep the
    // original learned records too, so vocabulary management can still delete them.
    const localByText = new Map()
    const usesByText = new Map()
    for (const item of items) {
      const previous = localByText.get(item.text)
      if (!previous || (!(previous.entityId || previous.hasDetail) && (item.entityId || item.hasDetail))) localByText.set(item.text, item)
      usesByText.set(item.text, Math.max(usesByText.get(item.text) || 0, Number(item.uses) || 0))
    }
    const enriched = documentItems.map((item) => {
      const known = localByText.get(item.text)
      if (!known) return item
      const kind = ['WORD', 'PHRASE'].includes(known.kind) ? item.kind : known.kind || item.kind
      return { ...known, ...item, kind, uses: Math.max(item.uses || 0, usesByText.get(item.text) || 0) }
    })
    if (!disposed) send({ __lo: 'lo-relay', type: 'writing-config', config: { ...preferences, session, writable, language, items: [...enriched, ...items] } })
  }
  async function refresh() {
    const seq = ++loadSequence
    const result = await api.list(projectId)
    if (disposed || seq !== loadSequence) return
    items = Array.isArray(result?.items) ? result.items : []
    publish()
  }
  async function seedDocument() {
    const seq = ++seedSequence
    const current = () => !disposed && seq === seedSequence
    if (!current() || !writable) return
    const view = await execute('set_revision_view', {}).catch(() => null)
    if (!current() || !view || view.mode === 'all') return
    // Load/manual refresh only: page the live body within one worker revision.
    // The API's 50-entry learn batch is independent of the document vocabulary.
    const paragraphs = []
    let revision, start = 0, chars = 0, stop = false
    for (let page = 0; page < 1000 && !stop; page++) {
      const result = await execute('get_document_text', { startParagraph: start, maxParagraphs: 200 }).catch(() => null)
      if (!current() || !result?.success || result.revision == null || !Array.isArray(result.paragraphs)) return
      if (revision == null) revision = result.revision
      if (result.revision !== revision) return
      for (const p of result.paragraphs) {
        if (typeof p.text !== 'string') return
        const size = p.text.length + (paragraphs.length ? 1 : 0)
        if (paragraphs.length >= 1000 || chars + size > 200000) { stop = true; break }
        paragraphs.push(p.text); chars += size
      }
      if (stop || paragraphs.length >= 1000 || !result.truncated) break
      if (!Number.isInteger(result.nextStartParagraph) || result.nextStartParagraph <= start) return
      start = result.nextStartParagraph
    }
    const context = await execute('get_review_context', {}).catch(() => null)
    if (!current() || context?.revision !== revision || context.reason === 'inline-revisions') return
    const entries = extractCompletionEntries(paragraphs.join('\n'), { limit: 500 })
    documentItems = entries.map((x) => ({ ...x, source: 'document', scope: 'project', uses: 1 }))
    publish()
    const entities = entries.filter((x) => !['WORD', 'PHRASE'].includes(x.kind) && !seededTexts.has(x.text))
      .slice(0, Math.max(0, 200 - seededTexts.size))
    for (let offset = 0; offset < entities.length; offset += 50) {
      if (!current() || !preferences.learning) return
      const batch = entities.slice(offset, offset + 50)
      await api.learn(projectId, { scope: 'project', entries: batch })
      batch.forEach((entry) => seededTexts.add(entry.text))
    }
  }
  async function perform(action, data) {
    switch (action) {
      case 'refresh': await refresh(); return {}
      case 'refreshDocument': await Promise.all([refresh(), seedDocument()]); return {}
      case 'learn':
        if (!writable || !preferences.learning) return { learned: 0 }
        if (!['project', 'user'].includes(data.scope)) throw new Error('Invalid learning scope')
        return api.learn(projectId, { scope: data.scope, entries: data.entries })
      case 'preferences': {
        const next = { ...preferences }
        for (const name of Object.keys(next)) if (typeof data[name] === 'boolean') next[name] = data[name]
        storage.set(key, next)
        for (const listener of preferenceListeners.get(key) || []) listener(next)
        return preferences
      }
      case 'delete': {
        const result = await api.remove(projectId, data.id); await refresh(); return result
      }
      case 'clear': {
        const result = await api.clear(projectId, data.scope); await refresh(); return result
      }
      case 'detail': return completionDetails(await (data.entityId ? api.detail(projectId, data.entityId) : api.learnedDetail(projectId, data.id)))
      case 'lookup':
        if (!writable) throw new Error('Read-only document')
        // This branch is only reached by the selected-text context menu's explicit action.
        return completionDetails(await api.lookup(projectId, { kind: data.kind, text: data.text }))
      default: throw new Error('Unknown writing action')
    }
  }
  return {
    session,
    async start() {
      publish()
      await Promise.allSettled([refresh(), seedDocument()])
    },
    async handle(msg) {
      if (disposed || msg?.type !== 'writing-request' || msg.session !== session) return false
      try {
        const result = await perform(msg.action, msg.data || {})
        if (!disposed) send({ __lo: 'lo-relay', type: 'writing-response', session, id: msg.id, result })
      } catch (e) {
        if (!disposed) send({ __lo: 'lo-relay', type: 'writing-response', session, id: msg.id, error: String(e?.message || e) })
      }
      return true
    },
    destroy() {
      if (disposed) return
      disposed = true; loadSequence++; seedSequence++
      const listeners = preferenceListeners.get(key)
      listeners?.delete(receivePreferences)
      if (!listeners?.size) preferenceListeners.delete(key)
      send({ __lo: 'lo-relay', type: 'writing-config', config: { session: '', items: [], writable: false, enabled: false, learning: false } })
    },
  }
}
