// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 标签栏「开多了就滚不动、也看不出还有更多」（dev-board#543）的三条结构契约。
// 病灶三件套：① .tab-item 没有 flex-shrink:0，标签一多是整批被压扁而不是溢出，
// 滚动条压根不会出现；② 滚动条被 show-scrollbar=false + display:none 整条藏掉，
// 溢出了也没有任何痕迹；③ 唯一的替代（滚轮横滚）因为 uni 重建事件丢 delta 而是死的
// （那半边守在 wheel-delta.test.mjs）。外加「活动标签要能自己滚进视野」。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const VUE = readFileSync(
  new URL('../../src/pages/project-overview/project-overview.vue', import.meta.url), 'utf8')
const SCSS = readFileSync(
  new URL('../../src/pages/project-overview/project-overview.scss', import.meta.url), 'utf8')
const TABS = readFileSync(
  new URL('../../src/pages/project-overview/fileOpenTabs.js', import.meta.url), 'utf8')
const DRAG = readFileSync(
  new URL('../../src/pages/project-overview/tabDragSplit.js', import.meta.url), 'utf8')
const APP = readFileSync(new URL('../../src/App.vue', import.meta.url), 'utf8')

/** 取某个 class 选择器名下的规则体（scss 嵌套：只取到第一层闭合前的声明） */
function bodyOf(src, cls) {
  const i = src.indexOf('.' + cls + ' {')
  assert.ok(i >= 0, '找不到 .' + cls + ' 的规则')
  return src.slice(i, src.indexOf('\n}', i))
}

test('.tab-item 不许被压扁：标签多了要溢出成横滚', () => {
  const body = bodyOf(SCSS, 'tab-item')
  assert.match(body, /flex-shrink:\s*0/,
    '没有 flex-shrink:0，.tabs-list 这一行会把每个标签越挤越窄，滚动条永远不出现')
  const max = /max-width:\s*(\d+)px/.exec(body)
  assert.ok(max && Number(max[1]) <= 160, '.tab-item 的 max-width 要收到 160px 以内，实际 ' + (max && max[1]))
  assert.match(body, /text-overflow:\s*ellipsis/, '.tab-name 仍要省略号收尾')
})

test('两个窗格的标签栏都换成 4px 悬浮细滑轨，不再整条藏掉', () => {
  const tags = [...VUE.matchAll(/<scroll-view[^>]*class="[^"]*tabs-scroll[^"]*"[\s\S]{0,200}?>/g)].map(m => m[0])
  assert.equal(tags.length, 2, '左右窗格各一条标签栏，实际找到 ' + tags.length)
  for (const t of tags) {
    assert.match(t, /class="[^"]*\bawd-hairline-scroll\b/, '标签栏要挂 .awd-hairline-scroll')
    assert.ok(!/:show-scrollbar="false"/.test(t),
      'show-scrollbar=false 会让 uni-h5 给真正滚动的内层元素挂上隐藏类，细滑轨一起没了')
    assert.ok(!/@wheel/.test(t),
      '模板上的 @wheel 是死的（uni 把 currentTarget 换成了普通对象），横滚要原生挂')
  }
  assert.ok(!/display:\s*none/.test(bodyOf(SCSS, 'tabs-scroll')),
    '.tabs-scroll 里还留着藏滚动条的 display:none')
  // 滑轨占的 4px 是从内容盒里扣的，height:100% 会让 36px 的 .tab-item 被
  // uni 给内层元素挂的 overflow-y:hidden 裁掉底下 4px。
  const scroll = bodyOf(SCSS, 'tabs-scroll')
  assert.match(scroll, /height:\s*calc\(100% \+ 4px\)/,
    '.tabs-scroll 要多留 4px 给滑轨，否则标签底部会被裁掉')
  // 那 4px 溢出到编辑区头上，而 .editors-container / .editor-pane 都是
  // position:relative + 有背景、排在更后面，z-index 只到 1 时会把滑轨整条盖掉
  // （#543 走查实测：注入 height:100% 立刻就能看见 thumb）。
  assert.match(scroll, /position:\s*relative/)
  const z = /z-index:\s*(\d+)/.exec(scroll)
  assert.ok(z && Number(z[1]) >= 2,
    '.tabs-scroll 的 z-index 要 >= 2，否则溢出的 4px 滑轨会被编辑区背景盖掉，实际 ' + (z && z[1]))
})

test('滚轮横滚改成原生 addEventListener 挂在真实元素上，且会摘干净', () => {
  assert.match(DRAG, /rebindTabsWheel\s*\(\)\s*\{/, 'tabDragSplit.js 缺少 rebindTabsWheel')
  assert.match(DRAG, /bindHorizontalWheelAll\(this\.\$el, '\.tabs-scroll'/,
    'rebindTabsWheel 要按 .tabs-scroll 在本实例的 DOM 子树里找元素')
  assert.match(DRAG, /unbindTabsWheel\s*\(\)\s*\{/, 'tabDragSplit.js 缺少 unbindTabsWheel')
  assert.ok(!/onTabsWheel/.test(DRAG + VUE), '模板事件版的 onTabsWheel 还没删干净')
  assert.match(VUE, /mounted\(\)[\s\S]{0,1200}?rebindTabsWheel\(\)/, 'mounted 里没有挂')
  assert.match(VUE, /beforeUnmount\(\)[\s\S]{0,400}?unbindTabsWheel\(\)/, 'beforeUnmount 里没有摘')
  // 分屏开关会把右侧那条标签栏整个建/拆，回来要重挂（bind 是幂等的）
  assert.match(VUE, /splitMode\(\)[^\n]*rebindTabsWheel\(\)/,
    'splitMode 变化后没有重挂：右侧标签栏是新建出来的元素，老监听在旧元素上')
})

test('.awd-hairline-scroll 定义在全局样式里，静止透明、悬停才显形', () => {
  assert.match(APP, /\.awd-hairline-scroll[^{]*::-webkit-scrollbar[^{]*\{[^}]*height:\s*4px/,
    'App.vue 里没有 .awd-hairline-scroll 的 4px 滑轨定义')
  assert.match(APP, /\.awd-hairline-scroll[^{]*::-webkit-scrollbar-thumb[^{]*\{[^}]*background:\s*transparent/,
    '静止时 thumb 要是透明的')
  assert.match(APP, /\.awd-hairline-scroll[^{]*::-webkit-scrollbar-thumb[^{]*\{[^}]*border-radius:\s*999px/,
    'thumb 要圆角（4px 的方块线条在浅色外壳上很硌眼）')
  assert.match(APP, /\.awd-hairline-scroll:hover[^{]*::-webkit-scrollbar-thumb[^{]*\{[^}]*var\(--awd-border-strong\)/,
    '悬停时 thumb 要用 --awd-border-strong 显形（跟随主题，不写死颜色）')
})

test('活动标签自动滚入视野：每个标签有 id，两个窗格各一个 scroll-into-view', () => {
  for (const pane of ['left', 'right']) {
    assert.ok(VUE.includes(`:id="tabDomId('${pane}', file.id)"`), pane + ' 窗格的标签没有 scroll-into-view 用的 id')
  }
  assert.match(VUE, /:scroll-into-view="tabsScrollIntoViewLeft"/)
  assert.match(VUE, /:scroll-into-view="tabsScrollIntoViewRight"/)
  assert.match(VUE, /<scroll-view[^>]*class="[^"]*tabs-scroll[\s\S]{0,200}?scroll-with-animation/)
  // 挂在 watcher 上而不是 activateTab 里：openFile / moveTabTo / closeFile 的相邻
  // 接管都是直接写 activeFileId*，不走 activateTab。
  assert.match(VUE, /activeFileIdLeft\(\)[^\n]*ensureActiveTabVisible\('left'\)/)
  assert.match(VUE, /activeFileIdRight\(\)[^\n]*ensureActiveTabVisible\('right'\)/)
})

test('tabDomId 产出的 id 过得了 uni 那道正则（不合就只 console.error，什么都不滚）', () => {
  // fileOpenTabs.js 带 @/ 别名 import，node 直接 import 不动；tabDomId 又刻意不导出
  // （模块级 export 会让别的测试那套 new Function 工厂语法出错），所以源码级取函数体。
  const fn = new Function(/function tabDomId[\s\S]*?\n\}/.exec(TABS)[0] + '; return tabDomId')()
  const ok = /^[_a-zA-Z][-_a-zA-Z0-9:]*$/
  for (const id of [123, 'vcmp-a1b2c3d4-1700000000', 'diff-9-10-1700000000', 'admin-settings',
                    'a/b c.docx', '中文文件名', '', null]) {
    const domId = fn('left', id)
    assert.match(domId, ok, '这个 id 会被 uni 的 scroll-into-view 直接拒掉：' + domId)
  }
  assert.notEqual(fn('left', 1), fn('right', 1), '左右窗格同一个文件的 id 不能撞')
})
