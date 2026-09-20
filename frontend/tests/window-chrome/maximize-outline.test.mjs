// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// Windows 非最大化时应用边界与浅色资源管理器分不清（dev-board#722）。
//
// 主进程随 chrome-state 一并上报 isMaximized（desktop/main/main.js 的
// sendChromeState），渲染层 utils/windowChrome.js 的 onState 回调要把它
// 翻成 documentElement 上的 `is-maximized` class，App.vue 再用
// `html.is-win:not(.is-maximized):not(.is-fullscreen)` 描边。
//
// 本用例只守渲染层这一段翻译逻辑——纯函数 applyChromeState，不依赖 document/host，
// 避免为了测一个 classList.toggle 去搭一整套 uni-app 组件桩。
// 还原病灶（把 applyChromeState 里 `is-maximized` 那行删掉，或恒传 false）本文件即转红。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

// windowChrome.js 顶部 `import { host, isDesktopHost } from '@/services/host.js'`
// 用的是 vite/uni 的 '@' 别名，纯 node --test 解析不了；applyChromeState 本身不
// 依赖 host，所以照 tests/version-history/* 的老办法，从源码里单独摘出这一个
// 函数来跑，不把整个模块 import 进来。
function loadApplyChromeState() {
  const source = readFileSync(new URL('../../src/utils/windowChrome.js', import.meta.url), 'utf8')
  const m = source.match(/export function applyChromeState\(classList, data\) \{[\s\S]*?\n\}/)
  assert.ok(m, 'applyChromeState 的函数体没找到——是不是签名或导出方式变了？同步改这里的正则')
  const body = m[0].replace(/^export function applyChromeState/, 'return function applyChromeState')
  return new Function(body)()
}

const applyChromeState = loadApplyChromeState()

/** 假 classList：只实现 toggle(name, force)，记录当前挂了哪些 class。 */
function fakeClassList() {
  const set = new Set()
  return {
    toggle(name, force) {
      if (force) set.add(name)
      else set.delete(name)
    },
    has: (name) => set.has(name),
  }
}

test('isMaximized: true 挂上 is-maximized', () => {
  const cl = fakeClassList()
  applyChromeState(cl, { fullscreen: false, isMaximized: true })
  assert.equal(cl.has('is-maximized'), true)
  assert.equal(cl.has('is-fullscreen'), false)
})

test('isMaximized: false（非最大化）不挂 is-maximized，描边规则的前提成立', () => {
  const cl = fakeClassList()
  applyChromeState(cl, { fullscreen: false, isMaximized: false })
  assert.equal(cl.has('is-maximized'), false)
})

test('先最大化再还原：is-maximized 会随之摘掉', () => {
  const cl = fakeClassList()
  applyChromeState(cl, { fullscreen: false, isMaximized: true })
  assert.equal(cl.has('is-maximized'), true)
  applyChromeState(cl, { fullscreen: false, isMaximized: false })
  assert.equal(cl.has('is-maximized'), false)
})

test('全屏与最大化互不影响，各自按自己的字段翻译', () => {
  const cl = fakeClassList()
  applyChromeState(cl, { fullscreen: true, isMaximized: false })
  assert.equal(cl.has('is-fullscreen'), true)
  assert.equal(cl.has('is-maximized'), false)
})

test('data 为 null/undefined 时不抛错、不改动 class（等首个真实 state）', () => {
  const cl = fakeClassList()
  assert.doesNotThrow(() => applyChromeState(cl, null))
  assert.doesNotThrow(() => applyChromeState(cl, undefined))
  assert.equal(cl.has('is-maximized'), false)
  assert.equal(cl.has('is-fullscreen'), false)
})

test('缺 isMaximized 字段（老版本壳）按未最大化处理，不抛错', () => {
  const cl = fakeClassList()
  applyChromeState(cl, { fullscreen: false })
  assert.equal(cl.has('is-maximized'), false)
})
