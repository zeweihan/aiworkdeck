// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// Execute the real SFC methods and market event subscriptions with a fake transport/event bus.
// No Vue dependency: unrelated page lifecycle and imports stay outside this harness.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const page = readFileSync(new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const sidebar = readFileSync(new URL('../../src/components/MarketSidebarPanel.vue', import.meta.url), 'utf8')
const flush = () => new Promise(resolve => setImmediate(resolve))
const quiet = { log() {}, warn() {}, error() {} }
const plugin = (extra = {}) => ({ id: 'tencent-meeting', name: '腾讯会议', enabled: true, frontendEntry: 'web/index.html', permissions: ['file_read'], ...extra })

function method(source, name, deps) {
  const match = new RegExp(`\\n    (?:async )?${name}\\([^\\n]*\\) \\{`).exec(source)
  assert.ok(match, `SFC method ${name} exists`)
  const end = source.indexOf('\n    },', match.index)
  assert.ok(end > match.index, `SFC method ${name} has a boundary`)
  return new Function(...Object.keys(deps), `return ({${source.slice(match.index, end + 6)}}).${name}`)(...Object.values(deps))
}

function harness() {
  let response = []
  let get = async () => response
  const listeners = new Map(), events = []
  const uni = {
    $on(name, fn) { const handlers = listeners.get(name) || []; handlers.push(fn); listeners.set(name, handlers) },
    $emit(name) { events.push(name); for (const fn of listeners.get(name) || []) fn() },
    showToast() {},
    showModal(options) { options.success({ confirm: true }) },
  }
  const deps = {
    uni, console: quiet, NO_PROJECT_DEFAULT_PANE: 'projects', getPlugins: () => get(), getSkills: async () => [],
    resolvePluginEntryUrl: (id, path) => path ? `/api/plugin-web/${id}/${path.slice(4)}` : '',
    getCurrentUser: () => ({ role: 'ADMIN' }), getPluginsForUser: () => [{ key: 'files' }],
    filterPluginsByEnabledSkills: rows => rows,
    rescanPlugins: async () => ({ pluginCount: response.length }), rescanSkills: async () => ({ skillCount: 0 }),
    getSkillMarket: async () => ({ skills: [] }), getPluginMarket: async () => ({ plugins: [] }),
    installMarketPlugin: async () => { response = [plugin({ enabled: false })] },
    isAccountLoginRetry: () => false,
  }
  const vm = { dynamicPlugins: [], enabledSkillIds: null, hasProject: true, leftPaneKey: 'files',
    applyPanelDocks: rows => rows, applyRailOrder: rows => rows, ttsEnabled: true }
  for (const name of ['loadDynamicPlugins', 'loadEnabledSkills']) vm[name] = method(page, name, deps).bind(vm)
  Object.defineProperty(vm, 'rail', { get: method(page, 'LEFT_SIDEBAR_PLUGINS', deps).bind(vm) })
  Object.defineProperty(vm, 'activeDynamicPlugin', { get: method(page, 'activeDynamicPlugin', deps).bind(vm) })

  // Run the unchanged source of onLoad's initial loads + both market subscriptions.
  const start = page.indexOf('    this.loadDynamicPlugins() // Fetch dynamic plugins')
  const last = "    uni.$on('awd:market-changed-from-sidebar', this._onMarketChanged)"
  const end = page.indexOf(last, start) + last.length
  assert.ok(start > 0 && end > start, 'market lifecycle fragment exists')
  new Function('uni', page.slice(start, end)).call(vm, uni)

  const market = { rescanning: false, pluginBusyId: '', $t: key => key }
  for (const name of ['rescan', 'reloadAll', 'loadInstalled', 'loadMarketSkills', 'loadMarketPlugins', 'installPluginRow']) {
    market[name] = method(sidebar, name, deps).bind(market)
  }
  return { vm, market, events, setResponse(value) { response = value }, setTransport(fn) { get = fn }, async emit(event) { uni.$emit(event); await flush() } }
}

for (const event of ['awd:market-changed', 'awd:market-changed-from-sidebar']) {
  test(`${event} refreshes enabled plugins and removes disabled/uninstalled entries`, async () => {
    const h = harness(); await flush()
    h.setResponse([plugin()]); await h.emit(event)
    assert.deepEqual(h.vm.rail.map(p => p.key), ['files', 'plugin-tencent-meeting'])
    h.vm.leftPaneKey = 'plugin-tencent-meeting'
    assert.equal(h.vm.activeDynamicPlugin.pluginId, 'tencent-meeting')
    h.setResponse([plugin({ enabled: false })]); await h.emit(event)
    assert.deepEqual(h.vm.rail.map(p => p.key), ['files'])
    assert.equal(h.vm.activeDynamicPlugin, null)
    assert.equal(h.vm.leftPaneKey, 'files')
    h.setResponse([plugin()]); await h.emit(event)
    assert.equal(h.vm.rail.length, 2)
    h.vm.leftPaneKey = 'plugin-tencent-meeting'
    h.setResponse([]); await h.emit(event)
    assert.deepEqual(h.vm.rail.map(p => p.key), ['files'])
    assert.equal(h.vm.activeDynamicPlugin, null)
    assert.equal(h.vm.leftPaneKey, 'files')
  })
}

test('list endpoint includes every plugin: rail admits only enabled, compatible, non-revoked rows', async () => {
  const h = harness(); await flush()
  h.setResponse({ data: [plugin(), plugin({ id: 'disabled', enabled: false }), plugin({ id: 'revoked', revokedReason: '下架' }),
    plugin({ id: 'new-host', incompatibleReason: '需要更新' }), plugin({ id: 'missing-enabled', enabled: undefined })] })
  await h.vm.loadDynamicPlugins()
  assert.deepEqual(h.vm.rail.map(p => p.key), ['files', 'plugin-tencent-meeting'])
})

test('sidebar install notifies the page but does not show the disabled installation until enabled', async () => {
  const h = harness(); await flush()
  await h.market.installPluginRow({ id: 'tencent-meeting', raw: plugin() }); await flush()
  assert.ok(h.events.includes('awd:market-changed-from-sidebar'))
  assert.deepEqual(h.vm.rail.map(p => p.key), ['files'])
  h.setResponse([plugin()]); await h.emit('awd:market-changed')
  assert.equal(h.vm.rail.at(-1).pluginId, 'tencent-meeting')
})

test('successful sidebar rescan propagates newly loaded plugins into the rail', async () => {
  const h = harness(); await flush()
  h.setResponse([plugin()]); await h.market.rescan(); await flush()
  assert.ok(h.events.includes('awd:market-changed-from-sidebar'))
  assert.equal(h.vm.rail.at(-1).pluginId, 'tencent-meeting')
  assert.equal(h.market.rescanning, false)
})

test('transport failure and malformed payload preserve the last valid rail', async () => {
  const h = harness(); await flush(); h.setResponse([plugin()]); await h.vm.loadDynamicPlugins()
  const previous = h.vm.dynamicPlugins
  h.setTransport(async () => { throw new Error('offline') }); await h.vm.loadDynamicPlugins()
  assert.equal(h.vm.dynamicPlugins, previous)
  h.setTransport(async () => ({ data: { message: 'unavailable' } })); await h.vm.loadDynamicPlugins()
  assert.equal(h.vm.dynamicPlugins, previous)
})

test('a delayed old enabled response cannot resurrect an entry removed by a newer refresh', async () => {
  const h = harness(); await flush()
  let resolveOld
  h.setTransport(() => new Promise(resolve => { resolveOld = resolve }))
  const old = h.vm.loadDynamicPlugins()
  h.setTransport(async () => []); await h.vm.loadDynamicPlugins()
  resolveOld([plugin()]); await old
  assert.deepEqual(h.vm.rail.map(p => p.key), ['files'])
})

test('removed dynamic selection falls back without a project; unrelated selection and failures are preserved', async () => {
  const h = harness(); await flush()
  h.vm.hasProject = false; h.vm.leftPaneKey = 'plugin-tencent-meeting'
  await h.vm.loadDynamicPlugins()
  assert.equal(h.vm.leftPaneKey, 'projects')
  h.vm.leftPaneKey = 'market'; await h.vm.loadDynamicPlugins()
  assert.equal(h.vm.leftPaneKey, 'market')
  h.vm.leftPaneKey = 'plugin-tencent-meeting'
  h.setTransport(async () => { throw new Error('offline') }); await h.vm.loadDynamicPlugins()
  assert.equal(h.vm.leftPaneKey, 'plugin-tencent-meeting')
})
