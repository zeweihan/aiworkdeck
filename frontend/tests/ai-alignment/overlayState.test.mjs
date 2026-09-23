// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// utils/overlayState.js 按持有者记账（dev-board#879）：两个浮层各自开关互不误伤，
// 同一持有者重复置位幂等。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = fs.readFileSync(path.join(here, '../../src/utils/overlayState.js'), 'utf8')

// 不引 vue：把 ref/computed 换成最小同步实现，只验记账逻辑
function load() {
  const body = src
    .replace(/import\s*\{[^}]*\}\s*from\s*'vue'\s*/, '')
    .replace(/export const globalOverlayActive = computed\(\(\) => (.*)\)/, 'const active = () => $1')
    .replace(/export function setGlobalOverlay/, 'function setGlobalOverlay')
    + '\nreturn { setGlobalOverlay, active }'
  const ref = (v) => ({ value: v })
  return new Function('ref', body)(ref)
}

test('two holders: the last one to release turns the overlay off, not the first', () => {
  const { setGlobalOverlay, active } = load()
  setGlobalOverlay(true)                       // 反馈浮窗（默认键）
  setGlobalOverlay(true, 'memory-browser:1')   // 记忆弹窗
  assert.equal(active(), true)
  setGlobalOverlay(false)                      // 反馈浮窗关了，记忆弹窗还开着
  assert.equal(active(), true)
  setGlobalOverlay(false, 'memory-browser:1')
  assert.equal(active(), false)
})

test('same holder repeated true/false is idempotent (rail icon clicked twice never leaks a hold)', () => {
  const { setGlobalOverlay, active } = load()
  setGlobalOverlay(true)
  setGlobalOverlay(true)
  setGlobalOverlay(false)
  assert.equal(active(), false)
  setGlobalOverlay(false)
  setGlobalOverlay(false)
  assert.equal(active(), false)
})

test('MemoryBrowser passes a per-instance holder key and AdminPane no longer clears others\' holds', () => {
  const browser = fs.readFileSync(path.join(here, '../../src/components/MemoryBrowser.vue'), 'utf8')
  assert.match(browser, /setGlobalOverlay\(want, this\.overlayKey\)/)
  assert.match(browser, /overlayKey: `memory-browser:\$\{\+\+overlayInstanceSeq\}`/)
  const admin = fs.readFileSync(path.join(here, '../../src/components/admin/AdminPane.vue'), 'utf8')
  assert.doesNotMatch(admin, /setGlobalOverlay\(/)
})
