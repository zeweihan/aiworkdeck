// 编辑器工具栏的两条布局契约（dev-board#502 / #503）。都是「不跑引擎也能红」的
// 结构断言——真机那一层的证据在下面各自的说明里。
//
// #503（点查找整个编辑区黑一下）的根因不在样式好不好看，而在**改不改 webview 的
// 尺寸**：查找栏留在文档流里时，开关它就把下面的 <webview> 挤矮/放高，Electron 的
// 客体合成面在新尺寸的那一帧到达之前被整块画成黑色（真 Electron + 真引擎探针实测：
// 10 次开关里有 8~11 帧出现整幅黑，最黑那帧是「工具栏以下 733 CSS px 全黑」；给
// 画布容器或 webview 元素铺底色都拦不住，因为客体面盖在它上面；把查找栏改成不占
// 布局高度的浮层之后，同样 10 次开关 0 帧）。所以这里守的是「查找栏不进文档流」。
//
// #502（滚动条过粗、与右侧控件不齐）：38px 的一行里，原生横向滚动条会把主命令区
// 撑到 41px，align-items:center 于是把左半边顶得比右侧常驻区高半格。
// #543 是它的续集：当时的修法是把滚动条整条藏掉（show-scrollbar=false + 一串
// display:none），于是「这里还能往右滚」在界面上没有任何痕迹，而唯一的替代
// ——滚轮横滚——又因为 uni 重建事件丢 delta 而是死的（见 wheel-delta.test.mjs），
// 主命令区右半截彻底够不着。现在守的是「6px 悬浮细滑轨（.awd-hairline-scroll）」
// +「横滚经 wheelDeltaOf 仍然可用」+「那一行还是 38px、两侧仍然对齐」。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const SRC = readFileSync(new URL('../../src/components/EditorToolbar.vue', import.meta.url), 'utf8')
// 注意 lastIndexOf：插入菜单与表格组里有内嵌的 <template v-if>，用 indexOf 会把
// 模板从中间截断（第一版就是这么写的，断言全成了空跑）。
const TEMPLATE = SRC.slice(SRC.indexOf('<template>'), SRC.lastIndexOf('</template>'))
const STYLE = SRC.slice(SRC.lastIndexOf('<style'), SRC.lastIndexOf('</style>'))

/** 取某个 class 选择器名下所有规则体（scoped 里同名可能写多条）。 */
function rulesFor(cls) {
  const out = []
  const re = new RegExp('\\.' + cls + '(?![\\w-])[^{}]*\\{([^}]*)\\}', 'g')
  let m
  while ((m = re.exec(STYLE))) out.push(m[1])
  return out
}
const declares = (cls, prop, value) =>
  rulesFor(cls).some((body) => new RegExp(prop + '\\s*:\\s*' + value).test(body))

// ---------- dev-board#503：查找栏不许占布局高度 ----------

test('查找栏挂在一个绝对定位的浮层上，不进 .etb-wrap 的竖向 flex 流', () => {
  // 找出所有「由 findOpen 控制显隐」的元素，逐个要求它自己就是浮层（或在浮层里）。
  const gated = [...TEMPLATE.matchAll(/<([a-zA-Z-]+)((?:[^>]*?\bv-if="[^"]*\bfindOpen\b[^"]*")[^>]*)>/g)]
  assert.ok(gated.length > 0, '模板里已经找不到由 findOpen 控制的元素了，这条断言失去了意义')
  for (const [, tag, attrs] of gated) {
    const cls = (/\bclass="([^"]*)"/.exec(attrs) || [, ''])[1].split(/\s+/).filter(Boolean)
    const floating = cls.some((c) => declares(c, 'position', '(absolute|fixed)'))
    assert.ok(
      floating,
      `<${tag} class="${cls.join(' ')}"> 由 findOpen 控制显隐，却不是绝对定位：` +
      '它一出现就会改变工具栏高度，下面的 webview 跟着改尺寸，编辑区就会黑一帧（dev-board#503）',
    )
  }
})

test('.etb-wrap 是浮层的定位参照，且不自造层叠上下文', () => {
  assert.ok(declares('etb-wrap', 'position', 'relative'),
    '.etb-wrap 不是 position:relative 的话，top:100% 的查找栏会跑到别人身上去')
  // 有 z-index 就会造出层叠上下文，把 popStyle 那些 fixed 弹层（z 900）关在里面
  assert.ok(!rulesFor('etb-wrap').some((b) => /z-index\s*:/.test(b)),
    '.etb-wrap 不要设 z-index：会造出层叠上下文，框住工具栏下拉用的 fixed 弹层')
})

test('浮层压得住画布上已有的几层（状态胶囊 20 / 证据投放层 25 / 改字提示条 30）', () => {
  const z = rulesFor('etb-find-layer').map((b) => /z-index\s*:\s*(\d+)/.exec(b)).find(Boolean)
  assert.ok(z, '.etb-find-layer 要显式给 z-index，否则会被画布上的浮层盖住')
  assert.ok(Number(z[1]) > 30, 'z-index 要高于改字提示条的 30，实际 ' + z[1])
})

test('查找/替换的功能没被这次布局改动动过', () => {
  for (const s of ['onFindInput', 'findNext', 'findPrev', 'replaceCurrent', 'replaceAll', 'toggleCase', 'toggleFind']) {
    assert.ok(TEMPLATE.includes(s + '"') || TEMPLATE.includes(s + '('), '查找栏丢了动作 ' + s)
  }
})

// ---------- dev-board#502：滚动条藏掉，横滚改走滚轮 ----------

test('主命令区用 6px 悬浮细滑轨，不再整条藏掉滚动条', () => {
  const tag = /<scroll-view[^>]*class="[^"]*etb-scroll[^"]*"[^>]*>/.exec(TEMPLATE)
  assert.ok(tag, '找不到 .etb-scroll 那个 scroll-view')
  assert.match(tag[0], /class="[^"]*\bawd-hairline-scroll\b/,
    '主命令区要挂 .awd-hairline-scroll（全局细滑轨，样式在 App.vue）')
  assert.ok(!/:show-scrollbar="false"/.test(tag[0]),
    'show-scrollbar=false 会让 uni-h5 给真正滚动的内层元素挂上隐藏类，把细滑轨也一起灭掉')
  assert.ok(!/\.etb-scroll[^{]*::-webkit-scrollbar[^{]*\{[^}]*display:\s*none/.test(STYLE),
    '组件里还留着把滚动条 display:none 的兜底规则，细滑轨看不见（dev-board#543）')
})

test('细滑轨占的 6px 不许把 38px 那一行的两侧顶歪', () => {
  // 滑轨是从内容盒里扣的：不定高的话，有滑轨时主命令区长到 26+6=32px、
  // 被 align-items:center 一居中就比右侧常驻区高 3px；命令放得下、没有滑轨时
  // 又反向偏 3px。定高 32 + 负外边距 6，让外边距盒恒为 26px，两种情况都对齐。
  assert.ok(declares('etb-scroll', 'height', 'calc\\(100% - 6px\\)'),
    '.etb-scroll 要定高 calc(100% - 6px)，才能不管有没有滑轨都跟右侧常驻区对齐')
  assert.ok(declares('etb-scroll', 'margin-bottom', '-6px'),
    '.etb-scroll 少了 margin-bottom:-6px，滑轨那 6px 会把左半边按钮顶高（同 dev-board#502 的错位）')
  assert.ok(!rulesFor('etb-scroll').some((b) => /z-index\s*:/.test(b)),
    '.etb-scroll 不要设 z-index：会造出层叠上下文，框住工具栏下拉用的 fixed 弹层')
})

test('横滚仍然能用（滚轮映射成横向，且位移经 wheelDeltaOf 取）', () => {
  const tag = /<scroll-view[^>]*class="[^"]*etb-scroll[^"]*"[^>]*>/.exec(TEMPLATE)
  assert.match(tag[0], /@wheel\.prevent="onToolbarWheel"/,
    '滑轨只有 6px，不接滚轮等于逼着人去拖那条线')
  assert.match(SRC, /onToolbarWheel\s*\(evt\)\s*\{/, '缺少 onToolbarWheel 实现')
  assert.match(SRC, /wheelDeltaOf\(evt\)/,
    '位移要经 wheelDeltaOf 取：uni 重建过的事件上没有 deltaX/deltaY，直接读恒 NaN（dev-board#543）')
  assert.match(SRC, /scroller\.scrollLeft\s*\+=\s*delta/, 'onToolbarWheel 没有真的横向滚动')
})

test('主命令区与右侧常驻区在同一行里居中对齐', () => {
  assert.ok(declares('etb', 'align-items', 'center'), '.etb 丢了 align-items:center')
  assert.ok(declares('etb', 'height', '38px'), '.etb 的行高被改了；两侧对齐是按这一行的高度算的')
  // .etb-scroll 的定高只许是「行高减去滑轨」——写死像素或写满 100%，
  // 都会在有/没有滑轨的两种情况里各偏 3px（见上一条）。
  const h = rulesFor('etb-scroll').map((b) => /(?:^|;)\s*height\s*:\s*([^;]+)/.exec(b)).find(Boolean)
  assert.ok(h && /calc\(100% - 6px\)/.test(h[1]),
    '.etb-scroll 的高度要跟着 .etb 那一行走（calc(100% - 6px)），实际 ' + (h && h[1]))
})
