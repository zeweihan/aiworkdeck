// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
for (const method of ['list_revisions', 'debug_revisions']) {
  for (const hidden of [false, true]) {
    test(`${method} reads ${hidden ? 'hidden' : 'inline'} text without probing the unsupported redline cursor`, () => {
      let invalidReads = 0
      const text = '完整修订文字'.repeat(60)
      const cursor = { gotoRange() {}, getString: () => hidden ? '' : text }
      const body = { createTextCursorByRange: () => cursor }
      const range = { getText: () => body, getPropertyValue: () => null }
      const redline = {
        // SwXRedline.createXTextCursor requires GetContentIdx; inline redlines
        // have none. A caught RuntimeException is still an invalid native call.
        getString() { invalidReads++; throw new Error('redline has no content section') },
        getPropertyValue: name => ({
          RedlineType: 'Delete', RedlineAuthor: '审阅人',
          RedlineText: hidden ? { getString: () => text } : null,
          RedlineStart: range, RedlineEnd: range,
        })[name],
      }
      const model = { getRedlines: () => ({ createEnumeration: () => {
        let pending = true
        return { hasMoreElements: () => pending, nextElement: () => { pending = false; return redline } }
      } }) }
      const start = source.indexOf(`  ${method}(`)
      const end = source.indexOf('\n  },', start) + 5
      const read = new Function('xModel', 'rangeStartsEqual', 'applyLocator', 'tableFail', 'errStr', 'currentReviewRevision', 'docSeq',
        `return ({${source.slice(start, end)}}).${method}`)(model, () => false, () => {}, String, String, () => 'doc-1:review-1', 1)
      const result = read({})
      assert.equal(result.success, true)
      assert.equal((result.revisions || result.redlines)[0].text, method === 'list_revisions' ? text : text.slice(0, 80))
      assert.equal(invalidReads, 0, 'never invoke SwXRedline.getString as a capability probe')
    })
  }
}
