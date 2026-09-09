// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const body = source.slice(source.indexOf('    insertAiMessageToDoc(message) {') >= 0 ? source.indexOf('    insertAiMessageToDoc(message) {') : source.indexOf('    async insertAiMessageToDoc(message) {'), source.indexOf('    async applyAiMessageToSelection(message) {'))
const notices = []
const insert = new Function('uni', 'markdownToPlainText', `return ({${body}}).insertAiMessageToDoc`)({showToast: x => notices.push(x)}, x => x)
function vm(executeCommand) { return { libreOfficeActive: true, libreOfficeExecutor: {executeCommand}, $t: k => k, notifyDocMutated() {}, insertPlainTextToWps: () => assert.fail('Markdown must not use plain text insertion') } }
test('AI markdown table reaches rich writer unchanged and flushes the final table', async () => {
  const calls = []; const text = '# 标题\n\n| 证据 | 依据 |\n| --- | --- |\n| **书证** | 卷一 |'
  await insert.call(vm(async (action, params) => {calls.push([action, params]); return {success:true}}), {content:text})
  assert.deepEqual(calls, [['stream_insert', {text, complete: true}]])
})
test('failed table flush never reports insertion success', async () => {
  notices.length = 0
  await insert.call(vm(async () => ({success:false,message:'table failed'})), {content:'| A | B |'})
  assert.ok(notices.some(n => n.icon === 'none'))
  assert.ok(!notices.some(n => n.icon === 'success'))
})
test('no active document does not issue a write', async () => {
  const host = vm(() => assert.fail('no active target')); host.libreOfficeActive = false
  await insert.call(host, {content:'text'})
})

test('a live Agent stream prevents mixing an older answer into its table', async () => {
  const host = vm(() => assert.fail('must not interrupt an active document stream'))
  host.$refs = { chatInterface: { menuState: () => ({aiRunning: true}) } }
  await insert.call(host, {content:'old answer'})
})
