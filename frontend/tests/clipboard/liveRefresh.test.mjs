// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// BUG-62：剪贴板面板开着时，外部连续复制三条都不出现，切标签才刷出来。
// 采集桥入库成功后只经 this.$refs.clipboardPanel 去通知面板——ref 不在这个页面实例上
// （页面栈里另一个实例的监听器先抢到了这次复制、或面板挂在别的坞位）时通知就丢了，
// 只留一个 pending 标记等切标签。改成入库成功即广播全局事件，面板自己订阅重拉。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

import { shouldAcceptResponse } from '../../src/utils/requestGeneration.js'

function makeBus() {
  const handlers = {}
  return {
    handlers,
    $on: (ev, fn) => { (handlers[ev] = handlers[ev] || []).push(fn) },
    $off: (ev, fn) => { handlers[ev] = (handlers[ev] || []).filter((h) => h !== fn) },
    $emit: (ev, payload) => { for (const h of handlers[ev] || []) h(payload) },
    showToast: () => {},
  }
}

function makeBridge(bus) {
  const SRC = readFileSync(new URL('../../src/pages/project-overview/clipboardBridge.js', import.meta.url), 'utf8')
  const body = SRC
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
    .replace('export const clipboardBridgeMethods =', 'return')
  const deps = {
    saveClipboardText: async (t) => ({ data: { id: 1, text: t } }),
    saveClipboardFile: async () => ({ data: {} }),
    getCurrentUser: () => null,
    host: {},
    isDesktopHost: () => true,
    window: {},
    document: {},
    uni: bus,
  }
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  return new Function(...names, body)(...names.map((n) => deps[n]))
}

function makePanel(bus, server) {
  const SRC = readFileSync(new URL('../../src/components/ClipboardPanel.vue', import.meta.url), 'utf8')
  const script = SRC.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import\s[\s\S]*?from\s+'[^']+'\s*;?\s*$/gm, '')
  const deps = {
    listClipboard: async () => { server.lists += 1; return { items: server.rows.slice(), limited: false, hiddenCount: 0 } },
    deleteClipboardItem: async () => {},
    getApiBaseUrl: () => '',
    getClipboardTypeMeta: () => ({ label: 'TEXT', tone: 'neutral' }),
    getSessionId: () => 's',
    ICONS: {},
    UnlockHint: {},
    shouldAcceptResponse,
    host: { app: {} },
    uni: bus,
    setTimeout,
    clearTimeout,
  }
  const names = Object.keys(deps)
  // eslint-disable-next-line no-new-func
  const component = new Function(...names, script.replace('export default', 'return'))(...names.map((n) => deps[n]))
  const base = { $t: (k) => k, $emit: () => {}, query: '' }
  const vm = Object.assign(base, component.data.call(base), component.methods)
  return { vm, component }
}

const flush = () => new Promise((r) => setTimeout(r, 0))

test('采集桥入库成功后，开着的剪贴板面板自己重拉（不依赖 $refs）', async () => {
  const bus = makeBus()
  const server = { rows: [], lists: 0 }
  const { vm: panel, component } = makePanel(bus, server)
  component.mounted.call(panel)
  await flush()
  assert.equal(panel.items.length, 0)

  // 抢到这次复制的页面实例上没有 clipboardPanel 这个 ref
  const page = Object.assign({ $refs: {}, $t: (k) => k }, makeBridge(bus))
  server.rows = [{ id: 1, type: 'TEXT', text: 'QA-C8-剪贴一' }]
  page.onClipboardSaved(server.rows[0])
  await flush()

  assert.deepEqual(panel.items.map((i) => i.text), ['QA-C8-剪贴一'], '面板开着时新复制的内容应自动出现')

  component.beforeUnmount.call(panel)
  const before = server.lists
  page.onClipboardSaved(server.rows[0])
  await flush()
  assert.equal(server.lists, before, '面板卸载后必须退订，不再重拉')
})
