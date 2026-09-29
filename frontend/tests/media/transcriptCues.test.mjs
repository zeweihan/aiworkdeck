// SPDX-FileCopyrightText: 2026 北京京微资易科技有限公司 and AI WorkDeck contributors
// SPDX-License-Identifier: AGPL-3.0-or-later
// 自动字幕的纯逻辑（utils/media/transcriptCues.js，规格 3.5）：
//   段 → cue、二分查找、说话人命名、42 字长句切分与 800ms 下限、300ms 间隙连续。
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseSegments, buildSpeakerLabels, buildCues, cueIndexAt, textWidth,
} from '../../src/utils/media/transcriptCues.js'

// ── textWidth ───────────────────────────────────────────────────────────────

test('textWidth：CJK 与全角标点计 1，ASCII 计 0.5，空值为 0', () => {
  assert.equal(textWidth('你好'), 2)
  assert.equal(textWidth('ab'), 1)
  assert.equal(textWidth('你好ab。'), 4)
  assert.equal(textWidth(''), 0)
  assert.equal(textWidth(null), 0)
  assert.equal(textWidth(undefined), 0)
})

// ── parseSegments ───────────────────────────────────────────────────────────

test('parseSegments：合法 JSON 数组原样解析', () => {
  const segs = parseSegments('[{"speaker":"1","start":0,"end":1000,"text":"开始"}]')
  assert.equal(segs.length, 1)
  assert.equal(segs[0].text, '开始')
})

test('parseSegments：非法 JSON / 非数组 / 空值一律 []', () => {
  assert.deepEqual(parseSegments('{not json'), [])
  assert.deepEqual(parseSegments('{"a":1}'), [])
  assert.deepEqual(parseSegments('null'), [])
  assert.deepEqual(parseSegments(''), [])
  assert.deepEqual(parseSegments(null), [])
  assert.deepEqual(parseSegments(undefined), [])
})

test('parseSegments：数组里的非对象元素被剔掉', () => {
  assert.deepEqual(parseSegments('[1, null, "x", {"text":"留下"}]'), [{ text: '留下' }])
})

// ── buildSpeakerLabels ──────────────────────────────────────────────────────

const twoSpeakers = [
  { speaker: '2', start: 0, end: 1000, text: 'a' },
  { speaker: '1', start: 1000, end: 2000, text: 'b' },
  { speaker: '2', start: 2000, end: 3000, text: 'c' },
]

test('buildSpeakerLabels：没有命名时按原始说话人 id 命名（中文无空格，与会议面板 speakerDefaultName 同形），不按出现顺序', () => {
  const { labels, count } = buildSpeakerLabels(twoSpeakers, null, 'zh-CN')
  assert.equal(count, 2)
  assert.equal(labels['2'], '说话人2') // 先出场的是 id 2，仍叫说话人2
  assert.equal(labels['1'], '说话人1')
})

test('buildSpeakerLabels：id 为 2 的说话人叫说话人2（只有一个人也不重排成 1）', () => {
  const { labels, count } = buildSpeakerLabels([{ speaker: 2, start: 0, end: 1000, text: 'x' }], null, 'zh-CN')
  assert.equal(count, 1)
  assert.equal(labels['2'], '说话人2')
})

test('buildSpeakerLabels：en 开头的 locale 用 Speaker {id}', () => {
  const { labels } = buildSpeakerLabels(twoSpeakers, undefined, 'en-US')
  assert.equal(labels['2'], 'Speaker 2')
  assert.equal(labels['1'], 'Speaker 1')
})

test('buildSpeakerLabels：speakerNames 字符串或对象都认且优先，空白名字退回按 id 的默认名', () => {
  const fromStr = buildSpeakerLabels(twoSpeakers, '{"1":"王律师","2":"  "}', 'zh-CN')
  assert.equal(fromStr.labels['1'], '王律师')
  assert.equal(fromStr.labels['2'], '说话人2')
  const fromObj = buildSpeakerLabels(twoSpeakers, { 2: ' 李律师 ' }, 'zh-CN')
  assert.equal(fromObj.labels['2'], '李律师')
  assert.equal(fromObj.labels['1'], '说话人1')
})

test('buildSpeakerLabels：speakerNames 非法 JSON 按无命名处理；空段 count 为 0', () => {
  const r = buildSpeakerLabels(twoSpeakers, '{bad', 'zh-CN')
  assert.equal(r.labels['2'], '说话人2')
  assert.deepEqual(buildSpeakerLabels([], null, 'zh-CN'), { labels: {}, count: 0 })
  assert.deepEqual(buildSpeakerLabels(null, null, 'zh-CN'), { labels: {}, count: 0 })
})

test('buildSpeakerLabels：没有 speaker 字段的段不计入说话人数', () => {
  const r = buildSpeakerLabels([{ start: 0, end: 1, text: 'x' }, { speaker: 0, start: 1, end: 2, text: 'y' }], null, 'zh-CN')
  assert.equal(r.count, 1)
  assert.equal(r.labels['0'], '说话人0')
})

// ── buildCues ───────────────────────────────────────────────────────────────

test('buildCues：短段原样成一个 cue，segIndex 指回原段，按 start 排序', () => {
  const cues = buildCues([
    { speaker: '1', start: 5000, end: 6000, text: '第二句' },
    { speaker: '2', start: 0, end: 2000, text: ' 第一句 ' },
  ])
  assert.equal(cues.length, 2)
  assert.deepEqual(cues[0], { start: 0, end: 2000, text: '第一句', speaker: '2', segIndex: 1 })
  assert.deepEqual(cues[1], { start: 5000, end: 6000, text: '第二句', speaker: '1', segIndex: 0 })
})

test('buildCues：空文本或时间非法的段被跳过；空输入返回 []', () => {
  assert.deepEqual(buildCues([]), [])
  assert.deepEqual(buildCues(null), [])
  const cues = buildCues([
    { start: 0, end: 1000, text: '   ' },
    { start: 'x', end: 1000, text: '坏' },
    { start: 2000, end: 1000, text: '倒挂' },
    { start: 3000, end: 4000, text: '好' },
  ])
  assert.equal(cues.length, 1)
  assert.equal(cues[0].segIndex, 3)
})

test('buildCues：宽度恰好 42 不切，超过 42 按中文标点切分，时长按宽度比例分配、首尾对齐原段', () => {
  const exact = '一二三四五六七八九十'.repeat(4) + '一二'
  assert.equal(textWidth(exact), 42)
  assert.equal(buildCues([{ start: 0, end: 10000, text: exact }]).length, 1)

  const a = '一二三四五六七八九十一二三四五六七八九。' // 20
  const b = '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九，' // 30
  const cues = buildCues([{ speaker: '1', start: 0, end: 10000, text: a + b }])
  assert.equal(cues.length, 2)
  assert.equal(cues[0].text, a)
  assert.equal(cues[1].text, b)
  assert.equal(cues[0].start, 0)
  assert.equal(cues[0].end, 4000)
  assert.equal(cues[1].start, 4000)
  assert.equal(cues[1].end, 10000)
  assert.ok(cues.every(c => c.segIndex === 0 && c.speaker === '1'))
})

test('buildCues：英文按 .!?;, 切分，但小数点不切', () => {
  const text = 'The fee is 3.5 million dollars in total, payable within thirty days. The client agreed to it; we will draft now.'
  const cues = buildCues([{ start: 0, end: 20000, text }])
  assert.ok(cues.length >= 3)
  assert.ok(cues.every(c => !/^5 million/.test(c.text)), '3.5 不应被切开')
  assert.equal(cues.map(c => c.text).join(' '), text)
})

test('buildCues：子 cue 不足 800ms 与相邻合并', () => {
  // 总时长 2000ms，总宽 48：短尾「好。」只分到 2/48*2000≈83ms，必须并回前一句
  const head = '一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十一二三四五，' // 46
  const cues = buildCues([{ start: 0, end: 2000, text: head + '好。' }])
  assert.ok(cues.every(c => c.end - c.start >= 800), JSON.stringify(cues))
  assert.equal(cues.length, 1)
  assert.equal(cues[0].text, head + '好。')
  assert.equal(cues[0].start, 0)
  assert.equal(cues[0].end, 2000)
})

test('buildCues：minMs 可调，maxChars 可调', () => {
  const text = '甲乙丙，丁戊己，庚辛壬。'
  assert.equal(buildCues([{ start: 0, end: 9000, text }]).length, 1)
  const cues = buildCues([{ start: 0, end: 9000, text }], { maxChars: 5, minMs: 100 })
  assert.equal(cues.length, 3)
  assert.deepEqual(cues.map(c => [c.start, c.end]), [[0, 3000], [3000, 6000], [6000, 9000]])
})

test('buildCues：超长但没有任何可切标点的段原样成一个 cue', () => {
  const text = '甲'.repeat(60)
  const cues = buildCues([{ start: 0, end: 6000, text }])
  assert.equal(cues.length, 1)
  assert.equal(cues[0].text, text)
})

// ── cueIndexAt ──────────────────────────────────────────────────────────────

const cues3 = [
  { start: 0, end: 1000, text: 'a' },
  { start: 1200, end: 2000, text: 'b' },
  { start: 5000, end: 6000, text: 'c' },
]

test('cueIndexAt：落在 cue [start,end] 内返回它，边界含两端', () => {
  assert.equal(cueIndexAt(cues3, 0), 0)
  assert.equal(cueIndexAt(cues3, 500), 0)
  assert.equal(cueIndexAt(cues3, 1000), 0)
  assert.equal(cueIndexAt(cues3, 1200), 1)
  assert.equal(cueIndexAt(cues3, 6000), 2)
})

test('cueIndexAt：cue 结束后 300ms 内且未进入下一 cue 仍返回它（不闪烁）', () => {
  assert.equal(cueIndexAt(cues3, 1100), 0)  // 1000..1200 的缝隙
  assert.equal(cueIndexAt(cues3, 2300), 1)  // end+300 恰好
  assert.equal(cueIndexAt(cues3, 2301), -1) // 超出间隙
  assert.equal(cueIndexAt(cues3, 6300), 2)
  assert.equal(cueIndexAt(cues3, 6301), -1)
})

test('cueIndexAt：开头之前、空数组、非数 ms 返回 -1；gapMs 可调', () => {
  const later = [{ start: 500, end: 1000, text: 'x' }]
  assert.equal(cueIndexAt(later, 499), -1)
  assert.equal(cueIndexAt([], 10), -1)
  assert.equal(cueIndexAt(null, 10), -1)
  assert.equal(cueIndexAt(cues3, NaN), -1)
  assert.equal(cueIndexAt(cues3, 2100, { gapMs: 0 }), -1)
  assert.equal(cueIndexAt(cues3, 2900, { gapMs: 1000 }), 1)
})

test('cueIndexAt：两千个 cue 的二分查找与线性扫描逐点一致', () => {
  const many = []
  for (let i = 0; i < 2000; i++) many.push({ start: i * 1000, end: i * 1000 + 600, text: String(i) })
  const linear = (ms) => {
    for (let i = many.length - 1; i >= 0; i--) {
      if (many[i].start <= ms) return ms <= many[i].end + 300 ? i : -1
    }
    return -1
  }
  for (let ms = -50; ms < 2000 * 1000; ms += 137) assert.equal(cueIndexAt(many, ms), linear(ms), 'ms=' + ms)
})
