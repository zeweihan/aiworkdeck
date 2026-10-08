// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { serializeTab, restore } from '../../src/pages/project-overview/tabSnapshot.js'
import { isContextEligibleTab } from '../../src/pages/project-overview/activeTabContext.js'

const source = readFileSync(new URL('../../src/pages/project-overview/panelSwitching.js', import.meta.url), 'utf8')
const { panelSwitchingMethods } = await import('data:text/javascript;base64,' + Buffer.from(source.replace(/^import .*$/gm, '') + '\nconst track = () => {}; const isPaneAllowedWithoutProject = () => false; const workbenchStorageKey = () => "pane";').toString('base64'))

test('rail click opens the plugin workspace instead of only selecting a narrow pane', () => {
  const opened = []
  globalThis.uni = { setStorageSync() {} }
  const vm = { hasProject: true, projectId: 1, leftPaneKey: 'files', lastActiveIdsByMode: { left: {}, right: {} }, dynamicPlugins: [{ key: 'plugin-tencent-meeting', pluginId: 'tencent-meeting' }], openPluginTab(id) { opened.push(id) }, saveActiveIdsByMode() {}, toggleSidebar() {} }
  panelSwitchingMethods.toggleLeftPane.call(vm, 'plugin-tencent-meeting')
  assert.deepEqual(opened, ['tencent-meeting'])
  panelSwitchingMethods.toggleLeftPane.call(vm, 'plugin-tencent-meeting')
  assert.deepEqual(opened, ['tencent-meeting', 'tencent-meeting'])
})

test('plugin snapshot only keeps identity and is never a document context', () => {
  const tab = { id: 'plugin-tencent-meeting', tabType: 'plugin', pluginId: 'tencent-meeting', name: '腾讯会议', url: 'https://untrusted.invalid', permissions: ['editor'], devInstalled: true }
  assert.deepEqual(serializeTab(tab), { id: tab.id, tabType: 'plugin', pluginId: tab.pluginId, name: tab.name })
  assert.equal(isContextEligibleTab(tab), false)
  const snapshot = { v: 1, left: [tab], right: [{ ...tab, id: 'duplicate' }], splitMode: true }
  const restored = restore(snapshot)
  assert.equal(restored.leftFiles.length + restored.rightFiles.length, 1)
  assert.equal(restore(snapshot, { hasProject: false }).leftFiles.length, 0)
})

const { pluginWorkspaceTabMethods } = await import('../../src/pages/project-overview/pluginWorkspaceTabs.js')
const { tabSnapshotMethods } = await import('../../src/pages/project-overview/tabSnapshot.js')
const plugin = (id = 'tencent-meeting') => ({ pluginId: id, key: `plugin-${id}`, label: id, hasFrontend: true, frontendEntry: `/api/plugin-web/${id}/index.html`, permissions: ['file_read'], devInstalled: false })
function workbench(extra = {}) {
  const vm = { hasProject: true, dynamicPlugins: [plugin(), plugin('due-diligence')], dynamicPluginsLoaded: true,
    leftFiles: [], rightFiles: [], activeFileIdLeft: null, activeFileIdRight: null, splitMode: false, focusedPane: 'left',
    lastActiveIdsByMode: { left: {}, right: {} }, saveActiveIdsByMode() {}, $nextTick(fn) { fn() }, triggerWorkbenchResize() {}, ...extra }
  return Object.assign(vm, pluginWorkspaceTabMethods)
}

test('plugins open as independent tabs and refocus a singleton across panes', () => {
  const vm = workbench({ splitMode: true, focusedPane: 'right' })
  vm.openPluginTab('tencent-meeting')
  const first = vm.rightFiles[0]
  assert.equal(vm.activeFileIdRight, 'plugin-tencent-meeting')
  vm.focusedPane = 'left'
  vm.openPluginTab('due-diligence')
  assert.equal(vm.activeFileIdLeft, 'plugin-due-diligence')
  vm.openPluginTab('tencent-meeting')
  assert.equal(vm.focusedPane, 'right')
  assert.equal(vm.rightFiles[0], first)
  assert.equal(vm.leftFiles.length + vm.rightFiles.length, 2)
})

test('no-project and unavailable plugin entry cannot open a workspace', () => {
  const vm = workbench({ hasProject: false })
  vm.openPluginTab('tencent-meeting')
  vm.hasProject = true
  vm.openPluginTab('uninstalled')
  assert.deepEqual(vm.leftFiles, [])
})

test('workspace uses live manifest metadata; snapshot fields cannot grant permissions or change URL', () => {
  const vm = workbench()
  vm.openPluginTab('tencent-meeting')
  const tab = vm.leftFiles[0]
  Object.assign(tab, { frontendEntry: 'https://untrusted.invalid', permissions: ['editor'], devInstalled: true })
  let entries = vm.pluginWorkspaceTabs(vm.leftFiles)
  assert.equal(entries[0].plugin, vm.dynamicPlugins[0])
  assert.equal(entries[0].plugin.frontendEntry, '/api/plugin-web/tencent-meeting/index.html')
  assert.deepEqual(entries[0].plugin.permissions, ['file_read'])
  assert.equal(entries[0].plugin.devInstalled, false)
  vm.dynamicPlugins = [{ ...plugin(), permissions: [], frontendEntry: '/api/plugin-web/tencent-meeting/v2.html' }]
  entries = vm.pluginWorkspaceTabs(vm.leftFiles)
  assert.equal(entries[0].plugin.frontendEntry, '/api/plugin-web/tencent-meeting/v2.html')
  assert.deepEqual(entries[0].plugin.permissions, [])
})

test('switching active files does not remove either plugin from the mounted pool; closing does', () => {
  const vm = workbench()
  vm.openPluginTab('tencent-meeting')
  vm.openPluginTab('due-diligence')
  const initial = vm.pluginWorkspaceTabs(vm.leftFiles).map(e => e.tab)
  vm.leftFiles.push({ id: 42, fileType: 'docx' })
  vm.activeFileIdLeft = 42
  assert.deepEqual(vm.pluginWorkspaceTabs(vm.leftFiles).map(e => e.tab), initial)
  vm.leftFiles.splice(0, 1)
  assert.deepEqual(vm.pluginWorkspaceTabs(vm.leftFiles).map(e => e.tab.pluginId), ['due-diligence'])
})

test('authoritative removal cleans both panes and stale active memories, preserving other tabs', () => {
  const vm = workbench({ splitMode: true })
  vm.leftFiles.push({ id: 11, name: 'file' })
  vm.openPluginTab('tencent-meeting')
  vm.focusedPane = 'right'
  vm.openPluginTab('due-diligence')
  vm.lastActiveIdsByMode.left['plugin-tencent-meeting'] = vm.activeFileIdLeft
  vm.lastActiveIdsByMode.right.files = vm.activeFileIdRight
  vm.dynamicPlugins = []
  vm.reconcilePluginTabs()
  assert.deepEqual(vm.leftFiles, [{ id: 11, name: 'file' }])
  assert.deepEqual(vm.rightFiles, [])
  assert.equal(vm.activeFileIdLeft, 11)
  assert.equal(vm.activeFileIdRight, null)
  assert.equal(vm.lastActiveIdsByMode.left['plugin-tencent-meeting'], null)
  assert.equal(vm.lastActiveIdsByMode.right.files, null)
})

test('restored tabs wait for an authoritative list; a failed first request cannot mount or delete them', () => {
  const vm = workbench({ dynamicPluginsLoaded: false, dynamicPlugins: [], leftFiles: [{ id: 'plugin-tencent-meeting', tabType: 'plugin', pluginId: 'tencent-meeting', name: 'old' }] })
  vm.reconcilePluginTabs()
  assert.equal(vm.leftFiles.length, 1)
  assert.deepEqual(vm.pluginWorkspaceTabs(vm.leftFiles), [])
  vm.dynamicPlugins = [plugin()]
  vm.dynamicPluginsLoaded = true
  vm.reconcilePluginTabs()
  assert.equal(vm.leftFiles[0].name, 'tencent-meeting')
  assert.equal(vm.pluginWorkspaceTabs(vm.leftFiles).length, 1)
  vm.dynamicPlugins = []
  vm.reconcilePluginTabs()
  assert.deepEqual(vm.leftFiles, [])
})

test('snapshot finishing after plugin list still reconciles unavailable entries', async () => {
  const tab = { id: 'plugin-removed', tabType: 'plugin', pluginId: 'removed', name: 'old' }
  const vm = workbench({ projectId: 1, $tabStorage: { getStorageSync() { return { v: 1, left: [tab], right: [] } } } })
  Object.assign(vm, tabSnapshotMethods)
  vm.scheduleTabSnapshotSave = () => {}
  await vm.restoreTabSnapshot()
  assert.deepEqual(vm.leftFiles, [])
  assert.equal(vm.activeFileIdLeft, null)
})

test('plugin tabs cannot be duplicated with an Alt drag', async () => {
  const dragSource = readFileSync(new URL('../../src/pages/project-overview/tabDragSplit.js', import.meta.url), 'utf8')
  const { tabDragSplitMethods } = await import('data:text/javascript;base64,' + Buffer.from(dragSource.replace(/^import .*$/gm, '')).toString('base64'))
  const vm = workbench()
  vm.openPluginTab('tencent-meeting')
  assert.equal(tabDragSplitMethods.canDualOpenTab.call(vm, 'plugin-tencent-meeting', 'left'), false)
})
