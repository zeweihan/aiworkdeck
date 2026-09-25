// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-60：地址栏「收藏本页」星标点亮后，到收藏面板把这条收藏删掉，星标仍是实心，
// 连点刷新都不变，切标签（组件重建）才恢复。两处病灶：
//   ① 本会话新增的 URL 记在 localFavUrls 里、永远不清，服务端列表再怎么重拉都盖不掉它；
//   ② 收藏面板删除成功后没有任何通知，BrowserPane 根本不知道要重拉。
// 这里把 BrowserPane / ProjectFavoritesPanel 的 <script> 剥出来当普通对象真跑。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { shouldAcceptResponse } from '../../src/utils/requestGeneration.js'

function loadComponent(file, names, deps) {
  const src = readFileSync(new URL(`../../src/components/${file}`, import.meta.url), 'utf8')
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  // eslint-disable-next-line no-new-func
  return new Function(...names, script.replace('export default', 'return'))(...names.map((n) => deps[n]))
}

function makeBus() {
  const handlers = {}
  return {
    handlers,
    $on: (ev, fn) => { (handlers[ev] = handlers[ev] || []).push(fn) },
    $off: (ev, fn) => { handlers[ev] = (handlers[ev] || []).filter((h) => h !== fn) },
    $emit: (ev, payload) => { for (const h of handlers[ev] || []) h(payload) },
    showToast: () => {},
    getStorageSync: () => '',
    setStorageSync: () => {},
  }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

function makeBrowserPane(server, bus) {
  const names = ['getApiBaseUrl', 'createProjectFavorite', 'getProjectFavorites', 'getMyFavorites',
    'ICONS', 'host', 'shouldShowLoadError', 'loadErrorMessageKey', 'uni']
  const deps = {
    getApiBaseUrl: () => '',
    createProjectFavorite: async (pid, body) => {
      const row = { id: server.nextId++, sourceUrl: body.sourceUrl, title: body.title }
      server.rows.push(row)
      return row
    },
    getProjectFavorites: async () => server.rows.slice(),
    getMyFavorites: async () => server.rows.slice(),
    ICONS: {},
    host: { app: {} },
    shouldShowLoadError: () => false,
    loadErrorMessageKey: () => '',
    uni: bus,
  }
  const component = loadComponent('BrowserPane.vue', names, deps)
  const base = { $t: (k) => k, $emit: () => {}, $nextTick: (fn) => fn && fn(), projectId: 7, url: '', tabId: 't1' }
  const vm = Object.assign(base, component.data.call(base), component.methods)
  for (const [k, def] of Object.entries(component.computed || {})) {
    const fn = typeof def === 'function' ? def : def.get
    Object.defineProperty(vm, k, { get: () => fn.call(vm), configurable: true })
  }
  // 桌面宿主走 BrowserView 分支，这里测 Web 分支即可（收藏星标逻辑两边同一份）
  Object.defineProperty(vm, 'isDesktopBrowser', { get: () => false, configurable: true })
  return { vm, component }
}

function makeFavoritesPanel(server, bus) {
  const names = ['getProjectFavorites', 'deleteFavorite', 'getFavoriteImageUrl', 'ICONS', 'isDesktopHost',
    'shouldAcceptResponse', 'favoriteKind', 'uni', 'setTimeout', 'clearTimeout']
  const deps = {
    getProjectFavorites: async () => server.rows.slice(),
    deleteFavorite: async (id) => { server.rows = server.rows.filter((r) => r.id !== id) },
    getFavoriteImageUrl: () => '',
    ICONS: {},
    isDesktopHost: () => true,
    shouldAcceptResponse,
    favoriteKind: () => 'web',
    uni: bus,
    setTimeout: () => 0,
    clearTimeout: () => {},
  }
  const component = loadComponent('ProjectFavoritesPanel.vue', names, deps)
  const base = { $t: (k) => k, $emit: () => {}, projectId: 7, query: '' }
  return Object.assign(base, component.data.call(base), component.methods)
}

test('星标收藏后在收藏面板删除：星标恢复为未收藏（不用切标签）', async () => {
  const server = { rows: [], nextId: 1 }
  const bus = makeBus()
  const { vm, component } = makeBrowserPane(server, bus)
  globalThis.window = globalThis.window || { addEventListener: () => {}, removeEventListener: () => {} }
  component.mounted.call(vm)
  await flush()

  vm.currentUrl = 'https://example.com/page'
  await vm.favoriteCurrentPage()
  await flush()
  assert.equal(vm.isCurrentFavorited, true, '收藏后星标应实心')

  const panel = makeFavoritesPanel(server, bus)
  await panel.refresh(true)
  await panel.confirmDelete(server.rows[0].id)
  await flush()
  await flush()

  assert.equal(server.rows.length, 0)
  assert.equal(vm.isCurrentFavorited, false, '收藏已删，星标仍是实心')
})

test('点刷新也会重拉收藏列表，别处删掉的收藏不再点亮星标', async () => {
  const server = { rows: [{ id: 3, sourceUrl: 'https://example.com/a' }], nextId: 9 }
  const bus = makeBus()
  const { vm } = makeBrowserPane(server, bus)
  vm.currentUrl = 'https://example.com/a'
  await vm.loadFavorites()
  assert.equal(vm.isCurrentFavorited, true)

  server.rows = [] // 别的入口（设置页全部收藏、另一个窗口）删掉了
  vm.reload()
  await flush()
  assert.equal(vm.isCurrentFavorited, false)
})
