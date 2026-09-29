// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// EvidenceLink 音视频定位契约（P3，原在 tests/evidence/previewLocate.test.mjs，随播放器
// 搬进 components/media/MediaPlayer.vue，契约不变）：
//
//   带定位打开 → 元数据就绪后 seek 到秒数并**停在那一帧**，不自动播放，emit locator-consumed 一次
//   不带定位   → 视频自动播放、音频不动
//   「从这里播放」→ 从标记处起播
//
// 做法同原测试：把组件里的方法体抠出来真跑，媒体元素用假对象。时间标记与刻度的
// 渲染在 mediaPlayerRender.test.mjs（SSR 真渲染）。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseMediaStartSec } from '../../src/utils/evidenceLocator.js'

const SRC = readFileSync(new URL('../../src/components/media/MediaPlayer.vue', import.meta.url), 'utf8')

// 组件里方法体一律 4 空格缩进、以 `\n    },` 收尾（内层块是 6 空格，不会提前截断）
function pickMethod(name) {
  const m = SRC.match(new RegExp('\\n    ' + name + '\\(([^)]*)\\) \\{\\n([\\s\\S]*?)\\n    \\},'))
  assert.ok(m, 'MediaPlayer.vue 里找不到方法 ' + name)
  return new Function(m[1], m[2])
}

function fakeMedia(opts = {}) {
  return {
    currentTime: 0,
    paused: true,
    readyState: opts.readyState === undefined ? 1 : opts.readyState,
    playCalls: 0,
    buffered: { length: 0 },
    pause() { this.paused = true },
    play() { this.playCalls += 1; this.paused = false; return Promise.resolve() },
  }
}

function makeVm({ kind, locator, el }) {
  const emitted = []
  const vm = {
    kind,
    // FilePreview 传下来的就是 parseMediaStartSec(appliedLocator)
    locatorSec: parseMediaStartSec(locator),
    currentSec: 0,
    buffered: [],
    markVisible: false,
    _media: el,
    _fixingDuration: false,
    _autoplayed: false,
    _locatorPending: parseMediaStartSec(locator) !== null,
    $emit: (...a) => emitted.push(a),
    emitted,
  }
  for (const name of ['afterMetadata', 'seekToLocator', 'relocate', 'playFromMark', 'playMedia', 'readBuffered']) {
    vm[name] = pickMethod(name).bind(vm)
  }
  return vm
}

test('音频定位：{type:media, startMs:125000} → 元数据就绪后 seek 到 125s 且暂停，不自动播放', () => {
  const el = fakeMedia()
  el.paused = false // 哪怕元素处于播放态，定位也要把它停下
  const vm = makeVm({ kind: 'audio', locator: { type: 'media', startMs: 125000 }, el })
  vm.afterMetadata()
  assert.equal(el.currentTime, 125)
  assert.equal(el.paused, true, '定位是为了看/听那一处，不能自动播下去把定位冲掉')
  assert.equal(el.playCalls, 0)
  assert.equal(vm.currentSec, 125, '控制条读数同步到定位时刻')
  assert.deepEqual(vm.emitted, [['locator-consumed']])
  vm.afterMetadata() // 回退到 blob 后元数据会再来一次
  assert.deepEqual(vm.emitted, [['locator-consumed']], 'locator-consumed 只 emit 一次')
})

test('视频定位：带定位时不自动播放（即使视频默认是自动播放的）', () => {
  const el = fakeMedia()
  const vm = makeVm({ kind: 'video', locator: { type: 'media', startMs: 125000 }, el })
  vm.afterMetadata()
  assert.equal(el.currentTime, 125)
  assert.equal(el.paused, true)
  assert.equal(el.playCalls, 0, '带定位打开绝不自动播放')
})

test('元数据没就绪时 seek 静默跳过、不 emit，就绪后再落一次', () => {
  const el = fakeMedia({ readyState: 0 })
  const vm = makeVm({ kind: 'video', locator: { type: 'media', startMs: 125000 }, el })
  assert.equal(vm.seekToLocator(), false)
  assert.equal(el.currentTime, 0)
  assert.deepEqual(vm.emitted, [])
  el.readyState = 1
  vm.afterMetadata()
  assert.equal(el.currentTime, 125)
  assert.deepEqual(vm.emitted, [['locator-consumed']])
})

test('webm 时长还在修正（Infinity 技巧进行中）时不落定位，免得被跳回开头冲掉', () => {
  const el = fakeMedia()
  const vm = makeVm({ kind: 'audio', locator: { type: 'media', startMs: 125000 }, el })
  vm._fixingDuration = true
  assert.equal(vm.seekToLocator(), false)
  assert.equal(el.currentTime, 0)
})

test('不带定位：视频自动播放一次，音频不动', () => {
  const v = fakeMedia()
  const vm = makeVm({ kind: 'video', locator: null, el: v })
  vm.afterMetadata()
  assert.equal(v.playCalls, 1, '没有定位时保持原来的自动播放')
  vm.afterMetadata()
  assert.equal(v.playCalls, 1, '回退重载元数据时不重复起播')
  assert.deepEqual(vm.emitted, [])

  const a = fakeMedia()
  const am = makeVm({ kind: 'audio', locator: null, el: a })
  am.afterMetadata()
  assert.equal(a.playCalls, 0, '音频从来不自动播放')
})

test('locator 缺 startMs → 不 seek、不暂停、不 emit', () => {
  const el = fakeMedia()
  el.paused = false
  const vm = makeVm({ kind: 'audio', locator: { type: 'media' }, el })
  assert.equal(vm.locatorSec, null)
  vm.afterMetadata()
  assert.equal(el.currentTime, 0)
  assert.equal(el.paused, false, '没有定位点就别去动用户的播放器')
  assert.deepEqual(vm.emitted, [])
})

test('「从这里播放」从标记处起播，把播放权交回用户', () => {
  const el = fakeMedia()
  const vm = makeVm({ kind: 'audio', locator: { type: 'media', startMs: 125000 }, el })
  vm.afterMetadata()
  el.currentTime = 300 // 用户拖走了
  vm.playFromMark()
  assert.equal(el.currentTime, 125)
  assert.equal(el.playCalls, 1)
  assert.equal(el.paused, false)
})

test('同一文件再次被链接点中（relocate）：标记重新亮出、再落一次定位并通知宿主', () => {
  const el = fakeMedia()
  const vm = makeVm({ kind: 'video', locator: { type: 'media', startMs: 125000 }, el })
  vm.afterMetadata()
  vm.markVisible = false // 用户关掉了标记
  el.currentTime = 400
  el.paused = false
  vm.relocate()
  assert.equal(vm.markVisible, true)
  assert.equal(el.currentTime, 125)
  assert.equal(el.paused, true)
  assert.deepEqual(vm.emitted, [['locator-consumed'], ['locator-consumed']])
})

test('媒体元素命令式创建且不带原生控件、不靠 autoplay 属性（自动播放只由 afterMetadata 决定）', () => {
  const setup = SRC.match(/\n    setupMedia\(\) \{\n([\s\S]*?)\n    \},/)
  assert.ok(setup, '找不到 setupMedia')
  const body = setup[1]
  assert.match(body, /document\.createElement\('video'\)/)
  assert.match(body, /new window\.Audio\(\)/)
  assert.match(body, /el\.autoplay = false/)
  assert.match(body, /el\.controls = false/)
  assert.match(body, /preload = 'metadata'/)
  assert.match(body, /setAttribute\('playsinline', ''\)/)
  assert.match(body, /buildStreamUrl\(getFileBytesUrl\(/, '直链流式取源')
})

// ── 打开媒体文件时的焦点接管（真机：从资源管理器点开后焦点在 .file-tree 上） ──
import { shouldGrabMediaFocus } from '../../src/utils/media/mediaShortcuts.js'

const FP_SRC = readFileSync(new URL('../../src/components/FilePreview.vue', import.meta.url), 'utf8')

function makeFocusVm(activeEl) {
  const m = FP_SRC.match(/\n    focusMediaPlayer\(\) \{\n([\s\S]*?)\n    \},/)
  assert.ok(m, 'FilePreview.vue 里找不到 focusMediaPlayer')
  let focused = 0
  const vm = { $refs: { mediaPlayer: { focusRoot() { focused += 1 } } } }
  const doc = { activeElement: activeEl, body: { tagName: 'BODY' } }
  // eslint-disable-next-line no-new-func
  new Function('document', 'shouldGrabMediaFocus', m[1]).call(vm, doc, shouldGrabMediaFocus)
  return focused
}

test('焦点接管：焦点在带 tabindex 的文件树 div 上 → 播放器接过来', () => {
  assert.equal(makeFocusVm({ tagName: 'DIV', className: 'file-tree', tabIndex: 0 }), 1)
})

test('焦点接管：焦点为空或在 body 上 → 接过来', () => {
  assert.equal(makeFocusVm(null), 1)
  assert.equal(makeFocusVm({ tagName: 'BODY' }), 1)
})

test('焦点接管：用户正在输入（input / textarea / contenteditable）→ 不抢', () => {
  assert.equal(makeFocusVm({ tagName: 'INPUT' }), 0)
  assert.equal(makeFocusVm({ tagName: 'TEXTAREA' }), 0)
  assert.equal(makeFocusVm({ tagName: 'DIV', isContentEditable: true }), 0)
})

test('焦点接管：焦点在 iframe 里（LOWA 编辑器等）→ 不抢', () => {
  assert.equal(makeFocusVm({ tagName: 'IFRAME' }), 0)
  assert.equal(shouldGrabMediaFocus({ tagName: 'iframe' }), false)
})

test('文件切换同走这条规则：reloadPreview 的媒体分支调用 focusMediaPlayer', () => {
  const m = FP_SRC.match(/\n    reloadPreview\(newFile\) \{\n([\s\S]*?)\n    \},/)
  assert.ok(m)
  assert.match(m[1], /isVideo \|\| this\.isAudio\) \{[\s\S]*?focusMediaPlayer\(\)/)
})
