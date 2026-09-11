// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'
const source = readFileSync(new URL('../../src/zetaoffice/public/office_thread.js', import.meta.url), 'utf8')
const helpers = source.slice(source.indexOf('function commentAt(ref)'), source.indexOf('// Insert text at the view cursor'))
function environment() {
  let fields = []
  const realm = vm.createContext({ xModel: { getTextFields: () => ({ createEnumeration() {
    let index = 0
    return { hasMoreElements: () => index < fields.length, nextElement: () => fields[index++] }
  } }) } })
  vm.runInContext(helpers, realm)
  return { setFields(value) { fields = value },
    id(field) { realm.field = field; return vm.runInContext('commentIdOf(field)', realm) },
    at(ref) { realm.ref = ref; return vm.runInContext('commentAt(ref)', realm) },
  }
}
function annotation(Name, ParaId) {
  return { supportsService: () => true,
    getPropertyValue(name) { if (!['Name', 'ParaId'].includes(name)) throw Error(name); return {Name, ParaId}[name] },
    setPropertyValue() { throw Error('identification must never mutate the document') } }
}
test('fresh unnamed comments use distinct native IDs and keep their identities after enumeration changes', () => {
  const e = environment(), a = annotation('', 'A1'), b = annotation('', 'A2')
  assert.equal(e.id(a), 'postit:161'); assert.equal(e.id(b), 'postit:162')
  e.setFields([a, b]); assert.equal(e.at('postit:162'), b)
  e.setFields([b, a]); assert.equal(e.at('postit:162'), b)
  e.setFields([a]); assert.equal(e.at('postit:162'), null)
})
test('named imported comments preserve existing identifiers and invalid native identifiers are not invented', () => {
  const e = environment(), named = annotation('__Annotation__15', 'A1')
  assert.equal(e.id(named), '__Annotation__15')
  assert.equal(e.id(annotation('', 'not-a-number')), '')
  assert.equal(e.id(annotation('', '0')), 'postit:0')
  e.setFields([named]); assert.equal(e.at('__Annotation__15'), named); assert.equal(e.at(0), named)
})
test('an imported name colliding with a native fallback cannot select either comment', () => {
  const e = environment(); e.setFields([annotation('postit:161', 'B1'), annotation('', 'A1')])
  assert.equal(e.at('postit:161'), null)
})
