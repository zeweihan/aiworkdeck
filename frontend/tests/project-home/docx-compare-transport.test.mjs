// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/services/api.js', import.meta.url), 'utf8')
function harness(response, status = 200) {
  const calls = []
  class XHR {
    headers = {}
    open(method, url) { this.method = method; this.url = url }
    setRequestHeader(k, v) { this.headers[k] = this.headers[k] ? this.headers[k] + ', ' + v : v }
    send(body) { this.body = body; this.status = status; this.response = response; this.responseText = JSON.stringify(response); calls.push(this); queueMicrotask(() => this.onload()) }
  }
  const block = (name, end) => source.slice(source.indexOf('export function ' + name), source.indexOf(end, source.indexOf('export function ' + name))).replace('export function', 'function')
  const code = block('fetchProjectFileBytes', '// Web 插件面板') + block('createComparisonFile', '// xlsx / pptx：')
  const api = new Function('XMLHttpRequest', 'getApiBaseUrl', 'getAuthHeaders', 'getSessionId', 't', code + ';return {fetchProjectFileBytes,createComparisonFile}')(
    XHR, () => 'http://localhost:9696', () => ({ 'Content-Type': 'application/json', 'X-Session-Id': 'test-session' }), () => 'test-session', (k) => k)
  return { api, calls }
}
const payload = { bytes: new Uint8Array([80, 75, 3, 4]), name: 'comparison.docx', baseFileId: 1, revisedFileId: 2, baseSha256: 'a'.repeat(64), revisedSha256: 'b'.repeat(64) }
test('download sends exactly one session header and preserves binary bytes', async () => {
  const { api, calls } = harness(Uint8Array.from([80,75,3,4]).buffer)
  assert.deepEqual(await api.fetchProjectFileBytes(3), Uint8Array.from([80,75,3,4]))
  assert.equal(calls[0].headers['X-Session-Id'], 'test-session')
  assert.equal(calls[0].timeout, 60000)
})
test('comparison accepts backend direct ProjectFile and uploads exact binary form', async () => {
  const file = { id: 44, fileType: 'docx', name: 'A与B（比对稿）.docx' }
  const { api, calls } = harness(file)
  assert.deepEqual(await api.createComparisonFile(7, payload), file)
  assert.deepEqual(new Uint8Array(await calls[0].body.get('file').arrayBuffer()), payload.bytes)
  assert.equal(calls[0].headers['Content-Type'], undefined)
  assert.equal(calls[0].body.get('baseSha256'), payload.baseSha256)
})
test('stale source HTTP409 preserves server reason and error message', async () => {
  const { api } = harness({ code: -1, reason: 'SOURCE_CHANGED', message: '来源文件已更新，请重新比对' }, 409)
  await assert.rejects(api.createComparisonFile(7, payload), e => e.message.includes('来源文件已更新') && e.reason === 'SOURCE_CHANGED')
})
test('HTTP200 global error is not accepted as file', async () => {
  const { api } = harness({ code: 4010, message: '请先登录' })
  await assert.rejects(api.createComparisonFile(7, payload), /请先登录/)
})
