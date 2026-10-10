// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
const source = readFileSync(new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')
const methods = new Function(source.replace(/^import .*$/gm, '').replace('export const fileOpenTabsMethods = {', 'return {'))()
const vm = { libreOfficePreferred: true, ...methods }

test('PDF always uses the PDF preview, including files with the legacy editor id', () => {
  for (const fileType of ['pdf', 'PDF']) for (const wpsFileId of [undefined, 'local-42']) {
    const file = { id: 42, name: 'report.pdf', fileType, wpsFileId }
    assert.equal(vm.isEditorOpenableFile(file), false)
    assert.equal(vm.useLibreEditor(file), false)
    assert.equal(vm.isFileTypeSupported(file), true)
  }
})
test('Writer and Calc retain their editor routing', () => {
  for (const fileType of ['docx', 'odt', 'xlsx']) assert.equal(vm.useLibreEditor({ fileType }), true)
})
