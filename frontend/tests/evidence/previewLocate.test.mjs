// P3「底稿定位增强」的三种定位各一个可复现实例：把 FilePreview.vue 里的那几段源码
// 抠出来真跑一遍（同 openEvidenceTarget.test.mjs 的做法，不依赖 Vue 运行时）。
//
//   pdf   → 跳到第 3 页 + 引文卡；locator 没给 rects 就如实说「未能在本页定位到引文」
//   image → 画框常驻，3s 后只撤掉压暗遮罩
//   media → seek 到 125s 并**暂停在那一帧**，时间标记亮起
//
// 覆盖的是「组件方法的控制流」这一层：坐标换算本身在 locatorGeometry.test.mjs。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parsePdfLocator, parseImageRect, parseMediaStartSec } from '../../src/utils/evidenceLocator.js'

const SRC = readFileSync(new URL('../../src/components/FilePreview.vue', import.meta.url), 'utf8')

// 组件里方法体一律 4 空格缩进、以 `\n    },` 收尾（内层块是 6 空格，不会提前截断）
function pickMethod(name) {
  const m = SRC.match(new RegExp('\\n    ' + name + '\\(([^)]*)\\) \\{\\n([\\s\\S]*?)\\n    \\},'))
  assert.ok(m, 'FilePreview.vue 里找不到方法 ' + name)
  return new Function(m[1], m[2])
}

function makeVm(locator, opts = {}) {
  const emitted = []
  const timers = []
  const vm = {
    file: { id: 7, fileType: opts.fileType || 'pdf' },
    blobUrl: 'blob:fake',
    appliedLocator: null,
    evidenceRectVisible: false,
    evidenceRectUndimmed: false,
    pdfLocateVisible: false,
    mediaMarkVisible: false,
    imageNaturalWidth: opts.natW || 0,
    imageNaturalHeight: opts.natH || 0,
    imageScale: 1,
    imageTx: 0,
    imageTy: 0,
    imageRotation: 0,
    audioDuration: opts.audioDuration || 0,
    isAudio: opts.fileType === 'mp3',
    _audio: opts.audioEl || null,
    _videoEl: opts.videoEl || null,
    _rectFadeTimer: null,
    $nextTick: (fn) => fn(),
    $emit: (...a) => emitted.push(a),
    $refs: {},
    emitted,
    timers,
  }
  // 组件里这几个是 computed，测试里按同一份纯函数现算
  Object.defineProperty(vm, 'pdfLocate', { get: () => parsePdfLocator(vm.appliedLocator) })
  Object.defineProperty(vm, 'imageLocatorRect', { get: () => parseImageRect(vm.appliedLocator) })
  Object.defineProperty(vm, 'mediaLocatorSec', { get: () => parseMediaStartSec(vm.appliedLocator) })
  for (const name of ['applyLocator', 'seekToLocator', 'clearEvidenceRectTimers', 'toggleEvidenceRect',
    'hideEvidenceRect', 'closePdfLocate', 'pdfMapRectStyle', 'getVideoEl', 'playFromMark', 'pdfSrc',
    'attachVideoLocator', 'teardownVideoLocator']) {
    vm[name] = pickMethod(name).bind(vm)
  }
  // setTimeout 收进队列，测试里手动跑，免得等真的 3 秒
  vm.__setTimeout = globalThis.setTimeout
  globalThis.setTimeout = (fn) => { timers.push(fn); return timers.length }
  globalThis.clearTimeout = () => {}
  try {
    if (locator !== undefined) vm.applyLocator(locator)
  } finally {
    globalThis.setTimeout = vm.__setTimeout
  }
  return vm
}

function fakeMedia() {
  return {
    currentTime: 0,
    paused: false,
    playCalls: 0,
    pause() { this.paused = true },
    play() { this.playCalls += 1; this.paused = false; return Promise.resolve() },
  }
}

// ── 实例一：pdf ─────────────────────────────────────────────────────────────

test('pdf 定位实例：{type:pdf, page:3, quote} → 跳第 3 页、引文卡亮起、无 rects 时如实报未定位', () => {
  const vm = makeVm({ type: 'pdf', page: 3, quote: '统一社会信用代码 91310000MA1FL…' })
  assert.equal(vm.pdfSrc(), 'blob:fake#page=3', '#page= 必须拼上，跳页靠它')
  assert.equal(vm.pdfLocateVisible, true)
  assert.equal(vm.pdfLocate.page, 3)
  assert.equal(vm.pdfLocate.quote, '统一社会信用代码 91310000MA1FL…')
  assert.deepEqual(vm.pdfLocate.rects, [], '没有坐标就是没有——模板据此显示「未能在本页定位到引文」')
  assert.equal(vm.evidenceRectVisible, false, 'pdf 不画图片框')
  assert.deepEqual(vm.emitted, [['locator-consumed', 7]])
  vm.closePdfLocate()
  assert.equal(vm.pdfLocateVisible, false)
})

test('pdf 定位实例：带 rects 时按归一化坐标落成百分比，不掺猜测', () => {
  const vm = makeVm({ type: 'pdf', page: 3, quote: '注册资本', rects: [{ page: 3, x: 0.12, y: 0.3, w: 0.6, h: 0.04 }] })
  assert.equal(vm.pdfLocate.rects.length, 1)
  assert.deepEqual(vm.pdfMapRectStyle(vm.pdfLocate.rects[0]), {
    left: '12%', top: '30%', width: '60%', height: '4%',
  })
})

test('pdf 定位实例：locator 只有类型（OCR 什么都没给）→ 卡不出现，退化成只打开文件', () => {
  const vm = makeVm({ type: 'pdf' })
  assert.equal(vm.pdfLocateVisible, false)
  assert.equal(vm.pdfSrc(), 'blob:fake', '没有页码就不拼 #page=')
})

// ── 实例二：图片 ────────────────────────────────────────────────────────────

test('图片定位实例：{type:image, rect} → 画框常驻，3s 后只撤掉压暗遮罩', () => {
  const vm = makeVm(
    { type: 'image', rect: { x: 0.25, y: 0.5, w: 0.25, h: 0.1 } },
    { fileType: 'jpg', natW: 400, natH: 300 },
  )
  assert.equal(vm.evidenceRectVisible, true)
  assert.equal(vm.evidenceRectUndimmed, false, '刚定位时压暗周边，把注意力引过去')
  assert.equal(vm.timers.length, 1, '只挂一个撤遮罩的定时器')
  vm.timers[0]()
  assert.equal(vm.evidenceRectUndimmed, true)
  assert.equal(vm.evidenceRectVisible, true, '框不能跟着遮罩一起消失——缩放旋转后还要核对它')
  // 工具栏开关：收起再亮出来时不再压暗
  vm.toggleEvidenceRect()
  assert.equal(vm.evidenceRectVisible, false)
  vm.toggleEvidenceRect()
  assert.equal(vm.evidenceRectVisible, true)
  assert.equal(vm.evidenceRectUndimmed, true)
})

test('图片定位实例：rect 缺 w/h（OCR 只回了左上角）→ 不画框，也不报错', () => {
  const vm = makeVm({ type: 'image', rect: { x: 0.25, y: 0.5 } }, { fileType: 'jpg', natW: 400, natH: 300 })
  assert.equal(vm.evidenceRectVisible, false)
  assert.equal(vm.timers.length, 0)
  assert.deepEqual(vm.emitted, [['locator-consumed', 7]], '仍要通知宿主消费掉，否则切回标签会重复跳转')
})

// ── 实例三：音视频 ──────────────────────────────────────────────────────────

test('音频定位实例：{type:media, startMs:125000} → seek 到 125s 且暂停，时间标记亮起', () => {
  const el = fakeMedia()
  const vm = makeVm({ type: 'media', startMs: 125000 }, { fileType: 'mp3', audioEl: el, audioDuration: 600 })
  assert.equal(el.currentTime, 125)
  assert.equal(el.paused, true, '定位是为了看/听那一处，不能自动播下去把定位冲掉')
  assert.equal(vm.mediaMarkVisible, true)
  assert.equal(vm.mediaLocatorSec, 125)
  // 「从这里播放」把播放权交回用户
  vm.playFromMark()
  assert.equal(el.playCalls, 1)
  assert.equal(el.paused, false)
})

test('视频定位实例：元素还没挂上时 seek 静默跳过，挂上后再调一次就能落到 125s', () => {
  const vm = makeVm({ type: 'media', startMs: 125000 }, { fileType: 'mp4' })
  assert.equal(vm.mediaMarkVisible, true, '标记先亮，等元素就绪')
  const el = fakeMedia()
  vm._videoEl = el
  vm.seekToLocator()
  assert.equal(el.currentTime, 125)
  assert.equal(el.paused, true)
})

test('视频定位实例：attachVideoLocator 在原生元素上挂 loadedmetadata，元数据一到就落定位', () => {
  const el = fakeMedia()
  const handlers = {}
  el.tagName = 'VIDEO'
  el.addEventListener = (n, fn) => { handlers[n] = fn }
  el.removeEventListener = (n) => { delete handlers[n] }
  const vm = makeVm({ type: 'media', startMs: 125000 }, { fileType: 'mp4' })
  vm.$refs.videoPlayer = el
  vm.attachVideoLocator()
  assert.ok(handlers.loadedmetadata && handlers.loadeddata, 'uni 的 <video> 事件名不可靠，必须挂原生监听')
  assert.equal(el.currentTime, 125, '元素已经就绪时立刻落一次')
  // 模拟「挂的时候元数据还没好」：currentTime 被浏览器忽略，事件回调再落一次
  el.currentTime = 0
  el.paused = false
  handlers.loadedmetadata()
  assert.equal(el.currentTime, 125)
  assert.equal(el.paused, true)
  vm.teardownVideoLocator()
  assert.deepEqual(Object.keys(handlers), [], '换文件/卸载时监听要摘干净')
})

test('音视频定位实例：locator 缺 startMs → 不 seek、不暂停、不显示标记', () => {
  const el = fakeMedia()
  const vm = makeVm({ type: 'media' }, { fileType: 'mp3', audioEl: el, audioDuration: 600 })
  assert.equal(el.currentTime, 0)
  assert.equal(el.paused, false, '没有定位点就别去动用户的播放器')
  assert.equal(vm.mediaMarkVisible, false)
})
