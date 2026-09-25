// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#980 BUG-69：工具栏「解析」按钮要能「点开/点关」。openPanelInItsDock 原本
// 只会打开（状态条工具入口的收起走另一条 openToolFromStatusBar），插到编辑器工具栏上
// 复用同一个「已经开着再点一次要收起」语义就得先补上 closeDockedPanel/toggleDockedPanel
// 这两个通用出口——本用例钉住它们对 insight 面板在 left/right 两档下的开合行为，
// 不依赖 Vue/uni（panelDocking.js 本身要在组件里跑，这里剥出 panelDockingMethods
// 对象字面量配假 this 单测，同 tests/panel-dock/dock-resolve.test.mjs 的注册表单测同法）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { resolveDock } from '../../src/config/panelRegistry.js'

const SRC = readFileSync(new URL('../../src/pages/project-overview/panelDocking.js', import.meta.url), 'utf8')
const i = SRC.indexOf('export const panelDockingMethods = {')
if (i < 0) throw new Error('panelDockingMethods 没找到——panelDocking.js 改了结构？')
const body = SRC.slice(i).replace('export const panelDockingMethods = ', 'return ')
// eslint-disable-next-line no-new-func
const panelDockingMethods = new Function('resolveDock', body)(resolveDock)

function makeHost(overrides) {
  return Object.assign({
    // isPanelOpenIn/openPanelInItsDock/closeDockedPanel/toggleDockedPanel 互相用 this.
    // 调对方——call 单个方法时得把整组一起挂到假 this 上，不能只挂被测的那一个。
    isPanelOpenIn: panelDockingMethods.isPanelOpenIn,
    openPanelInItsDock: panelDockingMethods.openPanelInItsDock,
    closeDockedPanel: panelDockingMethods.closeDockedPanel,
    toggleDockedPanel: panelDockingMethods.toggleDockedPanel,
    panelDockOverrides: {},
    leftPaneKey: 'files',
    sidebarCollapsed: false,
    showAiPanel: false,
    rightPaneKey: 'ai',
    showToolsPanel: false,
    activeToolKey: '',
    $nextTick: (fn) => fn && fn(),
    triggerWorkbenchResize() {},
    toggleLeftPane(key) { this._toggleLeftPaneCalls = (this._toggleLeftPaneCalls || []).concat(key); this.leftPaneKey = key; this.sidebarCollapsed = false },
    toggleAiPanel() { this.showAiPanel = !this.showAiPanel; this._toggleAiPanelCalls = (this._toggleAiPanelCalls || 0) + 1 },
    switchToolTab(key) { this.activeToolKey = key },
    toggleToolsPanel() { this.showToolsPanel = !this.showToolsPanel },
  }, overrides)
}

test('insight 默认停右栏：右栏没开时点一次「解析」——开 AI 面板并切到 insight tab', () => {
  const host = makeHost()
  panelDockingMethods.toggleDockedPanel.call(host, 'insight')
  assert.equal(host.showAiPanel, true)
  assert.equal(host.rightPaneKey, 'insight')
})

test('insight 已经停右栏且开着、绑在这份文档上：再点一次「解析」——切回 AI 对话 tab，AI 面板本身不关', () => {
  const host = makeHost({ showAiPanel: true, rightPaneKey: 'insight' })
  panelDockingMethods.toggleDockedPanel.call(host, 'insight')
  assert.equal(host.rightPaneKey, 'ai')
  assert.equal(host.showAiPanel, true, '收起 insight 不该把整块 AI 面板一起关掉')
})

test('用户把 insight 拖到了左栏：没开时点一次——走 toggleLeftPane 打开', () => {
  const host = makeHost({ panelDockOverrides: { insight: 'left' } })
  panelDockingMethods.toggleDockedPanel.call(host, 'insight')
  assert.deepEqual(host._toggleLeftPaneCalls, ['insight'])
  assert.equal(host.leftPaneKey, 'insight')
})

test('insight 停左栏且已经开着：再点一次——同一个 toggleLeftPane 出口把侧栏收起（既有语义）', () => {
  const host = makeHost({ panelDockOverrides: { insight: 'left' }, leftPaneKey: 'insight', sidebarCollapsed: false })
  panelDockingMethods.toggleDockedPanel.call(host, 'insight')
  assert.deepEqual(host._toggleLeftPaneCalls, ['insight'], '收起走的应该是 toggleLeftPane(key) 本身（其内部识别同 key 再收展）')
})

test('closeDockedPanel 对没开着的面板是空操作，调用方不用先判断当前是不是开着', () => {
  const host = makeHost()
  panelDockingMethods.closeDockedPanel.call(host, 'insight')
  assert.equal(host.showAiPanel, false)
  assert.equal(host.rightPaneKey, 'ai')
  assert.equal(host._toggleAiPanelCalls, undefined)
})
