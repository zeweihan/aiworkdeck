// P3「底稿定位增强」的真渲染走查：把 FilePreview.vue 的 <template> 用 vue 自带的
// compiler-sfc 编译出来、真的渲染成 HTML，断言三种定位下该出现的东西确实出现了。
//
// 为什么要有这一层：previewLocate.test.mjs 只跑方法体，模板里写错 class 名、v-if 挂错
// 分支、i18n 键打错，那份测试一个都发现不了（「验证要走完 UI 链路」）。
// 这里不引任何新依赖——compiler-sfc 与 server-renderer 都是 vue 包自带的子路径导出。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parse, compileTemplate } from 'vue/compiler-sfc'
import * as VueRuntime from 'vue'
import * as SsrRuntime from 'vue/server-renderer'
import { renderToString } from 'vue/server-renderer'

const SFC = readFileSync(new URL('../../src/components/FilePreview.vue', import.meta.url), 'utf8')

// 编译产物是个 ES 模块（两行 import + 一个 export function），这里把它转成
// 可以直接 new Function 出来的形状：import 换成从参数里解构，export 去掉。
function buildSsrRender() {
  const { descriptor, errors } = parse(SFC, { filename: 'FilePreview.vue' })
  assert.equal(errors.length, 0, 'FilePreview.vue 解析失败')
  const compiled = compileTemplate({
    source: descriptor.template.content,
    filename: 'FilePreview.vue',
    id: 'file-preview',
    ssr: true,
    ssrCssVars: [],
  })
  assert.equal(compiled.errors.length, 0, '模板编译报错：' + compiled.errors.join('; '))
  const body = compiled.code
    .replace(/^import \{([\s\S]*?)\} from "vue"$/m, 'const {$1} = __vue')
    .replace(/^import \{([\s\S]*?)\} from "vue\/server-renderer"$/m, 'const {$1} = __ssr')
    .replace(/\bas\b/g, ':')                       // import 的 `a as _a` → 解构的 `a: _a`
    .replace(/^export function ssrRender/m, 'function ssrRender')
  // eslint-disable-next-line no-new-func
  return new Function('__vue', '__ssr', body + '\nreturn ssrRender')(VueRuntime, SsrRuntime)
}

const ssrRender = buildSsrRender()

// $t 直接回键名：断言键名即断言「模板引用的 i18n 键」，打错字就对不上。
// 返回的 HTML 把 &quot; 还原成 "，断言里不必迁就转义。
async function render(state) {
  const app = VueRuntime.createSSRApp({
    ssrRender,
    data: () => ({
      file: { id: 7, name: 'e.pdf', fileType: 'pdf' },
      blobUrl: 'blob:fake',
      ...state,
    }),
    methods: {
      $t: (k, p) => (p ? k + JSON.stringify(p) : k),
      formatFileSize: (n) => String(n),
      formatClock: (s) => 's' + s,
      pdfMapRectStyle: (r) => ({ left: r.x * 100 + '%', top: r.y * 100 + '%' }),
    },
  })
  // uni 的 view/text/web-view 在这儿都是未知元素，缺席的属性也只是 undefined，
  // 警告一律吞掉——这份测试看的是渲染结果，不是组件树完整性
  app.config.warnHandler = () => {}
  const realWarn = console.warn
  console.warn = () => {}
  try {
    const html = await renderToString(app)
    return html.replace(/&quot;/g, '"')
  } finally {
    console.warn = realWarn
  }
}

// 模板里的中文注释会原样进 HTML，而且里头就写着 `<video src>` 这样的字样——
// 断言 autoplay 之类的属性必须先把注释剥掉，否则会匹配到注释上（假绿）
function tagOf(html, name) {
  const m = html.replace(/<!--[\s\S]*?-->/g, '').match(new RegExp('<' + name + '[^>]*>'))
  assert.ok(m, '渲染结果里找不到 <' + name + '>')
  return m[0]
}

test('真渲染 pdf：有引文无坐标 → 卡上是「未能在本页定位到引文」，且 iframe 指向 #page=3', async () => {
  const html = await render({
    isPdf: true,
    pdfSrc: 'blob:fake#page=3',
    pdfLocateVisible: true,
    pdfLocate: { page: 3, quote: '统一社会信用代码 91310000MA1FL', rects: [] },
  })
  assert.match(html, /class="evidence-locate-card"/)
  assert.match(html, /files\.locate\.pdfPage\{"page":3\}/)
  assert.match(html, /class="elc-miss">files\.locate\.quoteNotFound</)
  assert.match(html, /class="elc-quote">统一社会信用代码 91310000MA1FL</)
  assert.match(html, /files\.locate\.copyQuote/)
  assert.ok(!html.includes('elc-map'), '没有坐标就不该画页位图——那才是假高亮')
  assert.match(html, /<iframe src="blob:fake#page=3"/)
})

test('真渲染 pdf：有坐标 → 画页位图，不再显示「未能定位」', async () => {
  const html = await render({
    isPdf: true,
    pdfSrc: 'blob:fake#page=3',
    pdfLocateVisible: true,
    pdfLocate: { page: 3, quote: '注册资本', rects: [{ x: 0.12, y: 0.3, w: 0.6, h: 0.04 }] },
  })
  assert.match(html, /class="elc-map"/)
  assert.match(html, /class="elc-map-rect" style="left:12%;top:30%;"/)
  assert.ok(!html.includes('quoteNotFound'), '画得出框就不该再说定位不到')
})

test('真渲染图片：定位框与工具栏（含旋转、定位框开关）都在', async () => {
  const html = await render({
    file: { id: 8, name: 'a.jpg', fileType: 'jpg' },
    isImage: true,
    imageReady: true,
    imageZoomPercentText: '100%',
    imageTransformStyle: { transform: 'translate(0px, 0px) rotate(90deg) scale(1)' },
    evidenceRectStyle: { left: '120px', top: '100px', width: '30px', height: '100px' },
    evidenceRectUndimmed: false,
    evidenceRectVisible: true,
    hasImageLocatorRect: true,
  })
  assert.match(html, /class="evidence-rect" style="left:120px;top:100px;width:30px;height:100px;"/)
  assert.match(html, /rotate\(90deg\)/)
  assert.match(html, /files\.rotate/)
  const btn = html.match(/<button class="[^"]*"[^>]*>files\.locate\.imageRect/)
  assert.ok(btn, '工具栏里没有「定位框」开关')
  assert.match(btn[0], /is-on/, '框亮着时开关要显示按下态')
})

test('真渲染视频：带定位时不 autoplay，且时间标记就在画面上', async () => {
  const withMark = await render({
    file: { id: 9, name: 'v.mp4', fileType: 'mp4' },
    isVideo: true,
    mediaLocatorSec: 125,
    mediaMarkVisible: true,
  })
  assert.ok(!tagOf(withMark, 'video').includes('autoplay'), '定位打开的目的是看那一帧，不能自动播下去')
  assert.match(withMark, /class="evidence-media-mark"/)
  assert.match(withMark, /files\.locate\.mediaMark\{"time":"s125"\}/)
  assert.match(withMark, /files\.locate\.playFromMark/)

  const noMark = await render({ file: { id: 9, name: 'v.mp4', fileType: 'mp4' }, isVideo: true, mediaLocatorSec: null })
  assert.match(tagOf(noMark, 'video'), /autoplay/, '没有定位时保持原来的自动播放')
  assert.ok(!noMark.includes('class="evidence-media-mark"'))
})

test('真渲染音频：轨道上有定位刻度，卡片里有时间标记', async () => {
  const html = await render({
    file: { id: 10, name: 'a.mp3', fileType: 'mp3' },
    isAudio: true,
    ICONS: { audioLines: [], play: [], pause: [], volume: [], volumeMute: [] },
    audioProgressPct: 0,
    audioCurrent: 0,
    audioDuration: 600,
    audioVolume: 1,
    audioRate: 1,
    mediaLocatorSec: 125,
    mediaMarkVisible: true,
    mediaMarkPct: 20.83,
  })
  assert.match(html, /class="audio-track-mark" style="left:20.83%;"/)
  assert.match(html, /class="evidence-media-mark is-inline"/)
  assert.match(html, /files\.locate\.mediaMark\{"time":"s125"\}/)
})
