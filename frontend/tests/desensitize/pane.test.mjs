import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import vm from 'node:vm'

const sfc = readFileSync(new URL('../../src/components/DesensitizePane.vue', import.meta.url), 'utf8')
const script = sfc.match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/^import .*$/m, '').replace('export default', 'const component =') + '\ncomponent'
function pane(api = {}) {
  const events = []
  const component = vm.runInNewContext(script, { ...api, URL, Blob, setTimeout })
  const state = { ...component.data(), projectId: 1, $t: x => x, $emit: (...args) => events.push(args) }
  for (const [key, fn] of Object.entries(component.methods)) state[key] = fn.bind(state)
  for (const [key, fn] of Object.entries(component.computed)) Object.defineProperty(state, key, { get: fn.bind(state) })
  return { state, component, events }
}

test('preview sends rules and file id without the recovery password or map', async () => {
  let payload
  const { state } = pane({ previewSensitiveFile: async value => { payload = value; return { text: '[[公司1_example]]', counts: { COMPANY: 1 } } } })
  state.selectFile({ id: 1, name: 'sample.docx', filePath: 'sample.docx' })
  state.selectedStrategies = ['COMPANY']; state.customTerms = '星河\n A+B '; state.password = 'secret-password'; state.recoveryKit = 'secret-map'
  await state.handlePreview()
  assert.equal(payload.mode, 'TOKEN')
  assert.deepEqual(Array.from(payload.customTerms), ['星河', 'A+B'])
  assert.equal(payload.password, undefined)
  assert.equal(payload.recoveryKit, undefined)
  assert.equal(state.totalMatches, 1)
})

test('new generation replaces the downloadable kit and clears preview tokens/password', async () => {
  const { state, events } = pane({ desensitizeFile: async () => ({ file: { id: 4 }, recoveryKit: 'AWD-RECOVERY-1:encrypted', counts: { PHONE: 2 } }) })
  state.fileId = 1; state.fileName = 'sample.txt'; state.preview = { text: 'preview' }; state.password = 'secret-password'
  let downloads = 0; state.downloadKit = () => { downloads++ }
  await state.handleGenerate()
  assert.equal(downloads, 1)
  assert.equal(state.exportedKit, 'AWD-RECOVERY-1:encrypted')
  assert.equal(state.preview, null); assert.equal(state.password, '')
  assert.equal(events[0][0], 'open-file'); assert.equal(events[0][1].id, 4)
})

test('zero-match generation never offers a stale map from a previous document', async () => {
  const { state } = pane({ desensitizeFile: async () => ({ file: { id: 5 }, recoveryKit: '', counts: {} }) })
  state.fileId = 1; state.preview = {}; state.exportedKit = 'old kit'
  await state.handleGenerate()
  assert.equal(state.exportedKit, '')
})

test('restore targets selected file, reports errors and never opens a failed result', async () => {
  let payload
  const { state, events } = pane({ restoreSensitiveFile: async value => { payload = value; throw new Error('wrong password') } })
  state.fileId = 6; state.recoveryKit = 'encrypted'; state.password = 'secret-password'
  await state.handleRestore()
  assert.equal(payload.fileId, 6); assert.equal(payload.recoveryKit, 'encrypted')
  assert.equal(state.error, 'wrong password'); assert.equal(state.processing, false)
  assert.equal(events.length, 0)
})

test('PDF selection uses irreversible mode; project change clears sensitive state', () => {
  const { state, component } = pane()
  state.selectFile({ id: 1, filePath: 'doc.pdf', name: 'doc.PDF' })
  assert.equal(state.effectiveMode, 'MASK')
  state.password = 'secret-password'; state.recoveryKit = 'map'; state.exportedKit = 'export'
  state.customTerms = 'private'; state.excludedTerms = 'also private'
  component.watch.projectId.call(state)
  assert.equal(state.fileId, null); assert.equal(state.password, '')
  assert.equal(state.recoveryKit, ''); assert.equal(state.exportedKit, '')
  assert.equal(state.customTerms, ''); assert.equal(state.excludedTerms, '')
})
