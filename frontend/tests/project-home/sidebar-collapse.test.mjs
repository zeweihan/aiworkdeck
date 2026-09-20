// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// dev-board#727：左栏收起状态持久化（rail 底部新增收起按钮）。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import {
  loadSidebarCollapsed,
  saveSidebarCollapsed,
} from '../../src/pages/project-overview/sidebarCollapse.js'

function fakeStorage(initial = {}) {
  const store = { ...initial }
  return {
    store,
    getStorageSync: (key) => (key in store ? store[key] : ''),
    setStorageSync: (key, value) => { store[key] = value },
  }
}

test('默认展开（未写过存储时返回 false）', () => {
  const storage = fakeStorage()
  assert.equal(loadSidebarCollapsed(storage), false)
})

test('写入后能原样读回 true', () => {
  const storage = fakeStorage()
  saveSidebarCollapsed(storage, true)
  assert.equal(storage.store.awd_sidebar_collapsed, true)
  assert.equal(loadSidebarCollapsed(storage), true)
})

test('写入 false 能原样读回 false', () => {
  const storage = fakeStorage({ awd_sidebar_collapsed: true })
  saveSidebarCollapsed(storage, false)
  assert.equal(loadSidebarCollapsed(storage), false)
})

test('坏值（非布尔真值）一律按未收起处理', () => {
  for (const bad of ['1', 1, 'true', null, undefined, {}, []]) {
    const storage = fakeStorage({ awd_sidebar_collapsed: bad })
    assert.equal(loadSidebarCollapsed(storage), false, `坏值 ${JSON.stringify(bad)} 应回落 false`)
  }
})

test('getStorageSync 抛异常时容错为未收起', () => {
  const storage = { getStorageSync: () => { throw new Error('boom') } }
  assert.equal(loadSidebarCollapsed(storage), false)
})

test('setStorageSync 抛异常时不向上抛出', () => {
  const storage = { setStorageSync: () => { throw new Error('boom') } }
  assert.doesNotThrow(() => saveSidebarCollapsed(storage, true))
})

test('saveSidebarCollapsed 把非布尔值归一成布尔再写入', () => {
  const storage = fakeStorage()
  saveSidebarCollapsed(storage, 'truthy-but-not-boolean')
  assert.equal(storage.store.awd_sidebar_collapsed, true)
})

// ======================================================================
// 接线核实：toggleSidebar 是三条入口（顶栏图标、rail 新按钮、Alt+Ctrl+B、
// 再点同一个 rail 面板图标）唯一共用的出口，必须同时落盘与触发工作台 resize，
// 否则收起状态在刷新/重开项目后又弹回来，或编辑器/iframe 不跟着重排。
// ======================================================================

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')

test('project-overview.vue 的 toggleSidebar 同时调用 saveSidebarCollapsed 与 triggerWorkbenchResize', () => {
  const src = read('pages/project-overview/project-overview.vue')
  assert.match(src, /import\s*\{\s*loadSidebarCollapsed,\s*saveSidebarCollapsed\s*\}\s*from\s*'\.\/sidebarCollapse\.js'/,
    'project-overview.vue 必须从 sidebarCollapse.js 引入这两个纯函数')

  const start = src.indexOf('toggleSidebar() {')
  assert.ok(start > 0, '找不到 toggleSidebar 方法')
  const end = src.indexOf('toggleAiPanel() {', start)
  assert.ok(end > start, '找不到紧随其后的 toggleAiPanel，方法边界定位失败')
  const body = src.slice(start, end)

  assert.match(body, /this\.sidebarCollapsed\s*=\s*!this\.sidebarCollapsed/, 'toggleSidebar 必须翻转 sidebarCollapsed')
  assert.match(body, /saveSidebarCollapsed\(uni,\s*this\.sidebarCollapsed\)/, 'toggleSidebar 必须落盘')
  assert.match(body, /triggerWorkbenchResize/, 'toggleSidebar 必须触发工作台 resize，否则编辑器/iframe 不跟着重排')
})

test('onLoad 用 loadSidebarCollapsed 恢复本机收起习惯', () => {
  const src = read('pages/project-overview/project-overview.vue')
  const onLoadStart = src.indexOf('onLoad(query) {')
  assert.ok(onLoadStart > 0, '找不到 onLoad')
  const onLoadEnd = src.indexOf('\n  onShow(', onLoadStart)
  const body = src.slice(onLoadStart, onLoadEnd > 0 ? onLoadEnd : onLoadStart + 6000)
  assert.match(body, /this\.sidebarCollapsed\s*=\s*loadSidebarCollapsed\(uni\)/,
    'onLoad 必须用 loadSidebarCollapsed 恢复状态，否则每次进项目左栏收起都会弹回来')
})

test('panelSwitching.js 的同 key 收展分支复用 toggleSidebar，不再各写各的翻转', () => {
  const src = read('pages/project-overview/panelSwitching.js')
  const start = src.indexOf('if (this.leftPaneKey === key) {')
  assert.ok(start > 0, '找不到同 key 收展分支')
  const end = src.indexOf('} else {', start)
  const body = src.slice(start, end)
  assert.match(body, /this\.toggleSidebar\(\)/, '再点同一个 rail 面板图标的收展必须走 toggleSidebar，否则漏掉持久化/resize')
  assert.doesNotMatch(body, /this\.sidebarCollapsed\s*=\s*!this\.sidebarCollapsed/,
    '不应该在这里再自己翻转一次，出口只有一个')
})

test('rail 底部渲染了收起/展开按钮，@tap 绑的是 toggleSidebar', () => {
  const src = read('pages/project-overview/project-overview.vue')
  const versionIdx = src.indexOf("@tap=\"toggleLeftPane('version')\"")
  const membersIdx = src.indexOf('Project Members Stack')
  assert.ok(versionIdx > 0 && membersIdx > versionIdx, '定位版本记录按钮与成员堆叠区块失败')
  const between = src.slice(versionIdx, membersIdx)
  assert.match(between, /@tap="toggleSidebar"/, '版本记录按钮与成员堆叠之间必须有一个走 toggleSidebar 的 rail-btn')
  assert.match(between, /class="rail-btn"/, '新按钮必须复用现有 .rail-btn 样式，不新起一套')
  assert.match(between, /GLYPHS\.panelLeft/, '图标复用顶栏同功能按钮的 GLYPHS.panelLeft')
})
