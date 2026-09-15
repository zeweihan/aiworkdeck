// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// 全屏浮层必须退出窗口拖拽区（dev-board B4 / 0907 清单 B10）。
//
// 病灶：桌面壳是无边框窗口，工作台顶栏 `.project-header` 那 42px 自己就是标题栏
// （App.vue：`-webkit-app-region: drag`）。拖拽区是壳按 app-region 另算的一套，
// **不受 z-index 与 DOM 命中管**（v0.18.0 顶栏死区那条实测结论，见
// utils/windowChrome.js 的长注释）。于是任何 `position: fixed; inset: 0` 的浮层
// 打开之后，它盖在顶栏上的那一条仍然是 drag：
//   - 用户点那一条想关掉浮层 → DOM 里根本收不到 click，浮层不关；
//   - 他于是再点一下 → 两次落在标题栏上的点击 = macOS 的「双击标题栏 = 缩放」，
//     窗口被 AppKit 自己撑成整块工作区（本机 1920×962 = workAreaSize）。
// 这就是「点团队页角色下拉后窗口撑满屏幕」以及 0907 那条「偶发自动全屏尺寸」的
// 由来——全仓没有任何改主窗口尺寸的代码（见 desktop/tests/main-window-bounds.test.js），
// 撑窗口的是 macOS 自己。
//
// 契约：凡是 `position: fixed` 且铺满视口的浮层，都要在 App.vue 里声明
// `-webkit-app-region: no-drag`。fixed 盒子恒排在常规流之后合成，所以后来的
// no-drag 抠洞会赢过顶栏的 drag（.awd-global-back 一直是好的，同一个道理）。
// 浮层铺满视口，所以它连带保护了自己里面那些菜单面板，不必逐个再列。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const SRC = path.resolve(HERE, '../../src')
const APP_VUE = path.join(SRC, 'App.vue')

/**
 * 明确豁免，每条都要写理由。
 * 豁免的判据只有一个：这层根本不吃鼠标事件，所以不会产生「点不动 → 再点一下 → 双击标题栏」
 * 那条链；顶栏该拖还能拖。
 */
const EXEMPT = new Map([
  ['.dock-drop-layer', 'pointer-events: none，只是拖面板时画三块高亮，不吃点击'],
  ['.ocr-frame-img', '在 .ocr-overlay 内部的装饰层，父层已 no-drag'],
  ['.ocr-frame-loading', '同上'],
  ['.ocr-frame-shade', '同上'],
])

function styleText(file) {
  const src = fs.readFileSync(file, 'utf8')
  if (!file.endsWith('.vue')) return src
  // .vue 里只看 <style>：否则正则会把 script 里的对象字面量当成规则块
  return [...src.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)].map((m) => m[1]).join('\n')
}

function ruleBlocks(css) {
  // 先摘注释：注释里提到的类名不能算「声明过」
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '\n').replace(/^\s*\/\/.*$/gm, '')
  return [...clean.matchAll(/([^{}]+)\{([^{}]*)\}/g)].map((m) => ({
    // 最后一个 `;` 之后的那一段才是选择器：scss 嵌套与 `}` 缺省分号都会让捕获
    // 里混进上一条规则的声明；而选择器列表（可以跨行、以逗号结尾）里不会有 `;`。
    selector: m[1].split(';').pop().trim(),
    body: m[2],
  }))
}

function classesIn(selector) {
  return selector.match(/\.[A-Za-z0-9_-]+/g) || []
}

function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, out)
    else if (/\.(vue|scss|css)$/.test(e.name)) out.push(p)
  }
  return out
}

/** 铺满视口的 fixed 浮层类名 → 声明它的文件 */
function fullViewportOverlays() {
  const found = new Map()
  for (const file of walk(SRC)) {
    for (const { selector, body } of ruleBlocks(styleText(file))) {
      if (!/position:\s*fixed/.test(body)) continue
      const bleeds = /inset:\s*0/.test(body)
        || (/left:\s*0/.test(body) && /top:\s*0/.test(body)
          && /right:\s*0/.test(body) && /bottom:\s*0/.test(body))
      if (!bleeds) continue
      for (const cls of classesIn(selector)) {
        if (!found.has(cls)) found.set(cls, new Set())
        found.get(cls).add(path.relative(SRC, file))
      }
    }
  }
  return found
}

/** App.vue 里声明了 no-drag 的类名 */
function noDragClasses() {
  const out = new Set()
  for (const { selector, body } of ruleBlocks(styleText(APP_VUE))) {
    if (!/-webkit-app-region:\s*no-drag/.test(body)) continue
    for (const cls of classesIn(selector)) out.add(cls)
  }
  return out
}

test('工作台顶栏确实是拖拽区（本契约的前提）', () => {
  const dragSelectors = ruleBlocks(styleText(APP_VUE))
    .filter((r) => /-webkit-app-region:\s*drag/.test(r.body))
    .map((r) => r.selector)
  assert.ok(dragSelectors.some((s) => s.includes('.project-header')),
    '前提变了：.project-header 不再声明 -webkit-app-region: drag，本契约要重写')
})

test('AwdSelect 的蒙层铺满视口，因此会压在顶栏上（B4 的前提）', () => {
  const overlays = fullViewportOverlays()
  assert.ok(overlays.has('.awd-select-mask'),
    '.awd-select-mask 不再是 position: fixed + inset: 0；若改成挂在触发器上的局部蒙层，本条可删')
})

test('每一个铺满视口的 fixed 浮层都在 App.vue 里退出了拖拽区', () => {
  const overlays = fullViewportOverlays()
  const noDrag = noDragClasses()
  const missing = []
  for (const [cls, files] of overlays) {
    if (noDrag.has(cls) || EXEMPT.has(cls)) continue
    missing.push(`${cls}（${[...files].join(', ')}）`)
  }
  assert.deepEqual(missing, [],
    '这些全屏浮层盖住了顶栏的拖拽区却没声明 no-drag：\n  '
    + missing.join('\n  ')
    + '\n浮层打开时点顶栏那一条会被 macOS 当成标题栏操作吃掉，连点两下就把窗口缩放成整块工作区。'
    + '\n修法：在 App.vue 的「全屏浮层退出拖拽区」那条规则里加上它；'
    + '\n真不吃点击的层才进本文件的 EXEMPT，并写理由。')
})

test('豁免名单不许留没用的条目', () => {
  const overlays = fullViewportOverlays()
  const stale = [...EXEMPT.keys()].filter((cls) => !overlays.has(cls))
  assert.deepEqual(stale, [], `EXEMPT 里这些类名已经不是全屏浮层了，删掉：${stale.join(', ')}`)
})
