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
// 撑到 41px，align-items:center 于是把左半边顶得比右侧常驻区高半格。守「滚动条被
// 藏掉」+「横滚仍然可用」。
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

test('主命令区关掉原生滚动条', () => {
  const tag = /<scroll-view[^>]*class="etb-scroll"[^>]*>/.exec(TEMPLATE)
  assert.ok(tag, '找不到 .etb-scroll 那个 scroll-view')
  assert.match(tag[0], /:show-scrollbar="false"/,
    'show-scrollbar=false 才会让 uni-h5 给真正 overflow 的内层元素挂上隐藏滚动条的类')
  // scoped 属性只落在 <uni-scroll-view> 根元素上，内层那个 div 不带 scope id，
  // 兜底规则必须走 :deep 才够得着。
  assert.match(STYLE, /\.etb-scroll\s*:deep\(\*::-webkit-scrollbar\)\s*\{[^}]*display:\s*none/,
    '缺少 :deep 兜底：只写 .etb-scroll::-webkit-scrollbar 打不到真正滚动的那个元素')
})

test('滚动条藏了之后横滚仍然能用（滚轮映射成横向）', () => {
  const tag = /<scroll-view[^>]*class="etb-scroll"[^>]*>/.exec(TEMPLATE)
  assert.match(tag[0], /@wheel\.prevent="onToolbarWheel"/,
    '藏了滚动条又不接滚轮，窄窗口下右半截命令就够不着了')
  assert.match(SRC, /onToolbarWheel\s*\(evt\)\s*\{/, '缺少 onToolbarWheel 实现')
  assert.match(SRC, /scroller\.scrollLeft\s*\+=\s*delta/, 'onToolbarWheel 没有真的横向滚动')
})

test('主命令区与右侧常驻区在同一行里居中对齐', () => {
  assert.ok(declares('etb', 'align-items', 'center'), '.etb 丢了 align-items:center')
  assert.ok(declares('etb', 'height', '38px'), '.etb 的行高被改了；两侧对齐是按这一行的高度算的')
  // 不给 .etb-scroll 定高：内容多高它就多高，交给 align-items:center 居中。
  // 一旦原生滚动条回来，这个高度会变成 26+15px，左半边就被顶高半格。
  assert.ok(!rulesFor('etb-scroll').some((b) => /(^|;)\s*height\s*:/.test(b)),
    '.etb-scroll 不要定高，让它跟着内容走')
})
