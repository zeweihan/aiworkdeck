// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 播放器三件套的 SSR 真渲染（dev-board#1023/#1024）：组件的 script 与模板一起装进来
// （sfcLoader.mjs），按真实 props / data 渲染成 HTML 再断言。只跑纯函数的测试看不到
// 模板里 class 写错、v-if 挂错分支、i18n 键打错，这一层专门拦这些。
//
// $t 直接回键名（带参数时拼上 JSON），断言键名即断言「模板引用的 i18n 键」。
import test from 'node:test'
import assert from 'node:assert/strict'
import * as Vue from 'vue'
import { renderToString } from 'vue/server-renderer'
import { loadSfc, readSfcTemplate } from './sfcLoader.mjs'

const MediaControls = await loadSfc('components/media/MediaControls.vue')
const MediaTranscript = await loadSfc('components/media/MediaTranscript.vue')
const MediaPlayer = await loadSfc('components/media/MediaPlayer.vue')

async function render(component, props, dataOverrides) {
  // extends 不继承 ssrRender，要显式带上；data 由 Vue 的 mergeDataFn 与组件自己的合并（覆盖同名项）
  const comp = dataOverrides ? { extends: component, ssrRender: component.ssrRender, data: () => dataOverrides } : component
  const app = Vue.createSSRApp(comp, props)
  app.config.globalProperties.$t = (k, p) => (p ? k + JSON.stringify(p) : k)
  app.config.warnHandler = () => {}
  const html = await renderToString(app)
  // 模板里的中文注释会原样进 HTML：断言标签与属性前先剥掉，免得匹配到注释上（假绿）
  return html.replace(/<!--[\s\S]*?-->/g, '').replace(/&quot;/g, '"')
}

// SSR 把静态 class 与 :class 合并时顺序不固定（动态在前），按「含有这个类」找标签
function tagWithClass(html, cls) {
  const re = new RegExp('<div[^>]*class="(?:[^"]*\\s)?' + cls + '(?:\\s[^"]*)?"[^>]*>')
  const m = html.match(re)
  return m ? m[0] : null
}

function classesOf(tag) {
  const m = tag && tag.match(/class="([^"]*)"/)
  return m ? m[1].split(/\s+/) : []
}

const baseControls = { variant: 'overlay', kind: 'video', durationSec: 120, currentSec: 30 }

// ── MediaControls ───────────────────────────────────────────────────────────

test('两套皮：视频 overlay、音频 card，根 class 上挂皮名', async () => {
  const v = await render(MediaControls, baseControls)
  assert.match(v, /^<div class="mpc mp--overlay mpc--video/)
  const a = await render(MediaControls, { ...baseControls, variant: 'card', kind: 'audio' })
  assert.match(a, /^<div class="mpc mp--card mpc--audio/)
})

test('CC 五态：各自的 class、图形与 i18n 键', async () => {
  const cc = async (extra) => tagWithClass(await render(MediaControls, { ...baseControls, ...extra }), 'mpc-cc')

  const none = await render(MediaControls, { ...baseControls, captionState: 'none' })
  assert.match(tagWithClass(none, 'mpc-cc'), /is-none/)
  assert.match(tagWithClass(none, 'mpc-cc'), /aria-label="files\.captions\.generate"/)
  assert.ok(none.includes('mpc-cc-plus'), 'none 要有「+」角标')

  const pending = await render(MediaControls, { ...baseControls, captionState: 'pending' })
  assert.match(tagWithClass(pending, 'mpc-cc'), /aria-label="files\.captions\.generate"/)
  assert.ok(pending.includes('mpc-cc-plus'), 'pending 与 none 同一呈现')

  const tr = await render(MediaControls, { ...baseControls, captionState: 'transcribing' })
  assert.match(tagWithClass(tr, 'mpc-cc'), /is-transcribing/)
  assert.match(tagWithClass(tr, 'mpc-cc'), /aria-label="files\.captions\.generating"/)
  assert.ok(tr.includes('mpc-cc-spin') && tr.includes('mpc-cc-dots'), '转圈 + 减少动态时的三点')
  assert.ok(!tr.includes('mpc-cc-plus'))

  const on = await cc({ captionState: 'ready', captionsOn: true })
  assert.match(on, /is-ready/)
  assert.match(on, /is-on/)
  assert.match(on, /aria-pressed="true"/)
  assert.match(on, /aria-label="files\.captions\.turnOff"/)
  const off = await cc({ captionState: 'ready', captionsOn: false })
  assert.doesNotMatch(off, /is-on/)
  assert.match(off, /aria-pressed="false"/)
  assert.match(off, /aria-label="files\.captions\.turnOn"/)
  const audioReady = await cc({ variant: 'card', kind: 'audio', captionState: 'ready', captionsOn: true })
  assert.match(audioReady, /aria-label="files\.player\.hideTranscript"/, '音频的 CC 就是逐字稿开关')

  const empty = await render(MediaControls, { ...baseControls, captionState: 'empty' })
  assert.match(tagWithClass(empty, 'mpc-cc'), /aria-label="files\.captions\.emptyRetry"/)
  assert.ok(empty.includes('d="M3.5 3.5l17 17"'), 'empty 用带斜杠的 captionsOff 图形')

  const longErr = '听悟任务失败：'.padEnd(80, '啊')
  const failed = await render(MediaControls, { ...baseControls, captionState: 'failed', captionError: longErr })
  assert.ok(failed.includes('mpc-cc-alert'), 'failed 要有警示小点')
  // $t 桩把参数拼成 JSON，其中的引号在 HTML 反转义后会截断属性正则，直接取参数那一段
  const err = tagWithClass(failed, 'mpc-cc').match(/aria-label="files\.captions\.failedRetry\{"error":"([^"]*)"\}"/)
  assert.ok(err, 'failed 的 aria-label 要走 failedRetry 并带错误原文')
  assert.ok(err[1].startsWith('听悟任务失败'))
  assert.ok(err[1].endsWith('…'), '错误原文超过 60 字要截断')
  assert.equal(err[1].length, 61)

  const hidden = await render(MediaControls, { ...baseControls, captionState: null })
  assert.ok(!hidden.includes('mpc-cc'), '拿不到项目时 CC 不渲染')
})

test('逐字稿按钮只在字幕就绪的视频上出现', async () => {
  for (const st of ['none', 'pending', 'transcribing', 'empty', 'failed']) {
    const html = await render(MediaControls, { ...baseControls, captionState: st })
    assert.ok(!html.includes('mpc-transcript'), st + ' 下不该有逐字稿按钮')
  }
  const ready = await render(MediaControls, { ...baseControls, captionState: 'ready', transcriptOpen: true })
  assert.match(tagWithClass(ready, 'mpc-transcript'), /is-active/)
  assert.match(tagWithClass(ready, 'mpc-transcript'), /aria-label="files\.player\.hideTranscript"/)
  const audio = await render(MediaControls, { ...baseControls, variant: 'card', kind: 'audio', captionState: 'ready' })
  assert.ok(!audio.includes('mpc-transcript'))
})

test('画中画按钮受能力开关控制；音频没有全屏与画中画', async () => {
  const noPip = await render(MediaControls, { ...baseControls, pipAvailable: false })
  assert.ok(!noPip.includes('mpc-pip'))
  assert.ok(noPip.includes('mpc-fullscreen'))
  const pip = await render(MediaControls, { ...baseControls, pipAvailable: true })
  assert.ok(pip.includes('mpc-pip'))
  const audio = await render(MediaControls, { ...baseControls, variant: 'card', kind: 'audio', pipAvailable: true })
  assert.ok(!audio.includes('mpc-fullscreen'), '音频无全屏')
  assert.ok(!audio.includes('mpc-pip'), '音频无画中画')
  const fs = await render(MediaControls, { ...baseControls, fullscreen: true })
  assert.match(tagWithClass(fs, 'mpc-fullscreen'), /aria-label="files\.player\.exitFullscreen"/)
})

test('进度轨：定位刻度、缓冲段、滑块 aria；倍速显示当前值', async () => {
  const html = await render(MediaControls, { ...baseControls, markSec: 30, buffered: [[0, 60], [90, 120]], rate: 1.5 })
  assert.match(html, /class="mpc-rail-mark" style="left:25%;"/)
  assert.match(html, /class="mpc-rail-buffered" style="left:0%;width:50%;"/)
  assert.match(html, /class="mpc-rail-buffered" style="left:75%;width:25%;"/)
  assert.match(html, /class="mpc-rail-fill" style="width:25%;"/)
  const rail = tagWithClass(html, 'mpc-rail')
  assert.match(rail, /role="slider"/)
  assert.match(rail, /aria-valuemax="120"/)
  assert.match(rail, /aria-valuenow="30"/)
  assert.match(rail, /aria-valuetext="0:30 \/ 2:00"/)
  assert.match(html, /<span>1\.5x<\/span>/)
  const one = await render(MediaControls, { ...baseControls, rate: 1 })
  assert.match(one, /<span>1\.0x<\/span>/)
  const noMark = await render(MediaControls, { ...baseControls, markSec: null })
  assert.ok(!noMark.includes('mpc-rail-mark'))
  const outOfRange = await render(MediaControls, { ...baseControls, markSec: 500 })
  assert.ok(!outOfRange.includes('mpc-rail-mark'), '定位超出时长就不画')
})

test('按钮都是可聚焦的 role=button，有 aria-label 与 title', async () => {
  const html = await render(MediaControls, { ...baseControls, captionState: 'ready', pipAvailable: true })
  const buttons = html.match(/<div[^>]*role="button"[^>]*>/g) || []
  assert.ok(buttons.length >= 7, '播放/CC/逐字稿/倍速/音量/画中画/全屏')
  for (const b of buttons) {
    assert.match(b, /tabindex="0"/)
    assert.match(b, /aria-label="[^"]+"/)
    assert.match(b, /title="[^"]+"/)
  }
  assert.match(tagWithClass(html, 'mpc-vol-track'), /role="slider"/)
  const muted = await render(MediaControls, { ...baseControls, muted: true })
  assert.match(tagWithClass(muted, 'mpc-vol-track'), /aria-valuenow="0"/)
})

// ── MediaTranscript ─────────────────────────────────────────────────────────

const cues = [
  { start: 0, end: 1500, text: '各位好，', speaker: '1', segIndex: 0 },
  { start: 1500, end: 3000, text: '开始开会。', speaker: '1', segIndex: 0 },
  { start: 3200, end: 6000, text: '收到。', speaker: '2', segIndex: 1 },
]

test('逐字稿：同段子 cue 合回一行，当前行高亮，≥2 人显示图例', async () => {
  const html = await render(MediaTranscript, {
    cues, labels: { 1: '张律师', 2: '说话人2' }, speakerCount: 2, currentIndex: 1, following: true,
  })
  const rows = html.match(/<div[^>]*data-row="\d+"[^>]*>/g)
  assert.equal(rows.length, 2, '两段两行')
  assert.ok(classesOf(rows[0]).includes('is-current'))
  assert.ok(!classesOf(rows[1]).includes('is-current'))
  assert.match(rows[0], /tabindex="0"/, '当前行是列表唯一的 Tab 停靠点')
  assert.match(rows[1], /tabindex="-1"/)
  assert.ok(html.includes('各位好，开始开会。'), 'CJK 子 cue 直接接上')
  assert.ok(html.includes('mpt-legend'))
  assert.ok(html.includes('张律师'))
  assert.match(html, /files\.player\.summary\{"count":2,"duration":"0:06"\}/)
  assert.ok(!html.includes('mpt-back'), '跟随中不出「回到当前」')
})

test('逐字稿：暂停跟随时出现「回到当前」；单人无图例；空列表给空态', async () => {
  const paused = await render(MediaTranscript, { cues, labels: {}, speakerCount: 1, currentIndex: 2, following: false })
  assert.match(paused, /class="mpt-back"[^>]*>files\.player\.backToCurrent</)
  assert.ok(!paused.includes('mpt-legend'))
  const empty = await render(MediaTranscript, { cues: [], labels: {}, speakerCount: 0, currentIndex: -1, following: true })
  assert.match(empty, /class="mpt-empty">files\.captions\.empty</)
})

// ── MediaPlayer ─────────────────────────────────────────────────────────────

test('模板里没有 <video>/<audio> 标签、没有 controls 属性（媒体元素一律命令式创建）', () => {
  const tpl = readSfcTemplate('components/media/MediaPlayer.vue').replace(/<!--[\s\S]*?-->/g, '')
  assert.doesNotMatch(tpl, /<video\b/i)
  assert.doesNotMatch(tpl, /<audio\b/i)
  assert.doesNotMatch(tpl, /\scontrols[\s=>]/)
})

const videoFile = { id: 9, name: 'v.mp4', fileType: 'mp4', projectId: 3 }
const audioFile = { id: 10, name: '会见录音.mp3', fileType: 'mp3', projectId: 3 }

test('视频：overlay 皮、舞台 + 控制条；渲染结果里同样没有 <video>', async () => {
  const html = await render(MediaPlayer, { kind: 'video', file: videoFile })
  assert.match(html, /^<div class="mp mp--overlay mp--video/)
  assert.match(html, /tabindex="0"/)
  assert.ok(html.includes('mp-surface') && html.includes('mp-scrim'))
  assert.ok(html.includes('mpc mp--overlay mpc--video'))
  assert.ok(!/<video\b/.test(html))
  assert.ok(html.includes('mp-spinner'), '元数据到之前是加载态')
})

test('音频：card 皮，头部文件名，无全屏', async () => {
  const html = await render(MediaPlayer, { kind: 'audio', file: audioFile })
  assert.match(html, /^<div class="mp mp--card mp--audio/)
  assert.ok(html.includes('会见录音.mp3'))
  assert.ok(html.includes('mpc mp--card mpc--audio'))
  assert.ok(!html.includes('mpc-fullscreen'))
})

test('EvidenceLink 时间标记：带定位时渲染标记条与「从这里播放」，轨道上有刻度', async () => {
  const video = await render(MediaPlayer, { kind: 'video', file: videoFile, locatorSec: 125 }, { durationSec: 600 })
  assert.deepEqual(classesOf(tagWithClass(video, 'mp-mark')), ['mp-mark'])
  assert.match(video, /files\.locate\.mediaMark\{"time":"2:05"\}/)
  assert.match(video, /files\.locate\.playFromMark/)
  assert.ok(video.includes('mpc-rail-mark'))

  const audio = await render(MediaPlayer, { kind: 'audio', file: audioFile, locatorSec: 125 }, { durationSec: 600 })
  assert.ok(classesOf(tagWithClass(audio, 'mp-mark')).includes('is-inline'))
  assert.match(audio, /class="mpc-rail-mark" style="left:20\.8333/)

  const none = await render(MediaPlayer, { kind: 'video', file: videoFile, locatorSec: null }, { durationSec: 600 })
  assert.ok(!none.includes('mp-mark'))
  assert.ok(!none.includes('mpc-rail-mark'))
  const closed = await render(MediaPlayer, { kind: 'video', file: videoFile, locatorSec: 125 }, { durationSec: 600, markVisible: false })
  assert.ok(!closed.includes('mp-mark'), '用户关掉标记条后刻度也撤掉')
})

const readyData = (extra) => ({
  durationSec: 10,
  loading: false,
  captionState: 'ready',
  captionsOn: true,
  cues: Object.freeze(cues),
  labels: { 1: '张律师', 2: '说话人2' },
  speakerCount: 2,
  currentSec: 2,
  ...extra,
})

test('字幕叠层：就绪 + 开 → 当前句；两人以上带说话人前缀；关掉就不渲染', async () => {
  const html = await render(MediaPlayer, { kind: 'video', file: videoFile }, readyData())
  assert.ok(classesOf(tagWithClass(html, 'mp-caption-layer')).includes('is-raised'), '暂停时控制条可见，字幕抬高让位')
  assert.match(html, /files\.player\.speakerPrefix\{"name":"张律师"\}<\/span>开始开会。/)
  const single = await render(MediaPlayer, { kind: 'video', file: videoFile }, readyData({ speakerCount: 1 }))
  assert.ok(!single.includes('speakerPrefix'), '单人不带前缀')
  assert.ok(single.includes('开始开会。'))
  const off = await render(MediaPlayer, { kind: 'video', file: videoFile }, readyData({ captionsOn: false }))
  assert.ok(!off.includes('mp-caption-layer'))
  const gap = await render(MediaPlayer, { kind: 'video', file: videoFile }, readyData({ currentSec: 9 }))
  assert.ok(!gap.includes('mp-caption-layer'), '没有 cue 覆盖的时刻不显示')
})

test('逐字稿：视频按开关出现在舞台旁；音频字幕开即显示逐字稿；没有项目 id 时不出 CC', async () => {
  const closed = await render(MediaPlayer, { kind: 'video', file: videoFile }, readyData())
  assert.ok(!closed.includes('class="mpt'))
  const open = await render(MediaPlayer, { kind: 'video', file: videoFile }, readyData({ transcriptOpen: true, wide: true }))
  assert.ok(classesOf(tagWithClass(open, 'mp-main')).includes('is-side'))
  assert.ok(classesOf(tagWithClass(open, 'mpt')).includes('mp-transcript'))
  const below = await render(MediaPlayer, { kind: 'video', file: videoFile }, readyData({ transcriptOpen: true, wide: false }))
  assert.ok(classesOf(tagWithClass(below, 'mp-main')).includes('is-below'))

  const audio = await render(MediaPlayer, { kind: 'audio', file: audioFile }, readyData())
  assert.ok(audio.includes('mp-transcript--audio'))
  assert.ok(classesOf(tagWithClass(audio, 'mp-card')).includes('is-wide'))
  assert.ok(!audio.includes('mp-caption-layer'), '音频没有叠层字幕')

  const noProject = await render(MediaPlayer, { kind: 'video', file: { id: 9, name: 'v.mp4', fileType: 'mp4' } }, { captionState: 'none' })
  assert.ok(!noProject.includes('mpc-cc'))
  const viaProp = await render(MediaPlayer, { kind: 'video', file: { id: 9, name: 'v.mp4', fileType: 'mp4' }, projectId: 3 }, { captionState: 'none' })
  assert.ok(viaProp.includes('mpc-cc'), 'file 上没有 projectId 时用 prop 兜底')
})

test('播放中且指针闲置 → 控制条与遮罩淡出；弹层打开时不隐藏', async () => {
  const idle = await render(MediaPlayer, { kind: 'video', file: videoFile }, { playing: true, idle: true, loading: false })
  assert.match(idle, /^<div class="mp mp--overlay mp--video is-idle/)
  assert.ok(classesOf(tagWithClass(idle, 'mp-controls')).includes('is-hidden'))
  assert.ok(classesOf(tagWithClass(idle, 'mp-scrim')).includes('is-hidden'))
  assert.ok(!idle.includes('mp-badge'), '播放中不显示中央徽标')
  const pop = await render(MediaPlayer, { kind: 'video', file: videoFile }, { playing: true, idle: true, popoverOpen: true, loading: false })
  assert.ok(!classesOf(tagWithClass(pop, 'mp-controls')).includes('is-hidden'))
  const paused = await render(MediaPlayer, { kind: 'video', file: videoFile }, { playing: false, loading: false })
  assert.ok(paused.includes('mp-badge'), '暂停时中央播放徽标')
})

test('焦点环：视频画在播放器外框，音频画在卡片上（根元素是整片预览区，不许描外框）', async () => {
  const { readFileSync } = await import('node:fs')
  const sfc = readFileSync(new URL('../../src/components/media/MediaPlayer.vue', import.meta.url), 'utf8')
  const css = sfc.slice(sfc.indexOf('<style')).replace(/\/\*[\s\S]*?\*\//g, '')
  assert.doesNotMatch(css, /\.mp:focus-visible/, '不许对两套皮一刀切画外框')
  assert.match(css, /\.mp--video:focus-visible::after\s*\{[^}]*box-shadow:\s*inset 0 0 0 2px var\(--awd-bamboo\)/)
  assert.match(css, /\.mp--card:focus-visible\s*\{[^}]*outline:\s*none/)
  assert.match(css, /\.mp--card:focus-visible \.mp-card\s*\{[^}]*box-shadow:\s*0 0 0 2px var\(--awd-bamboo\)/)
  const ctl = readFileSync(new URL('../../src/components/media/MediaControls.vue', import.meta.url), 'utf8')
  assert.match(ctl, /\.mpc-btn:focus-visible\s*\{[^}]*box-shadow:\s*0 0 0 2px var\(--mp-focus\)/, 'Tab 到按钮时按钮自身的焦点环仍在')
})
