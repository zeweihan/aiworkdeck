// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { buildAgentStreamFactory } from '../_lib/agent-stream-factory.mjs'
import { canDragCreatedFile, startCreatedFileDrag, endCreatedFileDrag } from '../../src/utils/generatedFileDrag.js'
const file = { fileId: 42, projectId: 1, fileName: '报告.docx', changeType: 'ADDED' }

test('only a real id from this project enables dragging, never a same-name guess', () => {
  assert.equal(canDragCreatedFile(file, '1'), true)
  for (const f of [{ ...file, projectId: 2 }, { ...file, fileId: null }, { ...file, fileId: -1 },
    { ...file, projectId: null }, { ...file, changeType: 'MODIFIED' }, { fileName: '报告.docx' }]) {
    assert.equal(canDragCreatedFile(f, 1), false)
  }
})
test('native payload uses stable id, project and move action; dragend consumes only our fallback', () => {
  const data = new Map(), doc = {}
  const dt = { setData: (k, v) => data.set(k, v) }
  assert.equal(startCreatedFileDrag({ dataTransfer: dt }, file, 1, doc), true)
  assert.equal(dt.effectAllowed, 'move')
  const payload = JSON.parse(data.get('application/x-checkba-file'))
  assert.deepEqual(payload, { fileId: 42, projectId: 1, name: '报告.docx', fileType: 'docx', source: 'chat-generated' })
  assert.equal(data.get('application/x-checkba-file'), data.get('text/checkba-file-json'))
  assert.deepEqual(doc.__checkbaDraggedFile, payload)
  endCreatedFileDrag(doc); assert.equal(doc.__checkbaDraggedFile, null)
  doc.__checkbaDraggedFile = { source: 'file-tree', fileId: 99 }
  endCreatedFileDrag(doc); assert.equal(doc.__checkbaDraggedFile.fileId, 99)
})
test('invalid drag is cancelled and does not create a fallback file', () => {
  let cancelled = false
  const doc = {}
  assert.equal(startCreatedFileDrag({ preventDefault: () => { cancelled = true } }, file, 2, doc), false)
  assert.equal(cancelled, true)
  assert.equal(doc.__checkbaDraggedFile, undefined)
})

test('file_change parser preserves server project identity for drag eligibility', t => {
  const stream = buildAgentStreamFactory({ expose: 'handleEvent, createAssistantBubble, currentAssistantBubble' })
  t.after(() => stream.resetSSE())
  const bubble = stream.createAssistantBubble()
  bubble.isStreaming = true
  stream.bubbles.value.push(bubble)
  stream.currentAssistantBubble.value = bubble
  stream.handleEvent('file_change', JSON.stringify(file))
  assert.equal(stream.fileChanges.value[0].projectId, 1)
  assert.equal(canDragCreatedFile(stream.fileChanges.value[0], 1), true)
})
