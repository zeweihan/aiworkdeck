// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createRelayExecutor } from '../../src/composables/zetaOfficeRelay.js'
const source = readFileSync(new URL('../../src/components/LibreOfficeEditor.vue', import.meta.url), 'utf8')
const start = source.indexOf('    async onGuestProcessGone(reason) {')
const end = source.indexOf('    // 诊断落盘', start)
const method = new Function('return ({' + source.slice(start, end) + '}).onGuestProcessGone')()
for (const repeated of [false, true]) test(`guest exit settles an unlimited comparison before restart (repeated=${repeated})`, async () => {
  const executor = createRelayExecutor({ send() {}, subscribe: () => () => {} })
  const pending = executor.executeCommand('build_comparison_document', {}, { waitForCompletion: true })
  let restarts = 0
  const vm = { executor, _guestGoneAt: repeated ? Date.now() : null, appendLog() {}, remountEditor: async () => { restarts++ } }
  await method.call(vm, 'crashed')
  assert.equal((await pending).code, 'EDITOR_DISPOSED')
  assert.equal(restarts, repeated ? 0 : 1)
})
