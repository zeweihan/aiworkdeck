// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { nativeDataTransfer, nativeEvent } from './fileTreeExternalDrop.js'
import { applyDragImage } from './dragImage.js'

export function canDragCreatedFile(file, projectId) {
  return file?.changeType === 'ADDED' && Number.isSafeInteger(Number(file.fileId)) && Number(file.fileId) > 0
    && Number(projectId) > 0 && Number(file.projectId) === Number(projectId)
}

export function startCreatedFileDrag(event, file, projectId, doc = globalThis.document) {
  if (!canDragCreatedFile(file, projectId)) { nativeEvent(event)?.preventDefault?.(); return false }
  const payload = { fileId: Number(file.fileId), projectId: Number(projectId), name: file.fileName,
    fileType: String(file.fileName || '').split('.').pop().toLowerCase(), source: 'chat-generated' }
  const dt = nativeDataTransfer(event)
  if (dt) {
    dt.effectAllowed = 'move'
    dt.setData('application/x-checkba-file', JSON.stringify(payload))
    dt.setData('text/checkba-file-json', JSON.stringify(payload))
    applyDragImage({ dataTransfer: dt })
  }
  if (doc) doc.__checkbaDraggedFile = payload
  return true
}

export function endCreatedFileDrag(doc = globalThis.document) {
  if (doc?.__checkbaDraggedFile?.source === 'chat-generated') doc.__checkbaDraggedFile = null
}
