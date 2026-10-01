// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { bootZetaOffice } from '../../src/composables/zetaOfficeBoot.js'

function environment(t) {
  const keys = ['document', 'Module', 'ResizeObserver', 'dispatchEvent']
  const saved = new Map(keys.map(k => [k, Object.getOwnPropertyDescriptor(globalThis, k)]))
  t.after(() => { for (const [k, d] of saved) d ? Object.defineProperty(globalThis, k, d) : delete globalThis[k] })
  let script, resolvePort, disconnects = 0
  const port = { onmessage: null }
  globalThis.ResizeObserver = class { observe() {} disconnect() { disconnects++ } }
  globalThis.dispatchEvent = () => {}
  globalThis.document = { createElement: () => ({}), body: { appendChild(s) {
    script = s
    globalThis.Module.uno_main = new Promise(resolve => { resolvePort = resolve })
  } } }
  return { port, get disconnects() { return disconnects }, async loaded() {
    // Font preparation awaits Promise.all even when there are no fonts.
    await Promise.resolve(); await Promise.resolve()
    script.onload(); resolvePort(port)
  } }
}

const options = () => ({ canvas: { style: {}, clientWidth: 800, clientHeight: 600 }, sofficeBaseUrl: '', uiLang: '' })

test('native abort during startup reports once, rejects boot and ignores late readiness', async t => {
  const env = environment(t), failures = [], logs = []
  let ready = 0
  const boot = bootZetaOffice({ ...options(), onFatal: (...args) => failures.push(args), onLog: m => logs.push(m), onReady: () => ready++ })
  const rejection = assert.rejects(boot, /LibreOffice engine stopped/)
  await Promise.resolve(); await Promise.resolve()
  assert.equal(typeof Module.onAbort, 'function')
  assert.equal(Object.prototype.propertyIsEnumerable.call(Module, 'onAbort'), true, 'Emscripten forwards enumerable onAbort into pthreads')
  Module.onAbort('OOM private diagnostic')
  Module.onAbort('duplicate abort')
  await rejection
  await env.loaded()
  assert.deepEqual(failures, [[]], 'diagnostic text stays out of the fatal callback payload')
  assert.ok(logs.some(m => m.includes('OOM private diagnostic')))
  assert.equal(env.disconnects, 1)
  assert.equal(env.port.onmessage, null, 'late port must not revive failed startup')
  assert.equal(ready, 0)
})

test('runtime abort after successful boot notifies once and stops late ready callbacks', async t => {
  const env = environment(t)
  let failures = 0, ready = 0
  const promise = bootZetaOffice({ ...options(), onFatal: () => failures++, onReady: () => ready++ })
  await env.loaded()
  const boot = await promise
  Module.onAbort('native trap')
  env.port.onmessage({ data: { cmd: 'ui_ready' } })
  Module.onAbort('again')
  boot.dispose()
  assert.equal(failures, 1)
  assert.equal(ready, 0)
  assert.equal(env.disconnects, 1)
})

test('onFatal is optional for existing boot consumers', async t => {
  const env = environment(t)
  const promise = bootZetaOffice(options())
  await env.loaded()
  const boot = await promise
  assert.doesNotThrow(() => Module.onAbort('native trap'))
  boot.dispose()
  assert.equal(env.disconnects, 1)
})

test('disposed boot never sends a late fatal notification', async t => {
  const env = environment(t)
  let failures = 0
  const promise = bootZetaOffice({ ...options(), onFatal: () => failures++ })
  await env.loaded()
  const boot = await promise
  boot.dispose()
  Module.onAbort('late callback')
  assert.equal(failures, 0)
})

test('editor fatal callback sends only the stable engine-failed envelope', () => {
  const source = fs.readFileSync(new URL('../../src/zetaoffice/editor-main.js', import.meta.url), 'utf8')
  const body = source.match(/  onFatal\(\) \{([\s\S]*?)\n  \},/)
  assert.ok(body, 'editor endpoint must provide the fatal callback')
  const sent = []
  const createCallback = new Function('hostTransport', 'let engineFailed = false; return function () {' + body[1] + '}')
  const callback = createCallback({ send: message => sent.push(message) })
  callback(); callback()
  assert.deepEqual(sent, [{ __lo: 'lo-relay', type: 'engine-failed', code: 'EDITOR_ENGINE_FAILED' }])
  assert.doesNotThrow(() => createCallback({ send() { throw new Error('host already removed') } })())
})
