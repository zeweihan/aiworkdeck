// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { markdownFileLinks, resolveMemoryLink } from '../../src/composables/memoryBrowserState.mjs'

test('relative Markdown links resolve against the current file and preserve only known files', () => {
  const files = ['remember.md', 'topics/preferences.md', 'sources.md']
  assert.equal(resolveMemoryLink('remember.md', './topics/preferences.md#writing', files), 'topics/preferences.md')
  assert.equal(resolveMemoryLink('topics/preferences.md', '../sources.md', files), 'sources.md')
  assert.equal(resolveMemoryLink('remember.md', './missing.md', files), null)
})

test('external, absolute, encoded traversal, and non-Markdown targets never become file actions', () => {
  const files = ['remember.md', 'private.md']
  for (const href of [
    'https://example.com/a.md', '/private.md', '../../private.md', '%2e%2e/private.md',
    'file:///tmp/private.md', 'javascript:alert(1)', 'notes.txt', '\\server\\private.md',
  ]) {
    assert.equal(resolveMemoryLink('remember.md', href, files), null, href)
  }
})

test('Markdown link extraction ignores images and unsafe links while retaining labels', () => {
  const content = '[Preferences](topics/preferences.md)\n![icon](icon.md)\n[Web](https://example.com/x.md)'
  assert.deepEqual(markdownFileLinks(content, 'remember.md', ['topics/preferences.md', 'icon.md']), [
    { label: 'Preferences', path: 'topics/preferences.md' },
  ])
})
