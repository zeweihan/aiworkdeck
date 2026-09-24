// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
//
// dev-board#884：FileTree.vue「管理标签」弹窗里点「创建新标签」展开的内嵌表单
// （TagSelector.vue 的 .color-picker-mode：类型 + 选颜色 + 创建/取消）被弹窗底边裁掉，
// 创建/取消按钮既看不见也点不到，两个候选滚动区域（弹窗、下拉菜单自身）都滚不动。
//
// 根因（用真实 Chrome 渲染 + getBoundingClientRect 量出来的，不是猜的）：
// TagSelector 的下拉是 `.dropdown-menu { position: absolute; top: 100% }`，绝对定位
// 元素不参与正常流布局，所以外层 `.awd-dialog`（FileTree.vue）的高度只按「标题 /
// 当前标签 / 输入框」这些正常流内容撑出来——展开创建表单前，弹窗天然就"够矮"；
// 表单一展开，`.dropdown-menu` 的实际内容比这份"自然高度"高出一截，又撞上
// `.awd-dialog { overflow: hidden }`，创建/取消按钮就落在弹窗盒子外面被裁掉。
//
// 第一版修法只加了 `.awd-dialog max-height: 85vh` + `.awd-dialog-body
// overflow-y: auto`，能把按钮滚出来，但复测发现观感很差：弹窗只给整个表单一个
// 170px 高的滚动小窗口，颜色行/按钮全挤在里面，弹窗底下却还留着一大截「完成」
// 按钮周围的空白——因为 `.dropdown-menu` 本质上还是个浮层，不参与撑高弹窗。
//
// 定案修法（本文件断言的就是这一版）：TagSelector.vue 新增 `inline` prop（默认
// false，其它调用点不变，浮层行为照旧）；FileTree.vue「管理标签」弹窗传
// `inline`，下拉退回 `position: static` 参与正常流——表单展开多高，弹窗
// （被 `.awd-dialog max-height:85vh` 和 `.awd-dialog-body overflow-y:auto`
// 兜底）就跟着自然长多高，只有真的顶到 85vh 才需要滚。真实 Chrome 量出的结果：
// 1400x900 下弹窗自然长到 587.5px，`.awd-dialog-body` scrollHeight(402) ===
// clientHeight(402)（不需要滚，三个按钮——创建/取消/完成——全部在视口内）；
// 1000x600 下弹窗撞上 85vh 上限，body scrollHeight(402) > clientHeight(325)
// （这时才真的需要滚），配合 TagSelector.startCreate() 里的 scrollIntoView
// 兜底，创建/取消仍然滚得到。过程截图（会话内证据，不随代码提交）：
// /private/tmp/.../scratchpad/inline-fix-{1400x900,1000x600}.png。
//
// FileTree.vue / TagSelector.vue 体量大、@/ 别名多，本仓对这类文件的既有做法是只做
// 源码文本断言（见 audit-rE-source-assertions.test.mjs 的说明），这里跟随同一惯例。

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const read = (rel) => readFileSync(new URL('../../src/' + rel, import.meta.url), 'utf8')
const stripComments = (s) =>
  s.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

function extractBlock(src, marker, braceOpenOffset) {
  const start = src.indexOf(marker)
  assert.ok(start > 0, '找不到 ' + JSON.stringify(marker))
  // marker 自带结尾的 '{'（比如 CSS 选择器 '.foo {'）时那个 '{' 就是要找的开括号，
  // 不能再往后找下一个——marker.length 已经越过它，若还去 "找下一个 {" 会跳过
  // 这条规则本身、平衡到隔壁一条规则的花括号上（曾经真的因为这个把 .awd-dialog-body
  // 和紧挨着的 .scrollable-body 两条规则串成了一个块）。
  let i
  if (marker.trimEnd().endsWith('{')) {
    i = start + marker.length - 1
  } else {
    i = start + (braceOpenOffset != null ? braceOpenOffset : marker.length)
    while (src[i] !== '{') i++
  }
  let depth = 0
  for (; i < src.length; i++) {
    if (src[i] === '{') depth++
    else if (src[i] === '}') { depth--; if (depth === 0) { i++; break } }
  }
  return src.slice(start, i)
}

// ======================================================================
// 1. FileTree.vue：「管理标签」弹窗（.awd-dialog / .awd-dialog-body）
//    仍然保留高度上限 + 可滚——这是「表单顶到 85vh」时的兜底，不是常规状态。
// ======================================================================

test('.awd-dialog 有视口相对的高度上限（表单顶到上限时才需要滚，不是常态）', () => {
  const src = read('components/FileTree.vue')
  const block = extractBlock(stripComments(src), '.awd-dialog {')
  assert.match(block, /max-height:\s*\d+vh/, '.awd-dialog 缺 max-height，弹窗高度仍然只由内容撑出来')
})

test('.awd-dialog-body 可以真的滚动（兜底：表单顶到 .awd-dialog 的高度上限时用它）', () => {
  const src = read('components/FileTree.vue')
  const block = extractBlock(stripComments(src), '.awd-dialog-body {')
  assert.match(block, /overflow-y:\s*auto/, '.awd-dialog-body 缺 overflow-y:auto，弹窗撑爆了也没有任何容器能滚')
})

test('FileTree.vue 的「管理标签」弹窗给 <TagSelector> 传了 inline', () => {
  const src = read('components/FileTree.vue')
  const start = src.indexOf('<TagSelector')
  const end = src.indexOf('/>', start)
  assert.ok(start > 0 && end > start, '找不到 <TagSelector ... /> 这一段')
  const block = src.slice(start, end)
  assert.match(block, /\binline\b/, '「管理标签」弹窗里的 TagSelector 必须是 inline 模式，下拉才会参与正常流撑高弹窗')
})

// ======================================================================
// 2. TagSelector.vue：inline prop 存在，且默认 false（其它调用点——比如普通
//    搜索场景——必须保持浮层行为不变，不能因为这张卡的修复动到它们）。
// ======================================================================

test('TagSelector 有 inline prop，默认 false（不影响既有的浮层调用点）', () => {
  const src = stripComments(read('components/TagSelector.vue'))
  const propsBlock = extractBlock(src, 'props: {')
  const inlineMatch = propsBlock.match(/inline\s*:\s*\{([\s\S]*?)\}/)
  assert.ok(inlineMatch, 'props 里找不到 inline 声明')
  assert.match(inlineMatch[1], /type:\s*Boolean/)
  assert.match(inlineMatch[1], /default:\s*false/, 'inline 必须默认 false，否则普通搜索场景的下拉会被意外改成正常流')
})

test('inline 时 .dropdown-menu 退回正常流（position:static），不再是浮层', () => {
  const src = read('components/TagSelector.vue')
  // 模板上把 is-inline 挂到 inline prop 上
  assert.match(src, /class="dropdown-menu"\s+:class="\{\s*'is-inline':\s*inline\s*\}"/)
  const cssBlock = extractBlock(stripComments(src), '.dropdown-menu.is-inline {')
  assert.match(cssBlock, /position:\s*static/, 'is-inline 必须把 position 改回 static，否则还是浮层，弹窗还是不会跟着长高')
  // 浮层专属的那套视觉（阴影、独立圆角卡片、限高裁切）在 inline 下都不该再生效——
  // 表单能撑多高完全交给宿主弹窗的 max-height/overflow-y 去兜底。
  assert.match(cssBlock, /box-shadow:\s*none/)
  assert.match(cssBlock, /max-height:\s*none/)
})

test('TagSelector.startCreate 展开创建表单后，把操作按钮那一行滚动到可见（兜底：弹窗顶到高度上限时用）', () => {
  const src = stripComments(read('components/TagSelector.vue'))
  const body = extractBlock(src, 'startCreate()')
  assert.match(body, /isCreatingTag\s*=\s*true/)
  assert.match(body, /querySelector\(\s*['"]\.picker-actions-compact['"]\s*\)/,
    'scrollIntoView 应该对准操作按钮行，而不是打在更高的 .color-picker-mode 容器上')
  assert.match(body, /scrollIntoView\(/)
})

test('TagSelector.vue 的 dropdown-menu / color-picker-mode 结构没有被意外改动（回归定位锚点）', () => {
  const src = read('components/TagSelector.vue')
  assert.match(src, /class="color-picker-mode"/)
  assert.match(src, /class="picker-actions-compact"/)
})
