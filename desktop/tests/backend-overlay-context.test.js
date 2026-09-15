// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const vm = require('node:vm')
const { createServiceManager } = require('../main/services/service-manager')
const { createBackendDescriptor, backendLayout } = require('../main/services/backend-service')
const overlay = require('../main/services/overlay')

function harness(t) {
  const home = fs.mkdtempSync(path.join(os.tmpdir(), 'awd-backend-overlay-'))
  t.after(() => fs.rmSync(home, { recursive: true, force: true }))
  const resourcesPath = path.join(home, 'resources')
  const builtin = path.join(resourcesPath, 'backend', 'app.jar')
  fs.mkdirSync(path.dirname(builtin), { recursive: true })
  fs.writeFileSync(builtin, 'bundled backend')
  const source = fs.readFileSync(path.join(__dirname, '../main/main.js'), 'utf8')
  const start = source.indexOf('function createServices()')
  const end = source.indexOf('// 组件管理（模型下载/状态）', start)
  assert.ok(start >= 0 && end > start)
  const context = vm.createContext({
    path, process: { resourcesPath }, __dirname: path.join(__dirname, '../main'),
    app: { isPackaged: true, getVersion: () => '0.38.4', getPath: () => home },
    createServiceManager, createBackendDescriptor, createModelManager: () => ({}),
    createPptxDescriptor: () => ({ name: 'pptx-service' }),
    createMineruDescriptor: () => ({ name: 'mineru-service' }),
    createKokoroDescriptor: () => ({ name: 'kokoro-service' }),
    createAsrDescriptor: () => ({ name: 'asr-service' }),
    require: name => { assert.equal(name, './services/win-arch'); return { isWinArmEmulated: () => false } },
  })
  vm.runInContext(`let modelManager = null; ${source.slice(start, end)}; globalThis.manager = createServices()`, context)
  const correctCtx = { packaged: true, appVersion: '0.38.4', dataDir: path.join(home, '.aiworkdeck') }
  return { home, builtin, manager: context.manager, correctCtx }
}

test('shipping service creation loads the activated backend patch and honors rollback', t => {
  const h = harness(t)
  assert.equal(backendLayout(h.manager.ctx).appJar, h.builtin)
  const staged = path.join(h.home, 'staged')
  fs.mkdirSync(staged); fs.writeFileSync(path.join(staged, 'app.jar'), 'patched backend')
  overlay.activate(h.correctCtx, '0.38.5', { 'backend-app': staged })
  const expected = path.join(overlay.componentDir(h.correctCtx, 'backend-app'), 'app.jar')
  assert.equal(backendLayout(h.manager.ctx).appJar, expected)
  assert.equal(backendLayout(h.manager.ctx).libDir, path.join(path.dirname(h.builtin), 'lib'))
  overlay.revert(h.correctCtx)
  assert.equal(backendLayout(h.manager.ctx).appJar, h.builtin)
})

test('shipping service creation ignores patches from another major version', t => {
  const h = harness(t)
  const staged = path.join(h.home, 'staged')
  fs.mkdirSync(staged); fs.writeFileSync(path.join(staged, 'app.jar'), 'other-major backend')
  overlay.activate({ ...h.correctCtx, appVersion: '0.37.0' }, '0.37.1', { 'backend-app': staged })
  assert.equal(backendLayout(h.manager.ctx).appJar, h.builtin)
  assert.equal(h.manager.ctx.appVersion, '0.38.4')
})
