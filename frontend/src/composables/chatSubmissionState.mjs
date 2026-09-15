// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export const submissionFingerprint = (draft = {}) => JSON.stringify({
  prompt: draft.prompt || '',
  displayText: draft.displayText || '',
  contentHtml: draft.contentHtml || '',
  fileIds: Array.isArray(draft.fileIds) ? draft.fileIds.map(String) : [],
  imageKeys: Array.isArray(draft.imageKeys) ? draft.imageKeys.map(String) : [],
  submissionMode: draft.submissionMode === 'queue' ? 'queue' : 'steer',
  editorHtml: draft.editorHtml || '',
  conversationId: draft.conversationId || '',
  projectId: draft.projectId == null ? '' : String(draft.projectId),
  modelId: draft.modelId || '',
  mode: draft.mode || '',
  skillIds: Array.isArray(draft.skillIds) ? draft.skillIds.map(String) : [],
  activeContext: draft.activeContext || null,
})

export const shouldClearChatDraft = (sent, current) => submissionFingerprint(sent) === submissionFingerprint(current)

export function beginChatSubmission(tracker, draft, createId) {
  const fingerprint = submissionFingerprint(draft)
  if (!tracker.inflight) tracker.inflight = {}
  const inflight = tracker.inflight[fingerprint]
  if (inflight) return inflight
  const failed = tracker.failed
  const attempt = failed && failed.fingerprint === fingerprint
    ? { ...draft, ...failed, fingerprint }
    : { ...draft, clientRequestId: createId(), fingerprint }
  tracker.inflight[fingerprint] = attempt
  return attempt
}

export function failChatSubmission(tracker, attempt) {
  tracker.failed = { ...attempt }
  if (tracker.inflight) delete tracker.inflight[attempt.fingerprint]
  return tracker.failed
}

export function receiptChatSubmission(tracker, attempt, receipt) {
  if (!receipt || receipt.status !== 'accepted' || !receipt.messageId) return false
  if (tracker.inflight) delete tracker.inflight[attempt.fingerprint]
  if (tracker.failed && tracker.failed.clientRequestId === attempt.clientRequestId) tracker.failed = null
  return true
}

export function submitChatAttempt(attempt, submit) {
  if (attempt.receiptPromise) return attempt.receiptPromise
  const pending = Promise.resolve().then(submit)
  attempt.receiptPromise = pending.finally(() => {
    if (attempt.receiptPromise === pendingResult) delete attempt.receiptPromise
  })
  const pendingResult = attempt.receiptPromise
  return pendingResult
}
